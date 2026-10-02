package jrosx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static jrosx.ActionMessages.*;

@Timeout(30)
class ActionIntegrationTest {
    record Goal(int value) {}
    record Result(int value) {}
    record Feedback(int value) {}
    private static final ActionType<Goal, Result, Feedback> TYPE = new ActionType<>(
            "test_interfaces/action/Count", Goal.class, Result.class, Feedback.class, () -> new Result(0));
    private static final Duration WAIT = Duration.ofSeconds(8);

    private static <T> T get(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition timed out");
    }

    static final class Fixture implements AutoCloseable {
        final Node serverNode = new Node("action_test_server", 153, 10);
        final Node clientNode = new Node("action_test_client", 153, 11);
        final ActionServer<Goal, Result, Feedback> server;
        final ActionClient<Goal, Result, Feedback> client;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;
        Fixture(Predicate<Goal> accept, Predicate<ActionServer.GoalHandle<Goal, Result, Feedback>> cancel,
                Consumer<ActionServer.GoalHandle<Goal, Result, Feedback>> execute, Duration retention) throws Exception {
            server = serverNode.createActionServer("/tests/count", TYPE, accept, cancel, execute, retention);
            client = clientNode.createActionClient("tests/count", TYPE);
            thread = Thread.ofPlatform().daemon().start(() -> {
                try { server.spin(); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                catch (Throwable e) { failure.set(e); }
            });
            assertTrue(client.waitForServer(WAIT));
        }
        public void close() throws Exception {
            clientNode.close(); serverNode.close();
            thread.interrupt(); thread.join(2000);
            assertNull(failure.get());
            assertFalse(thread.isAlive());
        }
    }

    @Test void concurrentClientsReceiveTheirOwnResultsAndFeedback() throws Exception {
        try (var f = new Fixture(g -> true, g -> true, g -> {
            g.publishFeedback(new Feedback(g.getGoal().value()));
            g.succeed(new Result(g.getGoal().value() * 2));
        }, Duration.ofMinutes(1));
             var otherNode = new Node("second_client", 153, 12);
             var other = otherNode.createActionClient("/tests/count", TYPE)) {
            assertTrue(other.waitForServer(WAIT));
            var seen = new CopyOnWriteArrayList<Integer>();
            var firstFuture = f.client.sendGoal(new Goal(7), x -> seen.add(x.value()), WAIT);
            var secondFuture = other.sendGoal(new Goal(19), x -> {}, WAIT);
            var first = get(firstFuture);
            var second = get(secondFuture);
            assertTrue(first.isAccepted());
            assertTrue(second.isAccepted());
            await(() -> seen.contains(7));
            var firstResult = get(first.getResult(WAIT));
            assertEquals(new Result(14), firstResult.result());
            assertEquals(ActionStatus.SUCCEEDED, firstResult.status());
            assertEquals(new Result(38), get(second.getResult(WAIT)).result());
            assertEquals(firstResult, get(first.getResult(WAIT))); // cached result
            await(() -> f.client.getLatestStatus().statusList().size() == 2);
        }
    }

    @Test void pendingResultDoesNotBlockCancellation() throws Exception {
        try (var f = new Fixture(g -> true, g -> true, g -> {
            while (!g.isCancelRequested()) {
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
            g.canceled(new Result(42));
        }, Duration.ofMinutes(1))) {
            var goal = get(f.client.sendGoal(new Goal(1)));
            var pending = goal.getResult(WAIT);
            assertFalse(pending.isDone());
            var response = get(goal.cancel(WAIT));
            assertEquals(CancelResponse.NONE, response.returnCode());
            assertEquals(goal.getGoalId(), response.goalsCanceling().getFirst().goalId());
            assertEquals(ActionStatus.CANCELED, get(pending).status());
            assertEquals(CancelResponse.GOAL_TERMINATED, get(goal.cancel(WAIT)).returnCode());
        }
    }

    @Test void rejectionDuplicateUnknownAndAbortedGoals() throws Exception {
        try (var f = new Fixture(g -> g.value() >= 0, g -> false, g -> {
            g.abort(new Result(-1));
        }, Duration.ofMinutes(1))) {
            assertFalse(get(f.client.sendGoal(new Goal(-1))).isAccepted());
            var goal = get(f.client.sendGoal(new Goal(1)));
            assertEquals(ActionStatus.ABORTED, get(goal.getResult(WAIT)).status());
            // Completed result removed its callback; server must still reject the duplicate UUID.
            assertFalse(get(f.client.sendGoal(goal.getGoalId(), new Goal(2), x -> {}, WAIT)).isAccepted());
            var unknown = UUID.randomUUID();
            assertEquals(ActionStatus.UNKNOWN, get(f.client.getResult(unknown, WAIT)).status());
            assertEquals(CancelResponse.UNKNOWN_GOAL_ID, get(f.client.cancel(unknown, Time.ZERO, WAIT)).returnCode());
        }
    }

    @Test void cancellationSelectorsAndRejection() throws Exception {
        try (var f = new Fixture(g -> true, g -> g.getGoal().value() != 99, g -> {
            while (!g.isCancelRequested()) {
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
            g.canceled(new Result(g.getGoal().value()));
        }, Duration.ofMinutes(1))) {
            var first = get(f.client.sendGoal(new Goal(1)));
            Thread.sleep(5);
            var second = get(f.client.sendGoal(new Goal(2)));
            var refused = get(f.client.sendGoal(new Goal(99)));
            var before = get(f.client.cancel(ZERO_UUID, first.getStamp(), WAIT));
            assertEquals(List.of(first.getGoalId()), before.goalsCanceling().stream().map(GoalInfo::goalId).toList());
            assertEquals(CancelResponse.REJECTED, get(refused.cancel(WAIT)).returnCode());
            var all = get(f.client.cancelAll(WAIT));
            assertEquals(List.of(second.getGoalId()), all.goalsCanceling().stream().map(GoalInfo::goalId).toList());
        }
    }

    @Test void expiredResultsAndTimeoutCleanup() throws Exception {
        try (var f = new Fixture(g -> true, g -> true, g -> g.succeed(new Result(1)), Duration.ofMillis(100))) {
            var goal = get(f.client.sendGoal(new Goal(1)));
            Thread.sleep(300);
            assertEquals(ActionStatus.UNKNOWN, get(goal.getResult(WAIT)).status());
        }
        try (var node = new Node("no_server", 153, 13);
             var client = node.createActionClient("/missing", TYPE)) {
            var missing = client.sendGoal(new Goal(1), x -> {}, Duration.ofMillis(100));
            assertInstanceOf(TimeoutException.class, assertThrows(ExecutionException.class, () -> get(missing)).getCause());
            var outstanding = client.sendGoal(new Goal(2));
            client.close();
            assertThrows(Exception.class, () -> get(outstanding));
            assertThrows(IllegalStateException.class, () -> client.sendGoal(new Goal(3)));
        }
    }
    @Test void severalOutstandingCallsOnOneClientStayCorrelated() throws Exception {
        var release = new CountDownLatch(1);
        try (var f = new Fixture(g -> true, g -> true, g -> {
            if (g.getGoal().value() == 1) {
                try { release.await(); } catch (InterruptedException e) { return; }
            }
            g.succeed(new Result(g.getGoal().value()));
        }, Duration.ofMinutes(1))) {
            var first = get(f.client.sendGoal(new Goal(1)));
            var pendingFirst = first.getResult(WAIT);
            var second = get(f.client.sendGoal(new Goal(2)));
            assertEquals(new Result(2), get(second.getResult(WAIT)).result());
            assertFalse(pendingFirst.isDone());
            release.countDown();
            assertEquals(new Result(1), get(pendingFirst).result());
            try (var lateNode = new Node("late_status_client", 153, 14);
                 var late = lateNode.createActionClient("/tests/count", TYPE)) {
                await(() -> late.getLatestStatus().statusList().size() == 2);
                assertTrue(late.getLatestStatus().statusList().stream().allMatch(s -> s.status() == ActionStatus.SUCCEEDED));
            }
        } finally { release.countDown(); }
    }

    @Test void unfinishedCallbackAbortsAndTerminalStateCannotChange() throws Exception {
        var handle = new AtomicReference<ActionServer.GoalHandle<Goal, Result, Feedback>>();
        try (var f = new Fixture(g -> true, g -> true, g -> handle.set(g), Duration.ofMinutes(1))) {
            var goal = get(f.client.sendGoal(new Goal(3)));
            assertEquals(ActionStatus.ABORTED, get(goal.getResult(WAIT)).status());
            assertThrows(IllegalStateException.class, () -> handle.get().succeed(new Result(3)));
            assertThrows(IllegalStateException.class, () -> handle.get().publishFeedback(new Feedback(3)));
        }
    }

}
