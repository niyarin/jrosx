package jrosx;

import ddsj.dds.core.DataWriter;

public class Publisher<T> implements AutoCloseable {
    private final String topicName;
    private final DataWriter<T> writer;

    Publisher(String topicName, DataWriter<T> writer) {
        this.topicName = topicName;
        this.writer = writer;
    }

    public String getTopicName() {
        return topicName;
    }

    public void publish(T message) {
        writer.write(message);
    }

    @Override
    public void close() throws Exception {
        writer.close();
    }
}
