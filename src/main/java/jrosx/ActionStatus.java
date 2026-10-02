package jrosx;

/** Values defined by action_msgs/msg/GoalStatus. */
public enum ActionStatus {
    UNKNOWN, ACCEPTED, EXECUTING, CANCELING, SUCCEEDED, CANCELED, ABORTED;

    public byte code() { return (byte) ordinal(); }
    public boolean isTerminal() { return ordinal() >= SUCCEEDED.ordinal(); }
    public static ActionStatus fromCode(byte code) {
        if (code < 0 || code >= values().length) throw new IllegalArgumentException("Invalid goal status: " + code);
        return values()[code];
    }
}
