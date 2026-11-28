package app.vstab;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.function.Consumer;

/** Receives browser OAuth returns without sharing browser/WebView storage. */
final class AuthCallbackServer implements Closeable {
    private final ServerSocket listener;
    private final String path;
    private volatile Consumer<String> receiver;
    private volatile boolean closed;

    AuthCallbackServer() throws IOException {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        StringBuilder token = new StringBuilder();
        for (byte value : random) token.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        path = "/" + token + "/callback";
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
        Thread thread = new Thread(this::accept, "vstab browser sign-in callback");
        thread.setDaemon(true);
        thread.start();
    }

    String url() { return "http://127.0.0.1:" + listener.getLocalPort() + path; }
    void receive(Consumer<String> receiver) { this.receiver = receiver; }

    private void accept() {
        while (!closed) {
            try (Socket client = listener.accept()) {
                client.setSoTimeout(2000);
                ByteArrayOutputStream header = new ByteArrayOutputStream();
                int state = 0;
                while (header.size() < 65536 && state < 4) {
                    int value = client.getInputStream().read();
                    if (value < 0) break;
                    header.write(value);
                    if (value == (state == 0 || state == 2 ? '\r' : '\n')) state++;
                    else state = value == '\r' ? 1 : 0;
                }
                String[] first = header.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n", 2)[0].split(" ", 3);
                Consumer<String> current = receiver;
                boolean valid = state == 4 && first.length == 3 && first[0].equals("GET")
                        && first[1].startsWith(path + "?") && current != null;
                if (valid) current.accept(url() + first[1].substring(path.length()));
                byte[] body = (valid ? "Sign-in returned to VStab. You can return to the app." : "Sign-in callback not found.")
                        .getBytes(StandardCharsets.UTF_8);
                String response = "HTTP/1.1 " + (valid ? "200 OK" : "404 Not Found")
                        + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length
                        + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
                client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().write(body);
            } catch (IOException | RuntimeException ignored) { /* A failed browser request cannot stop other returns. */ }
        }
    }

    @Override public void close() {
        closed = true;
        receiver = null;
        try { listener.close(); } catch (IOException ignored) { }
    }
}
