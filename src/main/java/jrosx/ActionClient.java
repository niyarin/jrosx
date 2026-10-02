package jrosx;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.*;
import static jrosx.ActionMessages.*;

/**
 * ROS 2 action client with an internal receive loop. Non-async future continuations and
 * feedback callbacks run on that loop and must not block; use thenAcceptAsync for blocking work.
 */
public final class ActionClient<G extends Record, R extends Record, F extends Record> implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ActionClient.class.getName());
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private final String name;
    private final Node node;
    private final ActionType<G, R, F> type;
    private final ActionResources resources = new ActionResources();
    private final ActionRpc.Client<SendGoalRequest<G>, SendGoalResponse> send;
    private final ActionRpc.Client<GetResultRequest, GetResultResponse<R>> result;
    private final ActionRpc.Client<CancelRequest, CancelResponse> cancel;
    private final Subscription<FeedbackMessage<F>> feedback;
    private final Subscription<StatusArray> status;
    private final ConcurrentMap<UUID, Consumer<F>> callbacks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService receiver;
    private volatile StatusArray latestStatus = new StatusArray(List.of());
    private volatile boolean closed;

    ActionClient(Node node, String name, ActionType<G, R, F> type) {
        this.name = "/" + Node.actionName(name);
        this.node = node;
        this.type = type;
        String base = Node.actionName(name) + "/_action/";
        Objects.requireNonNull(type);
        try {
            send = resources.add(node.actionRpcClient(base + "send_goal", type.sendRequest, type.sendResponse));
            result = resources.add(node.actionRpcClient(base + "get_result", type.resultRequest, type.resultResponse));
            cancel = resources.add(node.actionRpcClient(base + "cancel_goal", ActionType.CANCEL_REQUEST, ActionType.CANCEL_RESPONSE));
            feedback = resources.add(node.createSubscription(base + "feedback", type.feedback.getType(), type.feedback));
            status = resources.add(node.actionStatusSubscription(base + "status"));
            receiver = Executors.newSingleThreadScheduledExecutor(r -> {
                var thread = new Thread(r, "jrosx-action-client-" + this.name);
                thread.setDaemon(true);
                return thread;
            });
            receiver.scheduleWithFixedDelay(this::poll, 0, 5, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            try { resources.close(); } catch (RuntimeException closing) { e.addSuppressed(closing); }
            throw e;
        }
    }

    public String getActionName() { return name; }

    /** Whether all remote server endpoints have been discovered; not a delivery guarantee. */
    public boolean isServerAvailable() {
        ensureOpen();
        return node.hasActionServer(Node.actionName(name) + "/_action/", type);
    }

    /** Waits for discovery of a remote server, using a monotonic timeout. Zero checks once. */
    public boolean waitForServer(Duration timeout) throws InterruptedException {
        long budget = Objects.requireNonNull(timeout).toNanos();
        if (budget < 0) throw new IllegalArgumentException("Timeout must be nonnegative");
        long start = System.nanoTime();
        do {
            if (Thread.interrupted()) throw new InterruptedException();
            if (isServerAvailable()) return true;
            if (System.nanoTime() - start >= budget) return false;
            Thread.sleep(10);
        } while (true);
    }

    public StatusArray getLatestStatus() { return latestStatus; }

    public CompletableFuture<GoalHandle> sendGoal(G goal) {
        return sendGoal(goal, ignored -> {}, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<GoalHandle> sendGoal(G goal, Consumer<F> feedbackCallback, Duration timeout) {
        return sendGoal(UUID.randomUUID(), goal, feedbackCallback, timeout);
    }

    /** Explicit IDs allow applications to retain identity when an acceptance response times out. */
    public synchronized CompletableFuture<GoalHandle> sendGoal(UUID id, G goal, Consumer<F> feedbackCallback, Duration timeout) {
        ensureOpen();
        Objects.requireNonNull(id);
        Objects.requireNonNull(goal);
        Objects.requireNonNull(feedbackCallback);
        if (callbacks.putIfAbsent(id, feedbackCallback) != null) throw new IllegalArgumentException("Goal ID already in use");
        try {
            var response = send.call(new SendGoalRequest<>(id, goal), timeout);
            response.whenComplete((value, error) -> {
                if (error != null || !value.accepted()) callbacks.remove(id);
            });
            return response.thenApply(value -> new GoalHandle(id, value.accepted(), value.stamp()));
        } catch (RuntimeException e) {
            callbacks.remove(id);
            throw e;
        }
    }

    /** Also supports retrieving retained results after reconnecting or losing a send-goal response. */
    public CompletableFuture<GetResultResponse<R>> getResult(UUID id, Duration timeout) {
        ensureOpen();
        var future = result.call(new GetResultRequest(Objects.requireNonNull(id)), timeout);
        future.thenAccept(value -> callbacks.remove(id));
        return future;
    }

    public CompletableFuture<CancelResponse> cancel(UUID id, Time stamp, Duration timeout) {
        ensureOpen();
        return cancel.call(new CancelRequest(new GoalInfo(Objects.requireNonNull(id), Objects.requireNonNull(stamp))), timeout);
    }
    public CompletableFuture<CancelResponse> cancelAll(Duration timeout) {
        return cancel(ZERO_UUID, Time.ZERO, timeout);
    }
    /** Stops local feedback delivery without canceling the remote goal. */
    public void forgetGoal(UUID id) { callbacks.remove(id); }

    public final class GoalHandle {
        private final UUID id;
        private final boolean accepted;
        private final Time stamp;
        private GoalHandle(UUID id, boolean accepted, Time stamp) {
            this.id = id; this.accepted = accepted; this.stamp = stamp;
        }
        public UUID getGoalId() { return id; }
        public boolean isAccepted() { return accepted; }
        public Time getStamp() { return stamp; }
        public CompletableFuture<GetResultResponse<R>> getResult(Duration timeout) {
            if (!accepted) throw new IllegalStateException("Goal was rejected");
            return ActionClient.this.getResult(id, timeout);
        }
        public CompletableFuture<CancelResponse> cancel(Duration timeout) {
            if (!accepted) throw new IllegalStateException("Goal was rejected");
            return ActionClient.this.cancel(id, Time.ZERO, timeout);
        }
    }

    private void poll() {
        if (closed) return;
        try {
            send.poll(); cancel.poll();
            for (var sample : feedback.take()) {
                if (!sample.hasValidData()) continue;
                var value = sample.data();
                var callback = callbacks.get(value.goalId());
                if (callback != null) {
                    try { callback.accept(value.feedback()); }
                    catch (RuntimeException e) { LOG.log(Level.WARNING, "Action feedback callback failed", e); }
                }
            }
            result.poll();
            for (var sample : status.take()) if (sample.hasValidData()) latestStatus = sample.data();
        } catch (RuntimeException e) {
            if (!closed) LOG.log(Level.WARNING, "Action receive failed", e);
        }
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Action client closed"); }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        receiver.shutdownNow();
        callbacks.clear();
        resources.close();
    }
}
