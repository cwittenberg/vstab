package app.vstab;

import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class SshPortForwardsTest {
    @Test public void developmentListenersCannotOccupyAnyWorkbenchOrigin() throws Exception {
        try (SocketTransport transport = new SocketTransport()) {
            for (int slot = 0; slot < WorkspaceWindows.LIMIT; slot++) {
                String workbenchHost = WorkspaceWindows.loopbackHost(slot);
                assertEquals("127.0.0." + (slot + 11), SshPortForwards.localHostFor(workbenchHost));
                assertThrows(IllegalArgumentException.class, () -> new SshPortForwards(workbenchHost, transport));
            }
            assertThrows(IllegalArgumentException.class, () -> SshPortForwards.localHostFor("localhost"));
            assertThrows(IllegalArgumentException.class, () -> new SshPortForwards("0.0.0.0", transport));
        }
    }

    private static final class SocketTransport implements SshPortForwards.Transport, AutoCloseable {
        final Map<Integer, ServerSocket> sockets = new HashMap<>();
        final List<String> targets = new ArrayList<>();
        boolean connected = true;
        Runnable afterBind;
        @Override public boolean isConnected() { return connected; }
        @Override public int open(String localHost, int localPort, String remoteHost, int remotePort) throws Exception {
            ServerSocket socket = new ServerSocket();
            try { socket.bind(new InetSocketAddress(InetAddress.getByName(localHost), localPort)); }
            catch (Exception error) { socket.close(); throw error; }
            sockets.put(socket.getLocalPort(), socket);
            targets.add(remoteHost + ":" + remotePort);
            if (afterBind != null) afterBind.run();
            return socket.getLocalPort();
        }
        @Override public void close(String localHost, int localPort) throws Exception {
            ServerSocket socket = sockets.remove(localPort);
            if (socket != null) socket.close();
        }
        @Override public void close() throws Exception {
            connected = false;
            for (ServerSocket socket : sockets.values()) socket.close();
            sockets.clear();
        }
    }

    @Test public void collisionAllocatesARealLoopbackListenerAndClosingReleasesIt() throws Exception {
        try (SocketTransport transport = new SocketTransport(); ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.11"))) {
            SshPortForwards registry = new SshPortForwards("127.0.0.11", transport);
            int requested = occupied.getLocalPort();
            SshPortForwards.Forward forward = registry.forward("localhost", requested, requested);
            assertNotEquals(requested, forward.localPort);
            assertEquals("127.0.0.11", transport.sockets.get(forward.localPort).getInetAddress().getHostAddress());
            assertEquals("127.0.0.1:" + requested, transport.targets.get(0));
            assertSame(forward, registry.forward("127.0.0.1", requested, requested));
            assertEquals(1, transport.sockets.size());
            assertTrue(registry.close(forward.id, forward.connectionId));
            assertFalse(registry.close(forward.id, forward.connectionId));
            try (ServerSocket rebound = new ServerSocket(forward.localPort, 1, InetAddress.getByName("127.0.0.11"))) {
                assertEquals(forward.localPort, rebound.getLocalPort());
            }
        }
    }

    @Test public void explicitLocalAddressChangeReplacesTheListenerAndStaleHandleCannotCloseIt() throws Exception {
        try (SocketTransport transport = new SocketTransport()) {
            SshPortForwards registry = new SshPortForwards("127.0.0.12", transport);
            SshPortForwards.Forward first = registry.forward("localhost", 3000, 0);
            int changedPort;
            try (ServerSocket available = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.12"))) { changedPort = available.getLocalPort(); }
            SshPortForwards.Forward changed = registry.forward("localhost", 3000, changedPort);
            assertEquals(changedPort, changed.localPort);
            assertEquals("127.0.0.12:" + changedPort, changed.localAddress);
            assertNotEquals(first.id, changed.id);
            assertEquals(1, transport.sockets.size());
            assertFalse(registry.close(first.id, first.connectionId));
            assertEquals(1, registry.list().size());
            assertTrue(registry.close(changed.id, changed.connectionId));
        }
    }

    @Test public void disconnectRevokesExistingHandlesAndAReconnectionHasADifferentIdentity() throws Exception {
        try (SocketTransport transport = new SocketTransport(); SocketTransport replacement = new SocketTransport()) {
            SshPortForwards old = new SshPortForwards("127.0.0.11", transport);
            SshPortForwards.Forward before = old.forward("localhost", 3000, 0);
            old.invalidate();
            transport.close(); // Session.disconnect closes every local listener.
            assertThrows(IllegalStateException.class, () -> old.forward("localhost", 3000, 0));
            assertThrows(IllegalStateException.class, old::list);
            SshPortForwards fresh = new SshPortForwards("127.0.0.11", replacement);
            SshPortForwards.Forward after = fresh.forward("localhost", 3000, before.localPort);
            assertNotEquals(before.connectionId, after.connectionId);
            assertThrows(SecurityException.class, () -> fresh.close(before.id, before.connectionId));
            assertEquals(1, fresh.list().size());
            assertTrue(fresh.close(after.id, after.connectionId));
        }
    }

    @Test public void disconnectDuringBindClosesTheJustCreatedListener() throws Exception {
        try (SocketTransport transport = new SocketTransport()) {
            SshPortForwards registry = new SshPortForwards("127.0.0.11", transport);
            transport.afterBind = registry::invalidate;
            assertThrows(IllegalStateException.class, () -> registry.forward("localhost", 3000, 0));
            assertTrue(transport.sockets.isEmpty());
        }
    }

    @Test public void boundsRejectNetworkTargetsAndNormalizeDiscoveredWildcardServices() throws Exception {
        try (SocketTransport transport = new SocketTransport()) {
            SshPortForwards registry = new SshPortForwards("127.0.0.11", transport);
            assertThrows(IllegalArgumentException.class, () -> registry.forward("192.168.1.1", 3000, 0));
            assertThrows(IllegalArgumentException.class, () -> registry.forward("localhost", 0, 0));
            assertThrows(IllegalArgumentException.class, () -> registry.forward("localhost", 65536, 0));
            assertThrows(IllegalArgumentException.class, () -> registry.forward("localhost", 3000, -1));
            assertEquals("127.0.0.1", registry.forward("0.0.0.0", 3000, 0).remoteHost);
            assertEquals("::1", registry.forward("::", 3001, 0).remoteHost);
            for (int port = 3002; port < 3032; port++) registry.forward("localhost", port, 0);
            assertEquals(SshPortForwards.LIMIT, registry.list().size());
            assertThrows(IllegalStateException.class, () -> registry.forward("localhost", 3032, 0));
        }
    }

    @Test public void privilegedRemotePortsUseAnAvailableAndroidHighPort() throws Exception {
        try (SocketTransport transport = new SocketTransport()) {
            SshPortForwards registry = new SshPortForwards("127.0.0.11", transport);
            SshPortForwards.Forward http = registry.forward("localhost", 80, 80);
            SshPortForwards.Forward https = registry.forward("localhost", 443, 443);
            assertTrue(http.localPort >= 1024);
            assertTrue(https.localPort >= 1024);
            assertEquals("127.0.0.1:80", transport.targets.get(0));
            assertEquals("127.0.0.1:443", transport.targets.get(1));
            assertSame(http, registry.forward("localhost", 80, 80));
        }
    }
}
