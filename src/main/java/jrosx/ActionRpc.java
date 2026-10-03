package jrosx;

import ddsj.dds.core.DataReader;
import ddsj.dds.core.DataWriter;
import ddsj.dds.exception.ReturnCode;
import ddsj.dds.sample.Sample;
import ddsj.rtps.message.SampleIdentity;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Asynchronous RPC with full writer GUID and sequence-number correlation. */
final class ActionRpc {
    private ActionRpc() {}

    static SampleIdentity requestIdentity(Sample<?> request) {
        return RpcIdentity.request(request);
    }

    static final class Client<Q, S> implements AutoCloseable {
        private final DataWriter<Q> writer;
        private final DataReader<S> reader;
        private final Map<SampleIdentity, CompletableFuture<S>> pending = new HashMap<>();
        private boolean closed;
        Client(DataWriter<Q> writer, DataReader<S> reader) { this.writer = writer; this.reader = reader; }

        synchronized CompletableFuture<S> call(Q request, Duration timeout) {
            if (closed) throw new IllegalStateException("Action client closed");
            long nanos = timeout.toNanos();
            if (nanos <= 0) throw new IllegalArgumentException("Timeout must be positive");
            var future = new CompletableFuture<S>();
            try {
                var id = writer.writeRequest(request);
                pending.put(id, future);
                future.orTimeout(nanos, TimeUnit.NANOSECONDS).whenComplete((value, error) -> {
                    synchronized (this) { pending.remove(id); }
                });
            } catch (RuntimeException e) { future.completeExceptionally(e); }
            return future;
        }

        void poll() {
            List<Runnable> completions = new ArrayList<>();
            synchronized (this) {
                if (closed) return;
                for (var sample : reader.take()) {
                    if (!sample.hasValidData()) continue;
                    sample.relatedSampleIdentity().ifPresent(id -> {
                        var future = pending.remove(id);
                        if (future != null) completions.add(() -> future.complete(sample.data()));
                    });
                }
            }
            completions.forEach(Runnable::run);
        }

        public void close() {
            List<CompletableFuture<S>> futures;
            synchronized (this) {
                if (closed) return;
                closed = true;
                futures = List.copyOf(pending.values());
                pending.clear();
                writer.close();
                reader.close();
            }
            futures.forEach(f -> f.completeExceptionally(new CancellationException("Action client closed")));
        }
    }

    static final class Server<Q, S> implements AutoCloseable {
        private final DataReader<Q> reader;
        private final DataWriter<S> writer;
        Server(DataReader<Q> reader, DataWriter<S> writer) { this.reader = reader; this.writer = writer; }
        List<Sample<Q>> take() { return reader.take().stream().filter(Sample::hasValidData).toList(); }
        void reply(Sample<Q> request, S response) {
            var code = writer.writeWithRelatedSampleIdentity(response, requestIdentity(request));
            if (code != ReturnCode.OK) throw new IllegalStateException("Action reply failed: " + code);
        }
        public void close() { reader.close(); writer.close(); }
    }
}
