package app.vstab;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class LinuxBrowserBridgeTest {
    private static JSONObject owner(int port, int pid) throws Exception {
        return new JSONObject().put("port", port).put("host", "127.0.0.1").put("pid", pid)
                .put("startTime", "1234").put("inode", "socket-" + port).put("uid", 1000);
    }
    private static JSONObject scope() throws Exception { return new JSONObject().put("pid", 100).put("startTime", "1000").put("uid", 1000); }
    private static String authorization(int port) throws Exception {
        return "https://identity.invalid/authorize?response_type=code&redirect_uri="
                + URLEncoder.encode("http://localhost:" + port + "/callback?state=literal%26value", "UTF-8");
    }
    private static JSONObject nativeRequest(int required, JSONObject... owners) throws Exception {
        JSONArray array = new JSONArray(); for (JSONObject value : owners) array.put(value);
        return new JSONObject().put("url", authorization(required)).put("scope", scope()).put("listenerOwners", array);
    }
    private static final class Fake implements LinuxBrowserBridge.Transport {
        final List<String> calls = new ArrayList<>();
        final Set<String> active = new LinkedHashSet<>();
        final List<JSONObject> assertions = new ArrayList<>();
        JSONObject inventory;
        volatile boolean verified = true, differentPort, blockVerification;
        final java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
        String failAddress; int failPort;
        @Override public synchronized JSONObject helper(String mode, String... args) throws Exception {
            calls.add("helper:" + mode + ":" + String.join(",", args));
            if (mode.equals("--list-session-listeners")) return new JSONObject(inventory.toString());
            if (!mode.equals("--verify-listeners")) throw new IOException("Unexpected helper mode");
            assertions.add(new JSONObject(new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8)));
            if (blockVerification) { blocked.countDown(); Thread.sleep(3000); }
            return new JSONObject().put("verified", verified);
        }
        @Override public synchronized int forward(String address, int port, String remote) throws Exception {
            calls.add("forward:" + address + ":" + port + ":" + remote);
            if (address.equals(failAddress) && (failPort == 0 || failPort == port)) throw new IOException("occupied");
            int allocated = differentPort ? port + 1 : port; active.add(address + ":" + allocated); return allocated;
        }
        @Override public synchronized void remove(String address, int port) { calls.add("remove:" + address + ":" + port); active.remove(address + ":" + port); }
        synchronized Set<String> active() { return new LinkedHashSet<>(active); }
        synchronized int forwards() { return (int) calls.stream().filter(value -> value.startsWith("forward:")).count(); }
    }
    private interface Action { void run() throws Exception; }
    private static void fails(Action action) throws Exception {
        try { action.run(); fail("Expected the browser preparation to fail"); } catch (IOException expected) { }
    }

    @Test public void standardRedirectsDecodeOnceAndAcceptIpv4AndIpv6Loopback() throws Exception {
        assertEquals(Collections.singleton(14555), LinuxBrowserBridge.requiredPorts(authorization(14555)));
        assertEquals(Collections.singleton(16000), LinuxBrowserBridge.requiredPorts("http://[::1]:16000/callback"));
        assertTrue(LinuxBrowserBridge.requiredPorts("https://identity.invalid/?redirect_uri=https%3A%2F%2Freturn.invalid%2Fcallback").isEmpty());
        fails(() -> LinuxBrowserBridge.requiredPorts("https://user:password@identity.invalid/"));
        fails(() -> LinuxBrowserBridge.requiredPorts("http://localhost/callback"));
        fails(() -> LinuxBrowserBridge.requiredPorts("https://identity.invalid/?redirect_uri=http%3A%2F%2Flocalhost%3A14555%2F&redirect_uri=http%3A%2F%2Flocalhost%3A14556%2F"));
    }
    @Test public void normalWebPagesDoNotEnumerateOrForwardUnrelatedBackendServices() throws Exception {
        Fake transport = new Fake();
        try (LinuxBrowserBridge bridge = new LinuxBrowserBridge(transport, 100)) {
            bridge.prepareExternal("https://docs.invalid/read");
            bridge.prepareNative(new JSONObject().put("url", "https://docs.invalid/read"));
            assertTrue(transport.calls.isEmpty()); assertTrue(transport.active().isEmpty());
        }
    }
    @Test public void externalRedirectInventoryIsVerifiedBeforeExactIpv4AndIpv6Binding() throws Exception {
        Fake transport = new Fake(); JSONObject callback = owner(14555, 101);
        transport.inventory = new JSONObject().put("scope", scope()).put("listeners", new JSONArray().put(callback));
        try (LinuxBrowserBridge bridge = new LinuxBrowserBridge(transport, 100)) {
            bridge.prepareExternal(authorization(14555));
            assertTrue(transport.calls.get(0).startsWith("helper:--list-session-listeners:100,14555"));
            assertTrue(transport.calls.get(1).startsWith("helper:--verify-listeners:"));
            assertEquals(new LinkedHashSet<>(Arrays.asList("127.0.0.1:14555", "::1:14555")), transport.active());
            assertEquals(100, transport.assertions.get(0).getJSONObject("scope").getInt("pid"));
            assertEquals(101, transport.assertions.get(0).getJSONArray("listeners").getJSONObject(0).getInt("pid"));
        }
        assertTrue(transport.active().isEmpty());
    }
    @Test public void unverifiedOrIncompleteOwnershipNeverCreatesAForward() throws Exception {
        Fake transport = new Fake(); transport.verified = false;
        try (LinuxBrowserBridge bridge = new LinuxBrowserBridge(transport, 100)) {
            fails(() -> bridge.prepareNative(nativeRequest(14555, owner(14555, 101))));
            assertEquals(0, transport.forwards());
            transport.verified = true;
            fails(() -> bridge.prepareNative(nativeRequest(14555, owner(14556, 101))));
            assertEquals(0, transport.forwards());
        }
    }
    @Test public void reusingAnExistingOwnerStillRequiresFreshAuthoritativeVerification() throws Exception {
        Fake transport = new Fake();
        try (LinuxBrowserBridge bridge = new LinuxBrowserBridge(transport, 100)) {
            JSONObject request = nativeRequest(14555, owner(14555, 101));
            bridge.prepareNative(request); assertEquals(2, transport.forwards());
            transport.verified = false;
            fails(() -> bridge.prepareNative(request));
            assertEquals(2, transport.forwards()); assertEquals(2, transport.assertions.size());
        }
    }
    @Test public void occupiedIpv6PortRollsBackTheIpv4Half() throws Exception {
        Fake transport = new Fake(); transport.failAddress = "::1";
        try (LinuxBrowserBridge bridge = new LinuxBrowserBridge(transport, 100)) {
            fails(() -> bridge.prepareNative(nativeRequest(14555, owner(14555, 101))));
            assertTrue(transport.active().isEmpty()); assertTrue(transport.calls.contains("remove:127.0.0.1:14555"));
        }
    }
    @Test public void anUnexpectedAllocatedPortIsAlsoRemoved() throws Exception {
        Fake transport = new Fake(); transport.differentPort = true;
        try (LinuxBrowserBridge bridge = new LinuxBrowserBridge(transport, 100)) {
            fails(() -> bridge.prepareNative(nativeRequest(14555, owner(14555, 101))));
            assertTrue(transport.active().isEmpty()); assertTrue(transport.calls.contains("remove:127.0.0.1:14556"));
        }
    }
    @Test public void aFailedMultiPortRequestRemovesNewBindingsAndPreservesAnExistingBinding() throws Exception {
        Fake transport = new Fake();
        try (LinuxBrowserBridge bridge = new LinuxBrowserBridge(transport, 100)) {
            JSONObject existing = owner(14550, 101);
            bridge.prepareNative(nativeRequest(14550, existing));
            transport.failAddress = "::1"; transport.failPort = 14556;
            fails(() -> bridge.prepareNative(nativeRequest(14555, existing, owner(14555, 102), owner(14556, 103))));
            assertEquals(new LinkedHashSet<>(Arrays.asList("127.0.0.1:14550", "::1:14550")), transport.active());
        }
    }
    @Test public void closedOrReusedOwnerSocketsAreRemovedByTheLifecycleMonitor() throws Exception {
        Fake transport = new Fake();
        try (LinuxBrowserBridge bridge = new LinuxBrowserBridge(transport, 100)) {
            bridge.prepareNative(nativeRequest(14555, owner(14555, 101)));
            transport.verified = false;
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while (!transport.active().isEmpty() && System.nanoTime() < until) Thread.sleep(20);
            assertTrue("The closed callback owner retained its Android listeners", transport.active().isEmpty());
            JSONObject monitorScope = transport.assertions.get(transport.assertions.size() - 1).getJSONObject("scope");
            assertEquals(101, monitorScope.getInt("pid")); assertEquals("1234", monitorScope.getString("startTime"));
        }
    }
    @Test public void closeInterruptsABlockedMonitorBeforeWaitingForItsLock() throws Exception {
        Fake transport = new Fake(); LinuxBrowserBridge bridge = new LinuxBrowserBridge(transport, 100);
        try {
            bridge.prepareNative(nativeRequest(14555, owner(14555, 101)));
            transport.blockVerification = true;
            assertTrue("The active callback monitor did not run", transport.blocked.await(8, java.util.concurrent.TimeUnit.SECONDS));
            long started = System.nanoTime(); bridge.close();
            assertTrue("Close waited for the stalled probe instead of interrupting it",
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
            assertTrue(transport.active().isEmpty());
        } finally { bridge.close(); }
    }
}
