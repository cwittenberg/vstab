package app.vstab;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the browser origin while the native Node backend uses a private socket. */
final class LocalEditorGateway implements Closeable {
    static final String COOKIE = "vstab-native-gateway";
    private static final int MAX_CONNECTIONS = 24;
    private final ServerSocket listener;
    private final String secret;
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final Set<Relay> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(null, task, "vstab local editor relay", 256 * 1024);
        thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String backend;

    LocalEditorGateway(int preferredPort, String secret) throws IOException {
        this.secret = secret;
        ServerSocket chosen = new ServerSocket();
        try { chosen.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort), MAX_CONNECTIONS); }
        catch (IOException occupied) {
            chosen.close();
            if (preferredPort == 0) throw occupied;
            chosen = new ServerSocket();
            chosen.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), MAX_CONNECTIONS);
        }
        listener = chosen;
        Thread acceptor = new Thread(this::accept, "vstab local editor listener");
        acceptor.setDaemon(true); acceptor.start();
    }

    int port() { return listener.getLocalPort(); }
    void selectBackend(String socketPath) { backend = socketPath; }
    void withdrawBackend() { backend = null; for (Relay relay : active) relay.close(); }
    String cookie() { return COOKIE + "=" + secret + "; Path=/; HttpOnly; SameSite=Strict"; }
    String requestCookie() { return COOKIE + "=" + secret; }

    private void accept() {
        while (!closed.get()) {
            try {
                Socket client = listener.accept();
                if (!slots.tryAcquire()) { reject(client, 503, "Service Unavailable"); continue; }
                Relay relay = new Relay(client);
                active.add(relay);
                try { workers.execute(relay::run); }
                catch (java.util.concurrent.RejectedExecutionException ending) { relay.close(); }
            } catch (IOException error) { if (!closed.get()) close(); }
        }
    }

    private final class Relay implements Closeable {
        private final Socket client;
        private final AtomicBoolean ended = new AtomicBoolean();
        private LocalSocket target;
        Relay(Socket client) { this.client = client; }

        void run() {
            try {
                client.setTcpNoDelay(true);
                client.setSoTimeout(10000);
                byte[] header = readHeader(client.getInputStream());
                if (!authenticated(header)) { reject(client, 403, "Forbidden"); return; }
                String destination = backend;
                if (destination == null) { reject(client, 503, "Service Unavailable"); return; }
                target = new LocalSocket();
                target.connect(new LocalSocketAddress(destination, LocalSocketAddress.Namespace.FILESYSTEM));
                target.setSoTimeout(120000);
                client.setSoTimeout(120000);
                target.getOutputStream().write(header);
                target.getOutputStream().flush();
                workers.execute(() -> {
                    try { pump(target.getInputStream(), client.getOutputStream()); }
                    catch (IOException ignored) { }
                    finally { close(); }
                });
                pump(client.getInputStream(), target.getOutputStream());
            } catch (IOException error) {
                if (!ended.get()) reject(client, 503, "Service Unavailable");
            } finally { close(); }
        }

        @Override public void close() {
            if (!ended.compareAndSet(false, true)) return;
            try { client.close(); } catch (IOException ignored) { }
            if (target != null) try { target.close(); } catch (IOException ignored) { }
            active.remove(this); slots.release();
        }
    }

    private boolean authenticated(byte[] header) {
        String text = new String(header, StandardCharsets.ISO_8859_1);
        for (String line : text.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 1 || !line.substring(0, colon).equalsIgnoreCase("cookie")) continue;
            for (String cookie : line.substring(colon + 1).split(";")) {
                String trimmed = cookie.trim();
                int equals = trimmed.indexOf('=');
                if (equals > 0 && trimmed.substring(0, equals).equals(COOKIE)) {
                    return MessageDigest.isEqual(trimmed.substring(equals + 1).getBytes(StandardCharsets.US_ASCII), secret.getBytes(StandardCharsets.US_ASCII));
                }
            }
        }
        return false;
    }

    private static byte[] readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int sequence = 0;
        while (bytes.size() < 65536) {
            int value = input.read();
            if (value < 0) throw new IOException("Incomplete request");
            bytes.write(value);
            if (value == (sequence == 0 || sequence == 2 ? '\r' : '\n')) sequence++;
            else sequence = value == '\r' ? 1 : 0;
            if (sequence == 4) return bytes.toByteArray();
        }
        throw new IOException("Request header too large");
    }

    private static void pump(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32768]; int count;
        while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); output.flush(); }
    }

    private static void reject(Socket client, int status, String reason) {
        try { client.getOutputStream().write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); }
        catch (IOException ignored) { }
        try { client.close(); } catch (IOException ignored) { }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        backend = null;
        try { listener.close(); } catch (IOException ignored) { }
        for (Relay relay : active) relay.close();
        workers.shutdownNow();
    }
}
