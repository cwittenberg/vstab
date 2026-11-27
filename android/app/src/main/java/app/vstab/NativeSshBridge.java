package app.vstab;

import android.net.Uri;
import android.os.Looper;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** A scoped control channel for the bundled first-party SSH extension only. */
final class NativeSshBridge {
    interface Handler {
        void request(String method, JSONObject params, Consumer<JSONObject> success, Consumer<Failure> failure);
    }
    static final class Failure {
        final String code, message;
        Failure(String code, String message) {
            this.code = code == null || code.isEmpty() ? "Unavailable" : code;
            this.message = message == null ? "The native SSH operation failed." : message;
        }
    }
    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_PENDING = 64;
    private static final long MAX_SAFE_ID = 9007199254740991L;
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList(
            "ssh.getState", "ssh.readConfig", "ssh.writeConfig", "ssh.addHost", "ssh.removeHost",
            "ssh.connect", "ssh.disconnect", "ssh.reconnect", "ssh.listDirectory", "ssh.openFolder",
            "ssh.importKey", "ssh.respondPrompt", "workspace.local", "app.about", "window.new",
            "documents.getState", "documents.pickFolder", "documents.pickFile", "documents.openApproved", "documents.sync", "documents.resolve"));
    private final android.os.Handler main = new android.os.Handler(Looper.getMainLooper());
    private final Handler handler;
    private final Map<Long, Runnable> pending = new HashMap<>();
    private volatile WebMessagePort nativePort;
    private WebView view;
    private String origin;
    private volatile int generation;

    NativeSshBridge(Handler handler) { this.handler = handler; }

    void attach(WebView web, String exactOrigin) {
        requireMain();
        close();
        Uri expected = Uri.parse(exactOrigin);
        if (!"http".equals(expected.getScheme()) ||
                !WorkspaceWindows.isWorkbenchHost(expected.getHost()) ||
                expected.getPort() <= 0 || expected.getUserInfo() != null || expected.getQuery() != null ||
                expected.getFragment() != null || (expected.getPath() != null && !expected.getPath().isEmpty()) ||
                !matchesWorkbench(web.getUrl(), exactOrigin)) {
            throw new IllegalArgumentException("SSH controls require the active loopback workbench origin.");
        }
        view = web; origin = exactOrigin;
        WebMessagePort[] channel = web.createWebMessageChannel();
        nativePort = channel[0];
        WebMessagePort endpoint = nativePort;
        int current = generation;
        endpoint.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
            @Override public void onMessage(WebMessagePort port, WebMessage message) {
                if (!current(endpoint, current)) return;
                String data = message.getData();
                if (data == null || data.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return;
                try {
                    JSONObject call = new JSONObject(data);
                    Object rawId = call.opt("id");
                    if (!(rawId instanceof Number)) return;
                    double numeric = ((Number) rawId).doubleValue();
                    if (!Double.isFinite(numeric) || numeric < 1 || numeric > MAX_SAFE_ID || numeric != Math.rint(numeric)) return;
                    long id = ((Number) rawId).longValue();
                    String method = call.optString("method", "");
                    JSONObject params = call.optJSONObject("params");
                    Object rawVersion = call.opt("v");
                    if (!(rawVersion instanceof Number) || ((Number) rawVersion).doubleValue() != 1 ||
                            !METHODS.contains(method) || params == null) {
                        error(endpoint, current, id, "NoPermissions", "Unsupported SSH control request."); return;
                    }
                    if (method.equals("ssh.respondPrompt") && params.has("value") &&
                            (!(params.opt("value") instanceof String) ||
                            params.getString("value").getBytes(StandardCharsets.UTF_8).length > 16 * 1024)) {
                        error(endpoint, current, id, "NoPermissions", "Invalid SSH prompt response."); return;
                    }
                    if (pending.containsKey(id) || pending.size() >= MAX_PENDING) {
                        error(endpoint, current, id, "Unavailable", "Too many or duplicate SSH control requests."); return;
                    }
                    Runnable timeout = () -> finish(endpoint, current, id, null,
                            new Failure("Unavailable", "The native SSH operation timed out."));
                    pending.put(id, timeout);
                    main.postDelayed(timeout, 120000);
                    try { handler.request(method, params,
                            result -> finish(endpoint, current, id, result, null),
                            failure -> finish(endpoint, current, id, null, failure == null
                                    ? new Failure("Unavailable", "The SSH operation failed.") : failure)); }
                    catch (Exception failure) { finish(endpoint, current, id, null,
                            new Failure("Unavailable", "The native SSH handler could not finish the operation.")); }
                } catch (Exception invalid) {
                    // Malformed envelopes do not authorize any native operation.
                }
            }
        }, main);
        // Android targets the top-level frame and enforces this exact origin.
        web.postWebMessage(new WebMessage("vstab-ssh-ready", new WebMessagePort[]{channel[1]}), expected);
    }

    void publishState(JSONObject state) { publish("stateChanged", state); }
    void publishConfigChanged(JSONObject snapshot) { publish("configChanged", snapshot); }
    void publishPrompt(JSONObject prompt) { publish("sshPrompt", prompt); }
    boolean isAttached() { requireMain(); return current(nativePort, generation); }

    private void publish(String event, JSONObject payload) {
        WebMessagePort endpoint = nativePort;
        int current = generation;
        main.post(() -> {
            if (!current(endpoint, current) || payload == null) return;
            try {
                JSONObject envelope = new JSONObject().put("v", 1).put("event", event);
                if (event.equals("stateChanged")) envelope.put("state", payload);
                else if (event.equals("sshPrompt")) envelope.put("prompt", payload);
                else for (String key : new String[]{"revision", "mtime", "size"})
                    if (payload.has(key)) envelope.put(key, payload.get(key));
                send(endpoint, current, envelope);
            } catch (Exception ignored) { }
        });
    }

    void close() {
        requireMain();
        generation++;
        WebMessagePort endpoint = nativePort;
        nativePort = null; view = null; origin = null;
        for (Runnable timeout : pending.values()) main.removeCallbacks(timeout);
        pending.clear();
        if (endpoint != null) try { endpoint.close(); } catch (IllegalStateException ignored) { }
    }

    private void finish(WebMessagePort endpoint, int current, long id, JSONObject result, Failure failure) {
        Runnable completion = () -> {
            if (!current(endpoint, current)) return;
            Runnable timeout = pending.remove(id);
            if (timeout == null) return;
            main.removeCallbacks(timeout);
            try {
                JSONObject response = new JSONObject().put("v", 1).put("id", id).put("ok", failure == null);
                if (failure == null) response.put("result", result == null ? JSONObject.NULL : result);
                else response.put("error", new JSONObject().put("code", failure.code).put("message", failure.message));
                if (!send(endpoint, current, response)) error(endpoint, current, id, "Unavailable", "SSH response exceeds the message limit.");
            } catch (Exception ignored) { }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) completion.run();
        else main.post(completion);
    }

    private void error(WebMessagePort endpoint, int current, long id, String code, String message) {
        try { send(endpoint, current, new JSONObject().put("v", 1).put("id", id).put("ok", false)
                .put("error", new JSONObject().put("code", code).put("message", message))); }
        catch (Exception ignored) { }
    }

    private boolean send(WebMessagePort endpoint, int current, JSONObject envelope) {
        if (!current(endpoint, current)) return false;
        String text = envelope.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return false;
        try { endpoint.postMessage(new WebMessage(text)); return true; }
        catch (IllegalStateException ignored) { return false; }
    }

    private boolean current(WebMessagePort endpoint, int expectedGeneration) {
        return endpoint != null && endpoint == nativePort && expectedGeneration == generation &&
                view != null && matchesWorkbench(view.getUrl(), origin);
    }

    private static boolean matchesWorkbench(String address, String expectedOrigin) {
        if (!matchesOrigin(address, expectedOrigin)) return false;
        String path = Uri.parse(address).getPath();
        return path == null || path.isEmpty() || path.equals("/") || path.equals("/index.html");
    }

    static boolean matchesOrigin(String address, String expectedOrigin) {
        if (address == null || expectedOrigin == null) return false;
        Uri actual = Uri.parse(address), expected = Uri.parse(expectedOrigin);
        return expected.getScheme() != null && expected.getScheme().equals(actual.getScheme()) &&
                expected.getHost() != null && expected.getHost().equals(actual.getHost()) &&
                expected.getPort() == actual.getPort() && actual.getUserInfo() == null;
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("SSH bridge lifecycle must run on the main thread.");
    }
}
