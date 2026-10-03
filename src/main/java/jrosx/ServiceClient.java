package jrosx;

import ddsj.dds.core.DataReader;
import ddsj.dds.core.DataWriter;
import ddsj.rtps.message.SampleIdentity;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ROS 2 service client. Responses are matched by request writer GUID and sequence number.
 * Blocking calls may run concurrently with each other and with callAsync/takeResponse.
 */
public class ServiceClient<Req, Res> implements AutoCloseable {
    private final String serviceName;
    private final DataWriter<Req> requestWriter;
    private final DataReader<Res> responseReader;
    private final Map<SampleIdentity, Pending<Res>> pending = new HashMap<>();
    private final ArrayDeque<Res> responses = new ArrayDeque<>();
    private boolean closed;

    private static final class Pending<T> {
        final boolean polled;
        T response;
        boolean completed;
        Pending(boolean polled) { this.polled = polled; }
    }

    ServiceClient(String serviceName, DataWriter<Req> requestWriter, DataReader<Res> responseReader) {
        this.serviceName = serviceName;
        this.requestWriter = requestWriter;
        this.responseReader = responseReader;
    }

    public String getServiceName() { return serviceName; }

    /**
     * Sends a request and waits only for its matching response.
     * Timeout or interruption removes the local pending request; late replies are discarded.
     * Closing the client causes waiting calls to fail with IllegalStateException.
     */
    public Res call(Req request, Duration timeout) throws TimeoutException, InterruptedException {
        long budget = Objects.requireNonNull(timeout, "timeout").toNanos();
        if (budget <= 0) throw new IllegalArgumentException("Timeout must be positive");
        if (Thread.interrupted()) throw new InterruptedException();
        var entry = new Pending<Res>(false);
        long start = System.nanoTime();
        SampleIdentity identity = send(request, entry);
        try {
            while (true) {
                if (Thread.interrupted()) throw new InterruptedException();
                if (System.nanoTime() - start >= budget)
                    throw new TimeoutException("Service call timed out: " + serviceName);
                synchronized (this) {
                    ensureOpen();
                    receive();
                    if (entry.completed) return entry.response;
                }
                long remaining = budget - (System.nanoTime() - start);
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(Math.min(remaining, 10_000_000));
            }
        } finally {
            synchronized (this) { pending.remove(identity); }
        }
    }

    /** Calls the service with a five-second timeout. */
    public Res call(Req request) throws TimeoutException, InterruptedException {
        return call(request, Duration.ofSeconds(5));
    }

    /**
     * Sends without waiting. Its response is available through takeResponse().
     * Polling requests remain pending until a reply arrives or this client closes.
     */
    public void callAsync(Req request) { send(request, new Pending<>(true)); }

    /**
     * Returns the next matching response to a callAsync request, in arrival order.
     * Responses belonging to blocking calls, other clients, expired calls, and duplicates
     * are never returned here. This also dispatches replies to concurrent blocking calls.
     */
    public synchronized Optional<Res> takeResponse() {
        ensureOpen();
        receive();
        return Optional.ofNullable(responses.poll());
    }

    private synchronized SampleIdentity send(Req request, Pending<Res> entry) {
        ensureOpen();
        // Keep receive() excluded until the identity has been registered.
        SampleIdentity identity = requestWriter.writeRequest(Objects.requireNonNull(request, "request"));
        pending.put(identity, entry);
        return identity;
    }

    /** Called with the client monitor held. */
    private void receive() {
        for (var sample : responseReader.take()) {
            if (!sample.hasValidData()) continue;
            var identity = sample.relatedSampleIdentity();
            if (identity.isEmpty()) continue;
            var entry = pending.remove(identity.get());
            if (entry == null) continue;
            if (entry.polled) responses.add(sample.data());
            else {
                entry.response = sample.data();
                entry.completed = true;
            }
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Service client closed: " + serviceName);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        pending.clear();
        responses.clear();
        try { requestWriter.close(); }
        finally { responseReader.close(); }
    }
}
