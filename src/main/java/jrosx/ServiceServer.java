package jrosx;

import ddsj.dds.core.DataReader;
import ddsj.dds.core.DataWriter;
import ddsj.dds.sample.Sample;
import ddsj.rtps.message.SampleIdentity;

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
        if (sample.isPresent()) {
            Req request = sample.get().data();
            Res response = handler.apply(request);

            // For DDS-RPC: send response with related_sample_identity
            // Use GUID from request's related_sample_identity (client's response reader GUID)
            // Use sequence number from request's DATA writerSN (not from Inline QoS which may be garbage)
            Optional<SampleIdentity> relatedId = sample.get().relatedSampleIdentity();
            if (relatedId.isPresent()) {
                // Combine: client's response reader GUID + request's writerSN
                SampleIdentity responseId = new SampleIdentity(
                        relatedId.get().writerGuid(),
                        sample.get().writerSequenceNumber());
                responseWriter.writeWithRelatedSampleIdentity(response, responseId);
            } else {
                // Fallback for non-RPC or jros-to-jros
                responseWriter.write(response);
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
