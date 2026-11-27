package app.vstab;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Qualifies the production on-device manager and real Bionic backend startup. */
public final class NativeRuntimeBackendTest {
    @Test(timeout = 190000) public void pairedBackendStartsWithRealAndroidTerminalAndStorage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VstabApplication application = (VstabApplication) context.getApplicationContext();
        LocalRuntimeManager manager = application.runtime();
        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<ConnectionController.Ready> result = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<String> status = new AtomicReference<>("not started");
        LocalRuntimeManager.Observer observer = new LocalRuntimeManager.Observer() {
            @Override public void onLocalRuntimeStatus(String message) { status.set(message); }
            @Override public void onLocalRuntimeReady(ConnectionController.Ready ready) {
                result.compareAndSet(null, ready);
                completion.countDown();
            }
            @Override public void onLocalRuntimeFailure(String message) {
                failure.compareAndSet(null, message);
                completion.countDown();
            }
        };
        manager.observe(observer);
        try {
            manager.start();
            assertTrue("Production native runtime timed out at: " + status.get(),
                    completion.await(180, TimeUnit.SECONDS));
            assertNull("Production native runtime failed: " + failure.get(), failure.get());
            ConnectionController.Ready ready = result.get();
            assertNotNull("No actual local backend was returned", ready);
            assertTrue("Backend must run on this Android device", ready.localRuntime);
            URI origin = URI.create(ready.origin);
            assertEquals("http", origin.getScheme());
            assertEquals("localhost", origin.getHost());
            assertTrue("The native gateway must be listening", origin.getPort() > 0);
            assertTrue("The actual local workspace must exist", new File(ready.folder).isDirectory());

            JSONObject probe = manager.lastProbeForDiagnostics();
            assertEquals("ready", probe.getString("status"));
            assertEquals("android", probe.getString("platform"));
            assertTrue("A real native Node child process must start", probe.getBoolean("fork"));
            assertTrue("The native PTY must execute bundled Bash", probe.getBoolean("pty"));
            assertTrue("Native SQLite must execute a query", probe.getBoolean("sqlite"));
            assertTrue("Native Argon2 must hash and verify", probe.getBoolean("argon2"));
            assertTrue("Native inotify must deliver a filesystem event", probe.getBoolean("watcher"));
            File node = new File(context.getApplicationInfo().nativeLibraryDir, "libnode.so");
            assertEquals(node.getCanonicalPath(), probe.getString("executable"));
            assertFalse("Node must be read-only to this app UID", node.canWrite());
            JSONObject child = probe.getJSONObject("child");
            assertEquals("android", child.getString("platform"));
            assertEquals(node.getCanonicalPath(), child.getString("executable"));
            System.out.println("VSTAB_NATIVE_BACKEND_PROBE " + probe);
        } finally {
            manager.unobserve(observer);
            manager.stop();
        }
    }
}
