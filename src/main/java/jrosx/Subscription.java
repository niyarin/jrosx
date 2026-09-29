package jrosx;

import ddsj.dds.core.DataReader;
import ddsj.dds.sample.Sample;
import java.util.List;
import java.util.Optional;

public class Subscription<T> implements AutoCloseable {
    private final String topicName;
    private final DataReader<T> reader;

    Subscription(String topicName, DataReader<T> reader) {
        this.topicName = topicName;
        this.reader = reader;
    }

    public String getTopicName() {
        return topicName;
    }

    public List<Sample<T>> take() {
        return reader.take();
    }

    public Optional<Sample<T>> takeOne() {
        return reader.takeNextSample();
    }

    @Override
    public void close() {
        reader.close();
    }
}
