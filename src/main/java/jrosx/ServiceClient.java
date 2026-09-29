package jrosx;

import ddsj.dds.core.DataReader;
import ddsj.dds.core.DataWriter;
import ddsj.dds.sample.Sample;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * ROS 2 Service Client.
 * Sends requests and waits for responses.
 *
 * @param <Req> Request message type
 * @param <Res> Response message type
 */
public class ServiceClient<Req, Res> implements AutoCloseable {
    private final String serviceName;
    private final DataWriter<Req> requestWriter;
    private final DataReader<Res> responseReader;

    ServiceClient(String serviceName, DataWriter<Req> requestWriter, DataReader<Res> responseReader) {
        this.serviceName = serviceName;
        this.requestWriter = requestWriter;
        this.responseReader = responseReader;
    }

    public String getServiceName() {
        return serviceName;
    }

    /**
     * Call the service and wait for response (blocking).
     *
     * @param request the request message
     * @param timeout maximum time to wait for response
     * @return the response message
     * @throws TimeoutException if no response received within timeout
     */
    public Res call(Req request, Duration timeout) throws TimeoutException, InterruptedException {
        requestWriter.write(request);

        long startTime = System.currentTimeMillis();
        long timeoutMs = timeout.toMillis();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            Optional<Sample<Res>> sample = responseReader.takeNextSample();
            if (sample.isPresent()) {
                return sample.get().data();
            }
            Thread.sleep(10); // Poll interval
        }

        throw new TimeoutException("Service call timed out: " + serviceName);
    }

    /**
     * Call the service with default timeout of 5 seconds.
     */
    public Res call(Req request) throws TimeoutException, InterruptedException {
        return call(request, Duration.ofSeconds(5));
    }

    /**
     * Call the service asynchronously (non-blocking).
     * Returns immediately after sending request.
     *
     * @param request the request message
     */
    public void callAsync(Req request) {
        requestWriter.write(request);
    }

    /**
     * Try to take a response if available (non-blocking).
     *
     * @return Optional containing response if available
     */
    public Optional<Res> takeResponse() {
        return responseReader.takeNextSample()
                .map(Sample::data);
    }

    @Override
    public void close() {
        requestWriter.close();
        responseReader.close();
    }
}
