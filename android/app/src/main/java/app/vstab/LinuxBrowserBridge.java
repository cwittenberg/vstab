package app.vstab;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Browser/loopback integration for ordinary Linux programs, independent of extension identity. */
final class LinuxBrowserBridge implements Closeable {
    interface Transport {
        JSONObject helper(String mode, String... arguments) throws Exception;
        int forward(String localHost, int port, String remoteHost) throws Exception;
        void remove(String localHost, int port) throws Exception;
    }
    private static final class Binding {
        final JSONObject owner;
        final List<String> addresses = new ArrayList<>();
        Binding(JSONObject owner) { this.owner = owner; }
    }
    private final Transport transport;
    private final int backendPid;
    private final Map<Integer, Binding> bindings = new HashMap<>();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "vstab browser callbacks"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;

    LinuxBrowserBridge(Transport transport, int backendPid) {
        if (backendPid < 1) throw new IllegalArgumentException("The Linux backend identity is missing.");
        this.transport = transport; this.backendPid = backendPid;
        monitor.scheduleWithFixedDelay(this::reconcile, 5, 5, TimeUnit.SECONDS);
    }

    synchronized void prepareNative(JSONObject request) throws Exception {
        requireActive();
        String url = request.getString("url"); requireWebUrl(url);
        JSONArray owners = request.optJSONArray("listenerOwners");
        if (owners == null) owners = new JSONArray();
        JSONObject scope = request.optJSONObject("scope");
        Set<Integer> required = requiredPorts(url);
        JSONArray stated = request.optJSONArray("requiredPorts");
        if (stated != null) for (int i = 0; i < stated.length(); i++) required.add(validPort(stated.getInt(i)));
        if (owners.length() == 0 && required.isEmpty()) return;
        if (scope == null || owners.length() == 0 || owners.length() > 32) throw new IOException("The Linux callback listener could not be identified.");
        JSONObject assertion = new JSONObject().put("scope", scope).put("listeners", owners);
        verify(assertion);
        Set<Integer> found = new HashSet<>();
        for (int i = 0; i < owners.length(); i++) found.add(validPort(owners.getJSONObject(i).getInt("port")));
        if (!found.containsAll(required)) throw new IOException("The Linux callback listener is not ready.");
        Map<Integer, Binding> before = new HashMap<>(bindings);
        try {
            for (int i = 0; i < owners.length(); i++) bind(owners.getJSONObject(i));
        } catch (Exception failed) {
            for (Map.Entry<Integer, Binding> item : new ArrayList<>(bindings.entrySet()))
                if (before.get(item.getKey()) != item.getValue()) remove(item.getKey(), item.getValue());
            throw failed;
        }
    }

    synchronized void prepareExternal(String url) throws Exception {
        requireActive(); requireWebUrl(url);
        Set<Integer> required = requiredPorts(url);
        if (required.isEmpty()) return;
        StringJoiner ports = new StringJoiner(","); for (int port : required) ports.add(Integer.toString(port));
        JSONObject inventory = transport.helper("--list-session-listeners", Integer.toString(backendPid), ports.toString());
        JSONObject request = new JSONObject().put("url", url).put("scope", inventory.getJSONObject("scope"))
                .put("listenerOwners", inventory.getJSONArray("listeners"));
        prepareNative(request);
    }

    private void verify(JSONObject assertion) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(assertion.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject result = transport.helper("--verify-listeners", encoded);
        if (!result.optBoolean("verified")) throw new IOException("The Linux callback listener changed. Retry sign-in.");
    }

    private void bind(JSONObject owner) throws Exception {
        int port = validPort(owner.getInt("port"));
        String remote = owner.getString("host");
        if (!remote.equals("127.0.0.1") && !remote.equals("::1")) throw new IOException("Only Linux loopback callbacks can be forwarded.");
        Binding previous = bindings.get(port);
        if (previous != null) {
            if (sameOwner(previous.owner, owner)) return;
            remove(port, previous);
        }
        Binding created = new Binding(new JSONObject(owner.toString()));
        try {
            for (String address : Arrays.asList("127.0.0.1", "::1")) {
                requireActive();
                int allocated = transport.forward(address, port, remote);
                if (allocated != port) {
                    if (allocated > 0 && allocated <= 65535) try { transport.remove(address, allocated); } catch (Exception ignored) { }
                    throw new IOException("The browser callback port is occupied.");
                }
                created.addresses.add(address);
            }
            requireActive(); bindings.put(port, created);
        } catch (Exception failed) {
            for (String address : created.addresses) try { transport.remove(address, port); } catch (Exception ignored) { }
            throw failed;
        }
    }

    private void reconcile() {
        synchronized (this) {
            if (closed || bindings.isEmpty()) return;
            for (Map.Entry<Integer, Binding> item : new ArrayList<>(bindings.entrySet())) {
                if (closed) break;
                JSONObject owner = item.getValue().owner;
                try {
                    JSONObject scope = new JSONObject().put("pid", owner.getInt("pid")).put("uid", owner.getInt("uid"))
                            .put("startTime", owner.get("startTime"));
                    verify(new JSONObject().put("scope", scope).put("listeners", new JSONArray().put(owner)));
                } catch (Exception gone) { remove(item.getKey(), item.getValue()); }
            }
        }
    }
    private void remove(int port, Binding binding) {
        bindings.remove(port);
        for (String address : binding.addresses) try { transport.remove(address, port); } catch (Exception ignored) { }
    }
    private static boolean sameOwner(JSONObject left, JSONObject right) {
        for (String field : Arrays.asList("pid", "startTime", "inode", "host", "port"))
            if (!Objects.equals(String.valueOf(left.opt(field)), String.valueOf(right.opt(field)))) return false;
        return true;
    }
    static Set<Integer> requiredPorts(String value) throws Exception {
        Set<Integer> result = new LinkedHashSet<>();
        URI address = requireWebUrl(value);
        if (isLoopback(address.getHost())) result.add(validPort(address.getPort() == -1 ? ("https".equalsIgnoreCase(address.getScheme()) ? 443 : 80) : address.getPort()));
        String query = address.getRawQuery();
        boolean redirected = false;
        if (query != null) for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            if (pair.length != 2 || !URLDecoder.decode(pair[0], "UTF-8").equals("redirect_uri")) continue;
            if (redirected) throw new IOException("The browser address has ambiguous redirect parameters.");
            redirected = true;
            URI redirect = new URI(URLDecoder.decode(pair[1], "UTF-8"));
            if (("http".equalsIgnoreCase(redirect.getScheme()) || "https".equalsIgnoreCase(redirect.getScheme())) && isLoopback(redirect.getHost()))
                result.add(validPort(redirect.getPort() == -1 ? ("https".equalsIgnoreCase(redirect.getScheme()) ? 443 : 80) : redirect.getPort()));
        }
        return result;
    }
    private static boolean isLoopback(String host) { return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host); }
    private static int validPort(int port) throws IOException {
        if (port < 1024 || port > 65535) throw new IOException("The browser callback requires an unavailable port."); return port;
    }
    private static URI requireWebUrl(String value) throws Exception {
        URI address = new URI(value);
        if (!("http".equalsIgnoreCase(address.getScheme()) || "https".equalsIgnoreCase(address.getScheme()))
                || address.getHost() == null || address.getUserInfo() != null) throw new IOException("Only browser web addresses are supported.");
        return address;
    }
    private void requireActive() throws IOException { if (closed) throw new IOException("The Linux browser session was closed."); }
    @Override public void close() {
        closed = true;
        // Interrupt a blocked reconciliation before waiting for its lock. The
        // volatile closed flag also stops it from probing additional owners.
        monitor.shutdownNow();
        synchronized (this) {
            for (Map.Entry<Integer, Binding> item : new ArrayList<>(bindings.entrySet())) remove(item.getKey(), item.getValue());
        }
    }
}
