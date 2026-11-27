package app.vstab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Owns the APK-delivered Android Node server, extensions and PTY processes. */
final class LocalRuntimeManager {
    interface Observer {
        void onLocalRuntimeStatus(String message);
        void onLocalRuntimeReady(ConnectionController.Ready ready);
        void onLocalRuntimeFailure(String message);
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();
    private final ArrayDeque<String> tail = new ArrayDeque<>();
    private volatile Process process;
    private volatile LocalEditorGateway gateway;
    private volatile ConnectionController.Ready ready;
    private volatile boolean starting;
    private volatile String status = "Starting the on-device development environment…";
    private volatile String failure;

    LocalRuntimeManager(Context context) { this.context = context.getApplicationContext(); }
    ConnectionController.Ready current() { return ready; }
    boolean isStarting() { return starting; }
    JSONObject lastProbeForDiagnostics() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(), "native-runtime/last-probe.json").toPath()), StandardCharsets.UTF_8));
    }
    void observe(Observer observer) {
        observers.addIfAbsent(observer);
        main.post(() -> {
            if (!observers.contains(observer)) return;
            if (ready != null) observer.onLocalRuntimeReady(ready);
            else if (failure != null) observer.onLocalRuntimeFailure(failure);
            else observer.onLocalRuntimeStatus(status);
        });
    }
    void unobserve(Observer observer) { observers.remove(observer); }

    synchronized void start() {
        if (starting || ready != null) return;
        int operation = generation.incrementAndGet();
        starting = true; failure = null;
        synchronized (tail) { tail.clear(); }
        worker.execute(() -> launch(operation));
    }

    synchronized void stop() {
        generation.incrementAndGet();
        starting = false; ready = null;
        LocalEditorGateway route = gateway;
        if (route != null) route.withdrawBackend();
        Process running = process; process = null;
        if (running != null) {
            running.destroy();
            Thread end = new Thread(() -> {
                try { if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly(); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }, "vstab native stop");
            end.setDaemon(true); end.start();
        }
        failure = "The on-device runtime is stopped.";
        main.post(() -> { for (Observer observer : observers) observer.onLocalRuntimeFailure(failure); });
    }

    private void launch(int operation) {
        Process child = null;
        try {
            publish(operation, "Preparing the bundled Android runtime…");
            byte[] manifestBytes = assetBytes("local-runtime/runtime.json", 4 * 1024 * 1024);
            JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
            if (manifest.optInt("schema") != 1) throw new IOException("Unsupported native runtime package.");
            JSONObject platforms = manifest.getJSONObject("architectures");
            String abi = null;
            for (String candidate : Build.SUPPORTED_ABIS) if (platforms.has(candidate)) { abi = candidate; break; }
            if (abi == null) throw new IOException("The APK has no native runtime for this device architecture.");
            JSONObject platform = platforms.getJSONObject(abi);
            String identity = manifest.getString("runtimeId").replaceAll("[^a-zA-Z0-9_.-]", "_") + "-" + sha(manifestBytes).substring(0, 12);
            File collection = new File(context.getFilesDir(), "native-runtime");
            ensureDirectory(collection);
            File root = new File(collection, identity);
            File backend = new File(root, "backend");
            File usr = new File(root, "usr");
            if (!new File(root, ".complete").isFile()) {
                File stage = new File(collection, "prepare-" + UUID.randomUUID());
                try {
                    ensureDirectory(stage);
                    unpack(assetName(manifest.getString("backendAsset")), manifest.getString("backendSha256"), new File(stage, "backend"), operation);
                    unpack(assetName(platform.getString("usrArchive")), platform.getString("usrSha256"), new File(stage, "usr"), operation);
                    Files.write(new File(stage, ".complete").toPath(), sha(manifestBytes).getBytes(StandardCharsets.US_ASCII));
                    if (root.exists()) deleteOwnedTree(root);
                    Files.move(stage.toPath(), root.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } finally { if (stage.exists()) deleteOwnedTree(stage); }
            }
            check(operation);
            File nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
            File node = nativeFile(nativeDirectory, manifest.optString("nodeExecutable", "libnode.so"));
            File bash = nativeFile(nativeDirectory, manifest.optString("bashExecutable", "libbash.so"));
            ensureDirectory(new File(usr, "bin"));
            link(new File(usr, "bin/node"), node);
            link(new File(usr, "bin/bash"), bash);
            JSONObject tools = manifest.optJSONObject("symlinks");
            if (tools != null) for (java.util.Iterator<String> names = tools.keys(); names.hasNext();) {
                String name = names.next();
                if (!name.matches("[a-zA-Z0-9_.+-]+")) throw new IOException("Invalid bundled tool name.");
                link(new File(usr, "bin/" + name), nativeFile(nativeDirectory, tools.getString(name)));
            }
            JSONObject usrLinks = manifest.optJSONObject("usrLinks");
            if (usrLinks != null) for (java.util.Iterator<String> names = usrLinks.keys(); names.hasNext();) {
                String relative = names.next();
                link(linkLocation(usr, relative), nativeFile(nativeDirectory, usrLinks.getString(relative)));
            }
            JSONObject links = manifest.optJSONObject("addonLinks");
            if (links == null) links = platform.optJSONObject("addonLinks");
            if (links != null) for (java.util.Iterator<String> names = links.keys(); names.hasNext();) {
                String relative = names.next();
                link(linkLocation(backend, relative), nativeFile(nativeDirectory, links.getString(relative)));
            }
            // code-server's internal launchers must resolve to the same readonly
            // Android executable, never the Linux binary from its archive.
            link(new File(backend, "lib/vscode/node"), node);
            File home = new File(context.getFilesDir(), "home"); ensureDirectory(home);
            File workspace = new File(context.getFilesDir(), "workspaces/default"); ensureDirectory(workspace);
            initializeWorkspace(workspace);
            File state = new File(home, ".local/share/vstab/user-data"); ensureDirectory(state);
            ensureDirectory(new File(state, "extensions"));
            File temporary = new File(context.getCacheDir(), "native-tmp"); ensureDirectory(temporary);
            Map<String, String> environment = environment(nativeDirectory, node, usr, home, temporary);
            Map<String, String> substitutions = new java.util.HashMap<>();
            substitutions.put("backend", backend.getAbsolutePath()); substitutions.put("usr", usr.getAbsolutePath());
            substitutions.put("home", home.getAbsolutePath()); substitutions.put("workspace", workspace.getAbsolutePath());
            substitutions.put("state", state.getAbsolutePath()); substitutions.put("nativeLibDir", nativeDirectory.getAbsolutePath());
            JSONObject declaredEnvironment = manifest.optJSONObject("environment");
            if (declaredEnvironment != null) for (java.util.Iterator<String> names = declaredEnvironment.keys(); names.hasNext();) {
                String name = names.next();
                // Keep the device's own trust roots, including user-added roots.
                if (name.equals("SSL_CERT_FILE") || name.equals("GIT_SSL_CAINFO") || name.equals("NODE_EXTRA_CA_CERTS")) continue;
                environment.put(name, substitute(declaredEnvironment.getString(name), substitutions));
            }
            prepareShell(home, usr);
            publish(operation, "Checking native Node, terminals and extension storage…");
            String proof = runProbe(node, backend, environment, operation);
            Files.write(new File(collection, "last-probe.json").toPath(), proof.getBytes(StandardCharsets.UTF_8));
            String password = password(new File(home, ".vstab-server-password"));
            File config = new File(home, ".vstab-server.yaml");
            Files.write(config.toPath(), ("auth: password\npassword: " + password + "\ncert: false\n").getBytes(StandardCharsets.UTF_8));
            Os.chmod(config.getAbsolutePath(), 0600);
            if (gateway == null) {
                SharedPreferences preferences = context.getSharedPreferences("native-runtime-origin", Context.MODE_PRIVATE);
                LocalEditorGateway created = new LocalEditorGateway(preferences.getInt("port", 0), randomSecret());
                if (!preferences.edit().putInt("port", created.port()).commit()) { created.close(); throw new IOException("Cannot save the local editor identity. Free device storage and retry."); }
                gateway = created;
            }
            LocalEditorGateway route = gateway;
            // Keep the UNIX address short enough for Android's sockaddr_un.
            File socket = new File(context.getFilesDir(), "native-editor-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
            substitutions.put("socket", socket.getAbsolutePath()); substitutions.put("config", config.getAbsolutePath());
            String origin = "http://localhost:" + route.port();
            List<String> command = new ArrayList<>();
            command.add(node.getAbsolutePath()); command.add("--max-old-space-size=1024");
            org.json.JSONArray arguments = manifest.getJSONArray("arguments");
            for (int index = 0; index < arguments.length(); index++) command.add(substitute(arguments.getString(index), substitutions));
            publish(operation, "Starting the on-device Node extension host and terminal services…");
            ProcessBuilder builder = new ProcessBuilder(command).directory(backend).redirectErrorStream(true);
            builder.environment().putAll(environment);
            child = builder.start();
            process = child;
            child.getOutputStream().close();
            drain(child.getInputStream());
            route.selectBackend(socket.getAbsolutePath());
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (true) {
                check(operation);
                if (!child.isAlive()) throw new IOException("Native server exited: " + logTail());
                if (System.nanoTime() > until) throw new IOException("The on-device server did not become ready: " + logTail());
                if (healthy(origin, route.requestCookie())) break;
                Thread.sleep(200);
            }
            List<String> cookies = authenticate(origin, password, route.requestCookie());
            cookies.add(0, route.cookie());
            check(operation);
            ConnectionController.Profile profile = new ConnectionController.Profile("localhost", route.port(), "Android", "", workspace.getAbsolutePath(), null, "");
            ConnectionController.Ready completed = new ConnectionController.Ready(origin,
                    origin + "/?folder=" + encode(workspace.getAbsolutePath()), profile, manifest.getString("codeVersion"),
                    manifest.getString("codeServerVersion"), home.getAbsolutePath(), workspace.getAbsolutePath(), route.port(), cookies, true);
            ready = completed; starting = false;
            publish(operation, "On-device development environment ready");
            main.post(() -> { if (operation == generation.get()) for (Observer observer : observers) observer.onLocalRuntimeReady(completed); });
            Process monitored = child;
            Thread watcher = new Thread(() -> {
                try {
                    int exit = monitored.waitFor();
                    if (operation == generation.get()) failed(operation, "The on-device server stopped (exit " + exit + "). " + logTail());
                } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }, "vstab native process monitor");
            watcher.setDaemon(true); watcher.start();
        } catch (Exception error) {
            if (child != null) child.destroyForcibly();
            if (operation == generation.get()) failed(operation, error.getMessage() == null ? "The on-device runtime could not start." : error.getMessage());
        }
    }

    private Map<String, String> environment(File nativeDirectory, File node, File usr, File home, File temporary) throws Exception {
        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("HOME", home.getAbsolutePath());
        environment.put("XDG_CONFIG_HOME", new File(home, ".config").getAbsolutePath());
        environment.put("TMPDIR", temporary.getAbsolutePath());
        environment.put("LD_LIBRARY_PATH", nativeDirectory + ":" + new File(usr, "lib"));
        environment.put("PATH", nativeDirectory + ":" + new File(usr, "bin") + ":/system/bin");
        environment.put("SHELL", new File(usr, "bin/bash").getAbsolutePath());
        environment.put("TERM", "xterm-256color");
        environment.put("TERMINFO", new File(usr, "share/terminfo").getAbsolutePath());
        environment.put("LANG", "en_US.UTF-8");
        environment.put("PREFIX", usr.getAbsolutePath());
        environment.put("VSTAB_NODE", node.getAbsolutePath());
        environment.put("VSTAB_NODE_PATH", node.getAbsolutePath());
        environment.put("VSTAB_USR", usr.getAbsolutePath());
        environment.put("VSTAB_RIPGREP_PATH", new File(nativeDirectory, "librg.so").getAbsolutePath());
        environment.put("BASH_ENV", new File(home, ".vstab-bash-env").getAbsolutePath());
        File certificates = new File(home, ".vstab-trust.pem");
        KeyStore store = KeyStore.getInstance("AndroidCAStore"); store.load(null, null);
        StringBuilder pem = new StringBuilder();
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            Certificate certificate = store.getCertificate(aliases.nextElement());
            if (certificate != null) pem.append("-----BEGIN CERTIFICATE-----\n")
                    .append(Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded()))
                    .append("\n-----END CERTIFICATE-----\n");
        }
        Files.write(certificates.toPath(), pem.toString().getBytes(StandardCharsets.US_ASCII));
        environment.put("NODE_EXTRA_CA_CERTS", certificates.getAbsolutePath());
        environment.put("SSL_CERT_FILE", certificates.getAbsolutePath());
        environment.put("GIT_SSL_CAINFO", certificates.getAbsolutePath());
        return environment;
    }

    private String runProbe(File node, File backend, Map<String, String> environment, int operation) throws Exception {
        File script = new File(backend, "vstab-runtime-probe.cjs");
        if (!script.isFile()) throw new IOException("The APK is missing its native runtime self-check.");
        ProcessBuilder builder = new ProcessBuilder(node.getAbsolutePath(), script.getAbsolutePath()).directory(backend).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process probe = builder.start();
        try {
            if (!probe.waitFor(45, TimeUnit.SECONDS)) throw new IOException("The native runtime self-check timed out.");
            String output = new String(read(probe.getInputStream(), 65536), StandardCharsets.UTF_8).trim();
            if (probe.exitValue() != 0) throw new IOException("Native runtime self-check failed: " + output);
            JSONObject report = null;
            for (String line : output.split("\n")) if (line.trim().startsWith("{")) report = new JSONObject(line.trim());
            if (report == null || !"ready".equals(report.optString("status")) || !"android".equals(report.optString("platform"))) throw new IOException("The native runtime did not pass its Android self-check.");
            for (String item : new String[]{"fork", "pty", "sqlite", "argon2", "watcher"}) if (!report.optBoolean(item)) throw new IOException("Native runtime component failed: " + item);
            check(operation);
            return report.toString();
        } finally { if (probe.isAlive()) probe.destroyForcibly(); }
    }

    private void unpack(String name, String expectedHash, File directory, int operation) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = context.getAssets().open(name)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) { digest.update(buffer, 0, count); check(operation); }
        }
        if (!hex(digest.digest()).equals(expectedHash)) throw new IOException("A bundled native runtime archive failed verification.");
        ensureDirectory(directory);
        try (ZipInputStream input = new ZipInputStream(context.getAssets().open(name))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                check(operation);
                File target = inside(directory, entry.getName());
                if (entry.isDirectory()) { ensureDirectory(target); continue; }
                ensureDirectory(target.getParentFile());
                try (FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
            }
        }
    }

    private static boolean healthy(String origin, String cookie) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(origin + "/healthz").openConnection();
            http.setConnectTimeout(1000); http.setReadTimeout(1000); http.setInstanceFollowRedirects(false);
            http.setRequestProperty("Cookie", cookie);
            return http.getResponseCode() == 200;
        } catch (IOException ignored) { return false; }
        finally { if (http != null) http.disconnect(); }
    }

    private static List<String> authenticate(String origin, String password, String gatewayCookie) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(origin + "/login").openConnection();
        try {
            http.setConnectTimeout(15000); http.setReadTimeout(15000); http.setInstanceFollowRedirects(false);
            http.setRequestMethod("POST"); http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            http.setRequestProperty("Origin", origin); http.setRequestProperty("Cookie", gatewayCookie);
            byte[] body = ("password=" + encode(password) + "&base=%2F").getBytes(StandardCharsets.UTF_8);
            http.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream request = http.getOutputStream()) { request.write(body); }
            int status = http.getResponseCode();
            if (status != 302 && status != 303) throw new IOException("The on-device workbench authentication failed (HTTP " + status + ").");
            List<String> cookies = new ArrayList<>();
            for (Map.Entry<String, List<String>> item : http.getHeaderFields().entrySet()) if ("Set-Cookie".equalsIgnoreCase(item.getKey())) cookies.addAll(item.getValue());
            if (cookies.isEmpty()) throw new IOException("The on-device workbench did not issue an authenticated session.");
            return cookies;
        } finally { http.disconnect(); }
    }

    private void prepareShell(File home, File usr) throws Exception {
        String env = "# vstab provides Node/npm/npx as real APK-native processes on PATH.\n";
        Files.write(new File(home, ".vstab-bash-env").toPath(), env.getBytes(StandardCharsets.UTF_8));
        File rc = new File(home, ".bashrc");
        if (!rc.exists()) Files.write(rc.toPath(), ("[ -r \"$HOME/.vstab-bash-env\" ] && . \"$HOME/.vstab-bash-env\"\n").getBytes(StandardCharsets.UTF_8));
        File profile = new File(home, ".bash_profile");
        if (!profile.exists()) Files.write(profile.toPath(), ("[ -r \"$HOME/.bashrc\" ] && . \"$HOME/.bashrc\"\n").getBytes(StandardCharsets.UTF_8));
    }

    private String password(File file) throws Exception {
        if (file.isFile()) {
            String saved = new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII).trim();
            if (saved.matches("[a-f0-9]{64}")) return saved;
            throw new IOException("The private runtime password file is invalid.");
        }
        String password = randomSecret();
        Files.write(file.toPath(), password.getBytes(StandardCharsets.US_ASCII)); Os.chmod(file.getAbsolutePath(), 0600);
        return password;
    }
    private void drain(InputStream stream) {
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line; while ((line = input.readLine()) != null) synchronized (tail) { if (tail.size() >= 40) tail.removeFirst(); tail.add(line.length() > 2048 ? line.substring(0, 2048) : line); }
            } catch (IOException ignored) { }
        }, "vstab native runtime output");
        reader.setDaemon(true); reader.start();
    }
    private String logTail() { synchronized (tail) { return String.join("\n", tail); } }
    private void publish(int operation, String message) {
        if (operation != generation.get()) return;
        status = message;
        main.post(() -> { if (operation == generation.get()) for (Observer observer : observers) observer.onLocalRuntimeStatus(message); });
    }
    private void failed(int operation, String message) {
        if (operation != generation.get()) return;
        starting = false; ready = null; failure = message;
        if (gateway != null) gateway.withdrawBackend();
        main.post(() -> { if (operation == generation.get()) for (Observer observer : observers) observer.onLocalRuntimeFailure(message); });
    }
    private void check(int operation) throws InterruptedException { if (operation != generation.get()) throw new InterruptedException("Native startup cancelled."); }
    private byte[] assetBytes(String name, int max) throws Exception { try (InputStream input = context.getAssets().open(name)) { return read(input, max); } }
    private static byte[] read(InputStream input, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) { if (output.size() + count > max) throw new IOException("Runtime response is too large."); output.write(buffer, 0, count); }
        return output.toByteArray();
    }
    private static String assetName(String name) throws IOException { if (name.startsWith("/") || name.contains("..")) throw new IOException("Invalid native asset path."); return name.startsWith("local-runtime/") ? name : "local-runtime/" + name; }
    private static File nativeFile(File root, String name) throws Exception { if (!name.matches("lib[a-zA-Z0-9_.+-]+\\.so")) throw new IOException("Invalid native executable name."); File file = inside(root, name); if (!file.isFile() || !file.canExecute()) throw new IOException("APK native component is missing: " + name); return file; }
    private static File inside(File root, String name) throws IOException { if (name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) throw new IOException("Invalid runtime path."); File file = new File(root, name).getCanonicalFile(); if (!file.getPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Native runtime path traversal was rejected."); return file; }
    private static File linkLocation(File root, String name) throws IOException {
        if (name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) throw new IOException("Invalid native link path.");
        for (String part : name.split("/")) if (part.equals("..") || part.equals(".")) throw new IOException("Invalid native link path.");
        File location = new File(root, name);
        File parent = location.getParentFile().getCanonicalFile();
        String base = root.getCanonicalPath();
        if (!parent.getPath().equals(base) && !parent.getPath().startsWith(base + File.separator)) throw new IOException("Native link path traversal was rejected.");
        return new File(parent, location.getName());
    }
    private static String substitute(String value, Map<String, String> substitutions) throws IOException {
        for (Map.Entry<String, String> item : substitutions.entrySet()) value = value.replace("{" + item.getKey() + "}", item.getValue());
        if (value.indexOf('{') >= 0 || value.indexOf('}') >= 0) throw new IOException("Unknown native runtime argument placeholder.");
        return value;
    }
    private void initializeWorkspace(File directory) throws IOException {
        File initialized = new File(context.getFilesDir(), "local-workspace-initialized");
        if (!initialized.exists()) {
            File readme = new File(directory, "README.md");
            if (!readme.exists()) Files.write(readme.toPath(), ("# Your vstab workspace\n\nThese files are saved privately on this Android device.\n\nThe on-device Node runtime runs local terminals and extensions. Use **Remote-SSH: Connect to Host...** for a remote Linux workspace.\n").getBytes(StandardCharsets.UTF_8));
            File example = new File(directory, "hello.js");
            if (!example.exists()) Files.write(example.toPath(), "console.log('Hello from vstab');\n".getBytes(StandardCharsets.UTF_8));
            Files.write(initialized.toPath(), new byte[]{1});
        }
    }

    private static void ensureDirectory(File directory) throws IOException { if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create the native runtime directory. Free device storage and retry."); }
    private static void link(File link, File target) throws Exception { ensureDirectory(link.getParentFile()); Files.deleteIfExists(link.toPath()); Os.symlink(target.getAbsolutePath(), link.getAbsolutePath()); }
    private static String encode(String text) throws java.io.UnsupportedEncodingException { return URLEncoder.encode(text, "UTF-8"); }
    private static String randomSecret() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return hex(bytes); }
    private static String sha(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(); for (byte item : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255)); return value.toString(); }
    private static void deleteOwnedTree(File directory) throws IOException { try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) { for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); } }
}
