package app.vstab;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated, root-scoped filesystem transport for the managed Linux guest. */
final class AndroidFileBroker implements Closeable {
    static final int CHUNK = 128 * 1024, MAX_BODY = 192 * 1024, MAX_HANDLES = 64;
    static final long MAX_STAGE = 512L * 1024 * 1024;
    private final Context context;
    private final ServerSocket listener;
    private final String token = secret();
    private final Map<String, Root> roots = new LinkedHashMap<>();
    private final Map<String, Opened> handles = new ConcurrentHashMap<>();
    private final Semaphore handleSlots = new Semaphore(MAX_HANDLES);
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> { Thread t = new Thread(task, "vstab files request"); t.setDaemon(true); return t; });
    private volatile boolean closed;

    static final class Failure extends IOException {
        final int errno;
        Failure(int errno, String message) { super(message); this.errno = errno; }
    }
    static final class Entry {
        final String name; final int mode; final long size, mtime, inode;
        Entry(String name, int mode, long size, long mtime, long inode) {
            this.name = name; this.mode = mode; this.size = size; this.mtime = mtime; this.inode = inode;
        }
        JSONObject json() throws Exception { return new JSONObject().put("name", name).put("mode", mode)
                .put("size", size).put("mtime", mtime).put("inode", inode); }
    }
    interface FileHandle extends Closeable {
        byte[] read(long offset, int count) throws Exception;
        int write(long offset, byte[] bytes) throws Exception;
        void truncate(long size) throws Exception;
        void sync() throws Exception;
        Entry stat() throws Exception;
    }
    interface Store extends Closeable {
        default void cancel() { }
        void checkAccess(boolean write) throws Exception;
        JSONObject capabilities() throws Exception;
        Entry stat(String path) throws Exception;
        List<Entry> list(String path) throws Exception;
        FileHandle open(String path, boolean read, boolean write, boolean create, boolean exclusive, boolean truncate, int mode) throws Exception;
        void mkdir(String path, int mode) throws Exception;
        void delete(String path, boolean directory) throws Exception;
        void rename(String from, String to, boolean noReplace) throws Exception;
        void chmod(String path, int mode) throws Exception;
        void setTimes(String path, long accessMs, long modifiedMs) throws Exception;
        long available();
    }
    private static final class Root {
        final String id, mountPath, capability = secret(); final boolean readOnly; final Store store;
        volatile boolean active = true;
        Root(String id, String mountPath, boolean readOnly, Store store) {
            this.id = id; this.mountPath = mountPath; this.readOnly = readOnly; this.store = store;
        }
        JSONObject json() throws Exception { return new JSONObject().put("id", id).put("mountPath", mountPath)
                .put("capability", capability).put("readOnly", readOnly).put("capabilities", store.capabilities()); }
    }
    private static final class Opened {
        final Root root; final FileHandle file; final boolean readable, writable;
        Opened(Root root, FileHandle file, boolean readable, boolean writable) { this.root = root; this.file = file; this.readable = readable; this.writable = writable; }
    }

    AndroidFileBroker(Context context) throws IOException {
        this.context = context == null ? null : context.getApplicationContext();
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
        Thread acceptor = new Thread(this::accept, "vstab files listener"); acceptor.setDaemon(true); acceptor.start();
    }
    int port() { return listener.getLocalPort(); }

    JSONObject addAppDirectory(String id, String mountPath, File directory, boolean readOnly) throws Exception {
        return addRoot(id, mountPath, readOnly, AndroidBrokerStorage.directory(directory));
    }
    JSONObject addDocumentTree(String id, String mountPath, Uri tree, boolean readOnly) throws Exception {
        if (context == null) throw new IllegalStateException("Android context is required for document grants.");
        return addRoot(id, mountPath, readOnly, AndroidBrokerStorage.documents(context, tree, readOnly));
    }
    synchronized JSONObject addRoot(String id, String mountPath, boolean readOnly, Store store) throws Exception {
        if (closed) { store.close(); throw new IOException("File broker is closed."); }
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) { store.close(); throw new IllegalArgumentException("Invalid exported root identity."); }
        String suffix = mountPath.startsWith("/mnt/android/") ? mountPath.substring(13) : "";
        try { path(suffix); } catch (Exception invalid) { closeQuietly(store); throw invalid; }
        if (mountPath.getBytes(StandardCharsets.UTF_8).length > 4096 || !roots.containsKey(id) && roots.size() >= 64) {
            store.close(); throw new IllegalArgumentException("The exported filesystem exceeds its bound.");
        }
        if (suffix.isEmpty()) { store.close(); throw new IllegalArgumentException("Mounts must be below /mnt/android."); }
        for (Root root : roots.values()) if (!root.id.equals(id) && (root.mountPath.equals(mountPath)
                || root.mountPath.startsWith(mountPath + "/") || mountPath.startsWith(root.mountPath + "/"))) {
            store.close(); throw new IllegalArgumentException("Exported mount paths must not overlap.");
        }
        try { store.checkAccess(!readOnly); } catch (Exception denied) { closeQuietly(store); throw denied; }
        revoke(id);
        Root root = new Root(id, mountPath, readOnly, store); roots.put(id, root); return root.json();
    }
    synchronized void revoke(String id) {
        Root root = roots.remove(id); if (root == null) return;
        root.active = false; root.store.cancel();
        synchronized (root) {
            closeQuietly(root.store);
            Iterator<Map.Entry<String, Opened>> entries = handles.entrySet().iterator();
            while (entries.hasNext()) { Opened item = entries.next().getValue(); if (item.root == root) { entries.remove(); closeQuietly(item.file); handleSlots.release(); } }
        }
    }
    synchronized JSONObject manifest(String guestHost) throws Exception {
        if (!guestHost.matches("(?:127\\.0\\.0\\.1|10\\.0\\.2\\.2)")) throw new IllegalArgumentException("Use the managed guest host address.");
        return new JSONObject().put("version", 1).put("endpoint", "http://" + guestHost + ":" + port() + "/v1")
                .put("token", token).put("roots", rootList());
    }
    private synchronized JSONArray rootList() throws Exception {
        JSONArray result = new JSONArray(); for (Root root : roots.values()) if (root.active) result.put(root.json()); return result;
    }

    static String path(String value) throws Failure {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 4096 || value.startsWith("/") || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0)
            throw new Failure(22, "Invalid relative path.");
        if (!value.isEmpty()) for (String part : value.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.getBytes(StandardCharsets.UTF_8).length > 255)
                throw new Failure(22, "Invalid path component.");
        return value;
    }
    private static String secret() { byte[] data = new byte[32]; new SecureRandom().nextBytes(data); StringBuilder s = new StringBuilder();
        for (byte value : data) s.append(String.format(Locale.ROOT, "%02x", value & 255)); return s.toString(); }
    private static boolean equal(String a, String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static void closeQuietly(Closeable value) { try { value.close(); } catch (Exception ignored) { } }

    private void accept() {
        while (!closed) try {
            Socket client = listener.accept(); clients.add(client);
            try { workers.execute(() -> serve(client)); }
            catch (RejectedExecutionException full) { clients.remove(client); closeQuietly(client); }
        } catch (IOException error) { if (!closed) close(); }
    }
    private void serve(Socket client) {
        try (Socket owned = client) {
            owned.setSoTimeout(5000); owned.setTcpNoDelay(true);
            InputStream input = owned.getInputStream(); ByteArrayOutputStream header = new ByteArrayOutputStream(); int state = 0;
            while (state < 4 && header.size() < 16384) { int b = input.read(); if (b < 0) break; header.write(b);
                if (b == (state == 0 || state == 2 ? '\r' : '\n')) state++; else state = b == '\r' ? 1 : 0; }
            if (state != 4) return;
            String[] lines = header.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n");
            Map<String, String> headers = new HashMap<>(); boolean duplicate = false;
            for (int i = 1; i < lines.length; i++) { int at = lines[i].indexOf(':'); if (at < 1) { duplicate = true; break; }
                String key = lines[i].substring(0, at).trim().toLowerCase(Locale.ROOT);
                if (headers.put(key, lines[i].substring(at + 1).trim()) != null) duplicate = true; }
            if (!lines[0].equals("POST /v1 HTTP/1.1") || duplicate || headers.containsKey("transfer-encoding")) { respond(owned, 400, "{}"); return; }
            if (!equal(headers.get("authorization"), "Bearer " + token)) { respond(owned, 403, "{}"); return; }
            if (!"application/json".equals(headers.get("content-type"))) { respond(owned, 415, "{}"); return; }
            int length; try { length = Integer.parseInt(headers.getOrDefault("content-length", "-1")); } catch (NumberFormatException invalid) { length = -1; }
            if (length < 2 || length > MAX_BODY) { respond(owned, 413, "{}"); return; }
            byte[] bytes = new byte[length]; new DataInputStream(input).readFully(bytes);
            JSONObject result;
            try { result = dispatch(new JSONObject(new String(bytes, StandardCharsets.UTF_8))); }
            catch (Exception failure) { result = error(failure); }
            respond(owned, 200, result.toString());
        } catch (Exception ignored) { /* No request content or private path is logged. */ }
        finally { clients.remove(client); }
    }
    private static JSONObject error(Exception failure) throws Exception {
        int errno = failure instanceof Failure ? ((Failure) failure).errno : failure instanceof SecurityException ? 13 :
                failure instanceof FileNotFoundException ? 2 : failure instanceof org.json.JSONException ? 22 :
                failure instanceof android.os.OperationCanceledException ? 125 : 5;
        return new JSONObject().put("ok", false).put("errno", errno).put("message", "The exported filesystem could not complete this operation.");
    }
    private static void respond(Socket client, int code, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " Response\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        OutputStream out = client.getOutputStream(); out.write(header.getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush();
    }
    private synchronized Root authorized(JSONObject request, boolean write) throws Exception {
        Root root = roots.get(request.getString("root"));
        if (root == null || !root.active) throw new Failure(116, "The exported root is no longer available.");
        if (!equal(root.capability, request.optString("capability"))) throw new Failure(13, "The exported root capability is invalid.");
        if (write && root.readOnly) throw new Failure(30, "The exported root is read only.");
        // Closing a handle must still reclaim resources after the Android grant
        // disappears; it does not authorize another read or write.
        if (!"release".equals(request.optString("op"))) root.store.checkAccess(write);
        return root;
    }
    private Opened opened(JSONObject request, Root root, boolean write) throws Exception {
        Opened file = handles.get(request.getString("handle"));
        if (file == null || file.root != root || !root.active) throw new Failure(9, "The file handle is closed.");
        if (write && !file.writable) throw new Failure(9, "The file handle is read only.");
        return file;
    }
    JSONObject dispatch(JSONObject request) throws Exception {
        String op = request.getString("op"); JSONObject result = new JSONObject().put("ok", true);
        if (op.equals("roots")) return result.put("roots", rootList());
        boolean mutating = Arrays.asList("write", "mkdir", "unlink", "rmdir", "rename", "chmod", "utimens", "truncate").contains(op)
                || op.equals("open") && (request.optBoolean("write") || request.optBoolean("create") || request.optBoolean("truncate"));
        Root root = authorized(request, mutating); String name = path(request.optString("path", ""));
        synchronized (root) {
            if (!root.active) throw new Failure(116, "The exported root was revoked.");
            switch (op) {
                case "stat": return result.put("entry", root.store.stat(name).json());
                case "fstat": return result.put("entry", opened(request, root, false).file.stat().json());
                case "list": {
                    int offset = request.optInt("offset", 0), limit = request.optInt("limit", 256);
                    if (offset < 0 || limit < 1 || limit > 256) throw new Failure(22, "Invalid directory page.");
                    List<Entry> entries = root.store.list(name); if (entries.size() > 50000) throw new Failure(75, "Directory is too large.");
                    entries.sort(Comparator.comparing(entry -> entry.name)); JSONArray values = new JSONArray();
                    for (int i = offset; i < Math.min(entries.size(), (long) offset + limit); i++) values.put(entries.get(i).json());
                    return result.put("entries", values).put("next", (long) offset + values.length() < entries.size() ? offset + values.length() : -1);
                }
                case "open": {
                    boolean read = request.optBoolean("read", true), write = request.optBoolean("write"), create = request.optBoolean("create"), truncate = request.optBoolean("truncate");
                    if (!read && !write) throw new Failure(22, "A file handle requires read or write access.");
                    if (!write && (create || truncate)) throw new Failure(22, "Creation requires write access.");
                    if (!handleSlots.tryAcquire()) throw new Failure(24, "Too many open files.");
                    FileHandle handle = null;
                    try {
                        handle = root.store.open(name, read, write, create, request.optBoolean("exclusive"), truncate, request.optInt("mode", 0644) & 0777);
                        JSONObject metadata = handle.stat().json();
                        String id = secret(); handles.put(id, new Opened(root, handle, read, write)); return result.put("handle", id).put("entry", metadata);
                    } catch (Exception failed) { if (handle != null) closeQuietly(handle); handleSlots.release(); throw failed; }
                }
                case "read": {
                    Opened file = opened(request, root, false); int count = request.getInt("size"); long offset = request.getLong("offset");
                    if (!file.readable) throw new Failure(9, "The file handle is write only.");
                    if (count < 0 || count > CHUNK || offset < 0) throw new Failure(22, "Invalid read size.");
                    return result.put("data", Base64.getEncoder().encodeToString(file.file.read(offset, count)));
                }
                case "write": {
                    Opened file = opened(request, root, true); long offset = request.getLong("offset"); byte[] data;
                    try { data = Base64.getDecoder().decode(request.getString("data")); } catch (IllegalArgumentException invalid) { throw new Failure(22, "Invalid write data."); }
                    if (offset < 0 || data.length > CHUNK || offset > Long.MAX_VALUE - data.length) throw new Failure(22, "Invalid write size.");
                    if (request.optBoolean("append")) offset = file.file.stat().size;
                    return result.put("written", file.file.write(offset, data));
                }
                case "truncate": {
                    long size = request.getLong("size"); if (size < 0) throw new Failure(22, "Invalid file size.");
                    if (request.has("handle")) opened(request, root, true).file.truncate(size);
                    else try (FileHandle file = root.store.open(name, false, true, false, false, false, 0644)) { file.truncate(size); file.sync(); }
                    return result;
                }
                case "flush": case "fsync":
                    opened(request, root, false).file.sync();
                    if (op.equals("fsync") && !root.store.capabilities().optBoolean("durableFlush")) throw new Failure(95, "Durable flushing is unavailable.");
                    return result;
                case "release": {
                    Opened file = opened(request, root, false); handles.remove(request.getString("handle"));
                    try { file.file.close(); } finally { handleSlots.release(); } return result;
                }
                case "mkdir": requireChild(name); root.store.mkdir(name, request.optInt("mode", 0755) & 0777); return result;
                case "unlink": case "rmdir": requireChild(name); root.store.delete(name, op.equals("rmdir")); return result;
                case "rename": { String to = path(request.getString("to")); requireChild(name); requireChild(to);
                    root.store.rename(name, to, request.optBoolean("noReplace")); return result; }
                case "chmod": requireChild(name); root.store.chmod(name, request.getInt("mode") & 0777); return result;
                case "utimens": root.store.setTimes(name, request.getLong("access"), request.getLong("modified")); return result;
                case "statfs": return result.put("available", Math.max(0, root.store.available()));
                default: throw new Failure(38, "Unsupported filesystem operation.");
            }
        }
    }
    private static void requireChild(String name) throws Failure { if (name.isEmpty()) throw new Failure(16, "The exported root cannot be changed."); }
    @Override public synchronized void close() {
        if (closed) return; closed = true; closeQuietly(listener);
        for (String id : new ArrayList<>(roots.keySet())) revoke(id);
        for (Socket client : clients) closeQuietly(client); clients.clear(); workers.shutdownNow();
    }
}
