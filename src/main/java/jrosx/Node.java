package jrosx;

import ddsj.dds.core.*;
import ddsj.dds.qos.DomainParticipantQos;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayList;
import java.time.Duration;

public class Node implements AutoCloseable {
    private final String name;
    private final DomainParticipant participant;
    private final ddsj.dds.core.Publisher ddsPub;
    private final Subscriber ddsSub;
    private final List<AutoCloseable> actions = new ArrayList<>();
    private boolean closed;

    public Node(String name) {
        this(name, 0, 1);
    }

    public Node(String name, int domainId) {
        this(name, domainId, 1);
    }

    public Node(String name, int domainId, int participantIndex) {
        this.name = name;
        DomainParticipantQos qos = DomainParticipantQos.withParticipantIndex(participantIndex);
        this.participant = DomainParticipantFactory.getInstance()
                .createParticipant(domainId, qos);
        this.ddsPub = participant.createPublisher();
        this.ddsSub = participant.createSubscriber();
    }

    public String getName() {
        return name;
    }

    /**
     * Creates a publisher using CDR serialization and a ROS type name derived
     * from the record's package and class name (e.g. std_msgs.msg.String_).
     *
     * @param topicName ROS topic name, e.g. /chatter or chatter
     * @param type message record class in a ROS message package such as std_msgs.msg
     */
    public <T extends Record> Publisher<T> createPublisher(String topicName, Class<T> type) {
        return createPublisher(topicName, type, Ros2TypeSupport.of(type));
    }

    /** Creates a publisher for a ROS topic name, e.g. /chatter or chatter. */
    public <T> Publisher<T> createPublisher(String topicName, Class<T> type, TypeSupport<T> typeSupport) {
        Topic<T> topic = participant.createTopic(toDdsTopicName(topicName), type, typeSupport);
        DataWriter<T> writer = ddsPub.createDataWriter(topic, Ros2QosProfiles.defaultWriter());
        return new Publisher<>(topicName, writer);
    }

    /**
     * Creates a subscription using CDR serialization and a ROS type name derived
     * from the record's package and class name (e.g. std_msgs.msg.String_).
     *
     * @param topicName ROS topic name, e.g. /chatter or chatter
     * @param type message record class in a ROS message package such as std_msgs.msg
     */
    public <T extends Record> Subscription<T> createSubscription(String topicName, Class<T> type) {
        return createSubscription(topicName, type, Ros2TypeSupport.of(type));
    }

    /** Creates a subscription for a ROS topic name, e.g. /chatter or chatter. */
    public <T> Subscription<T> createSubscription(String topicName, Class<T> type, TypeSupport<T> typeSupport) {
        Topic<T> topic = participant.createTopic(toDdsTopicName(topicName), type, typeSupport);
        DataReader<T> reader = ddsSub.createDataReader(topic, Ros2QosProfiles.defaultReader());
        return new Subscription<>(topicName, reader);
    }

    private static String toDdsTopicName(String topicName) {
        return topicName.startsWith("/") ? "rt" + topicName : "rt/" + topicName;
    }

    private static String toDdsServiceTopicName(String prefix, String serviceName, String suffix) {
        return prefix + (serviceName.startsWith("/") ? serviceName : "/" + serviceName) + suffix;
    }

    /**
     * Create a service client.
     *
     * @param serviceName service name (e.g., "add_two_ints" or "/add_two_ints")
     * @param requestType request message class
     * @param responseType response message class
     * @param requestTypeSupport type support for request
     * @param responseTypeSupport type support for response
     */
    public <Req, Res> ServiceClient<Req, Res> createClient(
            String serviceName,
            Class<Req> requestType,
            Class<Res> responseType,
            TypeSupport<Req> requestTypeSupport,
            TypeSupport<Res> responseTypeSupport) {

        String requestTopicName = toDdsServiceTopicName("rq", serviceName, "Request");
        String responseTopicName = toDdsServiceTopicName("rr", serviceName, "Reply");

        Topic<Req> requestTopic = participant.createTopic(requestTopicName, requestType, requestTypeSupport);
        Topic<Res> responseTopic = participant.createTopic(responseTopicName, responseType, responseTypeSupport);

        DataWriter<Req> requestWriter = ddsPub.createDataWriter(requestTopic, Ros2QosProfiles.serviceWriter());
        DataReader<Res> responseReader = ddsSub.createDataReader(responseTopic, Ros2QosProfiles.serviceReader());

        return new ServiceClient<>(serviceName, requestWriter, responseReader);
    }

    /**
     * Create a service server.
     *
     * @param serviceName service name (e.g., "add_two_ints" or "/add_two_ints")
     * @param requestType request message class
     * @param responseType response message class
     * @param requestTypeSupport type support for request
     * @param responseTypeSupport type support for response
     * @param handler function to handle requests
     */
    public <Req, Res> ServiceServer<Req, Res> createService(
            String serviceName,
            Class<Req> requestType,
            Class<Res> responseType,
            TypeSupport<Req> requestTypeSupport,
            TypeSupport<Res> responseTypeSupport,
            Function<Req, Res> handler) {

        String requestTopicName = toDdsServiceTopicName("rq", serviceName, "Request");
        String responseTopicName = toDdsServiceTopicName("rr", serviceName, "Reply");

        Topic<Req> requestTopic = participant.createTopic(requestTopicName, requestType, requestTypeSupport);
        Topic<Res> responseTopic = participant.createTopic(responseTopicName, responseType, responseTypeSupport);

        DataReader<Req> requestReader = ddsSub.createDataReader(requestTopic, Ros2QosProfiles.serviceReader());
        DataWriter<Res> responseWriter = ddsPub.createDataWriter(responseTopic, Ros2QosProfiles.serviceWriter());

        return new ServiceServer<>(serviceName, requestReader, responseWriter, handler);
    }


    /** Creates an action client with an internal receive loop. */
    public synchronized <G extends Record, R extends Record, F extends Record> ActionClient<G, R, F> createActionClient(
            String actionName, ActionType<G, R, F> type) {
        if (closed) throw new IllegalStateException("Node is closed");
        var client = new ActionClient<>(this, actionName, type);
        actions.add(client);
        return client;
    }

    /** Creates a server; call spin() to process requests. */
    public <G extends Record, R extends Record, F extends Record> ActionServer<G, R, F> createActionServer(
            String actionName, ActionType<G, R, F> type, Consumer<ActionServer.GoalHandle<G, R, F>> execute) {
        return createActionServer(actionName, type, goal -> true, goal -> true, execute, Duration.ofMinutes(15));
    }

    /** Result retention is nonnegative, or -1 seconds to retain results until close. */
    public synchronized <G extends Record, R extends Record, F extends Record> ActionServer<G, R, F> createActionServer(
            String actionName, ActionType<G, R, F> type, Predicate<G> accept,
            Predicate<ActionServer.GoalHandle<G, R, F>> cancel,
            Consumer<ActionServer.GoalHandle<G, R, F>> execute, Duration resultRetention) {
        if (closed) throw new IllegalStateException("Node is closed");
        var server = new ActionServer<>(this, actionName, type, accept, cancel, execute, resultRetention);
        actions.add(server);
        return server;
    }


    boolean hasActionServer(String base, ActionType<?, ?, ?> type) {
        var publications = participant.getDiscoveredPublications();
        var subscriptions = participant.getDiscoveredSubscriptions();
        return publications.stream().anyMatch(p -> p.topicName().equals("rr/" + base + "send_goalReply")
                    && p.typeName().equals(type.sendResponse.getTypeName()))
                && publications.stream().anyMatch(p -> p.topicName().equals("rr/" + base + "get_resultReply")
                    && p.typeName().equals(type.resultResponse.getTypeName()))
                && publications.stream().anyMatch(p -> p.topicName().equals("rr/" + base + "cancel_goalReply")
                    && p.typeName().equals(ActionType.CANCEL_RESPONSE.getTypeName()))
                && publications.stream().anyMatch(p -> p.topicName().equals("rt/" + base + "feedback")
                    && p.typeName().equals(type.feedback.getTypeName()))
                && publications.stream().anyMatch(p -> p.topicName().equals("rt/" + base + "status")
                    && p.typeName().equals(ActionType.STATUS.getTypeName()))
                && subscriptions.stream().anyMatch(p -> p.topicName().equals("rq/" + base + "send_goalRequest")
                    && p.typeName().equals(type.sendRequest.getTypeName()))
                && subscriptions.stream().anyMatch(p -> p.topicName().equals("rq/" + base + "get_resultRequest")
                    && p.typeName().equals(type.resultRequest.getTypeName()))
                && subscriptions.stream().anyMatch(p -> p.topicName().equals("rq/" + base + "cancel_goalRequest")
                    && p.typeName().equals(ActionType.CANCEL_REQUEST.getTypeName()));
    }

    static String actionName(String name) {
        if (name == null) throw new IllegalArgumentException("Action name is required");
        String normalized = name.startsWith("/") ? name.substring(1) : name;
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*(/[A-Za-z_][A-Za-z0-9_]*)*"))
            throw new IllegalArgumentException("Invalid action name: " + name);
        return normalized;
    }

    <Q, S> ActionRpc.Client<Q, S> actionRpcClient(String name, TypeSupport<Q> request, TypeSupport<S> response) {
        var requestTopic = participant.createTopic("rq/" + name + "Request", request.getType(), request);
        var responseTopic = participant.createTopic("rr/" + name + "Reply", response.getType(), response);
        var writer = ddsPub.createDataWriter(requestTopic, Ros2QosProfiles.serviceWriter());
        try {
            return new ActionRpc.Client<>(writer, ddsSub.createDataReader(responseTopic, Ros2QosProfiles.serviceReader()));
        } catch (RuntimeException e) { writer.close(); throw e; }
    }

    <Q, S> ActionRpc.Server<Q, S> actionRpcServer(String name, TypeSupport<Q> request, TypeSupport<S> response) {
        var requestTopic = participant.createTopic("rq/" + name + "Request", request.getType(), request);
        var responseTopic = participant.createTopic("rr/" + name + "Reply", response.getType(), response);
        var reader = ddsSub.createDataReader(requestTopic, Ros2QosProfiles.serviceReader());
        try {
            return new ActionRpc.Server<>(reader, ddsPub.createDataWriter(responseTopic, Ros2QosProfiles.serviceWriter()));
        } catch (RuntimeException e) { reader.close(); throw e; }
    }

    Publisher<ActionMessages.StatusArray> actionStatusPublisher(String name) {
        var topic = participant.createTopic(toDdsTopicName(name), ActionMessages.StatusArray.class, ActionType.STATUS);
        return new Publisher<>(name, ddsPub.createDataWriter(topic, Ros2QosProfiles.actionStatusWriter()));
    }

    Subscription<ActionMessages.StatusArray> actionStatusSubscription(String name) {
        var topic = participant.createTopic(toDdsTopicName(name), ActionMessages.StatusArray.class, ActionType.STATUS);
        return new Subscription<>(name, ddsSub.createDataReader(topic, Ros2QosProfiles.actionStatusReader()));
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (var action : actions) {
            try { action.close(); }
            catch (Exception e) {
                if (failure == null) failure = new IllegalStateException("Failed to close actions", e);
                else failure.addSuppressed(e);
            }
        }
        actions.clear();
        try { participant.close(); }
        catch (RuntimeException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }
}
