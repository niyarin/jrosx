package jrosx;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Common ROS 2 action wire messages. UUIDs are encoded as 16 fixed octets. */
public final class ActionMessages {
    private ActionMessages() {}
    public static final UUID ZERO_UUID = new UUID(0, 0);
    public record Time(int sec, int nanosec) implements Comparable<Time> {
        public static final Time ZERO = new Time(0, 0);
        public Time {
            if (nanosec < 0 || nanosec >= 1_000_000_000) throw new IllegalArgumentException("Invalid nanoseconds");
        }
        public static Time now() {
            var now = Instant.now();
            return new Time(Math.toIntExact(now.getEpochSecond()), now.getNano());
        }
        public int compareTo(Time other) {
            int seconds = Integer.compare(sec, other.sec);
            return seconds != 0 ? seconds : Integer.compare(nanosec, other.nanosec);
        }
    }
    public record GoalInfo(UUID goalId, Time stamp) {}
    public record GoalStatus(GoalInfo goalInfo, ActionStatus status) {}
    public record StatusArray(List<GoalStatus> statusList) {
        public StatusArray { statusList = List.copyOf(statusList); }
    }
    public record SendGoalRequest<G>(UUID goalId, G goal) {}
    public record SendGoalResponse(boolean accepted, Time stamp) {}
    public record GetResultRequest(UUID goalId) {}
    public record GetResultResponse<R>(ActionStatus status, R result) {}
    public record FeedbackMessage<F>(UUID goalId, F feedback) {}
    public record CancelRequest(GoalInfo goalInfo) {}
    public record CancelResponse(byte returnCode, List<GoalInfo> goalsCanceling) {
        public static final byte NONE = 0, REJECTED = 1, UNKNOWN_GOAL_ID = 2, GOAL_TERMINATED = 3;
        public CancelResponse { goalsCanceling = List.copyOf(goalsCanceling); }
    }
}
