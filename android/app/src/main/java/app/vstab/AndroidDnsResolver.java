package app.vstab;

import android.net.DnsResolver;
import android.os.CancellationSignal;

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Raw DNS through Android's current network resolver, including its VPN/DNS policy. */
final class AndroidDnsResolver implements Closeable {
    static final int MAX_PACKET_BYTES = 65535;
    interface Cancellation { void cancel(); }
    interface Callback {
        void answer(byte[] message);
        void failure(IOException error);
    }
    interface Transport extends Closeable {
        Cancellation query(byte[] message, Callback callback) throws IOException;
        @Override default void close() { }
    }
    static final class QueryTimeoutException extends IOException {
        QueryTimeoutException() { super("Android DNS query timed out."); }
    }

    private static final Cancellation CANCELLED = () -> { };
    private static final class Request {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final AtomicReference<Cancellation> cancellation = new AtomicReference<>();
        void attach(Cancellation operation) {
            if (!cancellation.compareAndSet(null, operation)) operation.cancel();
        }
        void cancel() {
            result.completeExceptionally(new IOException("Android DNS query was cancelled."));
            Cancellation operation = cancellation.getAndSet(CANCELLED);
            if (operation != null) operation.cancel();
        }
    }

    private final Transport transport;
    private final long timeoutMillis;
    private final Set<Request> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    AndroidDnsResolver() { this(new PlatformTransport(), 8000); }
    AndroidDnsResolver(Transport transport, long timeoutMillis) {
        if (timeoutMillis < 1 || timeoutMillis > 30000) throw new IllegalArgumentException("Invalid DNS timeout.");
        this.transport = transport;
        this.timeoutMillis = timeoutMillis;
    }

    byte[] query(byte[] message) throws IOException {
        if (message == null || message.length < 12 || message.length > MAX_PACKET_BYTES)
            throw new IllegalArgumentException("DNS packets must contain 12 to 65535 bytes.");
        if (closed) throw new IOException("Android DNS resolver is closed.");
        byte[] question = message.clone();
        Request request = new Request();
        active.add(request);
        try {
            if (closed) throw new IOException("Android DNS resolver is closed.");
            request.attach(transport.query(question.clone(), new Callback() {
                @Override public void answer(byte[] answer) {
                    if (answer == null || answer.length < 12 || answer.length > MAX_PACKET_BYTES) {
                        request.result.completeExceptionally(new IOException("Android returned an invalid DNS packet."));
                        return;
                    }
                    byte[] response = answer.clone();
                    // Android/netd may use its own transaction ID internally.
                    response[0] = question[0]; response[1] = question[1];
                    request.result.complete(response);
                }
                @Override public void failure(IOException error) {
                    request.result.completeExceptionally(error);
                }
            }));
            return request.result.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            throw new QueryTimeoutException();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Android DNS query interrupted.", error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException) throw (IOException) error.getCause();
            throw new IOException("Android DNS query failed.", error.getCause());
        } finally {
            active.remove(request);
            request.cancel();
        }
    }

    @Override public void close() {
        closed = true;
        for (Request request : active) request.cancel();
        transport.close();
    }

    private static final class PlatformTransport implements Transport {
        private final ExecutorService callbacks = Executors.newFixedThreadPool(2);
        @Override public Cancellation query(byte[] message, Callback callback) throws IOException {
            CancellationSignal cancellation = new CancellationSignal();
            try {
                DnsResolver.getInstance().rawQuery(null, message, DnsResolver.FLAG_EMPTY, callbacks, cancellation,
                        new DnsResolver.Callback<byte[]>() {
                            @Override public void onAnswer(byte[] answer, int rcode) {
                                // NXDOMAIN and other DNS rcodes remain intact in the raw packet.
                                callback.answer(answer);
                            }
                            @Override public void onError(DnsResolver.DnsException error) {
                                callback.failure(new IOException("Android DNS query failed (code " + error.code + ").", error));
                            }
                        });
            } catch (RuntimeException error) {
                cancellation.cancel();
                throw new IOException("Android DNS resolver could not start the query.", error);
            }
            return cancellation::cancel;
        }
        @Override public void close() { callbacks.shutdownNow(); }
    }
}
