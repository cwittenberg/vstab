package app.vstab;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real Android Binder/ContentResolver and clipboard integration, with a captured picker intent. */
public final class AndroidFileTransfersTest {
    private Context context;
    private AndroidFileTransfers transfers;
    private WebView owner, other;
    private Uri fileUri;
    private AtomicReference<Intent> pickerIntent;
    private AtomicReference<Integer> pickerCode;

    @Before public void prepare() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri authority = Uri.parse("content://" + TestDocumentsProvider.AUTHORITY);
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        context.startActivity(new Intent().setClassName("app.vstab.test", "app.vstab.DocumentFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bundle fixture = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fixture == null) {
            try { fixture = context.getContentResolver().call(authority, "seed", "transfers-" + UUID.randomUUID(), null); }
            catch (SecurityException pending) { if (System.nanoTime() >= deadline) throw pending; Thread.sleep(100); }
        }
        fileUri = Uri.parse(fixture.getString("fileUri"));
        pickerIntent = new AtomicReference<>(); pickerCode = new AtomicReference<>();
        onMain(() -> {
            owner = new WebView(context); other = new WebView(context);
            transfers = new AndroidFileTransfers(context, source -> source == owner || source == other,
                    (intent, request) -> { pickerIntent.set(intent); pickerCode.set(request); });
        });
    }
    @After public void cleanup() {
        onMain(() -> { if (transfers != null) transfers.close(); if (owner != null) owner.destroy(); if (other != null) other.destroy(); });
    }
    private static void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private JSONObject call(String method, JSONObject params) throws Exception { return call(owner, method, params); }
    private JSONObject call(WebView source, String method, JSONObject params) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicReference<NativeSshBridge.Failure> failure = new AtomicReference<>();
        onMain(() -> transfers.request(source, method, params, value -> { result.set(value); done.countDown(); },
                error -> { failure.set(error); done.countDown(); }));
        assertTrue("Native transfer timed out: " + method, done.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) throw new IllegalStateException(failure.get().code + ": " + failure.get().message);
        return result.get();
    }
    private static JSONObject json(String key, Object value) throws Exception { return new JSONObject().put(key, value); }
    private byte[] providerBytes() throws Exception {
        try (InputStream stream = context.getContentResolver().openInputStream(fileUri); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertNotNull(stream); byte[] chunk = new byte[8192]; int count;
            while ((count = stream.read(chunk)) != -1) bytes.write(chunk, 0, count);
            return bytes.toByteArray();
        }
    }
    private static WebChromeClient.FileChooserParams chooser(boolean multiple) {
        return new WebChromeClient.FileChooserParams() {
            @Override public int getMode() { return multiple ? MODE_OPEN_MULTIPLE : MODE_OPEN; }
            @Override public String[] getAcceptTypes() { return new String[]{"text/plain", "application/octet-stream"}; }
            @Override public boolean isCaptureEnabled() { return false; }
            @Override public CharSequence getTitle() { return null; }
            @Override public String getFilenameHint() { return null; }
            @Override public Intent createIntent() { return new Intent(Intent.ACTION_GET_CONTENT); }
        };
    }

    @Test public void chooserDeliversMultipleGrantedFilesAndCancelsStaleOrUnsafeResults() throws Exception {
        Uri second = Uri.parse(fileUri.toString().replace("app.js", "README.md"));
        AtomicReference<Uri[]> selection = new AtomicReference<>();
        onMain(() -> assertTrue(transfers.showFileChooser(owner, selection::set, chooser(true))));
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pickerIntent.get().getAction());
        assertTrue(pickerIntent.get().getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        ClipData clip = new ClipData("Android files", new String[]{"*/*"}, new ClipData.Item(fileUri)); clip.addItem(new ClipData.Item(second));
        onMain(() -> { Intent selected = new Intent(); selected.setClipData(clip); transfers.onActivityResult(AndroidFileTransfers.PICK_UPLOAD, Activity.RESULT_OK, selected); });
        assertArrayEquals(new Uri[]{fileUri, second}, selection.get());
        selection.set(new Uri[]{fileUri});
        onMain(() -> { transfers.showFileChooser(owner, selection::set, chooser(false)); transfers.invalidate(owner); });
        assertNull(selection.get());
        onMain(() -> {
            transfers.showFileChooser(owner, selection::set, chooser(false));
            transfers.onActivityResult(AndroidFileTransfers.PICK_UPLOAD, Activity.RESULT_OK, new Intent().setData(Uri.parse("file:///sdcard/private")));
        });
        assertNull(selection.get());
    }

    @Test public void stagedBinaryDownloadUsesAndroidSavePickerAndVerifiesExactProviderBytes() throws Exception {
        byte[] bytes = new byte[AndroidFileTransfers.CHUNK_BYTES * 2 + 113];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) ((i * 37) & 255);
        JSONObject begin = call("files.downloadBegin", new JSONObject().put("name", "binary sample.bin").put("type", "application/octet-stream").put("size", bytes.length));
        String token = begin.getString("token"); assertEquals(AndroidFileTransfers.CHUNK_BYTES, begin.getInt("chunkBytes"));
        for (int offset = 0; offset < bytes.length;) {
            int end = Math.min(offset + AndroidFileTransfers.CHUNK_BYTES, bytes.length);
            JSONObject result = call("files.downloadWrite", new JSONObject().put("token", token).put("offset", offset)
                    .put("dataBase64", Base64.encodeToString(Arrays.copyOfRange(bytes, offset, end), Base64.NO_WRAP)));
            assertEquals(end, result.getLong("offset")); offset = end;
        }
        CountDownLatch done = new CountDownLatch(1); AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        onMain(() -> transfers.request(owner, "files.downloadFinish", uncheckedJson("token", token), value -> { result.set(value); done.countDown(); },
                error -> { failure.set(error.message); done.countDown(); }));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pickerIntent.get() == null && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, pickerIntent.get().getAction());
        assertEquals("binary sample.bin", pickerIntent.get().getStringExtra(Intent.EXTRA_TITLE));
        onMain(() -> transfers.onActivityResult(AndroidFileTransfers.PICK_DOWNLOAD, Activity.RESULT_OK, new Intent().setData(fileUri)));
        assertTrue(done.await(20, TimeUnit.SECONDS)); assertNull(failure.get(), failure.get());
        assertTrue(result.get().getBoolean("saved")); assertEquals(bytes.length, result.get().getLong("size"));
        assertArrayEquals(bytes, providerBytes());
        try { call("files.downloadWrite", new JSONObject().put("token", token).put("offset", bytes.length).put("dataBase64", "")); fail("Completed token remained writable"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("NoPermissions")); }
    }

    @Test public void cancelledDownloadLeavesProviderOriginalAndRejectsIncompleteOffsetAndOtherWindow() throws Exception {
        byte[] original = providerBytes();
        String token = call("files.downloadBegin", new JSONObject().put("name", "cancel.txt").put("size", 3)).getString("token");
        try { call("files.downloadFinish", json("token", token)); fail("Incomplete download reached the picker"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("incomplete")); }
        try { call(other, "files.downloadWrite", new JSONObject().put("token", token).put("offset", 0).put("dataBase64", "YWJj")); fail("Another window received the token"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("NoPermissions")); }
        try { call("files.downloadWrite", new JSONObject().put("token", token).put("offset", 1).put("dataBase64", "YWJj")); fail("Nonsequential bytes accepted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("offset")); }
        call("files.downloadWrite", new JSONObject().put("token", token).put("offset", 0).put("dataBase64", "YWJj"));
        CountDownLatch done = new CountDownLatch(1); AtomicReference<JSONObject> result = new AtomicReference<>();
        onMain(() -> transfers.request(owner, "files.downloadFinish", uncheckedJson("token", token), value -> { result.set(value); done.countDown(); }, error -> done.countDown()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pickerIntent.get() == null && System.nanoTime() < deadline) Thread.sleep(20);
        onMain(() -> transfers.onActivityResult(AndroidFileTransfers.PICK_DOWNLOAD, Activity.RESULT_CANCELED, null));
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(result.get().getBoolean("cancelled"));
        assertArrayEquals(original, providerBytes());
    }

    @Test public void explicitAndroidClipboardReadReturnsCapabilityTokensAndExactBinaryChunks() throws Exception {
        byte[] original = providerBytes();
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        onMain(() -> clipboard.setPrimaryClip(ClipData.newUri(context.getContentResolver(), "copied file", fileUri)));
        JSONObject listing = call("files.clipboard", new JSONObject());
        JSONArray files = listing.getJSONArray("files"); assertEquals("Android denied foreground clipboard access", 1, files.length());
        JSONObject file = files.getJSONObject(0); assertEquals("app.js", file.getString("name"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); long offset = 0;
        while (true) {
            JSONObject chunk = call("files.read", new JSONObject().put("token", file.getString("token")).put("offset", offset));
            bytes.write(Base64.decode(chunk.getString("dataBase64"), Base64.NO_WRAP)); offset = chunk.getLong("nextOffset");
            if (chunk.getBoolean("eof")) break;
        }
        assertArrayEquals(original, bytes.toByteArray());
        call("files.release", json("token", file.getString("token")));
        try { call("files.read", new JSONObject().put("token", file.getString("token")).put("offset", offset)); fail("Released clipboard token remained readable"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("NoPermissions")); }
        onMain(() -> clipboard.setPrimaryClip(ClipData.newPlainText("text", "ordinary text paste")));
        assertEquals(0, call("files.clipboard", new JSONObject()).getJSONArray("files").length());
    }

    @Test public void unsafeNamesAndOversizedDownloadFailBeforeCreatingPickerOrStage() throws Exception {
        for (String name : new String[]{"../escape", "x/y", "x\\y", "..", "bad\nname", ""}) {
            try { call("files.downloadBegin", json("name", name)); fail("Unsafe name accepted: " + name); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("invalid")); }
        }
        try { call("files.downloadBegin", new JSONObject().put("name", "huge.bin").put("size", AndroidFileTransfers.MAX_FILE_BYTES + 1)); fail("Oversized download accepted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("256 MiB")); }
        assertNull(pickerIntent.get());
    }

    @Test public void unknownSizeDownloadAndExactChunkBoundaryPreserveBytesAndClosedCallbacksAreSafe() throws Exception {
        byte[] bytes = new byte[AndroidFileTransfers.CHUNK_BYTES];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i ^ 0xa5);
        String token = call("files.downloadBegin", new JSONObject().put("name", "unknown-size.bin").put("size", JSONObject.NULL)).getString("token");
        assertEquals(bytes.length, call("files.downloadWrite", new JSONObject().put("token", token).put("offset", 0)
                .put("dataBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))).getLong("offset"));
        CountDownLatch done = new CountDownLatch(1); AtomicReference<JSONObject> result = new AtomicReference<>();
        onMain(() -> transfers.request(owner, "files.downloadFinish", uncheckedJson("token", token), value -> { result.set(value); done.countDown(); }, error -> done.countDown()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pickerIntent.get() == null && System.nanoTime() < deadline) Thread.sleep(20);
        onMain(() -> transfers.onActivityResult(AndroidFileTransfers.PICK_DOWNLOAD, Activity.RESULT_OK, new Intent().setData(fileUri)));
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertNotNull(result.get()); assertTrue(result.get().getBoolean("saved"));
        assertArrayEquals(bytes, providerBytes());
        onMain(() -> {
            transfers.close(); transfers.invalidate(owner);
            assertTrue(transfers.onActivityResult(AndroidFileTransfers.PICK_UPLOAD, Activity.RESULT_OK, new Intent().setData(fileUri)));
            assertTrue(transfers.onActivityResult(AndroidFileTransfers.PICK_DOWNLOAD, Activity.RESULT_OK, new Intent().setData(fileUri)));
        });
        try { call("files.downloadBegin", json("name", "after-close.txt")); fail("Closed helper accepted work"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("SessionChanged")); }
    }

    @Test public void abortWhileSavePickerIsOpenCancelsFinishAndIgnoresStaleDestination() throws Exception {
        byte[] original = providerBytes();
        String token = call("files.downloadBegin", new JSONObject().put("name", "aborted.txt").put("size", 3)).getString("token");
        call("files.downloadWrite", new JSONObject().put("token", token).put("offset", 0).put("dataBase64", "YWJj"));
        CountDownLatch done = new CountDownLatch(1); AtomicReference<JSONObject> result = new AtomicReference<>();
        onMain(() -> transfers.request(owner, "files.downloadFinish", uncheckedJson("token", token), value -> { result.set(value); done.countDown(); }, error -> done.countDown()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pickerIntent.get() == null && System.nanoTime() < deadline) Thread.sleep(20);
        assertNotNull("Save picker did not open", pickerIntent.get());
        call("files.abort", json("token", token));
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertNotNull(result.get()); assertTrue(result.get().getBoolean("cancelled"));
        onMain(() -> assertTrue(transfers.onActivityResult(AndroidFileTransfers.PICK_DOWNLOAD, Activity.RESULT_OK, new Intent().setData(fileUri))));
        assertArrayEquals("An aborted picker result changed the original Android document", original, providerBytes());
        try { call("files.downloadWrite", new JSONObject().put("token", token).put("offset", 3).put("dataBase64", "")); fail("Aborted token remained writable"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("NoPermissions")); }
    }
    private static JSONObject uncheckedJson(String key, Object value) {
        try { return json(key, value); } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
}
