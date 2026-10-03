package jrosx;

import ddsj.dds.core.DataReader;
import ddsj.dds.core.DataWriter;
import ddsj.dds.sample.Sample;
import ddsj.dds.exception.ReturnCode;

import java.util.Optional;
import java.util.function.Function;

/**
 * ROS 2 Service Server.
 * Receives requests and sends responses.
 *
 * @param <Req> Request message type
 * @param <Res> Response message type
 */
public class ServiceServer<Req, Res> implements AutoCloseable {
    private final String serviceName;
    private final DataReader<Req> requestReader;
    private final DataWriter<Res> responseWriter;
    private final Function<Req, Res> handler;
    private volatile boolean running = false;

    ServiceServer(String serviceName, DataReader<Req> requestReader,
                  DataWriter<Res> responseWriter, Function<Req, Res> handler) {
        this.serviceName = serviceName;
        this.requestReader = requestReader;
        this.responseWriter = responseWriter;
        this.handler = handler;
    }

    public String getServiceName() {
        return serviceName;
    }

    /**
     * Process one request if available (non-blocking).
     *
     * @return true if a request was processed, false otherwise
     */
    public boolean spinOnce() {
        Optional<Sample<Req>> sample = requestReader.takeNextSample();
        if (sample.isPresent() && sample.get().hasValidData()) {
            var request = sample.get();
            var identity = RpcIdentity.request(request);
            Res response = handler.apply(request.data());
            var code = responseWriter.writeWithRelatedSampleIdentity(response, identity);
            if (code != ReturnCode.OK) {
                throw new IllegalStateException("Service response write failed: " + serviceName + ": " + code);
            }
            return true;
        }
        return false;
    }

    /**
     * Continuously process requests (blocking).
     * Call shutdown() to stop.
     *
     * @param pollIntervalMs milliseconds to wait between polls when idle
     */
    public void spin(long pollIntervalMs) throws InterruptedException {
        running = true;
        while (running) {
            if (!spinOnce()) {
                Thread.sleep(pollIntervalMs);
            }
        }
    }

    /**
     * Spin with default 10ms poll interval.
     */
    public void spin() throws InterruptedException {
        spin(10);
    }

    /**
     * Stop the spin loop.
     */
    public void shutdown() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        shutdown();
        requestReader.close();
        responseWriter.close();
    }
}
