package app.vstab;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Content-addressed static assets authorized exclusively by the APK manifest. */
final class VerifiedBackendAssets {
    interface Fetcher { InputStream open(String backendPath) throws IOException; }

    static final class Entry {
        final String sha256;
        final long bytes;
        final String backendPath;

        Entry(String sha256, long bytes, String backendPath) {
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}") || bytes < 0 || bytes > 512L * 1024 * 1024
                    || !safePath(backendPath, true)) throw new IllegalArgumentException("Invalid pinned editor asset.");
            this.sha256 = sha256; this.bytes = bytes; this.backendPath = backendPath;
        }
    }

    private final File cache;
    private final Map<String, Entry> entries;
    private final Fetcher fetcher;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    VerifiedBackendAssets(File cache, Map<String, Entry> entries, Fetcher fetcher) {
        this.cache = cache;
        Map<String, Entry> copy = new HashMap<>();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            if (!safePath(item.getKey(), false)) throw new IllegalArgumentException("Invalid editor asset key.");
            copy.put(item.getKey(), item.getValue());
        }
        this.entries = Collections.unmodifiableMap(copy);
        this.fetcher = fetcher;
    }

    boolean contains(String assetPath) { return entries.containsKey(assetPath); }

    InputStream open(String assetPath) throws IOException {
        Entry entry = entries.get(assetPath);
        if (entry == null) throw new IOException("Editor asset is not authorized by the APK manifest.");
        Object lock = locks.computeIfAbsent(entry.sha256, ignored -> new Object());
        synchronized (lock) {
            if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Cannot prepare the verified editor asset cache.");
            File destination = new File(cache, entry.sha256 + ".asset");
            InputStream cached = verifiedCache(destination, entry);
            if (cached != null) return cached;
            File temporary = File.createTempFile(entry.sha256 + "-", ".partial", cache);
            try {
                MessageDigest digest = digest();
                long length = 0;
                try (InputStream source = fetcher.open(entry.backendPath);
                     FileOutputStream target = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = source.read(buffer)) != -1) {
                        length += count;
                        if (length > entry.bytes) throw new IOException("The backend editor asset length differs from the APK manifest.");
                        digest.update(buffer, 0, count); target.write(buffer, 0, count);
                    }
                    target.getFD().sync();
                }
                if (length != entry.bytes || !hex(digest.digest()).equals(entry.sha256))
                    throw new IOException("The backend editor asset differs from the pinned APK bytes.");
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                InputStream verified = verifiedCache(destination, entry);
                if (verified == null) throw new IOException("The verified editor cache could not be read safely.");
                return verified;
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
        }
    }

    private static InputStream verifiedCache(File file, Entry entry) throws IOException {
        if (!file.isFile()) return null;
        FileInputStream input = new FileInputStream(file);
        boolean valid = false;
        try {
            MessageDigest digest = digest(); long length = 0;
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) {
                length += count; if (length > entry.bytes) break;
                digest.update(buffer, 0, count);
            }
            valid = length == entry.bytes && hex(digest.digest()).equals(entry.sha256);
            if (valid) { input.getChannel().position(0); return input; }
        } finally {
            if (!valid) { input.close(); Files.deleteIfExists(file.toPath()); }
        }
        return null;
    }

    static Fetcher authenticatedHttp(String origin, List<String> sessionCookies) throws IOException {
        final URI base;
        try { base = new URI(origin); }
        catch (Exception malformed) { throw new IOException("Invalid authenticated editor origin."); }
        String host = base.getHost();
        if ((!("http".equals(base.getScheme()) || "https".equals(base.getScheme())))
                || host == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IOException("Invalid authenticated editor origin.");
        Map<String, String> pairs = new java.util.LinkedHashMap<>();
        for (String cookie : sessionCookies) {
            String pair = cookie.split(";", 2)[0].trim();
            int equals = pair.indexOf('=');
            if (equals < 1 || pair.indexOf('\r') >= 0 || pair.indexOf('\n') >= 0)
                throw new IOException("Invalid editor session cookie.");
            // Match the sequential Set-Cookie application used by the WebView.
            pairs.put(pair.substring(0, equals), pair);
        }
        StringBuilder cookies = new StringBuilder();
        for (String pair : pairs.values()) {
            if (cookies.length() != 0) cookies.append("; ");
            cookies.append(pair);
        }
        if (cookies.length() == 0) throw new IOException("An authenticated editor session is required.");
        final String authorization = cookies.toString();
        return path -> {
            if (!safePath(path, true)) throw new IOException("Invalid pinned editor asset path.");
            final URL address;
            try { address = new URI(base.getScheme(), null, base.getHost(), base.getPort(), path, null, null).toURL(); }
            catch (Exception malformed) { throw new IOException("Invalid pinned editor asset URL."); }
            HttpURLConnection connection = (HttpURLConnection) address.openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
            connection.setRequestProperty("Cookie", authorization);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Cache-Control", "no-cache");
            try {
                if (connection.getResponseCode() != 200) throw new IOException("The matching backend did not provide the pinned editor asset.");
                String encoding = connection.getContentEncoding();
                if (encoding != null && !encoding.isEmpty() && !encoding.equalsIgnoreCase("identity"))
                    throw new IOException("The matching backend changed the editor asset encoding.");
                return new FilterInputStream(connection.getInputStream()) {
                    @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
                };
            } catch (IOException failure) { connection.disconnect(); throw failure; }
        };
    }

    private static boolean safePath(String path, boolean absolute) {
        if (path == null || path.isEmpty() || path.startsWith("//") || path.startsWith("/") != absolute
                || path.indexOf('\\') >= 0) return false;
        for (int index = 0; index < path.length(); index++) if (path.charAt(index) < 32 || path.charAt(index) == 127) return false;
        for (String part : path.split("/")) if (part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return output.toString();
    }
}
