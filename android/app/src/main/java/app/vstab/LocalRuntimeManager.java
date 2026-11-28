package app.vstab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Owns a real Linux machine. Extension packages and their engines run unchanged inside it. */
final class LocalRuntimeManager {
    interface Observer {
        void onLocalRuntimeStatus(String message);
        void onLocalRuntimeReady(ConnectionController.Ready ready);
        void onLocalRuntimeFailure(String message);
        default void onLocalBrowserRequest(String url) { }
    }
    private final Context context;
    private final LinuxEnvironmentStore selection;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();
    private final ArrayDeque<String> tail = new ArrayDeque<>();
    private volatile ConnectionController.Ready ready;
    private volatile boolean starting;
    private volatile String status = "Choose your Linux development environment.";
    private volatile String failure;
    private volatile Process process;
    private volatile Session ssh;
    private volatile SshPortForwards forwards;
    private volatile LocalEditorGateway gateway;
    private String gatewayEnvironmentId;
    private volatile LinuxControlServer controls;
    private volatile AndroidFileBroker files;
    private volatile JSONObject bootReport;
    private volatile String bootFailure;
    private volatile CountDownLatch bootReady;
    private volatile LinuxBrowserBridge browserBridge;
    private volatile File activeBootstrap;
    private volatile String activeEnvironmentId;
    private volatile String activeTermuxAbi;

    LocalRuntimeManager(Context context) {
        this.context = context.getApplicationContext(); selection = new LinuxEnvironmentStore(context);
    }
    ConnectionController.Ready current() { return ready; }
    boolean isStarting() { return starting; }
    boolean isConfigured() { return !selection.provider().isEmpty(); }
    String selectedProvider() { return selection.provider(); }
    void exportAndroidFolder(String id, android.net.Uri source) {
        AndroidFileBroker broker = files;
        if (broker == null) return;
        worker.execute(() -> {
            try {
                boolean writable = false;
                for (android.content.UriPermission grant : context.getContentResolver().getPersistedUriPermissions())
                    if (grant.getUri().equals(source) && grant.isWritePermission()) writable = true;
                addAndroidFolder(broker, source, !writable);
            } catch (Exception error) { main.post(() -> { for (Observer observer : observers) observer.onLocalRuntimeStatus("Android folder could not be mounted: " + error.getMessage()); }); }
        });
    }
    private static void addAndroidFolder(AndroidFileBroker broker, android.net.Uri source, boolean readOnly) throws Exception {
        String id = hex(MessageDigest.getInstance("SHA-256").digest(source.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        broker.addDocumentTree("folder-" + id, "/mnt/android/folders/" + id, source, readOnly);
    }
    void selectProvider(String provider) {
        if (starting || ready != null) throw new IllegalStateException("Stop the current environment before changing its provider.");
        selection.choose(provider); failure = null;
    }
    JSONObject lastProbeForDiagnostics() throws Exception {
        File f = new File(context.getFilesDir(), "linux-environments/last-probe.json");
        return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
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
        if (!isConfigured()) { failed(generation.get(), "Choose Termux or built-in Linux first."); return; }
        int operation = generation.incrementAndGet(); starting = true; failure = null;
        synchronized (tail) { tail.clear(); }
        worker.execute(() -> launch(operation));
    }
    synchronized void stop() {
        int operation = generation.incrementAndGet(); starting = false; ready = null;
        if (gateway != null) gateway.withdrawBackend();
        worker.execute(() -> {
            try { closeEnvironment(); }
            catch (RuntimeException error) { failed(operation, error.getMessage()); }
        });
        String message = "The Linux environment is stopped.";
        failure = message;
        main.post(() -> { if (operation == generation.get()) for (Observer observer : observers) observer.onLocalRuntimeFailure(message); });
    }

    /** Translate only explicitly exported Android roots, leaving ordinary guest paths untouched. */
    String guestPath(String path) {
        String root = context.getFilesDir().getAbsolutePath();
        String canonical;
        try { canonical = new File(path).getCanonicalPath(); } catch (IOException error) { return path; }
        for (String name : new String[]{"workspaces", "shared-documents"}) {
            String source = root + "/" + name;
            try { source = new File(source).getCanonicalPath(); } catch (IOException ignored) { }
            if (canonical.equals(source) || canonical.startsWith(source + "/"))
                return "/mnt/android/app/" + name + canonical.substring(source.length());
        }
        return path;
    }

    private void launch(int operation) {
        boolean launched = false;
        try {
            closeEnvironment(); check(operation);
            launched = true;
            publish(operation, "Preparing Linux…");
            JSONObject hostManifest = jsonAsset("linux-runtime/host-manifest.json");
            JSONObject guestManifest = jsonAsset("linux-runtime/guest-manifest.json");
            String abi = null;
            for (String candidate : Build.SUPPORTED_ABIS)
                if (hostManifest.getJSONObject("architectures").has(candidate)
                        && guestManifest.getJSONObject("architectures").has(candidate)) { abi = candidate; break; }
            if (abi == null) throw new IOException("This build does not include a Linux environment for this device architecture.");
            JSONObject host = hostManifest.getJSONObject("architectures").getJSONObject(abi);
            JSONObject guest = guestManifest.getJSONObject("architectures").getJSONObject(abi);
            guest.put("codeVersion", guestManifest.getString("codeVersion")).put("serverVersion", guestManifest.getString("serverVersion"));
            File collection = new File(context.getFilesDir(), "linux-environments"); ensureDirectory(collection);
            File environment = new File(collection, selection.environmentId()); ensureDirectory(environment);
            File machine = new File(environment, abi); ensureDirectory(machine);
            if (LinuxEnvironmentStore.BUNDLED.equals(selection.provider())) {
                guest = LinuxGuestInstallation.select(machine, guest, guestManifest.getString("serverVersion"));
                LinuxGuestInstallation.record(machine, guest, guestManifest.getString("serverVersion"));
            }
            File hostDirectory = new File(collection, "host-" + abi + "-" + host.getString("archiveSha256").substring(0, 12));
            if (!new File(hostDirectory, ".complete").isFile()) {
                publish(operation, "Setting up Linux…");
                unpack(host.getString("archive"), host.getString("archiveSha256"), hostDirectory, operation);
                Files.write(new File(hostDirectory, ".complete").toPath(), new byte[]{1});
            }
            File kernel = verifiedCopy(guest.getString("kernelAsset"), guest.getString("kernelSha256"), new File(machine, "kernel"), operation);
            File initrd = null;
            if (guest.has("initrdAsset")) initrd = verifiedCopy(guest.getString("initrdAsset"), guest.getString("initrdSha256"), new File(machine, "initrd"), operation);
            File disk = new File(machine, "system.ext4");
            if (LinuxEnvironmentStore.BUNDLED.equals(selection.provider()) && !disk.isFile()) {
                publish(operation, "Creating your workspace…");
                inflateDisk(guest, disk, operation);
            }
            if (LinuxEnvironmentStore.BUNDLED.equals(selection.provider())
                    && guest.optJSONObject("capabilities") != null && guest.getJSONObject("capabilities").optBoolean("diskGrowth")) {
                LinuxDiskCapacity.ensureMinimum(disk, guest.getLong("logicalDiskBytes"), true, true);
            }
            File bootstrap = new File(machine, "bootstrap"); ensureDirectory(bootstrap);
            Files.deleteIfExists(new File(bootstrap, "stop-request").toPath());
            File keys = new File(environment, "machine-key");
            if (!keys.isFile()) {
                KeyPair key = KeyPair.genKeyPair(new JSch(), KeyPair.ECDSA, 256);
                try {
                    key.writePrivateKey(keys.getAbsolutePath()); key.writePublicKey(keys.getAbsolutePath() + ".pub", "vstab-machine");
                    Os.chmod(keys.getAbsolutePath(), 0600);
                } finally { key.dispose(); }
            }
            String publicKey = new String(Files.readAllBytes(new File(keys.getAbsolutePath() + ".pub").toPath()), StandardCharsets.US_ASCII).trim();
            String password = privateSecret(new File(environment, "editor-password"));
            String controlToken = randomSecret();
            files = new AndroidFileBroker(context);
            File projects = new File(context.getFilesDir(), "workspaces"); ensureDirectory(projects);
            File shared = new File(context.getFilesDir(), "shared-documents"); ensureDirectory(shared);
            files.addAppDirectory("projects", "/mnt/android/app/workspaces", projects, false);
            files.addAppDirectory("shared", "/mnt/android/app/shared-documents", shared, false);
            File sshHome = new File(context.getFilesDir(), "home/.ssh"); ensureDirectory(sshHome);
            Os.chmod(sshHome.getAbsolutePath(), 0700);
            files.addAppDirectory("ssh", "/mnt/android/app/ssh", sshHome, false);
            JSONObject fileConfiguration = files.manifest("10.0.2.2");
            bootReport = null; bootFailure = null; bootReady = new CountDownLatch(1);
            controls = new LinuxControlServer(context, controlToken, new LinuxControlServer.Handler() {
                @Override public void console(String line) { acceptConsole(line, operation); }
                @Override public JSONObject browser(JSONObject request) throws Exception { check(operation); return browserRequest(request, operation); }
            });
            StringBuilder config = new StringBuilder();
            config(config, "VSTAB_ENVIRONMENT_ID_B64", selection.environmentId());
            config(config, "VSTAB_AUTHORIZED_KEY_B64", publicKey);
            config(config, "VSTAB_EDITOR_PASSWORD_B64", password);
            config(config, "VSTAB_FILES_CONFIG_B64", fileConfiguration.toString());
            config(config, "VSTAB_FILES_TOKEN_B64", fileConfiguration.getString("token"));
            config(config, "VSTAB_MOUNTS_B64", fileConfiguration.getJSONArray("roots").toString());
            config.append("VSTAB_FILES_HOST=10.0.2.2\nVSTAB_FILES_PORT=").append(files.port()).append('\n');
            config.append("VSTAB_CONTROL_HOST=10.0.2.2\nVSTAB_CONTROL_PORT=").append(controls.port()).append('\n');
            config(config, "VSTAB_CONTROL_TOKEN_B64", controlToken);
            File bootFile = new File(bootstrap, "bootstrap.env");
            Files.write(bootFile.toPath(), config.toString().getBytes(StandardCharsets.UTF_8)); Os.chmod(bootFile.getAbsolutePath(), 0600);
            int sshPort = freePort(), editorPort = freePort();
            check(operation);
            publish(operation, "Starting Linux…");
            activeEnvironmentId = selection.environmentId();
            if (LinuxEnvironmentStore.TERMUX.equals(selection.provider())) {
                activeTermuxAbi = abi;
                startTermux(guest, abi, bootstrap, machine, controlToken, sshPort, editorPort);
            } else {
                activeBootstrap = bootstrap;
                File executable = new File(context.getApplicationInfo().nativeLibraryDir, host.getString("executable"));
                verifyFile(executable, host.getString("executableSha256"));
                List<String> command = vmCommand(executable.getAbsolutePath(), hostDirectory, guest, kernel, initrd, disk, bootstrap, sshPort, editorPort);
                ProcessBuilder builder = new ProcessBuilder(command).directory(machine).redirectErrorStream(true);
                builder.environment().remove("LD_PRELOAD"); builder.environment().remove("NODE_OPTIONS");
                builder.environment().put("LD_LIBRARY_PATH", new File(hostDirectory, "lib").getAbsolutePath());
                builder.environment().put("HOME", machine.getAbsolutePath()); builder.environment().put("TMPDIR", machine.getAbsolutePath());
                builder.environment().put("QEMU_MODULE_DIR", new File(hostDirectory, "modules").getAbsolutePath());
                process = builder.start(); process.getOutputStream().close(); drain(process.getInputStream(), operation);
            }
            long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            while (!bootReady.await(250, TimeUnit.MILLISECONDS)) {
                check(operation);
                if (process != null && !process.isAlive()) throw new IOException("Linux machine exited: " + logTail());
                if (System.nanoTime() > until) throw new IOException("Linux did not become ready. " + logTail());
            }
            check(operation);
            if (bootFailure != null) throw new IOException(bootFailure);
            JSONObject report = bootReport;
            if (report == null || !selection.environmentId().equals(report.getString("environmentId"))) throw new IOException("The Linux machine identity did not match.");
            connectGuest(keys, sshPort, report.getJSONObject("sshHostKey"));
            if (gateway != null && !selection.environmentId().equals(gatewayEnvironmentId)) {
                gateway.close(); gateway = null; gatewayEnvironmentId = null;
            }
            if (gateway == null) {
                SharedPreferences saved = context.getSharedPreferences("linux-runtime-origin", Context.MODE_PRIVATE);
                LocalEditorGateway created = new LocalEditorGateway(saved.getInt(selection.environmentId(), 0), randomSecret());
                if (!saved.edit().putInt(selection.environmentId(), created.port()).commit()) { created.close(); throw new IOException("Cannot save the editor origin."); }
                gateway = created;
                gatewayEnvironmentId = selection.environmentId();
            }
            gateway.selectLoopbackBackend(editorPort);
            String origin = "http://localhost:" + gateway.port();
            List<String> cookies = authenticate(origin, password, gateway.requestCookie()); cookies.add(0, gateway.cookie());
            File previousDefault = new File(projects, "default");
            String[] previousFiles = previousDefault.list();
            String folder = previousFiles != null && previousFiles.length > 0
                    ? "/mnt/android/app/workspaces/default" : "/home/developer/workspaces/default";
            ConnectionController.Profile profile = new ConnectionController.Profile("127.0.0.1", sshPort, "developer", "", folder, null, "", selection.environmentId());
            ConnectionController.Ready completed = new ConnectionController.Ready(origin, origin + "/?folder=" + encode(folder), profile,
                    report.optString("codeVersion", "1.146.0"), report.optString("serverVersion", "4.146.0"), "/home/developer", folder, 3000, cookies, true);
            completed.environmentProvider = selection.provider(); completed.environmentId = selection.environmentId(); completed.directLoopback = false;
            JSONObject proof = new JSONObject().put("status", "ready").put("platform", "linux").put("architecture", abi)
                    .put("provider", selection.provider()).put("environmentId", selection.environmentId()).put("guest", report)
                    .put("extensionSpecificAdapters", 0).put("engineSubstitutions", 0);
            Files.write(new File(collection, "last-probe.json").toPath(), proof.toString().getBytes(StandardCharsets.UTF_8));
            check(operation); ready = completed; starting = false;
            publish(operation, "Workspace ready");
            main.post(() -> { if (operation == generation.get()) for (Observer observer : observers) observer.onLocalRuntimeReady(completed); });
            for (android.content.UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
                if (!android.provider.DocumentsContract.isTreeUri(grant.getUri())) continue;
                try { check(operation); addAndroidFolder(files, grant.getUri(), !grant.isWritePermission()); }
                catch (Exception inaccessible) { publish(operation, "An Android folder needs its storage provider reconnected."); }
            }
            if (process != null) {
                Process running = process;
                Thread monitor = new Thread(() -> {
                    try { int exit = running.waitFor(); runtimeFailed(operation, "Linux stopped (exit " + exit + "). " + logTail()); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }, "vstab Linux monitor"); monitor.setDaemon(true); monitor.start();
            }
        } catch (Exception error) {
            if (operation == generation.get()) {
                failed(operation, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                if (launched) try { closeEnvironment(); }
                catch (RuntimeException cleanup) { failed(operation, cleanup.getMessage()); }
            }
        }
    }

    static List<String> vmCommand(String executable, File host, JSONObject guest, File kernel, File initrd,
                                  File disk, File bootstrap, int sshPort, int editorPort) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList(executable, "-L", new File(host, "share/qemu").getAbsolutePath(),
                "-no-user-config", "-nodefaults", "-display", "none", "-accel", "tcg", "-machine", guest.optString("guestMachine", "q35"),
                "-cpu", guest.optString("guestCpu", "max"), "-m", "1024", "-smp", "2", "-kernel", kernel.getAbsolutePath()));
        if (initrd != null) args.addAll(Arrays.asList("-initrd", initrd.getAbsolutePath()));
        args.addAll(Arrays.asList("-append", guest.getString("kernelArguments"), "-drive", "if=none,id=system,file=" + qemuPath(disk) + ",format=raw",
                "-device", "virtio-blk-pci,drive=system", "-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:" + sshPort + "-:22,hostfwd=tcp:127.0.0.1:" + editorPort + "-:3000",
                "-device", "virtio-net-pci,netdev=net0,romfile=", "-fsdev", "local,id=bootstrap,path=" + qemuPath(bootstrap) + ",security_model=none,readonly=on",
                "-device", "virtio-9p-pci,fsdev=bootstrap,mount_tag=vstab-bootstrap", "-object", "rng-random,id=rng0,filename=/dev/urandom",
                "-device", "virtio-rng-pci,rng=rng0", "-serial", "stdio", "-monitor", "none", "-no-reboot"));
        return args;
    }
    private static String qemuPath(File file) { return file.getAbsolutePath().replace(",", ",,"); }

    private void connectGuest(File keys, int port, JSONObject key) throws Exception {
        byte[] expected = Base64.getDecoder().decode(key.getString("key"));
        JSch client = new JSch(); client.addIdentity(keys.getAbsolutePath());
        client.setHostKeyRepository(new HostKeyRepository() {
            public int check(String host, byte[] offered) { return MessageDigest.isEqual(expected, offered) ? OK : CHANGED; }
            public void add(HostKey key, com.jcraft.jsch.UserInfo user) { }
            public void remove(String host, String type) { }
            public void remove(String host, String type, byte[] key) { }
            public String getKnownHostsRepositoryID() { return "Owned Linux machine"; }
            public HostKey[] getHostKey() { return new HostKey[0]; }
            public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
        });
        Session opened = client.getSession("developer", "127.0.0.1", port);
        opened.setConfig("StrictHostKeyChecking", "yes"); opened.setConfig("PreferredAuthentications", "publickey");
        opened.setServerAliveInterval(15000); opened.setServerAliveCountMax(3); opened.connect(30000); ssh = opened;
        browserBridge = new LinuxBrowserBridge(new LinuxBrowserBridge.Transport() {
            public JSONObject helper(String mode, String... arguments) throws Exception { return guestBrowserHelper(opened, mode, arguments); }
            public int forward(String address, int port, String remote) throws Exception { return opened.setPortForwardingL(address, port, remote, port); }
            public void remove(String address, int port) throws Exception { opened.delPortForwardingL(address, port); }
        }, bootReport.getInt("backendPid"));
        forwards = new SshPortForwards("127.0.0.21", new SshPortForwards.Transport() {
            public boolean isConnected() { return opened.isConnected(); }
            public int open(String host, int local, String remote, int destination) throws Exception { return opened.setPortForwardingL(host, local, remote, destination); }
            public void close(String host, int local) throws Exception { opened.delPortForwardingL(host, local); }
        });
    }

    private JSONObject browserRequest(JSONObject request, int operation) throws Exception {
        check(operation);
        URI target = new URI(request.getString("url"));
        if (!("http".equals(target.getScheme()) || "https".equals(target.getScheme())) || target.getHost() == null || target.getUserInfo() != null)
            throw new IOException("Only browser web addresses are supported.");
        Session session = ssh;
        if (session == null || !session.isConnected()) throw new IOException("The Linux browser bridge is not ready.");
        LinuxBrowserBridge bridge = browserBridge;
        if (bridge == null) throw new IOException("The Linux browser connection is unavailable.");
        JSONArray required = request.optJSONArray("requiredPorts");
        JSONArray owners = request.optJSONArray("listenerOwners");
        if (required != null && required.length() > 0 && (owners == null || owners.length() == 0)) {
            JSONObject opener = request.getJSONObject("opener");
            StringJoiner ports = new StringJoiner(",");
            for (int i = 0; i < required.length(); i++) ports.add(Integer.toString(required.getInt(i)));
            JSONObject discovered = guestBrowserHelper(session, "--discover-listeners", Integer.toString(opener.getInt("pid")), ports.toString());
            request.put("scope", discovered.getJSONObject("scope")).put("listenerOwners", discovered.getJSONArray("listeners"));
        }
        bridge.prepareNative(request); check(operation);
        String address = target.toASCIIString();
        main.post(() -> { if (operation == generation.get() && session == ssh) for (Observer observer : observers) observer.onLocalBrowserRequest(address); });
        JSONArray forwarded = new JSONArray();
        for (Object value : LinuxBrowserBridge.requiredPorts(address)) forwarded.put(value);
        JSONArray stated = request.optJSONArray("listeners");
        if (stated != null) for (int i = 0; i < stated.length(); i++) forwarded.put(stated.getInt(i));
        return new JSONObject().put("accepted", true).put("forwardedPorts", forwarded);
    }

    void prepareExternalBrowser(String address, Runnable open, Consumer<String> failed) {
        final int operation = generation.get(); final LinuxBrowserBridge bridge = browserBridge;
        worker.execute(() -> {
            try {
                check(operation); if (bridge == null) throw new IOException("The Linux browser connection is unavailable.");
                bridge.prepareExternal(address); check(operation);
                main.post(() -> { if (operation == generation.get()) open.run(); });
            } catch (Exception error) { main.post(() -> { if (operation == generation.get()) failed.accept(error.getMessage()); }); }
        });
    }
    private static JSONObject guestBrowserHelper(Session session, String mode, String... arguments) throws Exception {
        if (!Arrays.asList("--verify-listeners", "--list-session-listeners", "--discover-listeners").contains(mode)) throw new IOException("Unknown browser operation.");
        StringBuilder command = new StringBuilder("sudo -n /opt/code-server/lib/node /usr/local/lib/vstab/open-uri.cjs ").append(mode);
        for (String argument : arguments) {
            if (!argument.matches("[a-zA-Z0-9+/=,]{1,65536}")) throw new IOException("Invalid browser operation parameters.");
            command.append(" '").append(argument).append('\'');
        }
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command.toString()); channel.setInputStream(null);
        channel.setErrStream(new OutputStream() { @Override public void write(int b) { } });
        try {
            InputStream output = channel.getInputStream(); channel.connect(10000);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (true) {
                while (output.available() > 0) {
                    int n = output.read(buffer); if (n < 0) break;
                    if (bytes.size() + n > 65536) throw new IOException("The browser listener inventory is too large."); bytes.write(buffer, 0, n);
                }
                if (channel.isClosed() && output.available() == 0) break;
                if (System.nanoTime() > until) throw new IOException("Linux callback verification timed out."); Thread.sleep(20);
            }
            if (channel.getExitStatus() != 0) throw new IOException("The Linux callback listener could not be verified.");
            return new JSONObject(bytes.toString("UTF-8"));
        } finally { channel.disconnect(); }
    }

    void tunnelRequest(String method, JSONObject params, Consumer<JSONObject> success, Consumer<NativeSshBridge.Failure> failure) {
        worker.execute(() -> {
            try {
                SshPortForwards active = forwards;
                if (active == null) throw new IOException("The Linux environment is disconnected.");
                JSONObject result = new JSONObject();
                if (method.equals("tunnels.forward")) {
                    Integer preferred = params.has("localPort") && !params.isNull("localPort") ? params.getInt("localPort") : null;
                    result = forwardJson(active.forward(params.optString("remoteHost", "127.0.0.1"), params.getInt("remotePort"), preferred));
                } else if (method.equals("tunnels.close")) result.put("closed", active.close(params.getString("id"), params.getString("connectionId")));
                else if (method.equals("tunnels.list")) {
                    JSONArray entries = new JSONArray(); for (SshPortForwards.Forward value : active.list()) entries.put(forwardJson(value));
                    result.put("tunnels", entries).put("connectionId", active.connectionId);
                } else throw new IOException("Unknown tunnel operation.");
                success.accept(result);
            } catch (Exception error) { failure.accept(new NativeSshBridge.Failure("Unavailable", error.getMessage())); }
        });
    }
    private static JSONObject forwardJson(SshPortForwards.Forward value) throws Exception {
        return new JSONObject().put("id", value.id).put("connectionId", value.connectionId).put("remoteHost", value.remoteHost)
                .put("remotePort", value.remotePort).put("localHost", value.localHost).put("localPort", value.localPort).put("localAddress", value.localAddress);
    }

    private void acceptConsole(String text, int operation) {
        if (operation != generation.get()) return;
        for (String line : text.split("\n")) {
            int error = line.indexOf("VSTAB_LINUX_ERROR ");
            if (error >= 0) {
                bootFailure = "Linux startup failed: " + line.substring(error + 18, Math.min(line.length(), error + 800));
                CountDownLatch latch = bootReady; if (latch != null) latch.countDown();
                runtimeFailed(operation, bootFailure);
            }
            int marker = line.indexOf("VSTAB_LINUX_READY ");
            if (marker >= 0) {
                try {
                    JSONObject report = new JSONObject(line.substring(marker + 18));
                    if (selection.environmentId().equals(report.optString("environmentId"))) { bootReport = report; CountDownLatch latch = bootReady; if (latch != null) latch.countDown(); }
                } catch (Exception ignored) { }
            } else synchronized (tail) {
                if (tail.size() >= 30) tail.removeFirst(); tail.add(line.substring(0, Math.min(line.length(), 1000)));
            }
        }
    }
    private void drain(InputStream stream, int operation) {
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line; while ((line = input.readLine()) != null) acceptConsole(line, operation);
            } catch (IOException ignored) { }
            runtimeFailed(operation, "The Linux machine stopped. Your workspace is preserved; restart the environment to continue.");
        }, "vstab Linux console"); reader.setDaemon(true); reader.start();
    }
    private void closeEnvironment() {
        File bootstrap = activeBootstrap; activeBootstrap = null;
        String environmentId = activeEnvironmentId;
        if (bootstrap != null && environmentId != null) {
            try { Files.write(new File(bootstrap, "stop-request").toPath(), environmentId.getBytes(StandardCharsets.UTF_8)); }
            catch (IOException ignored) { }
        }
        LinuxBrowserBridge browser = browserBridge; browserBridge = null; if (browser != null) browser.close();
        SshPortForwards ports = forwards; forwards = null; if (ports != null) ports.invalidate();
        Session opened = ssh; ssh = null;
        if (opened != null) {
            try {
                ChannelExec shutdown = (ChannelExec) opened.openChannel("exec"); shutdown.setCommand("sudo -n /bin/busybox kill -TERM 1");
                shutdown.connect(1000); Thread.sleep(500); shutdown.disconnect();
            } catch (Exception ignored) { }
            opened.disconnect();
        }
        if (gateway != null) gateway.withdrawBackend();
        Process running = process; process = null;
        if (running != null && running.isAlive()) {
            try { if (!running.waitFor(5, TimeUnit.SECONDS)) running.destroy(); if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly(); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); running.destroy(); }
        }
        String termuxAbi = activeTermuxAbi; activeTermuxAbi = null;
        if (termuxAbi != null && environmentId != null) {
            TermuxRuntimeHost.Result stopped = TermuxRuntimeHost.stopAndWait(context, environmentId, termuxAbi, 45000);
            if (!"stopped".equals(stopped.status)) {
                // Preserve both the remote disk and its brokers until an owned stop succeeds.
                activeEnvironmentId = environmentId; activeTermuxAbi = termuxAbi;
                throw new IllegalStateException(stopped.message);
            }
        }
        if (files != null) { files.close(); files = null; }
        if (controls != null) { controls.close(); controls = null; }
        activeEnvironmentId = null;
    }

    private void startTermux(JSONObject guest, String abi, File bootstrap, File machine, String token, int sshPort, int editorPort) throws Exception {
        final int operation = generation.get();
        controls.exportAsset("kernel", guest.getString("kernelAsset"));
        if (guest.has("initrdAsset")) controls.exportAsset("initrd", guest.getString("initrdAsset"));
        controls.exportAsset("disk", guest.getString("diskAsset")); controls.exportFile("bootstrap", new File(bootstrap, "bootstrap.env"));
        StringBuilder spec = new StringBuilder();
        config(spec, "SCHEMA", "1"); config(spec, "KERNEL_SHA256", guest.getString("kernelSha256"));
        config(spec, "CODE_VERSION", guest.getString("codeVersion")); config(spec, "SERVER_VERSION", guest.getString("serverVersion"));
        boolean diskGrowth = guest.optJSONObject("capabilities") != null && guest.getJSONObject("capabilities").optBoolean("diskGrowth");
        config(spec, "DISK_GROWTH", Boolean.toString(diskGrowth));
        config(spec, "LOGICAL_DISK_BYTES", Long.toString(guest.optLong("logicalDiskBytes", guest.getLong("diskUnpackedBytes"))));
        config(spec, "INITRD_SHA256", guest.optString("initrdSha256", "")); config(spec, "DISK_SHA256", guest.getString("diskSha256"));
        config(spec, "DISK_UNPACKED_SHA256", guest.getString("diskUnpackedSha256"));
        config(spec, "DISK_COMPRESSION", guest.optString("diskCompression", "gzip"));
        config(spec, "KERNEL_ARGS_B64", Base64.getEncoder().encodeToString(guest.getString("kernelArguments").getBytes(StandardCharsets.UTF_8)));
        config(spec, "QEMU_MEMORY_MIB", "1024"); config(spec, "QEMU_SMP", "2");
        File specification = new File(machine, "termux-spec"); Files.write(specification.toPath(), spec.toString().getBytes(StandardCharsets.UTF_8));
        controls.exportFile("termux-spec", specification);
        TermuxRuntimeHost.start(context, "http://127.0.0.1:" + controls.port(), token, selection.environmentId(), abi, sshPort, editorPort,
                result -> {
                    if (operation != generation.get()) return;
                    bootFailure = "stopped".equals(result.status) ? "The Linux environment stopped." : result.message + (result.details.isEmpty() ? "" : "\n" + result.details);
                    CountDownLatch latch = bootReady; if (latch != null) latch.countDown();
                    runtimeFailed(operation, bootFailure);
                });
    }

    private JSONObject jsonAsset(String name) throws Exception {
        try (InputStream input = context.getAssets().open(name)) { return new JSONObject(new String(readBounded(input, 4 * 1024 * 1024), StandardCharsets.UTF_8)); }
    }
    private File verifiedCopy(String asset, String digest, File target, int operation) throws Exception {
        if (!asset.startsWith("linux-runtime/")) asset = "linux-runtime/" + asset;
        if (target.isFile()) { verifyFile(target, digest); return target; }
        File partial = new File(target.getPath() + ".partial");
        try (InputStream input = context.getAssets().open(asset); OutputStream output = new FileOutputStream(partial)) { copy(input, output, operation); }
        verifyFile(partial, digest); Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); return target;
    }
    private void unpack(String asset, String digest, File target, int operation) throws Exception {
        File archive = verifiedCopy(asset, digest, new File(target.getParentFile(), target.getName() + ".zip"), operation);
        ensureDirectory(target);
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                check(operation); File output = inside(target, entry.getName());
                if (entry.isDirectory()) { ensureDirectory(output); continue; }
                ensureDirectory(output.getParentFile());
                try (OutputStream file = new FileOutputStream(output)) { copy(input, file, operation); }
            }
        }
        Files.delete(archive.toPath());
    }
    private void inflateDisk(JSONObject guest, File disk, int operation) throws Exception {
        File compressed = verifiedCopy(guest.getString("diskAsset"), guest.getString("diskSha256"), new File(disk.getPath() + ".compressed"), operation);
        File partial = new File(disk.getPath() + ".partial");
        String format = guest.optString("diskCompression", "gzip");
        if (!format.equals("gzip") && !format.equals("xz")) throw new IOException("Unsupported Linux image compression.");
        try (InputStream raw = new FileInputStream(compressed);
             InputStream input = format.equals("xz") ? new org.tukaani.xz.XZInputStream(raw, 128 * 1024) : new GZIPInputStream(raw);
             RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
            output.setLength(0);
            byte[] buffer = new byte[65536]; int count; long length = 0;
            while ((count = input.read(buffer)) != -1) {
                check(operation); length += count;
                if (length > 16L * 1024 * 1024 * 1024) throw new IOException("The Linux disk exceeds its allowed capacity.");
                boolean empty = true; for (int i = 0; i < count; i++) if (buffer[i] != 0) { empty = false; break; }
                if (empty) output.seek(output.getFilePointer() + count); else output.write(buffer, 0, count);
            }
            output.setLength(length); output.getFD().sync();
        }
        verifyFile(partial, guest.getString("diskUnpackedSha256"));
        Files.move(partial.toPath(), disk.toPath(), StandardCopyOption.ATOMIC_MOVE); Files.delete(compressed.toPath());
    }
    private void copy(InputStream input, OutputStream output, int operation) throws Exception {
        byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) { check(operation); output.write(buffer, 0, count); }
    }
    private static void verifyFile(File file, String digest) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) { byte[] buffer = new byte[65536]; int n; while ((n = input.read(buffer)) != -1) hash.update(buffer, 0, n); }
        if (!hex(hash.digest()).equals(digest)) throw new IOException("A Linux runtime component failed checksum verification: " + file.getName());
    }
    private static List<String> authenticate(String origin, String password, String gatewayCookie) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(origin + "/login").openConnection();
        try {
            http.setConnectTimeout(15000); http.setReadTimeout(15000); http.setInstanceFollowRedirects(false);
            http.setRequestMethod("POST"); http.setDoOutput(true); http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            http.setRequestProperty("Origin", origin); http.setRequestProperty("Cookie", gatewayCookie);
            byte[] body = ("password=" + encode(password) + "&base=%2F").getBytes(StandardCharsets.UTF_8);
            http.setFixedLengthStreamingMode(body.length); try (OutputStream output = http.getOutputStream()) { output.write(body); }
            int code = http.getResponseCode();
            if (code != 302 && code != 303) throw new IOException("Linux editor authentication failed (HTTP " + code + ").");
            List<String> cookies = new ArrayList<>();
            for (Map.Entry<String, List<String>> item : http.getHeaderFields().entrySet()) if ("Set-Cookie".equalsIgnoreCase(item.getKey())) cookies.addAll(item.getValue());
            if (cookies.isEmpty()) throw new IOException("The Linux editor did not issue a session."); return cookies;
        } finally { http.disconnect(); }
    }
    private static void config(StringBuilder text, String key, String value) { text.append(key).append('=').append(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))).append('\n'); }
    private static int freePort() throws IOException { try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
    private static String privateSecret(File file) throws Exception {
        if (file.isFile()) { String value = new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII); if (value.matches("[a-f0-9]{64}")) return value; throw new IOException("Invalid runtime identity file."); }
        String value = randomSecret(); Files.write(file.toPath(), value.getBytes(StandardCharsets.US_ASCII)); Os.chmod(file.getAbsolutePath(), 0600); return value;
    }
    private void publish(int operation, String message) {
        if (operation != generation.get()) return; status = message;
        main.post(() -> { if (operation == generation.get()) for (Observer observer : observers) observer.onLocalRuntimeStatus(message); });
    }
    private void failed(int operation, String message) {
        if (operation != generation.get()) return; starting = false; ready = null; failure = message;
        if (gateway != null) gateway.withdrawBackend();
        main.post(() -> { if (operation == generation.get()) for (Observer observer : observers) observer.onLocalRuntimeFailure(message); });
    }
    private synchronized void runtimeFailed(int operation, String message) {
        if (operation != generation.get() || ready == null) return;
        int failedOperation = generation.incrementAndGet();
        failed(failedOperation, message);
        worker.execute(() -> {
            try { closeEnvironment(); }
            catch (RuntimeException error) { failed(failedOperation, error.getMessage()); }
        });
    }
    private void check(int operation) throws InterruptedException { if (operation != generation.get()) throw new InterruptedException("Linux startup cancelled."); }
    private String logTail() { synchronized (tail) { return String.join("\n", tail); } }
    private static void ensureDirectory(File directory) throws IOException { if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot prepare Linux storage. Free device storage and retry."); }
    private static File inside(File root, String path) throws IOException {
        if (path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) throw new IOException("Invalid Linux archive path.");
        File file = new File(root, path).getCanonicalFile(); if (!file.getPath().startsWith(root.getCanonicalPath() + "/")) throw new IOException("Linux archive traversal was rejected."); return file;
    }
    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = input.read(buffer)) != -1) { if (output.size() + n > limit) throw new IOException("Runtime metadata is too large."); output.write(buffer, 0, n); } return output.toByteArray();
    }
    private static String encode(String value) throws UnsupportedEncodingException { return URLEncoder.encode(value, "UTF-8"); }
    private static String randomSecret() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return hex(value); }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return out.toString(); }
}
