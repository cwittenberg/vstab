package app.vstab;

import android.content.Context;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Per-boot authenticated setup and browser channel; never an Android command executor. */
final class LinuxControlServer implements Closeable {
    interface Handler {
        void console(String line);
        JSONObject browser(JSONObject request) throws Exception;
    }
    private interface Content { InputStream open() throws IOException; }
    private final Context context;
    private final String token;
    private final Handler handler;
    private final AndroidDnsResolver dns = new AndroidDnsResolver();
    private final ServerSocket listener;
    private final Map<String, Content> exports = new HashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final Semaphore clients = new Semaphore(8);
    private final java.util.Set<Socket> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    LinuxControlServer(Context context, String token, Handler handler) throws IOException {
        this.context = context.getApplicationContext(); this.token = token; this.handler = handler;
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
        Thread accept = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = listener.accept();
                    if (!clients.tryAcquire()) { socket.close(); continue; }
                    active.add(socket);
                    try { workers.execute(() -> { try { serve(socket); } finally { active.remove(socket); clients.release(); } }); }
                    catch (RuntimeException stopping) { active.remove(socket); clients.release(); socket.close(); }
                } catch (IOException | RuntimeException ignored) { if (closed) break; }
            }
        }, "vstab Linux control");
        accept.setDaemon(true); accept.start();
    }

    int port() { return listener.getLocalPort(); }
    synchronized void exportAsset(String id, String asset) {
        requireId(id);
        if (asset.startsWith("/") || asset.contains("..") || asset.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Invalid Linux asset.");
        exports.put("/assets/" + id, () -> context.getAssets().open(asset));
    }
    synchronized void exportFile(String id, File file) {
        requireId(id); exports.put("/assets/" + id, () -> new FileInputStream(file));
    }
    private static void requireId(String id) {
        if (!id.matches("[a-zA-Z0-9_.-]{1,80}")) throw new IllegalArgumentException("Invalid export name.");
    }

    private void serve(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(30000);
            InputStream input = client.getInputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int state = 0;
            while (header.size() < 16384 && state < 4) {
                int c = input.read(); if (c < 0) throw new IOException("Incomplete request.");
                header.write(c);
                if (c == (state == 0 || state == 2 ? '\r' : '\n')) state++;
                else state = c == '\r' ? 1 : 0;
            }
            if (state != 4) { reply(client, 431, "Request too large"); return; }
            String[] lines = header.toString("ISO-8859-1").split("\r\n");
            String[] request = lines[0].split(" ", 3);
            if (request.length != 3) { reply(client, 400, "Invalid request"); return; }
            Map<String, String> fields = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 1) continue;
                String key = lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                if (fields.put(key, lines[i].substring(colon + 1).trim()) != null) {
                    reply(client, 400, "Duplicate request header"); return;
                }
            }
            byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, fields.getOrDefault("authorization", "").getBytes(StandardCharsets.US_ASCII))) {
                reply(client, 403, "Forbidden"); return;
            }
            if (fields.containsKey("transfer-encoding")) { reply(client, 400, "A bounded body is required"); return; }
            String route = request[1];
            if (request[0].equals("GET")) {
                Content content;
                synchronized (this) { content = exports.get(route); }
                if (content == null) { reply(client, 404, "Not found"); return; }
                try (InputStream asset = content.open()) {
                    OutputStream output = client.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    byte[] bytes = new byte[65536]; int n;
                    while (!closed && (n = asset.read(bytes)) != -1) output.write(bytes, 0, n);
                    output.flush();
                }
                return;
            }
            int length;
            try { length = Integer.parseInt(fields.getOrDefault("content-length", "-1")); }
            catch (NumberFormatException bad) { reply(client, 400, "Invalid body length"); return; }
            int bodyLimit = route.equals("/dns") ? 90000 : 65536;
            if (length < 0 || length > bodyLimit) { reply(client, 413, "Request too large"); return; }
            byte[] bytes = new byte[length]; int offset = 0;
            while (offset < length) {
                int n = input.read(bytes, offset, length - offset);
                if (n < 0) throw new IOException("Incomplete request body."); offset += n;
            }
            if (!request[0].equals("POST")) { reply(client, 405, "Method not allowed"); return; }
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (route.equals("/console")) {
                handler.console(body); reply(client, 200, "accepted");
            } else if (route.equals("/browser")) {
                try { reply(client, 200, handler.browser(new JSONObject(body)).toString()); }
                catch (Exception error) { reply(client, 409, "Browser forwarding could not be prepared. Return to VStab and retry."); }
            } else if (route.equals("/dns")) {
                try {
                    Object encoded = new JSONObject(body).opt("message");
                    if (!(encoded instanceof String) || ((String) encoded).length() > 87380)
                        throw new IllegalArgumentException("Invalid DNS message.");
                    byte[] answer = dns.query(Base64.getDecoder().decode((String) encoded));
                    reply(client, 200, new JSONObject().put("message", Base64.getEncoder().encodeToString(answer)).toString());
                } catch (AndroidDnsResolver.QueryTimeoutException error) {
                    reply(client, 504, "Android DNS query timed out.");
                } catch (org.json.JSONException | IllegalArgumentException error) {
                    reply(client, 400, "Invalid DNS message.");
                } catch (IOException error) {
                    reply(client, 502, "Android DNS query failed.");
                }
            } else reply(client, 404, "Not found");
        } catch (IOException ignored) { /* Disconnect cancels this one request. */ }
    }

    private static void reply(Socket client, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        OutputStream output = client.getOutputStream();
        output.write(("HTTP/1.1 " + status + " Response\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body); output.flush();
    }

    @Override public void close() {
        closed = true;
        dns.close();
        try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : active) try { socket.close(); } catch (IOException ignored) { }
        active.clear();
        workers.shutdownNow();
        synchronized (this) { exports.clear(); }
    }
}
