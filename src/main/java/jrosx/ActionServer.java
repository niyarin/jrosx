package jrosx;

import ddsj.dds.sample.Sample;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import static jrosx.ActionMessages.*;

/**
 * ROS 2 action server. Call spin() (or spinOnce()) to serve requests.
 * Accepted goals execute on virtual threads, independently of the request loop.
 * Acceptance and cancellation predicates run on the request loop and must be quick.
 */
public final class ActionServer<G extends Record, R extends Record, F extends Record> implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ActionServer.class.getName());
    private final String name;
    private final ActionType<G, R, F> type;
    private final Predicate<G> accept;
    private final Predicate<GoalHandle<G, R, F>> cancelPolicy;
    private final Consumer<GoalHandle<G, R, F>> execute;
    private final long retentionNanos;
    private final Map<UUID, GoalHandle<G, R, F>> goals = new LinkedHashMap<>();
    private final ActionResources resources = new ActionResources();
    private final ActionRpc.Server<SendGoalRequest<G>, SendGoalResponse> send;
    private final ActionRpc.Server<GetResultRequest, GetResultResponse<R>> result;
    private final ActionRpc.Server<CancelRequest, CancelResponse> cancel;
    private final Publisher<FeedbackMessage<F>> feedback;
    private final Publisher<StatusArray> status;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private boolean closed;

    ActionServer(Node node, String name, ActionType<G, R, F> type, Predicate<G> accept,
                 Predicate<GoalHandle<G, R, F>> cancelPolicy, Consumer<GoalHandle<G, R, F>> execute,
                 Duration retention) {
        this.name = "/" + Node.actionName(name);
        this.type = Objects.requireNonNull(type);
        this.accept = Objects.requireNonNull(accept);
        this.cancelPolicy = Objects.requireNonNull(cancelPolicy);
        this.execute = Objects.requireNonNull(execute);
        retentionNanos = Objects.requireNonNull(retention).toNanos();
        if (retentionNanos < 0 && !retention.equals(Duration.ofSeconds(-1)))
            throw new IllegalArgumentException("Retention must be nonnegative or -1 seconds (forever)");
        String base = Node.actionName(name) + "/_action/";
        try {
            send = resources.add(node.actionRpcServer(base + "send_goal", type.sendRequest, type.sendResponse));
            result = resources.add(node.actionRpcServer(base + "get_result", type.resultRequest, type.resultResponse));
            cancel = resources.add(node.actionRpcServer(base + "cancel_goal", ActionType.CANCEL_REQUEST, ActionType.CANCEL_RESPONSE));
            feedback = resources.add(node.createPublisher(base + "feedback", type.feedback.getType(), type.feedback));
            status = resources.add(node.actionStatusPublisher(base + "status"));
            publishStatus();
        } catch (RuntimeException e) {
            workers.shutdownNow();
            try { resources.close(); } catch (RuntimeException closing) { e.addSuppressed(closing); }
            throw e;
        }
    }
    public String getActionName() { return name; }

    public synchronized boolean spinOnce() {
        ensureOpen();
        boolean processed = false;
        expireResults();
        for (var request : send.take()) { receiveGoal(request); processed = true; }
        for (var request : result.take()) {
            var goal = goals.get(request.data().goalId());
            if (goal == null) result.reply(request, new GetResultResponse<>(ActionStatus.UNKNOWN, type.emptyResult()));
            else if (goal.status.isTerminal()) result.reply(request, new GetResultResponse<>(goal.status, goal.result));
            else goal.waiters.add(request);
            processed = true;
        }
        for (var request : cancel.take()) { cancel.reply(request, cancelGoals(request.data())); processed = true; }
        return processed;
    }

    private void receiveGoal(Sample<SendGoalRequest<G>> request) {
        var value = request.data();
        boolean accepted = false;
        if (!goals.containsKey(value.goalId())) {
            try { accepted = accept.test(value.goal()); }
            catch (RuntimeException e) { LOG.log(Level.WARNING, "Goal acceptance callback failed", e); }
        }
        if (!accepted) {
            send.reply(request, new SendGoalResponse(false, Time.ZERO));
            return;
        }
        var goal = new GoalHandle<>(this, new GoalInfo(value.goalId(), Time.now()), value.goal());
        goals.put(value.goalId(), goal);
        publishStatus();
        send.reply(request, new SendGoalResponse(true, goal.info.stamp()));
        workers.submit(() -> runGoal(goal));
    }

    private void runGoal(GoalHandle<G, R, F> goal) {
        synchronized (this) {
            if (closed) return;
            if (goal.status == ActionStatus.ACCEPTED) {
                goal.status = ActionStatus.EXECUTING;
                publishStatus();
            }
        }
        try {
            execute.accept(goal);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Action execution failed", e);
        } finally {
            synchronized (this) {
                if (!closed && !goal.status.isTerminal()) finish(goal, ActionStatus.ABORTED, type.emptyResult());
            }
        }
    }

    private CancelResponse cancelGoals(CancelRequest request) {
        GoalInfo selector = request.goalInfo();
        boolean allIds = selector.goalId().equals(ZERO_UUID);
        boolean noTime = selector.stamp().equals(Time.ZERO);
        List<GoalInfo> canceling = new ArrayList<>();
        for (var goal : goals.values()) {
            boolean selected = allIds && noTime || goal.info.goalId().equals(selector.goalId())
                    || !noTime && goal.info.stamp().compareTo(selector.stamp()) <= 0;
            if (!selected || goal.status.isTerminal() || goal.status == ActionStatus.CANCELING) continue;
            boolean allowed = false;
            try { allowed = cancelPolicy.test(goal); }
            catch (RuntimeException e) { LOG.log(Level.WARNING, "Cancel callback failed", e); }
            if (allowed && !goal.status.isTerminal()) {
                goal.status = ActionStatus.CANCELING;
                canceling.add(goal.info);
            }
        }
        if (!canceling.isEmpty()) {
            publishStatus();
            return new CancelResponse(CancelResponse.NONE, canceling);
        }
        byte code = CancelResponse.REJECTED;
        if (!allIds) {
            var goal = goals.get(selector.goalId());
            if (goal == null) code = CancelResponse.UNKNOWN_GOAL_ID;
            else if (goal.status.isTerminal()) code = CancelResponse.GOAL_TERMINATED;
        }
        return new CancelResponse(code, List.of());
    }

    private synchronized void finish(GoalHandle<G, R, F> goal, ActionStatus terminal, R value) {
        ensureOpen();
        if (goal.status.isTerminal()) throw new IllegalStateException("Goal already finished");
        if (terminal == ActionStatus.CANCELED && goal.status != ActionStatus.CANCELING)
            throw new IllegalStateException("Goal has not accepted cancellation");
        Objects.requireNonNull(value);
        // Reject invalid result values before committing the terminal state.
        type.resultResponse.serialize(new GetResultResponse<>(terminal, value));
        goal.result = value;
        goal.status = terminal;
        goal.finishedAt = System.nanoTime();
        for (var waiter : goal.waiters) result.reply(waiter, new GetResultResponse<>(terminal, value));
        goal.waiters.clear();
        publishStatus();
        expireResults();
    }
    private synchronized void publishFeedback(GoalHandle<G, R, F> goal, F value) {
        ensureOpen();
        if (goal.status != ActionStatus.EXECUTING && goal.status != ActionStatus.CANCELING)
            throw new IllegalStateException("Goal is not executing");
        feedback.publish(new FeedbackMessage<>(goal.info.goalId(), Objects.requireNonNull(value)));
    }
    private void expireResults() {
        if (retentionNanos < 0) return;
        long now = System.nanoTime();
        if (goals.values().removeIf(g -> g.status.isTerminal() && now - g.finishedAt >= retentionNanos)) publishStatus();
    }
    private void publishStatus() {
        status.publish(new StatusArray(goals.values().stream().map(g -> new GoalStatus(g.info, g.status)).toList()));
    }
    public void spin() throws InterruptedException { spin(5); }
    public void spin(long pollIntervalMillis) throws InterruptedException {
        if (pollIntervalMillis <= 0) throw new IllegalArgumentException("Poll interval must be positive");
        synchronized (this) { ensureOpen(); running = true; }
        try {
            while (running) {
                synchronized (this) {
                    if (closed || !running) break;
                    spinOnce();
                }
                Thread.sleep(pollIntervalMillis);
            }
        } finally { running = false; }
    }
    public void shutdown() { running = false; }
    public boolean isRunning() { return running; }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Action server closed"); }
    public synchronized void close() {
        if (closed) return;
        closed = true;
        running = false;
        workers.shutdownNow();
        goals.values().forEach(g -> g.waiters.clear());
        goals.clear();
        resources.close();
    }

    public static final class GoalHandle<G extends Record, R extends Record, F extends Record> {
        private final ActionServer<G, R, F> server;
        private final GoalInfo info;
        private final G goal;
        private volatile ActionStatus status = ActionStatus.ACCEPTED;
        private R result;
        private long finishedAt;
        private final List<Sample<GetResultRequest>> waiters = new ArrayList<>();
        private GoalHandle(ActionServer<G, R, F> server, GoalInfo info, G goal) {
            this.server = server; this.info = info; this.goal = goal;
        }
        public UUID getGoalId() { return info.goalId(); }
        public Time getStamp() { return info.stamp(); }
        public G getGoal() { return goal; }
        public ActionStatus getStatus() { return status; }
        public boolean isCancelRequested() { return status == ActionStatus.CANCELING; }
        public void publishFeedback(F value) { server.publishFeedback(this, value); }
        public void succeed(R value) { server.finish(this, ActionStatus.SUCCEEDED, value); }
        public void abort(R value) { server.finish(this, ActionStatus.ABORTED, value); }
        public void canceled(R value) { server.finish(this, ActionStatus.CANCELED, value); }
    }
}
