package app.vstab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Session-owned loopback listeners. No service is exposed on a LAN interface. */
final class SshPortForwards {
    static final int LIMIT = 32;

    interface Transport {
        boolean isConnected();
        int open(String localHost, int localPort, String remoteHost, int remotePort) throws Exception;
        void close(String localHost, int localPort) throws Exception;
    }

    static final class Forward {
        final String id, connectionId, remoteHost, localHost, localAddress;
        final int remotePort, localPort, requestedLocalPort;

        Forward(String connectionId, String remoteHost, int remotePort, String localHost, int localPort, int requestedLocalPort) {
            this.id = UUID.randomUUID().toString();
            this.connectionId = connectionId;
            this.remoteHost = remoteHost; this.remotePort = remotePort;
            this.localHost = localHost; this.localPort = localPort;
            this.requestedLocalPort = requestedLocalPort;
            this.localAddress = localHost + ":" + localPort;
        }
    }

    final String connectionId = UUID.randomUUID().toString();
    private final String localHost;
    private final Transport transport;
    private final Map<String, Forward> forwards = new LinkedHashMap<>();
    private volatile boolean active = true;

    SshPortForwards(String localHost, Transport transport) {
        if (!isLocalHost(localHost))
            throw new IllegalArgumentException("Port forwarding requires a dedicated development loopback address.");
        this.localHost = localHost;
        this.transport = transport;
    }

    static String localHostFor(String workbenchHost) {
        for (int slot = 0; slot < WorkspaceWindows.LIMIT; slot++)
            if (WorkspaceWindows.loopbackHost(slot).equals(workbenchHost)) return "127.0.0." + (slot + 11);
        throw new IllegalArgumentException("A private SSH workbench address is required.");
    }

    private static boolean isLocalHost(String host) {
        return "127.0.0.11".equals(host) || "127.0.0.12".equals(host)
                || "127.0.0.13".equals(host) || "127.0.0.14".equals(host);
    }

    synchronized Forward forward(String requestedHost, int remotePort, Integer requestedLocalPort) throws Exception {
        requireActive();
        String remoteHost = normalizeHost(requestedHost);
        if (remotePort < 1 || remotePort > 65535)
            throw new IllegalArgumentException("The remote port must be between 1 and 65535.");
        if (requestedLocalPort != null && (requestedLocalPort < 0 || requestedLocalPort > 65535))
            throw new IllegalArgumentException("The local port must be between 0 and 65535.");
        // VS Code supplies remotePort as the local preference, including 80
        // and 443. Android binds those services on an available high port.
        if (requestedLocalPort != null && requestedLocalPort < 1024) requestedLocalPort = 0;
        String endpoint = remoteHost + ":" + remotePort;
        Forward existing = forwards.get(endpoint);
        if (existing != null) {
            if (requestedLocalPort == null || requestedLocalPort == 0 || requestedLocalPort == existing.localPort
                    || requestedLocalPort == existing.requestedLocalPort) return existing;
            // VS Code's Change Local Address normally disposes first; also
            // handle an explicit replacement without leaving two listeners.
            transport.close(localHost, existing.localPort);
            forwards.remove(endpoint);
        }
        if (forwards.size() >= LIMIT)
            throw new IllegalStateException("32 ports are already forwarded. Stop forwarding one before adding another.");
        int preferred = requestedLocalPort == null ? (remotePort >= 1024 ? remotePort : 0) : requestedLocalPort;
        int localPort;
        try { localPort = transport.open(localHost, preferred, remoteHost, remotePort); }
        catch (Exception unavailable) {
            requireActive();
            if (preferred == 0) throw unavailable;
            localPort = transport.open(localHost, 0, remoteHost, remotePort);
        }
        // Disconnect can invalidate this registry while the local bind runs.
        if (!active || !transport.isConnected()) {
            try { transport.close(localHost, localPort); } catch (Exception ignored) { }
            throw new IllegalStateException("This SSH connection is no longer connected.");
        }
        Forward created = new Forward(connectionId, remoteHost, remotePort, localHost, localPort, preferred);
        forwards.put(endpoint, created);
        return created;
    }

    synchronized boolean close(String id, String expectedConnectionId) throws Exception {
        requireActive();
        if (!connectionId.equals(expectedConnectionId))
            throw new SecurityException("This forwarded port belongs to a previous SSH connection.");
        Forward found = null;
        String endpoint = null;
        for (Map.Entry<String, Forward> item : forwards.entrySet()) if (item.getValue().id.equals(id)) {
            found = item.getValue(); endpoint = item.getKey(); break;
        }
        if (found == null) return false;
        transport.close(localHost, found.localPort);
        forwards.remove(endpoint);
        return true;
    }

    synchronized List<Forward> list() {
        requireActive();
        return new ArrayList<>(forwards.values());
    }

    /** Synchronous revocation; the SSH session closes its listeners on disconnect. */
    void invalidate() { active = false; }

    private void requireActive() {
        if (!active || !transport.isConnected())
            throw new IllegalStateException("This SSH connection is no longer connected.");
    }

    private static String normalizeHost(String host) {
        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "0.0.0.0".equals(host)) return "127.0.0.1";
        if ("::1".equals(host) || "::".equals(host)) return "::1";
        throw new IllegalArgumentException("Forward a service on the remote host's localhost or loopback address.");
    }
}
