package example_interfaces.action;

import jrosx.ActionType;

/** Java records for example_interfaces/action/Fibonacci. */
public final class Fibonacci {
    private Fibonacci() {}
    public record Goal(int order) {}
    public record Result(int[] sequence) {}
    public record Feedback(int[] sequence) {}
    public static final ActionType<Goal, Result, Feedback> TYPE = new ActionType<>(
            "example_interfaces/action/Fibonacci", Goal.class, Result.class, Feedback.class,
            () -> new Result(new int[0]));
}
