package jrosx.cli;

import jrosx.Node;
import jrosx.Publisher;
import jrosx.Ros2TypeSupport;
import jrosx.ServiceClient;
import jrosx.ServiceServer;
import jrosx.Subscription;
import std_msgs.msg.String_;
import example_interfaces.srv.AddTwoInts_Request;
import example_interfaces.srv.AddTwoInts_Response;
import ddsj.dds.sample.Sample;

import java.util.List;

/**
 * ROS2-style CLI for jros.
 *
 * Usage:
 *   jros topic echo /chatter
 *   jros topic pub /chatter std_msgs/msg/String "data: 'hello'"
 */
public class Main {

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            return;
        }

        String command = args[0];
        String subcommand = args[1];

        try {
            switch (command) {
                case "topic" -> handleTopic(subcommand, args);
                case "service" -> handleService(subcommand, args);
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void handleTopic(String subcommand, String[] args) throws Exception {
        switch (subcommand) {
            case "echo" -> topicEcho(args);
            case "pub" -> topicPub(args);
            default -> {
                System.err.println("Unknown topic subcommand: " + subcommand);
                printUsage();
            }
        }
    }

    private static void handleService(String subcommand, String[] args) throws Exception {
        switch (subcommand) {
            case "call" -> serviceCall(args);
            case "server" -> serviceServer(args);
            default -> {
                System.err.println("Unknown service subcommand: " + subcommand);
                printUsage();
            }
        }
    }

    private static void serviceCall(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: jros service call <service_name> <type> \"{a: <val>, b: <val>}\"");
            return;
        }

        String serviceName = toInternalServiceName(args[2]);
        String typeName = args[3];
        String dataArg = args[4];

        if (!"example_interfaces/srv/AddTwoInts".equals(typeName)) {
            System.err.println("Currently only example_interfaces/srv/AddTwoInts is supported");
            return;
        }

        // Parse "{a: 1, b: 2}" format
        long[] values = parseAddTwoIntsRequest(dataArg);
        AddTwoInts_Request request = new AddTwoInts_Request(values[0], values[1]);

        System.out.println("Calling service: " + serviceName);
        System.out.println("Request: a=" + values[0] + ", b=" + values[1]);

        try (Node node = new Node("jros_service_client");
             ServiceClient<AddTwoInts_Request, AddTwoInts_Response> client = node.createClient(
                     serviceName,
                     AddTwoInts_Request.class,
                     AddTwoInts_Response.class,
                     AddTwoInts_Request.TYPE_SUPPORT,
                     AddTwoInts_Response.TYPE_SUPPORT)) {

            // Give time for discovery
            Thread.sleep(500);

            AddTwoInts_Response response = client.call(request);
            System.out.println("Response: sum=" + response.sum());
        }
    }

    private static void serviceServer(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: jros service server <service_name> <type>");
            return;
        }

        String serviceName = toInternalServiceName(args[2]);
        String typeName = args[3];

        if (!"example_interfaces/srv/AddTwoInts".equals(typeName)) {
            System.err.println("Currently only example_interfaces/srv/AddTwoInts is supported");
            return;
        }

        String requestTopic = "rq/" + serviceName + "Request";
        String replyTopic = "rr/" + serviceName + "Reply";
        String requestTypeName = Ros2TypeSupport.toDdsTypeName("example_interfaces/srv/AddTwoInts_Request");
        String responseTypeName = Ros2TypeSupport.toDdsTypeName("example_interfaces/srv/AddTwoInts_Response");
        System.out.println("Starting service server: " + serviceName);
        System.out.println("  Request topic: " + requestTopic);
        System.out.println("  Reply topic:   " + replyTopic);
        System.out.println("  Request type:  " + requestTypeName);
        System.out.println("  Response type: " + responseTypeName);

        try (Node node = new Node("jros_service_server");
             ServiceServer<AddTwoInts_Request, AddTwoInts_Response> server = node.createService(
                     serviceName,
                     AddTwoInts_Request.class,
                     AddTwoInts_Response.class,
                     AddTwoInts_Request.TYPE_SUPPORT,
                     AddTwoInts_Response.TYPE_SUPPORT,
                     request -> {
                         long sum = request.a() + request.b();
                         System.out.println("Received: a=" + request.a() + ", b=" + request.b() + " -> sum=" + sum);
                         return new AddTwoInts_Response(sum);
                     })) {

            server.spin();
        }
    }

    private static String toInternalServiceName(String rosServiceName) {
        // Remove leading slash if present
        if (rosServiceName.startsWith("/")) {
            return rosServiceName.substring(1);
        }
        return rosServiceName;
    }

    private static long[] parseAddTwoIntsRequest(String dataArg) {
        // Parse "{a: 1, b: 2}" or "a: 1, b: 2"
        String trimmed = dataArg.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }

        long a = 0, b = 0;
        String[] parts = trimmed.split(",");
        for (String part : parts) {
            String[] kv = part.split(":");
            if (kv.length == 2) {
                String key = kv[0].trim();
                long value = Long.parseLong(kv[1].trim());
                if ("a".equals(key)) {
                    a = value;
                } else if ("b".equals(key)) {
                    b = value;
                }
            }
        }
        return new long[]{a, b};
    }

    private static void topicEcho(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: jros topic echo <topic_name>");
            return;
        }

        String topicName = toInternalTopicName(args[2]);
        System.out.println("Subscribing to: " + topicName);

        try (Node node = new Node("jros_echo");
             Subscription<String_> sub = node.createSubscription(
                     topicName, String_.class, String_.TYPE_SUPPORT)) {

            while (true) {
                List<Sample<String_>> samples = sub.take();
                for (Sample<String_> sample : samples) {
                    System.out.println("data: '" + sample.data().data() + "'");
                }
                Thread.sleep(100);
            }
        }
    }

    private static void topicPub(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: jros topic pub <topic_name> <type> \"data: '<message>'\"");
            return;
        }

        String topicName = toInternalTopicName(args[2]);
        String typeName = args[3];
        String dataArg = args[4];

        // Parse "data: 'message'" format
        String message = parseDataArg(dataArg);

        if (!"std_msgs/msg/String".equals(typeName)) {
            System.err.println("Currently only std_msgs/msg/String is supported");
            return;
        }

        System.out.println("Publishing to: " + topicName);

        try (Node node = new Node("jros_pub");
             Publisher<String_> pub = node.createPublisher(
                     topicName, String_.class, String_.TYPE_SUPPORT)) {

            // Give time for discovery
            Thread.sleep(500);

            int count = 0;
            while (true) {
                String msg = message.isEmpty() ? "Hello from jros " + count : message;
                pub.publish(new String_(msg));
                System.out.println("Publishing: data: '" + msg + "'");
                Thread.sleep(1000);
                count++;
            }
        }
    }

    private static String toInternalTopicName(String rosTopicName) {
        // ROS2 topic /chatter -> DDS topic rt/chatter
        if (rosTopicName.startsWith("/")) {
            return "rt" + rosTopicName;
        }
        return "rt/" + rosTopicName;
    }

    private static String parseDataArg(String dataArg) {
        // Parse "data: 'message'" or "{data: 'message'}"
        String trimmed = dataArg.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.startsWith("data:")) {
            String value = trimmed.substring(5).trim();
            // Remove surrounding quotes
            if (value.startsWith("'") && value.endsWith("'")) {
                return value.substring(1, value.length() - 1);
            }
            if (value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }
        return dataArg;
    }

    private static void printUsage() {
        System.out.println("Usage: jros <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  topic echo <topic_name>                             Subscribe and print messages");
        System.out.println("  topic pub <topic_name> <type> \"data: '<msg>'\"       Publish messages");
        System.out.println("  service call <service_name> <type> \"{a: N, b: M}\"   Call a service");
        System.out.println("  service server <service_name> <type>                Start a service server");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  jros topic echo /chatter");
        System.out.println("  jros topic pub /chatter std_msgs/msg/String \"data: 'hello'\"");
        System.out.println("  jros service server /add_two_ints example_interfaces/srv/AddTwoInts");
        System.out.println("  jros service call /add_two_ints example_interfaces/srv/AddTwoInts \"{a: 1, b: 2}\"");
    }
}
