package app.vstab;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** A capability for one app-private workspace; no general Android file access. */
final class LocalWorkspaceBridge {
    interface UiActions {
        void handle(String method, JSONObject params, Consumer<JSONObject> success, Consumer<String> failure);
    }
    static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final long MAX_FILE = 16L * 1024 * 1024;
    private final File directory;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebMessagePort nativePort;
    private UiActions actions;

    LocalWorkspaceBridge(Context context) throws IOException {
        this.context = context.getApplicationContext();
        directory = new File(context.getFilesDir(), "workspaces/default").getCanonicalFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create the local workspace.");
        File initialized = new File(context.getFilesDir(), "local-workspace-initialized");
        if (!initialized.exists()) {
            File readme = new File(directory, "README.md");
            if (!readme.exists()) Files.write(readme.toPath(), ("# Your vstab workspace\n\nThese files are saved privately on this Android device.\n\nThe on-device Node runtime runs local terminals and extensions. Use **Remote-SSH: Connect to Host...** for a remote Linux workspace.\n").getBytes(StandardCharsets.UTF_8));
            File example = new File(directory, "hello.js");
            if (!example.exists()) Files.write(example.toPath(), "console.log('Hello from vstab');\n".getBytes(StandardCharsets.UTF_8));
            Files.write(initialized.toPath(), new byte[]{1});
        }
    }

    void setUiActions(UiActions actions) { this.actions = actions; }

    void attach(WebView web) {
        closePort();
        WebMessagePort[] channel = web.createWebMessageChannel();
        nativePort = channel[0];
        WebMessagePort endpoint = nativePort;
        endpoint.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
            @Override public void onMessage(WebMessagePort port, WebMessage message) {
                String data = message.getData();
                if (data == null || data.length() > 24 * 1024 * 1024) return;
                try {
                    JSONObject call = new JSONObject(data);
                    String method = call.optString("method");
                    if (method.equals("local.import") || method.equals("local.export") || method.equals("ssh.showConnections")) {
                        if (call.optInt("v") != 1 || actions == null) return;
                        JSONObject params = call.optJSONObject("params");
                        actions.handle(method, params == null ? new JSONObject() : params,
                                result -> reply(endpoint, uiReply(call, result, null)),
                                error -> reply(endpoint, uiReply(call, null, error)));
                        return;
                    }
                } catch (Exception ignored) { return; }
                io.execute(() -> {
                    String response = dispatch(data);
                    reply(endpoint, response);
                });
            }
        }, main);
        // postWebMessage targets the main frame and enforces the exact origin.
        // Only the local APK-owned workbench receives this workspace capability.
        web.postWebMessage(new WebMessage("vstab-native-ready", new WebMessagePort[]{channel[1]}), Uri.parse(ORIGIN));
    }

    void closePort() {
        if (nativePort != null) { nativePort.close(); nativePort = null; }
    }

    void importDocument(Uri source, String requestedName, Consumer<JSONObject> success, Consumer<String> failure) {
        io.execute(() -> {
            try {
                String name = requestedName.replace('/', '_').replace('\\', '_').replace('\0', '_');
                if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "imported-file";
                File destination = path("/workspace/" + name);
                int suffix = 1;
                while (destination.exists()) destination = path("/workspace/" + name + "-" + suffix++);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (java.io.InputStream input = context.getContentResolver().openInputStream(source)) {
                    if (input == null) throw new IOException("Cannot open selected file.");
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > MAX_FILE) throw new FsFailure("Unavailable", "Local files are limited to 16 MiB.");
                        output.write(buffer, 0, count);
                    }
                }
                writeFile(destination, new JSONObject().put("dataBase64", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)).put("create", true).put("overwrite", false));
                JSONObject result = new JSONObject().put("path", "/workspace/" + destination.getName());
                main.post(() -> success.accept(result));
            } catch (Exception error) { main.post(() -> failure.accept("The selected file could not be imported. Files must be at most 16 MiB.")); }
        });
    }

    void exportDocument(String virtualPath, Uri destination, Consumer<JSONObject> success, Consumer<String> failure) {
        io.execute(() -> {
            try {
                File source = path(virtualPath);
                if (!source.isFile() || source.length() > MAX_FILE) throw new IOException("Invalid local file.");
                try (java.io.InputStream input = Files.newInputStream(source.toPath());
                     java.io.OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new IOException("Cannot open export destination.");
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                main.post(() -> success.accept(new JSONObject()));
            } catch (Exception error) { main.post(() -> failure.accept("The local file could not be exported.")); }
        });
    }

    private void reply(WebMessagePort endpoint, String response) {
        main.post(() -> {
            if (nativePort != endpoint) return;
            try { endpoint.postMessage(new WebMessage(response)); }
            catch (IllegalStateException ignored) { }
        });
    }

    private String uiReply(JSONObject call, JSONObject result, String error) {
        JSONObject response = new JSONObject();
        try {
            response.put("v", 1).put("id", call.get("id")).put("ok", error == null);
            if (error == null) response.put("result", result == null ? JSONObject.NULL : result);
            else response.put("error", new JSONObject().put("code", "Unavailable").put("message", error));
        } catch (Exception ignored) { }
        return response.toString();
    }

    private String dispatch(String message) {
        JSONObject reply = new JSONObject();
        try {
            JSONObject call = new JSONObject(message);
            reply.put("v", 1).put("id", call.get("id"));
            if (call.optInt("v") != 1) throw new FsFailure("Unavailable", "Unsupported workspace protocol.");
            String method = call.getString("method");
            JSONObject params = call.optJSONObject("params");
            if (params == null) params = new JSONObject();
            Object result;
            switch (method) {
                case "fs.stat": result = stat(path(params.getString("path"))); break;
                case "fs.readDirectory": result = readDirectory(path(params.getString("path"))); break;
                case "fs.readFile": result = readFile(path(params.getString("path"))); break;
                case "fs.writeFile": writeFile(path(params.getString("path")), params); result = JSONObject.NULL; break;
                case "fs.createDirectory": createDirectory(path(params.getString("path"))); result = JSONObject.NULL; break;
                case "fs.delete": delete(path(params.getString("path")), params.optBoolean("recursive", false)); result = JSONObject.NULL; break;
                case "fs.rename": rename(path(params.getString("path")), path(params.getString("newPath")), params.optBoolean("overwrite", false)); result = JSONObject.NULL; break;
                default: throw new FsFailure("NoPermissions", "This native operation is not available.");
            }
            reply.put("ok", true).put("result", result);
        } catch (Exception error) {
            try {
                String code = error instanceof FsFailure ? ((FsFailure) error).code :
                        (error instanceof java.nio.file.NoSuchFileException ? "FileNotFound" : "Unavailable");
                String detail = error.getMessage();
                // Never expose an Android host path through error messages.
                if (!(error instanceof FsFailure)) detail = "The local workspace operation could not be completed.";
                reply.put("v", 1).put("ok", false).put("error", new JSONObject().put("code", code).put("message", detail));
            } catch (Exception ignored) { }
        }
        return reply.toString();
    }

    private File path(String virtual) throws Exception {
        if (!virtual.equals("/workspace") && !virtual.startsWith("/workspace/")) throw new FsFailure("NoPermissions", "The path is outside this workspace.");
        String relative = virtual.substring("/workspace".length());
        if (relative.indexOf('\0') >= 0 || relative.indexOf('\\') >= 0) throw new FsFailure("NoPermissions", "Invalid workspace path.");
        for (String piece : relative.split("/")) if (piece.equals("..")) throw new FsFailure("NoPermissions", "Parent traversal is not permitted.");
        File target = new File(directory, relative.startsWith("/") ? relative.substring(1) : relative).getCanonicalFile();
        if (!target.equals(directory) && !target.getPath().startsWith(directory.getPath() + File.separator)) throw new FsFailure("NoPermissions", "The path is outside this workspace.");
        return target;
    }

    private JSONObject stat(File file) throws Exception {
        if (!file.exists()) throw new FsFailure("FileNotFound", "The file does not exist.");
        BasicFileAttributes info = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        return new JSONObject().put("type", file.isDirectory() ? 2 : 1)
                .put("ctime", info.creationTime().toMillis()).put("mtime", info.lastModifiedTime().toMillis())
                .put("size", file.isDirectory() ? 0 : info.size());
    }

    private JSONArray readDirectory(File directory) throws Exception {
        if (!directory.exists()) throw new FsFailure("FileNotFound", "The directory does not exist.");
        if (!directory.isDirectory()) throw new FsFailure("FileNotADirectory", "This path is not a directory.");
        File[] files = directory.listFiles();
        if (files == null) throw new FsFailure("NoPermissions", "The directory cannot be read.");
        Arrays.sort(files, Comparator.comparing(File::getName));
        JSONArray output = new JSONArray();
        for (File file : files) {
            File verified = file.getCanonicalFile();
            if (!verified.getPath().startsWith(this.directory.getPath() + File.separator)) continue;
            output.put(new JSONArray().put(file.getName()).put(file.isDirectory() ? 2 : 1));
        }
        return output;
    }

    private JSONObject readFile(File file) throws Exception {
        if (!file.exists()) throw new FsFailure("FileNotFound", "The file does not exist.");
        if (file.isDirectory()) throw new FsFailure("FileIsADirectory", "This path is a directory.");
        if (file.length() > MAX_FILE) throw new FsFailure("Unavailable", "This file exceeds the 16 MiB local editor limit.");
        return new JSONObject().put("dataBase64", Base64.encodeToString(Files.readAllBytes(file.toPath()), Base64.NO_WRAP));
    }

    private void writeFile(File file, JSONObject params) throws Exception {
        if (file.equals(directory)) throw new FsFailure("FileIsADirectory", "This path is a directory.");
        if (file.isDirectory()) throw new FsFailure("FileIsADirectory", "This path is a directory.");
        if (!file.exists() && !params.optBoolean("create", true)) throw new FsFailure("FileNotFound", "The file does not exist.");
        if (file.exists() && !params.optBoolean("overwrite", true)) throw new FsFailure("FileExists", "The file already exists.");
        if (!file.getParentFile().isDirectory()) throw new FsFailure("FileNotFound", "The parent directory does not exist.");
        String base64 = params.getString("dataBase64");
        if (base64.length() > (MAX_FILE * 4 / 3 + 8)) throw new FsFailure("Unavailable", "This file exceeds the 16 MiB local editor limit.");
        byte[] content = Base64.decode(base64, Base64.DEFAULT);
        if (content.length > MAX_FILE) throw new FsFailure("Unavailable", "This file exceeds the 16 MiB local editor limit.");
        File temporary = File.createTempFile(".vstab-save-", ".tmp", file.getParentFile());
        try {
            Files.write(temporary.toPath(), content);
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    private void createDirectory(File file) throws Exception {
        if (file.exists() && !file.isDirectory()) throw new FsFailure("FileExists", "A file already exists at this path.");
        if (!file.isDirectory() && !file.mkdirs()) throw new FsFailure("NoPermissions", "The directory could not be created.");
    }

    private void delete(File file, boolean recursive) throws Exception {
        if (file.equals(directory)) throw new FsFailure("NoPermissions", "The workspace root cannot be deleted.");
        if (!file.exists()) throw new FsFailure("FileNotFound", "The file does not exist.");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new FsFailure("NoPermissions", "The directory cannot be read.");
            if (children.length > 0 && !recursive) throw new FsFailure("NoPermissions", "The directory is not empty.");
            for (File child : children) {
                File verified = child.getCanonicalFile();
                if (!verified.getPath().startsWith(directory.getPath() + File.separator)) throw new FsFailure("NoPermissions", "A directory entry leaves the workspace.");
                delete(verified, true);
            }
        }
        if (!file.delete()) throw new FsFailure("NoPermissions", "The file could not be deleted.");
    }

    private void rename(File source, File target, boolean overwrite) throws Exception {
        if (source.equals(directory) || target.equals(directory)) throw new FsFailure("NoPermissions", "The workspace root cannot be renamed.");
        if (!source.exists()) throw new FsFailure("FileNotFound", "The file does not exist.");
        if (target.exists() && !overwrite) throw new FsFailure("FileExists", "The destination already exists.");
        if (!target.getParentFile().isDirectory()) throw new FsFailure("FileNotFound", "The destination directory does not exist.");
        if (overwrite) Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        else Files.move(source.toPath(), target.toPath());
    }

    private static final class FsFailure extends Exception {
        final String code;
        FsFailure(String code, String message) { super(message); this.code = code; }
    }
}
