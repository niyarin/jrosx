package jrosx;

import ddsj.dds.core.*;
import ddsj.dds.qos.DomainParticipantQos;

import java.util.function.Function;

public class Node implements AutoCloseable {
    private final String name;
    private final DomainParticipant participant;
    private final ddsj.dds.core.Publisher ddsPub;
    private final Subscriber ddsSub;

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

    /**
     * Create a service client.
     *
     * @param serviceName service name (e.g., "add_two_ints")
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

        String requestTopicName = "rq/" + serviceName + "Request";
        String responseTopicName = "rr/" + serviceName + "Reply";

        Topic<Req> requestTopic = participant.createTopic(requestTopicName, requestType, requestTypeSupport);
        Topic<Res> responseTopic = participant.createTopic(responseTopicName, responseType, responseTypeSupport);

        DataWriter<Req> requestWriter = ddsPub.createDataWriter(requestTopic, Ros2QosProfiles.serviceWriter());
        DataReader<Res> responseReader = ddsSub.createDataReader(responseTopic, Ros2QosProfiles.serviceReader());

        return new ServiceClient<>(serviceName, requestWriter, responseReader);
    }

    /**
     * Create a service server.
     *
     * @param serviceName service name (e.g., "add_two_ints")
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

        String requestTopicName = "rq/" + serviceName + "Request";
        String responseTopicName = "rr/" + serviceName + "Reply";

        Topic<Req> requestTopic = participant.createTopic(requestTopicName, requestType, requestTypeSupport);
        Topic<Res> responseTopic = participant.createTopic(responseTopicName, responseType, responseTypeSupport);

        DataReader<Req> requestReader = ddsSub.createDataReader(requestTopic, Ros2QosProfiles.serviceReader());
        DataWriter<Res> responseWriter = ddsPub.createDataWriter(responseTopic, Ros2QosProfiles.serviceWriter());

        return new ServiceServer<>(serviceName, requestReader, responseWriter, handler);
    }

    @Override
    public void close() {
        participant.close();
    }
}
