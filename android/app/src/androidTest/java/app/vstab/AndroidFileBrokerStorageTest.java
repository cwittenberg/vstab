package app.vstab;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.system.Os;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Callable;
import static org.junit.Assert.*;

/** Actual Android descriptor and ContentResolver adapters; run only on the owned test device. */
public final class AndroidFileBrokerStorageTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static JSONObject query(JSONObject root, String operation, String path) throws Exception {
        return new JSONObject().put("root", root.getString("id")).put("capability", root.getString("capability"))
                .put("op", operation).put("path", path);
    }
    private static void denied(Callable<?> action, int... expected) throws Exception {
        try { action.call(); fail("Filesystem operation unexpectedly succeeded"); }
        catch (AndroidFileBroker.Failure failure) { for (int errno : expected) if (errno == failure.errno) return; throw failure; }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    @Test public void rawDescriptorsKeepModesAndExclusiveLocksAndRejectEscapingSymlinks() throws Exception {
        File base = new File(context.getCacheDir(), "broker-raw-" + UUID.randomUUID());
        File export = new File(base, "export"), outside = new File(base, "outside");
        assertTrue(export.mkdirs()); assertTrue(outside.mkdirs());
        Files.write(new File(outside, "private.txt").toPath(), bytes("outside the root"));
        Os.symlink(outside.getAbsolutePath(), new File(export, "escape").getAbsolutePath());
        try (AndroidFileBroker broker = new AndroidFileBroker(context)) {
            JSONObject root = broker.addAppDirectory("raw", "/mnt/android/app/projects", export, false);
            denied(() -> broker.dispatch(query(root, "open", "escape/private.txt")), 20, 40);
            denied(() -> broker.dispatch(query(root, "mkdir", "escape/new")), 20, 40);
            assertFalse(new File(outside, "new").exists());
            JSONObject open = query(root, "open", "index.lock").put("write", true).put("create", true).put("exclusive", true).put("mode", 0600);
            String handle = broker.dispatch(open).getString("handle");
            denied(() -> broker.dispatch(open), 17);
            broker.dispatch(query(root, "write", "").put("handle", handle).put("offset", 0).put("data", Base64.getEncoder().encodeToString(bytes("complete commit bytes\n"))));
            broker.dispatch(query(root, "fsync", "").put("handle", handle));
            broker.dispatch(query(root, "release", "").put("handle", handle));
            assertEquals(0600, broker.dispatch(query(root, "stat", "index.lock")).getJSONObject("entry").getInt("mode") & 0777);
            broker.dispatch(query(root, "rename", "index.lock").put("to", "index"));
            assertEquals("complete commit bytes\n", new String(Files.readAllBytes(new File(export, "index").toPath()), StandardCharsets.UTF_8));
            broker.dispatch(query(root, "chmod", "index").put("mode", 0640));
            assertEquals(0640, Os.stat(new File(export, "index").getAbsolutePath()).st_mode & 0777);
            broker.dispatch(query(root, "chmod", "index").put("mode", 0));
            broker.dispatch(query(root, "chmod", "index").put("mode", 0600));
            assertEquals(0600, Os.stat(new File(export, "index").getAbsolutePath()).st_mode & 0777);
        }
    }
    private Bundle grantedFixture(String project) throws Exception {
        Uri authority = Uri.parse("content://" + TestDocumentsProvider.AUTHORITY);
        context.startActivity(new Intent().setClassName("com.vstab.test", "app.vstab.DocumentFixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (true) try { return context.getContentResolver().call(authority, "seed", project, null); }
        catch (SecurityException pending) { if (System.nanoTime() >= until) throw pending; Thread.sleep(100); }
    }
    @Test public void realDocumentProviderSavesBytesAndReportsUnsupportedPosixOperations() throws Exception {
        String project = "broker-saf-" + UUID.randomUUID(); Bundle fixture = grantedFixture(project);
        Uri authority = Uri.parse("content://" + TestDocumentsProvider.AUTHORITY);
        try (AndroidFileBroker broker = new AndroidFileBroker(context)) {
            JSONObject root = broker.addDocumentTree("saf", "/mnt/android/folders/project", Uri.parse(fixture.getString("treeUri")), false);
            assertFalse(root.getJSONObject("capabilities").getBoolean("atomicRename"));
            assertFalse(root.getJSONObject("capabilities").getBoolean("exclusiveCreate"));
            denied(() -> broker.dispatch(query(root, "open", "new.lock").put("write", true).put("create", true).put("exclusive", true)), 95);
            denied(() -> broker.dispatch(query(root, "chmod", "app.js").put("mode", 0600)), 95);
            String handle = broker.dispatch(query(root, "open", "app.js").put("write", true).put("truncate", true)).getString("handle");
            broker.dispatch(query(root, "write", "").put("handle", handle).put("offset", 0).put("data", Base64.getEncoder().encodeToString(bytes("saved through Linux mount protocol\n"))));
            broker.dispatch(query(root, "flush", "").put("handle", handle));
            broker.dispatch(query(root, "release", "").put("handle", handle));
            assertEquals("saved through Linux mount protocol\n", context.getContentResolver().call(authority, "read", project + "/app.js", null).getString("content"));
        }
    }
    @Test public void concurrentAndroidEditIsNotOverwrittenByAStagedLinuxWrite() throws Exception {
        String project = "broker-conflict-" + UUID.randomUUID(); Bundle fixture = grantedFixture(project);
        Uri authority = Uri.parse("content://" + TestDocumentsProvider.AUTHORITY);
        File staging = new File(context.getFilesDir(), "file-broker/staging");
        java.util.Set<String> before = new java.util.HashSet<>();
        File[] existing = staging.listFiles(); if (existing != null) for (File file : existing) before.add(file.getName());
        try (AndroidFileBroker broker = new AndroidFileBroker(context)) {
            JSONObject root = broker.addDocumentTree("saf", "/mnt/android/folders/conflict", Uri.parse(fixture.getString("treeUri")), false);
            String handle = broker.dispatch(query(root, "open", "app.js").put("write", true).put("truncate", true)).getString("handle");
            broker.dispatch(query(root, "write", "").put("handle", handle).put("offset", 0).put("data", Base64.getEncoder().encodeToString(bytes("Linux staged version\n"))));
            Bundle change = new Bundle(); change.putString("content", "Android newer version\n");
            context.getContentResolver().call(authority, "write", project + "/app.js", change);
            denied(() -> broker.dispatch(query(root, "flush", "").put("handle", handle)), 11);
            assertEquals("Android newer version\n", context.getContentResolver().call(authority, "read", project + "/app.js", null).getString("content"));
            broker.revoke("saf");
            boolean retained = false;
            for (File file : staging.listFiles()) if (!before.contains(file.getName()) &&
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).equals("Linux staged version\n")) retained = true;
            assertTrue("The exact conflicting Linux bytes were not retained", retained);
        }
    }
}
