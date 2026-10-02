package jrosx.examples;

import example_interfaces.action.Fibonacci;
import jrosx.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Run "server" or "client [order] [cancel]".
 * Uses ROS_DOMAIN_ID and JROS_PARTICIPANT_INDEX (default 1).
 */
public final class FibonacciAction {
    private FibonacciAction() {}
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !(args[0].equals("server") || args[0].equals("client")))
            throw new IllegalArgumentException("Usage: FibonacciAction server | client [order] [cancel]");
        int domain = Integer.parseInt(System.getenv().getOrDefault("ROS_DOMAIN_ID", "0"));
        int participant = Integer.parseInt(System.getenv().getOrDefault("JROS_PARTICIPANT_INDEX", "1"));
        try (var node = new Node("fibonacci_" + args[0], domain, participant)) {
            if (args[0].equals("server")) {
                try (var server = node.createActionServer("/fibonacci", Fibonacci.TYPE,
                        goal -> goal.order() >= 0 && goal.order() <= 46, goal -> true,
                        FibonacciAction::execute, Duration.ofMinutes(15))) {
                    System.out.println("Serving /fibonacci");
                    server.spin();
                }
            } else {
                int order = args.length > 1 ? Integer.parseInt(args[1]) : 6;
                try (var client = node.createActionClient("/fibonacci", Fibonacci.TYPE)) {
                    if (!client.waitForServer(Duration.ofSeconds(10)))
                        throw new IllegalStateException("Action server discovery timed out");
                    var goal = client.sendGoal(new Fibonacci.Goal(order),
                            feedback -> System.out.println("Feedback: " + Arrays.toString(feedback.sequence())),
                            Duration.ofSeconds(10)).get(12, TimeUnit.SECONDS);
                    System.out.println("Goal: " + goal.getGoalId() + ", accepted=" + goal.isAccepted());
                    if (!goal.isAccepted()) return;
                    var result = goal.getResult(Duration.ofSeconds(60));
                    if (args.length > 2 && args[2].equals("cancel")) {
                        Thread.sleep(300);
                        System.out.println("Cancel: " + goal.cancel(Duration.ofSeconds(10)).get(12, TimeUnit.SECONDS).returnCode());
                    }
                    var response = result.get(65, TimeUnit.SECONDS);
                    System.out.println("Result: " + response.status() + " " + Arrays.toString(response.result().sequence()));
                }
            }
        }
    }
    private static void execute(ActionServer.GoalHandle<Fibonacci.Goal, Fibonacci.Result, Fibonacci.Feedback> goal) {
        int[] values = new int[goal.getGoal().order() + 2];
        values[1] = 1;
        int length = 2;
        while (length < values.length) {
            if (goal.isCancelRequested()) {
                goal.canceled(new Fibonacci.Result(Arrays.copyOf(values, length)));
                return;
            }
            values[length] = values[length - 1] + values[length - 2];
            length++;
            goal.publishFeedback(new Fibonacci.Feedback(Arrays.copyOf(values, length)));
            try { Thread.sleep(100); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (goal.isCancelRequested()) goal.canceled(new Fibonacci.Result(Arrays.copyOf(values, length)));
        else goal.succeed(new Fibonacci.Result(values));
    }
}
