package app.vstab;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Uses a real Android DocumentsProvider and Binder ContentResolver, not a filesystem mock. */
public final class SharedDocumentManagerTest {
    private Context context;
    private SharedDocumentManager manager;
    private String project;
    private Bundle fixture;
    private Uri authority;
    @Before public void prepare() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        authority = Uri.parse("content://" + TestDocumentsProvider.AUTHORITY);
        context.startActivity(new Intent().setClassName("app.vstab.test", "app.vstab.DocumentFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long grantDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { context.getContentResolver().call(authority, "seed", "grant-ready", null); break; }
            catch (SecurityException pending) {
                if (System.nanoTime() >= grantDeadline) throw pending;
                Thread.sleep(100);
            }
        }
        project = "integration-" + UUID.randomUUID();
        fixture = context.getContentResolver().call(authority, "seed", project, null);
        manager = new SharedDocumentManager(context);
    }
    @After public void cleanup() {
        if (manager != null) manager.close();
    }
    private SharedDocumentManager.Session open(boolean single) throws Exception {
        CountDownLatch opened = new CountDownLatch(1); AtomicReference<SharedDocumentManager.Session> session = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        SharedDocumentManager.Listener listener = new SharedDocumentManager.Listener() {
            @Override public void onOpened(SharedDocumentManager.Session value) { session.set(value); opened.countDown(); }
            @Override public void onChanged(SharedDocumentManager.Session value, DocumentSyncEngine.Result result) { }
            @Override public void onFailure(String message) { failure.set(message); opened.countDown(); }
        };
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (single) manager.openDocument(Uri.parse(fixture.getString("fileUri")), flags, listener);
        else manager.openTree(Uri.parse(fixture.getString("treeUri")), flags, listener);
        assertTrue("Provider open timed out", opened.await(20, TimeUnit.SECONDS));
        assertNull(failure.get(), failure.get()); assertNotNull(session.get()); return session.get();
    }
    private static void writeUtf8(java.nio.file.Path file, String content) throws java.io.IOException { Files.write(file, content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String readUtf8(java.nio.file.Path file) throws java.io.IOException { return new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8); }
    private String external(String path) { return context.getContentResolver().call(authority, "read", project + "/" + path, null).getString("content"); }
    private void externalWrite(String path, String value) {
        Bundle extras = new Bundle(); extras.putString("content", value);
        context.getContentResolver().call(authority, "write", project + "/" + path, extras);
    }
    private void waitFor(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) { if (condition.call()) return; Thread.sleep(100); }
        fail("Timed out waiting for Android provider synchronization");
    }
    @Test public void actualSafFolderRetainsGitAndPollsExternalChanges() throws Exception {
        SharedDocumentManager.Session session = open(false);
        assertEquals("ref: refs/heads/main\n", readUtf8(new File(session.directory, ".git/HEAD").toPath()));
        File code = new File(session.directory, "app.js"); writeUtf8(code.toPath(), "saved inside Code OSS\n");
        manager.syncNow(session.id); waitFor(() -> external("app.js").equals("saved inside Code OSS\n"));
        externalWrite("app.js", "changed through Android Files\n");
        // No explicit sync: proves the active-session polling path.
        waitFor(() -> readUtf8(code.toPath()).equals("changed through Android Files\n"));
        assertEquals(1, manager.recentSources().stream().filter(value -> value.id.equals(session.id)).count());
    }
    @Test public void openWithFileSavesToTheSameContentUriAndPreservesConflictVersions() throws Exception {
        SharedDocumentManager.Session session = open(true);
        assertTrue(session.singleDocument); assertEquals("app.js", session.documentFile.getName());
        writeUtf8(session.documentFile.toPath(), "saved back to original URI\n");
        manager.syncNow(session.id); waitFor(() -> external("app.js").equals("saved back to original URI\n"));
        // Conflict both sides inside one serialized worker pass.
        writeUtf8(session.documentFile.toPath(), "local divergent edit\n"); externalWrite("app.js", "external divergent edit\n");
        manager.syncNow(session.id); waitFor(() -> !session.conflicts().isEmpty());
        assertEquals("external divergent edit\n", external("app.js"));
        assertEquals("local divergent edit\n", readUtf8(session.documentFile.toPath()));
        assertEquals("external divergent edit\n", readUtf8(new File(session.directory, session.conflicts().get(0).recoveryPath).toPath()));
        manager.resolve(session.id, "app.js", true); waitFor(() -> external("app.js").equals("local divergent edit\n"));
    }
    @Test public void revokedProviderGrantRetainsUnsentLocalEditAndOriginalExternalBytes() throws Exception {
        SharedDocumentManager.Session session = open(false);
        String original = external("app.js");
        CountDownLatch observed = new CountDownLatch(1), failed = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        manager.openTree(session.source, 0, new SharedDocumentManager.Listener() {
            @Override public void onOpened(SharedDocumentManager.Session value) { observed.countDown(); }
            @Override public void onChanged(SharedDocumentManager.Session value, DocumentSyncEngine.Result result) { }
            @Override public void onFailure(String message) { error.set(message); failed.countDown(); }
        });
        assertTrue(observed.await(10, TimeUnit.SECONDS));
        context.startActivity(new Intent().setClassName("app.vstab.test", "app.vstab.DocumentFixtureActivity")
                .putExtra("revoke", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitFor(() -> {
            try { external("app.js"); return false; }
            catch (SecurityException denied) { return true; }
        });
        File local = new File(session.directory, "app.js");
        writeUtf8(local.toPath(), "unsent edit survives revoked permission\n");
        manager.syncNow(session.id);
        assertTrue("No actionable revoked-grant error was delivered", failed.await(10, TimeUnit.SECONDS));
        assertTrue(error.get(), error.get().contains("revoked") || error.get().contains("read only"));
        assertEquals("unsent edit survives revoked permission\n", readUtf8(local.toPath()));
        manager.close(); manager = null;
        context.startActivity(new Intent().setClassName("app.vstab.test", "app.vstab.DocumentFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitFor(() -> {
            try { return original.equals(external("app.js")); }
            catch (SecurityException pending) { return false; }
        });
        assertEquals(original, external("app.js"));
        writeUtf8(new File(context.getCacheDir(), "shared-document-revocation-proof.json").toPath(), new JSONObject()
                .put("capturedAt", java.time.Instant.now().toString())
                .put("environment", "Actual Android ContentResolver/DocumentsProvider, revoked test-only URI grant")
                .put("managerError", error.get()).put("localEditAfterRevocation", readUtf8(local.toPath()))
                .put("originalProviderBytesBefore", original).put("originalProviderBytesAfterGrantRestored", external("app.js"))
                .put("localEditRetained", true).put("providerOriginalUnchanged", true).toString(2) + "\n");
    }
}
