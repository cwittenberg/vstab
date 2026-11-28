package app.vstab;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class ConnectionController {
    interface Observer {
        void onStatus(String status, boolean busy);
        void onHostKey(String host, String algorithm, String fingerprint, Consumer<Boolean> answer);
        void onReady(Ready ready);
        void onFailure(String message);
        default void onConnectionPhase(String phase, String message) { }
    }

    static final class Profile {
        final String host, user, password, folder, passphrase, savedHostId;
        final int port;
        final Uri key;
        Profile(String host, int port, String user, String password, String folder, Uri key, String passphrase) {
            this(host, port, user, password, folder, key, passphrase, "");
        }
        Profile(String host, int port, String user, String password, String folder, Uri key, String passphrase, String savedHostId) {
            this.host = host; this.port = port; this.user = user; this.password = password;
            this.folder = folder; this.key = key; this.passphrase = passphrase;
            this.savedHostId = savedHostId;
        }
    }

    static final class Ready {
        final String origin, url, host, user, version, serverVersion, home, folder, savedHostId;
        final int remotePort, sshPort;
        final boolean localRuntime;
        final List<String> cookies;
        Ready(String origin, String url, Profile profile, String version, String serverVersion,
              String home, String folder, int remotePort, List<String> cookies) {
            this(origin, url, profile, version, serverVersion, home, folder, remotePort, cookies, false);
        }
        Ready(String origin, String url, Profile profile, String version, String serverVersion,
              String home, String folder, int remotePort, List<String> cookies, boolean localRuntime) {
            this.origin = origin; this.url = url; this.host = profile.host; this.user = profile.user;
            this.version = version; this.remotePort = remotePort; this.cookies = cookies;
            this.serverVersion = serverVersion; this.home = home; this.folder = folder;
            this.localRuntime = localRuntime;
            this.sshPort = profile.port; this.savedHostId = profile.savedHostId;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final Object connectionLock = new Object();
    private volatile Observer observer;
    private volatile Session session;
    private volatile SshPortForwards portForwards;
    private volatile ChannelExec bootstrap;
    private volatile boolean busy;
    private volatile Ready ready;
    private volatile CountDownLatch hostDecision;
    private final List<String> logs = new ArrayList<>();

    private final String loopbackHost;
    ConnectionController(Context context) { this(context, "127.0.0.1"); }
    ConnectionController(Context context, String loopbackHost) {
        if (!WorkspaceWindows.isWorkbenchHost(loopbackHost) || "localhost".equals(loopbackHost))
            throw new IllegalArgumentException("A private loopback host is required.");
        this.loopbackHost = loopbackHost;
        this.context = context.getApplicationContext();
        // Android's bundled provider is intentionally old. This private app process uses
        // the pinned provider so modern OpenSSH key formats work without native helpers.
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
        JSch.setConfig("FingerprintHash", "sha256");
    }

    void observe(Observer observer) { this.observer = observer; }
    void unobserve(Observer observer) { if (this.observer == observer) this.observer = null; }
    void close() { disconnect(); worker.shutdownNow(); }
    String loopbackHost() { return loopbackHost; }
    boolean isBusy() { return busy; }
    Ready current() { return ready; }
    String logText() { synchronized (logs) { return String.join("\n", logs); } }

    void connect(Profile profile) {
        disconnect();
        int operation = generation.incrementAndGet();
        busy = true;
        synchronized (logs) { logs.clear(); }
        phase(operation, "connecting", "Connecting to " + profile.host + ":" + profile.port);
        worker.execute(() -> {
            Session opened = null;
            try {
                JSch jsch = new JSch();
                jsch.setHostKeyRepository(new CheckedHosts(profile, operation));
                if (profile.key != null) {
                    byte[] key;
                    try (InputStream stream = context.getContentResolver().openInputStream(profile.key)) {
                        if (stream == null) throw new IllegalArgumentException("The selected key file cannot be opened. Select it again.");
                        key = readBounded(stream, 1024 * 1024);
                    }
                    byte[] passphrase = profile.passphrase.isEmpty() ? null : profile.passphrase.getBytes(StandardCharsets.UTF_8);
                    try { jsch.addIdentity("vstab-selected-key", key, null, passphrase); }
                    finally { Arrays.fill(key, (byte) 0); if (passphrase != null) Arrays.fill(passphrase, (byte) 0); }
                }
                opened = jsch.getSession(profile.user, profile.host, profile.port);
                opened.setConfig("StrictHostKeyChecking", "yes");
                opened.setConfig("PreferredAuthentications", profile.key == null ? "password" : "publickey");
                if (profile.key == null) opened.setPassword(profile.password);
                opened.setServerAliveInterval(15000);
                opened.setServerAliveCountMax(3);
                synchronized (connectionLock) {
                    checkCurrent(operation);
                    session = opened;
                }
                opened.connect(60000);
                checkCurrent(operation);
                phase(operation, "preparing", "SSH authenticated. Preparing remote workspace…");
                JSONObject result = prepareServer(opened, operation);
                int remotePort = result.getInt("port");
                if (remotePort < 1 || remotePort > 65535 || result.optInt("protocol") != 1) {
                    throw new IllegalStateException("The remote server returned an unsupported connection protocol.");
                }
                String serverPassword = result.getString("password");
                if (serverPassword.length() < 16) throw new IllegalStateException("The remote server did not provide secure authentication.");
                String remoteHome = result.optString("home", result.getString("workspace"));
                String folder = profile.folder.isEmpty() ? "" : resolveFolder(profile.folder, remoteHome, result.getString("workspace"));
                update(operation, "Opening encrypted workbench tunnel…");
                int localPort = openWorkspaceForward(opened, profile, remotePort, operation);
                String origin = "http://" + loopbackHost + ":" + localPort;
                List<String> cookies = login(origin, serverPassword);
                checkCurrent(operation);
                Ready completed = new Ready(origin, folder.isEmpty() ? origin + "/" : origin + "/?folder=" + encode(folder), profile,
                        result.optString("codeVersion", result.optString("serverVersion")),
                        result.getString("serverVersion"), remoteHome, folder, remotePort, cookies);
                final Session connected = opened;
                SshPortForwards forwards = new SshPortForwards(SshPortForwards.localHostFor(loopbackHost), new SshPortForwards.Transport() {
                    @Override public boolean isConnected() { return connected.isConnected(); }
                    @Override public int open(String localHost, int localPort, String remoteHost, int remotePort) throws Exception {
                        return connected.setPortForwardingL(localHost, localPort, remoteHost, remotePort);
                    }
                    @Override public void close(String localHost, int localPort) throws Exception {
                        connected.delPortForwardingL(localHost, localPort);
                    }
                });
                synchronized (connectionLock) {
                    checkCurrent(operation);
                    portForwards = forwards;
                    ready = completed;
                    busy = false;
                }
                phase(operation, "connected", "Connected over SSH · Code " + completed.version);
                main.post(() -> { if (operation == generation.get() && observer != null) observer.onReady(completed); });
                monitor(opened, operation);
            } catch (Exception error) {
                if (opened != null) opened.disconnect();
                synchronized (connectionLock) {
                    if (operation != generation.get()) return;
                    SshPortForwards forwards = portForwards;
                    portForwards = null;
                    if (forwards != null) forwards.invalidate();
                    busy = false; ready = null;
                }
                String reason = friendlyError(error);
                phase(operation, "error", reason);
                main.post(() -> { if (operation == generation.get() && observer != null) observer.onFailure(reason); });
            }
        });
    }

    private void monitor(Session opened, int operation) {
        Thread watcher = new Thread(() -> {
            try {
                while (operation == generation.get() && opened.isConnected()) Thread.sleep(1000);
                synchronized (connectionLock) {
                    if (operation != generation.get()) return;
                    SshPortForwards forwards = portForwards;
                    portForwards = null;
                    if (forwards != null) forwards.invalidate();
                    ready = null; busy = false;
                }
                opened.disconnect();
                String reason = "The SSH connection was interrupted. Reconnect to resume this workspace.";
                phase(operation, "error", reason);
                main.post(() -> { if (operation == generation.get() && observer != null) observer.onFailure(reason); });
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }, "vstab SSH connection monitor");
        watcher.setDaemon(true); watcher.start();
    }

    void disconnect() {
        CountDownLatch pending;
        ChannelExec running;
        Session opened;
        synchronized (connectionLock) {
            generation.incrementAndGet();
            busy = false; ready = null;
            SshPortForwards forwards = portForwards;
            portForwards = null;
            if (forwards != null) forwards.invalidate();
            pending = hostDecision;
            hostDecision = null;
            running = bootstrap;
            bootstrap = null;
            opened = session;
            session = null;
        }
        if (pending != null) pending.countDown();
        if (running != null) running.disconnect();
        if (opened != null) opened.disconnect();
    }

    void tunnelRequest(String method, JSONObject params, Consumer<JSONObject> success,
                       Consumer<NativeSshBridge.Failure> failure) {
        final SshPortForwards owner = portForwards;
        worker.execute(() -> {
            try {
                if (owner == null || owner != portForwards || ready == null)
                    throw new IllegalStateException("Connect to an SSH workspace before forwarding a port.");
                JSONObject result;
                switch (method) {
                    case "tunnels.forward": {
                        String host = params.has("remoteHost") ? params.getString("remoteHost") : "127.0.0.1";
                        int remotePort = tunnelPort(params, "remotePort");
                        Integer localPort = params.has("localPort") ? tunnelPort(params, "localPort") : null;
                        result = tunnelJson(owner.forward(host, remotePort, localPort));
                        break;
                    }
                    case "tunnels.close":
                        result = new JSONObject().put("closed", owner.close(params.getString("id"), params.getString("connectionId")));
                        break;
                    case "tunnels.list": {
                        JSONArray tunnels = new JSONArray();
                        for (SshPortForwards.Forward forward : owner.list()) tunnels.put(tunnelJson(forward));
                        result = new JSONObject().put("connectionId", owner.connectionId).put("tunnels", tunnels);
                        break;
                    }
                    default: throw new IllegalArgumentException("This port-forwarding action is unavailable.");
                }
                if (owner != portForwards) throw new IllegalStateException("The SSH connection changed. Retry the port action.");
                success.accept(result);
            } catch (Exception error) {
                String code = error instanceof SecurityException ? "NoPermissions" :
                        error instanceof IllegalArgumentException ? "InvalidConfiguration" : "Unavailable";
                failure.accept(new NativeSshBridge.Failure(code, error.getMessage() == null ? "The SSH port action failed." : error.getMessage()));
            }
        });
    }

    private static int tunnelPort(JSONObject params, String key) throws Exception {
        Object value = params.get(key);
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())
                || ((Number) value).doubleValue() != Math.rint(((Number) value).doubleValue())
                || ((Number) value).doubleValue() < 0 || ((Number) value).doubleValue() > 65535)
            throw new IllegalArgumentException("The port must be a whole number between 0 and 65535.");
        return ((Number) value).intValue();
    }

    private static JSONObject tunnelJson(SshPortForwards.Forward forward) throws Exception {
        return new JSONObject().put("id", forward.id).put("connectionId", forward.connectionId)
                .put("remoteHost", forward.remoteHost).put("remotePort", forward.remotePort)
                .put("localHost", forward.localHost).put("localPort", forward.localPort).put("localAddress", forward.localAddress);
    }

    synchronized Ready recordWorkspace(String path) {
        Ready workspace = ready;
        Session opened = session;
        if (workspace == null || opened == null || !opened.isConnected())
            throw new IllegalStateException("This SSH session is no longer connected.");
        if (path == null || path.length() > 4096 || path.indexOf('\0') >= 0 || (!path.isEmpty() && !path.startsWith("/")))
            throw new IllegalArgumentException("The selected remote workspace path must be absolute.");
        Profile profile = new Profile(workspace.host, workspace.sshPort, workspace.user, "", path, null, "", workspace.savedHostId);
        Ready changed = new Ready(workspace.origin, path.isEmpty() ? workspace.origin + "/" : workspace.origin + "/?folder=" + encode(path),
                profile, workspace.version, workspace.serverVersion, workspace.home, path, workspace.remotePort, workspace.cookies);
        ready = changed;
        return changed;
    }

    private int openWorkspaceForward(Session opened, Profile profile, int remotePort, int operation) throws Exception {
        SharedPreferences ports = context.getSharedPreferences("remote-workspace-origins", Context.MODE_PRIVATE);
        String identity = loopbackHost + "\0" + profile.host.toLowerCase(java.util.Locale.ROOT) + "\0" + profile.port + "\0" + profile.user;
        String key = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        Set<Integer> reserved = new HashSet<>();
        for (Map.Entry<String, ?> item : ports.getAll().entrySet()) {
            if (!item.getKey().equals(key) && item.getValue() instanceof Integer) reserved.add((Integer) item.getValue());
            if (item.getKey().startsWith("owner:") && item.getValue() instanceof String && !item.getValue().equals(key)) {
                try { reserved.add(Integer.parseInt(item.getKey().substring("owner:".length()))); }
                catch (NumberFormatException ignored) { }
            }
        }
        int preferred = ports.getInt(key, 0);
        if (preferred >= 1024 && preferred <= 65535 && !reserved.contains(preferred)) {
            // Keep historical origins owned by their profile even if a later
            // reconnect must fall back because another app occupies the port.
            if (!ports.edit().putString("owner:" + preferred, key).commit()) {
                throw new IllegalStateException("The device could not save the workspace connection identity. Free storage and retry.");
            }
            try { return opened.setPortForwardingL(loopbackHost, preferred, "127.0.0.1", remotePort); }
            catch (JSchException unavailable) {
                // Never connect to an existing listener just because its port was
                // remembered. Bind our own listener or safely choose another.
                update(operation, "The previous editor port is busy. Opening a new private tunnel…");
            }
        }
        for (int attempt = 0; attempt < 32; attempt++) {
            int allocated = opened.setPortForwardingL(loopbackHost, 0, "127.0.0.1", remotePort);
            if (reserved.contains(allocated)) {
                opened.delPortForwardingL(loopbackHost, allocated);
                continue;
            }
            if (!ports.edit().putInt(key, allocated).putString("owner:" + allocated, key).commit()) {
                opened.delPortForwardingL(loopbackHost, allocated);
                throw new IllegalStateException("The device could not save the workspace connection identity. Free storage and retry.");
            }
            return allocated;
        }
        throw new IllegalStateException("A distinct workspace tunnel could not be allocated. Close another connection and retry.");
    }

    private JSONObject prepareServer(Session opened, int operation) throws Exception {
        byte[] script;
        try (InputStream asset = context.getAssets().open("server-bootstrap.sh")) { script = readBounded(asset, 1024 * 1024); }
        ChannelExec channel = (ChannelExec) opened.openChannel("exec");
        bootstrap = channel;
        channel.setCommand("sh -s --");
        channel.setInputStream(new ByteArrayInputStream(script));
        ByteArrayOutputStream stderr = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int count) {
                super.write(bytes, offset, Math.max(0, Math.min(count, 65536 - size())));
            }
            @Override public synchronized void write(int value) { if (size() < 65536) super.write(value); }
        };
        channel.setErrStream(stderr);
        InputStream stdout = channel.getInputStream();
        channel.connect(20000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
        int reported = 0;
        try {
            while (true) {
                checkCurrent(operation);
                while (stdout.available() > 0) {
                    int count = stdout.read(buffer);
                    if (count < 0) break;
                    if (output.size() + count > 1024 * 1024) throw new IllegalStateException("Remote setup returned too much output.");
                    output.write(buffer, 0, count);
                }
                // Only bootstrap progress is logged. Its JSON stdout contains a secret.
                String progress = new String(stderr.toByteArray(), StandardCharsets.UTF_8);
                int completeLine = progress.lastIndexOf('\n');
                if (completeLine >= reported) {
                    String fresh = progress.substring(reported, completeLine + 1).trim();
                    reported = completeLine + 1;
                    for (String line : fresh.split("\n")) {
                        if (line.startsWith("vstab:") || line.startsWith("VSTAB:")) update(operation, line.trim());
                    }
                }
                if (channel.isClosed() && stdout.available() == 0) break;
                if (System.nanoTime() > deadline) throw new IllegalStateException("Remote setup timed out. Check the host's internet access and retry.");
                Thread.sleep(100);
            }
            String response = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (channel.getExitStatus() != 0) {
                String detail = new String(stderr.toByteArray(), StandardCharsets.UTF_8).trim();
                if (detail.length() > 1500) detail = detail.substring(detail.length() - 1500);
                throw new IllegalStateException("Remote setup failed" + (detail.isEmpty() ? "." : ": " + detail));
            }
            for (String line : response.split("\n")) {
                String candidate = line.trim();
                if (candidate.startsWith("VSTAB_READY ")) candidate = candidate.substring(12);
                if (candidate.startsWith("{")) {
                    JSONObject object = new JSONObject(candidate);
                    if ("ready".equals(object.optString("status"))) return object;
                }
            }
            throw new IllegalStateException("Remote setup did not return a ready server.");
        } finally { channel.disconnect(); bootstrap = null; }
    }

    private List<String> login(String origin, String password) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(origin + "/login").openConnection();
        try {
            http.setConnectTimeout(15000); http.setReadTimeout(15000);
            http.setInstanceFollowRedirects(false);
            http.setRequestMethod("POST"); http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            http.setRequestProperty("Origin", origin);
            byte[] body = ("password=" + encode(password) + "&base=%2F").getBytes(StandardCharsets.UTF_8);
            http.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream request = http.getOutputStream()) { request.write(body); }
            int status = http.getResponseCode();
            if (status != 302 && status != 303) throw new IllegalStateException("Remote workbench authentication failed (HTTP " + status + ").");
            List<String> cookies = new ArrayList<>();
            for (Map.Entry<String, List<String>> header : http.getHeaderFields().entrySet()) {
                if ("Set-Cookie".equalsIgnoreCase(header.getKey())) cookies.addAll(header.getValue());
            }
            if (cookies.isEmpty()) throw new IllegalStateException("The workbench did not issue an authenticated session.");
            return cookies;
        } finally { http.disconnect(); }
    }

    static String resolveFolder(String requested, String home, String fallback) {
        if (requested.isEmpty()) return fallback;
        if (requested.equals("~")) return home;
        if (requested.startsWith("~/")) return home + requested.substring(1);
        if (!requested.startsWith("/")) throw new IllegalArgumentException("Use an absolute remote folder or a path beginning with ~/.");
        return requested;
    }

    private void checkCurrent(int operation) throws InterruptedException {
        if (operation != generation.get()) throw new InterruptedException("Connection cancelled.");
    }

    private void update(int operation, String message) {
        if (operation != generation.get()) return;
        synchronized (logs) {
            if (logs.size() >= 200) logs.remove(0);
            logs.add(message);
        }
        main.post(() -> { if (operation == generation.get() && observer != null) observer.onStatus(message, busy); });
    }

    private void phase(int operation, String phase, String message) {
        update(operation, message);
        main.post(() -> { if (operation == generation.get() && observer != null) observer.onConnectionPhase(phase, message); });
    }

    private static String friendlyError(Exception error) {
        String message = error.getMessage();
        if (message == null) message = error.getClass().getSimpleName();
        if (message.contains("Auth fail")) return "SSH authentication failed. Check the user, password or selected key.";
        if (message.contains("HostKey has been changed") || message.contains("reject HostKey")) return "SSH host verification failed. A changed key is blocked; verify the server before changing saved host trust.";
        if (message.contains("Connection refused")) return "SSH connection refused. Check the host, port and SSH service.";
        if (message.contains("UnknownHost")) return "The SSH hostname could not be resolved.";
        return message;
    }

    private static byte[] readBounded(InputStream stream, int max) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size() + count > max) throw new IllegalArgumentException("The selected file is too large.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String encode(String input) {
        try { return URLEncoder.encode(input, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private final class CheckedHosts implements HostKeyRepository {
        private final Profile profile;
        private final int operation;
        private final SharedPreferences trust = context.getSharedPreferences("ssh-host-trust", Context.MODE_PRIVATE);
        private final SshKnownHosts known = new SshKnownHosts(new java.io.File(context.getFilesDir(), "home"));
        CheckedHosts(Profile profile, int operation) { this.profile = profile; this.operation = operation; }
        private String id() { return profile.host.toLowerCase(java.util.Locale.ROOT) + ":" + profile.port; }

        @Override public int check(String host, byte[] key) {
            try {
                int current = known.check(profile.host, profile.port, key);
                if (current == OK) { phase(operation, "authenticating", "Authenticating the SSH account…"); return OK; }
                if (current == CHANGED) return CHANGED;
            } catch (java.io.IOException error) { return CHANGED; }
            String encoded = Base64.encodeToString(key, Base64.NO_WRAP);
            String existing = trust.getString(id(), null);
            if (existing != null) {
                boolean unchanged = MessageDigest.isEqual(existing.getBytes(StandardCharsets.US_ASCII), encoded.getBytes(StandardCharsets.US_ASCII));
                if (unchanged) {
                    try { known.remember(profile.host, profile.port, key); }
                    catch (java.io.IOException error) { return NOT_INCLUDED; }
                    phase(operation, "authenticating", "Authenticating the SSH account…");
                }
                return unchanged ? OK : CHANGED;
            }
            phase(operation, "verifying-host", "Verify this SSH host's fingerprint to continue.");
            CountDownLatch answered = new CountDownLatch(1);
            hostDecision = answered;
            AtomicBoolean accepted = new AtomicBoolean();
            try {
                String fingerprint = "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP | Base64.NO_PADDING);
                String algorithm = new HostKey(host, key).getType();
                main.post(() -> {
                    Observer current = observer;
                    if (operation != generation.get() || current == null) { answered.countDown(); return; }
                    current.onHostKey(profile.host + ":" + profile.port, algorithm, fingerprint, value -> { accepted.set(value); answered.countDown(); });
                });
                if (!answered.await(2, TimeUnit.MINUTES) || !accepted.get() || operation != generation.get()) return NOT_INCLUDED;
                // Commit before reporting success so a process death never forgets a consent.
                if (!trust.edit().putString(id(), encoded).commit()) return NOT_INCLUDED;
                known.remember(profile.host, profile.port, key);
                phase(operation, "authenticating", "Authenticating the SSH account…");
                return OK;
            } catch (Exception error) { return NOT_INCLUDED; }
        }
        @Override public void add(HostKey hostkey, UserInfo ui) { }
        @Override public void remove(String host, String type) { }
        @Override public void remove(String host, String type, byte[] key) { }
        @Override public String getKnownHostsRepositoryID() { return "vstab private host trust"; }
        @Override public HostKey[] getHostKey() { return known.entries(null, null); }
        @Override public HostKey[] getHostKey(String host, String type) { return known.entries(host, type); }
    }
}
