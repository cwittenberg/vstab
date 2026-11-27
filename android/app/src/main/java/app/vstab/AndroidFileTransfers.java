package app.vstab;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Explicit Android document grants used by the ordinary workbench upload, paste and download UI. */
final class AndroidFileTransfers implements AutoCloseable {
    static final int PICK_UPLOAD = 71, PICK_DOWNLOAD = 72;
    static final int CHUNK_BYTES = 128 * 1024, MAX_FILES = 100;
    static final long MAX_FILE_BYTES = 256L * 1024 * 1024, MAX_CLIPBOARD_BYTES = 1024L * 1024 * 1024;
    private static final long TOKEN_LIFETIME_MS = 5 * 60 * 1000;
    interface PickerLauncher { void launch(Intent intent, int requestCode); }
    private final Context context;
    private final PickerLauncher launcher;
    private final Predicate<WebView> isCurrent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // Only the worker touches streams and transfer tokens. Picker state belongs to the main thread.
    private final Map<String, Transfer> transfers = new HashMap<>();
    private volatile boolean closed;
    private WebView uploadOwner;
    private ValueCallback<Uri[]> uploadCallback;
    private boolean uploadMultiple;
    private volatile DownloadPicker downloadPicker;

    private static final class Transfer {
        final String token = UUID.randomUUID().toString();
        final WebView owner;
        final String name, type;
        final long expectedSize;
        final Uri uri;
        final File stage;
        long touchedAt = android.os.SystemClock.elapsedRealtime();
        InputStream input;
        OutputStream output;
        long offset;
        boolean finished;
        volatile boolean pickerPending, cancelled;
        Transfer(WebView owner, String name, String type, long expectedSize, Uri uri, File stage) {
            this.owner = owner; this.name = name; this.type = type; this.expectedSize = expectedSize;
            this.uri = uri; this.stage = stage;
        }
        void close() {
            try { if (input != null) input.close(); } catch (IOException ignored) { }
            try { if (output != null) output.close(); } catch (IOException ignored) { }
            input = null; output = null;
            if (stage != null) stage.delete();
        }
    }
    private static final class DownloadPicker {
        final Transfer transfer;
        final Consumer<JSONObject> success;
        final Consumer<NativeSshBridge.Failure> failure;
        DownloadPicker(Transfer transfer, Consumer<JSONObject> success, Consumer<NativeSshBridge.Failure> failure) {
            this.transfer = transfer; this.success = success; this.failure = failure;
        }
    }

    AndroidFileTransfers(Activity activity, Predicate<WebView> isCurrent) {
        this(activity, isCurrent, activity::startActivityForResult);
    }
    AndroidFileTransfers(Context context, Predicate<WebView> isCurrent, PickerLauncher launcher) {
        this.context = context; this.isCurrent = isCurrent; this.launcher = launcher;
    }

    boolean showFileChooser(WebView owner, ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        requireMain();
        if (closed || !isCurrent.test(owner)) { callback.onReceiveValue(null); return true; }
        cancelUpload();
        if (downloadPicker != null) { callback.onReceiveValue(null); return true; }
        // Explorer uploads are file selection. Folder grants use Open Shared Folder instead.
        if (params.getMode() != WebChromeClient.FileChooserParams.MODE_OPEN &&
                params.getMode() != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            callback.onReceiveValue(null); return true;
        }
        uploadOwner = owner; uploadCallback = callback;
        uploadMultiple = params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, uploadMultiple)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String[] types = Arrays.stream(params.getAcceptTypes()).filter(value -> value != null && value.contains("/")).toArray(String[]::new);
        if (types.length == 1) intent.setType(types[0]);
        else if (types.length > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
        try { launcher.launch(intent, PICK_UPLOAD); }
        catch (RuntimeException unavailable) { cancelUpload(); }
        return true;
    }

    boolean onActivityResult(int request, int result, Intent data) {
        requireMain();
        if (closed) return request == PICK_UPLOAD || request == PICK_DOWNLOAD;
        if (request == PICK_UPLOAD) {
            ValueCallback<Uri[]> callback = uploadCallback; WebView owner = uploadOwner;
            boolean multiple = uploadMultiple; uploadCallback = null; uploadOwner = null;
            if (callback == null) return true;
            if (closed || !isCurrent.test(owner) || result != Activity.RESULT_OK || data == null) {
                callback.onReceiveValue(null); return true;
            }
            LinkedHashSet<Uri> selection = new LinkedHashSet<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++)
                selection.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) selection.add(data.getData());
            if (selection.isEmpty() || selection.size() > MAX_FILES || (!multiple && selection.size() > 1) ||
                    selection.stream().anyMatch(uri -> uri == null || !"content".equals(uri.getScheme()))) {
                callback.onReceiveValue(null); return true;
            }
            // SAF grants readable content URIs. WebView consumes those grants itself, without copying binary bytes through JS.
            callback.onReceiveValue(selection.toArray(new Uri[0])); return true;
        }
        if (request != PICK_DOWNLOAD) return false;
        DownloadPicker picker = downloadPicker; downloadPicker = null;
        if (picker == null) return true;
        picker.transfer.pickerPending = false;
        if (closed || picker.transfer.cancelled || !isCurrent.test(picker.transfer.owner) || result != Activity.RESULT_OK || data == null || data.getData() == null) {
            worker.execute(() -> remove(picker.transfer));
            deliver(picker.success, json("cancelled", true)); return true;
        }
        Uri destination = data.getData();
        if (!"content".equals(destination.getScheme())) {
            worker.execute(() -> remove(picker.transfer));
            fail(picker.failure, "NoPermissions", "Choose an Android document destination."); return true;
        }
        worker.execute(() -> {
            try {
                if (picker.transfer.cancelled || closed) { deliver(picker.success, json("cancelled", true)); return; }
                saveToDocument(picker.transfer.stage, destination);
                deliver(picker.success, new JSONObject().put("saved", true).put("name", picker.transfer.name).put("size", picker.transfer.offset));
            } catch (Exception error) { fail(picker.failure, "Unavailable", safeMessage(error)); }
            finally { remove(picker.transfer); }
        });
        return true;
    }

    void request(WebView owner, String method, JSONObject params, Consumer<JSONObject> success,
                 Consumer<NativeSshBridge.Failure> failure) {
        requireMain();
        if (closed || !isCurrent.test(owner)) { fail(failure, "SessionChanged", "The file-transfer workspace changed. Retry the action."); return; }
        // Android only grants clipboard access to the foreground app. Read it in direct response to the user's paste.
        ClipData clip;
        if (method.equals("files.clipboard")) {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            try { clip = clipboard == null ? null : clipboard.getPrimaryClip(); }
            catch (SecurityException denied) { fail(failure, "NoPermissions", "Android clipboard access is unavailable. Copy the files again, then paste in Explorer."); return; }
        } else clip = null;
        worker.execute(() -> {
            try {
                if (closed) throw new IOException("The file-transfer workspace closed.");
                expire();
                switch (method) {
                    case "files.clipboard": clipboard(owner, clip, success); return;
                    case "files.read": read(owner, params, success); return;
                    case "files.release": case "files.abort": {
                        Transfer transfer = transfers.get(params.getString("token"));
                        if (transfer != null) {
                            requireOwner(transfer, owner); remove(transfer);
                            main.post(() -> cancelDownloadPicker(transfer));
                        }
                        deliver(success, json("released", true)); return;
                    }
                    case "files.downloadBegin": begin(owner, params, success); return;
                    case "files.downloadWrite": write(owner, params, success); return;
                    case "files.downloadFinish": finish(owner, params, success, failure); return;
                    default: throw new SecurityException("This file-transfer action is unavailable.");
                }
            } catch (Exception error) { fail(failure, error instanceof SecurityException ? "NoPermissions" : "Unavailable", safeMessage(error)); }
        });
    }

    private void clipboard(WebView owner, ClipData clip, Consumer<JSONObject> success) throws Exception {
        // A newer paste supersedes any old clipboard readers belonging to this workspace.
        for (Transfer transfer : new ArrayList<>(transfers.values())) if (transfer.owner == owner && transfer.uri != null) remove(transfer);
        JSONArray files = new JSONArray(); List<Transfer> opened = new ArrayList<>();
        if (clip == null) { deliver(success, new JSONObject().put("files", files).put("chunkBytes", CHUNK_BYTES)); return; }
        if (clip.getItemCount() > MAX_FILES) throw new IOException("Paste at most 100 Android files at a time.");
        LinkedHashSet<Uri> uris = new LinkedHashSet<>();
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri != null) {
                if (!"content".equals(uri.getScheme())) throw new SecurityException("Paste files copied from Android's file manager.");
                uris.add(uri);
            }
        }
        long total = 0;
        try {
            for (Uri uri : uris) {
                String type = context.getContentResolver().getType(uri);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) throw new IOException("Paste individual files. Use Open Shared Folder for an Android folder.");
                String name; long size = -1;
                try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()) throw new IOException("Android could not open a copied file. Copy it again and retry.");
                    name = checkedName(cursor.getString(0)); if (!cursor.isNull(1)) size = cursor.getLong(1);
                }
                if (size > MAX_FILE_BYTES) throw new IOException("Android file transfers support files up to 256 MiB.");
                if (size >= 0) total += size;
                if (total > MAX_CLIPBOARD_BYTES) throw new IOException("Paste at most 1 GiB of Android files at a time.");
                Transfer transfer = new Transfer(owner, name, checkedType(type), size, uri, null);
                transfer.input = context.getContentResolver().openInputStream(uri);
                if (transfer.input == null) throw new IOException("Android could not open a copied file.");
                opened.add(transfer); transfers.put(transfer.token, transfer);
                files.put(new JSONObject().put("token", transfer.token).put("name", name).put("type", transfer.type).put("size", size));
            }
        } catch (Exception error) { for (Transfer transfer : opened) remove(transfer); throw error; }
        deliver(success, new JSONObject().put("files", files).put("chunkBytes", CHUNK_BYTES));
    }

    private void read(WebView owner, JSONObject params, Consumer<JSONObject> success) throws Exception {
        Transfer transfer = lookup(owner, params);
        if (transfer.uri == null || transfer.input == null) throw new SecurityException("This token does not identify a copied Android file.");
        long offset = params.getLong("offset");
        if (offset != transfer.offset) throw new IOException("The Android file-transfer offset changed. Copy the file again and retry.");
        byte[] bytes = new byte[CHUNK_BYTES]; int count = 0;
        while (count < bytes.length) { int next = transfer.input.read(bytes, count, bytes.length - count); if (next < 0) break; if (next == 0) continue; count += next; }
        if (transfer.offset + count > MAX_FILE_BYTES) { remove(transfer); throw new IOException("Android file transfers support files up to 256 MiB."); }
        transfer.offset += count;
        boolean eof = count < bytes.length;
        if (eof) {
            if (transfer.expectedSize >= 0 && transfer.offset != transfer.expectedSize) { remove(transfer); throw new IOException("The copied Android file changed while being read. Copy it again and retry."); }
            transfer.input.close(); transfer.input = null; transfer.finished = true;
        }
        deliver(success, new JSONObject().put("offset", offset).put("nextOffset", transfer.offset).put("eof", eof)
                .put("dataBase64", Base64.encodeToString(bytes, 0, count, Base64.NO_WRAP)));
    }

    private void begin(WebView owner, JSONObject params, Consumer<JSONObject> success) throws Exception {
        if (transfers.size() >= MAX_FILES + 4) throw new IOException("Too many file transfers are active. Finish the current action first.");
        String name = checkedName(params.getString("name")), type = checkedType(params.optString("type", "application/octet-stream"));
        long size = params.optLong("size", -1);
        if (size < -1 || size > MAX_FILE_BYTES) throw new IOException("Android file transfers support files up to 256 MiB.");
        File stage = File.createTempFile("vstab-download-", ".part", context.getCacheDir());
        Transfer transfer = new Transfer(owner, name, type, size, null, stage);
        try { transfer.output = new FileOutputStream(stage); transfers.put(transfer.token, transfer); }
        catch (Exception error) { transfer.close(); throw error; }
        deliver(success, new JSONObject().put("token", transfer.token).put("chunkBytes", CHUNK_BYTES));
    }

    private void write(WebView owner, JSONObject params, Consumer<JSONObject> success) throws Exception {
        Transfer transfer = lookup(owner, params);
        if (transfer.stage == null || transfer.output == null || transfer.finished) throw new SecurityException("This download is no longer writable.");
        if (params.getLong("offset") != transfer.offset) throw new IOException("The Android download offset changed. Retry the download.");
        String encoded = params.getString("dataBase64");
        if (encoded.length() > ((CHUNK_BYTES + 2) / 3) * 4 || !encoded.matches("[A-Za-z0-9+/]*={0,2}")) throw new IOException("The Android download chunk is invalid.");
        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
        if (bytes.length > CHUNK_BYTES || transfer.offset + bytes.length > MAX_FILE_BYTES ||
                (transfer.expectedSize >= 0 && transfer.offset + bytes.length > transfer.expectedSize)) throw new IOException("The Android download exceeds its expected size.");
        transfer.output.write(bytes); transfer.offset += bytes.length;
        deliver(success, json("offset", transfer.offset));
    }

    private void finish(WebView owner, JSONObject params, Consumer<JSONObject> success, Consumer<NativeSshBridge.Failure> failure) throws Exception {
        Transfer transfer = lookup(owner, params);
        if (transfer.stage == null || transfer.output == null || transfer.finished) throw new SecurityException("This download is no longer writable.");
        if (transfer.expectedSize >= 0 && transfer.offset != transfer.expectedSize) throw new IOException("The Android download is incomplete. Retry the download.");
        transfer.output.close(); transfer.output = null; transfer.finished = true; transfer.pickerPending = true;
        main.post(() -> {
            if (transfer.cancelled) { transfer.pickerPending = false; deliver(success, json("cancelled", true)); return; }
            if (closed || !isCurrent.test(owner)) {
                // close() has already scheduled complete cleanup and shut down
                // the executor; a queued picker launch must not submit again.
                if (!closed) worker.execute(() -> remove(transfer));
                fail(failure, "SessionChanged", "The file-transfer workspace changed. Retry the download."); return;
            }
            if (downloadPicker != null || uploadCallback != null) {
                worker.execute(() -> remove(transfer)); fail(failure, "Unavailable", "Finish the current Android file picker first."); return;
            }
            downloadPicker = new DownloadPicker(transfer, success, failure);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(transfer.type)
                    .putExtra(Intent.EXTRA_TITLE, transfer.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { launcher.launch(intent, PICK_DOWNLOAD); }
            catch (RuntimeException unavailable) {
                downloadPicker = null; worker.execute(() -> remove(transfer));
                fail(failure, "Unavailable", "No Android save picker is available.");
            }
        });
    }

    /** Stage the complete download first, retain the old document, and verify the provider's resulting bytes. */
    void saveToDocument(File stage, Uri destination) throws Exception {
        File backup = File.createTempFile("vstab-before-download-", ".recovery", context.getCacheDir());
        boolean keepRecovery = false;
        try {
            try (InputStream original = context.getContentResolver().openInputStream(destination); OutputStream recovery = new FileOutputStream(backup)) {
                copy(original, recovery);
            }
            try {
                try (InputStream input = new FileInputStream(stage); OutputStream output = context.getContentResolver().openOutputStream(destination, "rwt")) { copy(input, output); }
                if (!Arrays.equals(digest(new FileInputStream(stage)), digest(context.getContentResolver().openInputStream(destination))))
                    throw new IOException("Android retained an incomplete download.");
            } catch (Exception failure) {
                try {
                    try (InputStream input = new FileInputStream(backup); OutputStream output = context.getContentResolver().openOutputStream(destination, "rwt")) { copy(input, output); }
                    if (!Arrays.equals(digest(new FileInputStream(backup)), digest(context.getContentResolver().openInputStream(destination))))
                        throw new IOException("Android could not restore the previous document completely.");
                }
                catch (Exception restore) { keepRecovery = true; failure.addSuppressed(restore); }
                throw new IOException(keepRecovery ? "Android could not save the complete download. A recovery copy of the previous document was kept inside vstab." :
                        "Android could not save the complete download. The previous document was restored. Retry the download.", failure);
            }
        } finally { if (!keepRecovery) backup.delete(); }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        if (input == null || output == null) throw new IOException("Android could not open the document.");
        byte[] bytes = new byte[CHUNK_BYTES]; long count = 0; int read;
        while ((read = input.read(bytes)) != -1) {
            count += read; if (count > MAX_FILE_BYTES) throw new IOException("Android file transfers support files up to 256 MiB.");
            output.write(bytes, 0, read);
        }
    }
    private static byte[] digest(InputStream input) throws Exception {
        if (input == null) throw new IOException("Android could not verify the saved document.");
        try (InputStream stream = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[CHUNK_BYTES]; int read; long count = 0;
            while ((read = stream.read(bytes)) != -1) { count += read; if (count > MAX_FILE_BYTES) throw new IOException("The saved document exceeds the transfer limit."); digest.update(bytes, 0, read); }
            return digest.digest();
        }
    }
    static String checkedName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 255 ||
                name.contains("/") || name.contains("\\") || name.chars().anyMatch(value -> value < 32 || value == 127))
            throw new IOException("The Android file name is invalid.");
        return name;
    }
    private static String checkedType(String type) { return type != null && type.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+") ? type : "application/octet-stream"; }
    private Transfer lookup(WebView owner, JSONObject params) throws Exception {
        Transfer transfer = transfers.get(params.getString("token"));
        if (transfer == null) throw new SecurityException("This Android file-transfer token expired. Retry the action.");
        requireOwner(transfer, owner); transfer.touchedAt = android.os.SystemClock.elapsedRealtime(); return transfer;
    }
    private static void requireOwner(Transfer transfer, WebView owner) {
        if (transfer.owner != owner) throw new SecurityException("This Android file belongs to another workspace window.");
    }
    private void expire() {
        long now = android.os.SystemClock.elapsedRealtime();
        for (Transfer transfer : new ArrayList<>(transfers.values()))
            if (!transfer.pickerPending && now - transfer.touchedAt > TOKEN_LIFETIME_MS) remove(transfer);
    }
    private void remove(Transfer transfer) { transfers.remove(transfer.token); transfer.cancelled = true; transfer.close(); }
    private void cancelDownloadPicker(Transfer transfer) {
        requireMain();
        DownloadPicker picker = downloadPicker;
        if (picker == null || picker.transfer != transfer) return;
        downloadPicker = null; transfer.pickerPending = false;
        deliver(picker.success, json("cancelled", true));
        finishDownloadActivity();
    }
    private void finishDownloadActivity() {
        if (context instanceof Activity) ((Activity) context).finishActivity(PICK_DOWNLOAD);
    }
    private void cancelUpload() {
        ValueCallback<Uri[]> callback = uploadCallback; uploadCallback = null; uploadOwner = null;
        if (callback != null) callback.onReceiveValue(null);
    }
    void invalidate(WebView owner) {
        requireMain();
        if (closed) return;
        if (uploadOwner == owner) cancelUpload();
        if (downloadPicker != null && downloadPicker.transfer.owner == owner) {
            DownloadPicker picker = downloadPicker; downloadPicker = null;
            picker.transfer.pickerPending = false; finishDownloadActivity();
            fail(picker.failure, "SessionChanged", "The file-transfer workspace changed. Retry the action.");
        }
        worker.execute(() -> { for (Transfer transfer : new ArrayList<>(transfers.values())) if (transfer.owner == owner) remove(transfer); });
    }
    @Override public void close() {
        requireMain(); if (closed) return;
        closed = true; cancelUpload();
        if (downloadPicker != null) {
            DownloadPicker picker = downloadPicker; downloadPicker = null;
            picker.transfer.pickerPending = false; finishDownloadActivity();
            fail(picker.failure, "SessionChanged", "The file-transfer workspace closed.");
        }
        worker.execute(() -> { for (Transfer transfer : new ArrayList<>(transfers.values())) remove(transfer); });
        worker.shutdown();
    }
    private static JSONObject json(String key, Object value) {
        try { return new JSONObject().put(key, value); } catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
    }
    private void deliver(Consumer<JSONObject> success, JSONObject result) { main.post(() -> success.accept(result)); }
    private void fail(Consumer<NativeSshBridge.Failure> failure, String code, String message) { main.post(() -> failure.accept(new NativeSshBridge.Failure(code, message))); }
    private static String safeMessage(Exception error) {
        if (error instanceof SecurityException) return "Android file access is unavailable or was revoked. Copy or select the file again and retry.";
        String message = error.getMessage();
        return message == null || message.contains("/data/") || message.contains("content://") ? "Android could not complete the file transfer. Check access and available storage, then retry." : message;
    }
    private static void requireMain() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Android picker lifecycle must run on the main thread."); }
}
