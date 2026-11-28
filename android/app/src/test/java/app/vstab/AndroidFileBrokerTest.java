package app.vstab;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real HTTP transport with an ordinary JVM filesystem; Android adapters have device tests. */
public class AndroidFileBrokerTest {
    private static final class Fixture implements AutoCloseable {
        final Path directory = Files.createTempDirectory("vstab-files-test-");
        final Disk disk = new Disk(directory);
        final AndroidFileBroker broker = new AndroidFileBroker(null);
        final JSONObject root, config;
        Fixture(boolean readOnly) throws Exception {
            root = broker.addRoot("project", "/mnt/android/app/projects", readOnly, disk);
            config = broker.manifest("127.0.0.1");
        }
        JSONObject request(String op, String path) throws Exception {
            return new JSONObject().put("op", op).put("root", root.getString("id"))
                    .put("capability", root.getString("capability")).put("path", path);
        }
        JSONObject call(JSONObject request) throws Exception {
            HttpURLConnection connection = connection(config.getString("token"), request.toString().getBytes(StandardCharsets.UTF_8));
            assertEquals(200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) { return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
            finally { connection.disconnect(); }
        }
        HttpURLConnection connection(String token, byte[] bytes) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(config.getString("endpoint")).openConnection();
            connection.setConnectTimeout(3000); connection.setReadTimeout(3000); connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            return connection;
        }
        @Override public void close() throws Exception { broker.close(); try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(file); } }
    }
    static class Disk implements AndroidFileBroker.Store {
        final Path directory; boolean revoked, failList; int operations, closedFiles;
        Disk(Path directory) { this.directory = directory; }
        Path path(String value) throws Exception { AndroidFileBroker.path(value); return directory.resolve(value); }
        @Override public void checkAccess(boolean write) { if (revoked) throw new SecurityException("revoked"); }
        @Override public JSONObject capabilities() throws Exception { return new JSONObject().put("durableFlush", true); }
        @Override public AndroidFileBroker.Entry stat(String name) throws Exception { operations++; Path file = path(name);
            if (!Files.exists(file)) throw new FileNotFoundException();
            int permissions = 0; for (java.nio.file.attribute.PosixFilePermission permission : Files.getPosixFilePermissions(file))
                permissions |= 1 << (8 - permission.ordinal());
            return new AndroidFileBroker.Entry(file.getFileName().toString(), (Files.isDirectory(file) ? 0040000 : 0100000) | permissions,
                    Files.isDirectory(file) ? 0 : Files.size(file), Files.getLastModifiedTime(file).toMillis(), name.hashCode()); }
        @Override public List<AndroidFileBroker.Entry> list(String name) throws Exception { operations++; if (failList) throw new IOException("provider unavailable");
            List<AndroidFileBroker.Entry> result = new ArrayList<>(); try (DirectoryStream<Path> files = Files.newDirectoryStream(path(name))) {
                for (Path file : files) result.add(stat(directory.relativize(file).toString())); } return result; }
        @Override public AndroidFileBroker.FileHandle open(String name, boolean read, boolean write, boolean create, boolean exclusive, boolean truncate, int mode) throws Exception {
            operations++; Path value = path(name); boolean existed = Files.exists(value);
            if (exclusive && create) try { Files.createFile(value); } catch (FileAlreadyExistsException exists) { throw new AndroidFileBroker.Failure(17, "exists"); }
            if (!Files.exists(value) && !create) throw new FileNotFoundException();
            RandomAccessFile file = new RandomAccessFile(value.toFile(), write ? "rw" : "r"); if (truncate) file.setLength(0);
            if (!existed && create) chmod(name, mode);
            return new AndroidFileBroker.FileHandle() {
                @Override public byte[] read(long offset, int count) throws Exception { file.seek(offset); byte[] bytes = new byte[count]; int size = file.read(bytes); return size < 0 ? new byte[0] : Arrays.copyOf(bytes, size); }
                @Override public int write(long offset, byte[] bytes) throws Exception { file.seek(offset); file.write(bytes); return bytes.length; }
                @Override public void truncate(long size) throws Exception { file.setLength(size); }
                @Override public void sync() throws Exception { file.getFD().sync(); }
                @Override public AndroidFileBroker.Entry stat() throws Exception { return Disk.this.stat(name); }
                @Override public void close() throws IOException { closedFiles++; file.close(); }
            };
        }
        @Override public void mkdir(String path, int mode) throws Exception { operations++; Files.createDirectory(path(path)); }
        @Override public void delete(String path, boolean directory) throws Exception { operations++; Files.delete(path(path)); }
        @Override public void rename(String from, String to, boolean noReplace) throws Exception { operations++; if (noReplace) throw new AndroidFileBroker.Failure(95, "unsupported"); Files.move(path(from), path(to), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        @Override public void chmod(String path, int mode) throws Exception { operations++;
            Set<java.nio.file.attribute.PosixFilePermission> permissions = EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
            for (java.nio.file.attribute.PosixFilePermission permission : java.nio.file.attribute.PosixFilePermission.values()) if ((mode & (1 << (8 - permission.ordinal()))) != 0) permissions.add(permission);
            Files.setPosixFilePermissions(path(path), permissions);
        }
        @Override public void setTimes(String path, long access, long modified) throws Exception { operations++;
            Files.getFileAttributeView(path(path), java.nio.file.attribute.BasicFileAttributeView.class).setTimes(
                    modified < 0 ? null : java.nio.file.attribute.FileTime.fromMillis(modified), access < 0 ? null : java.nio.file.attribute.FileTime.fromMillis(access), null); }
        @Override public long available() { return directory.toFile().getUsableSpace(); }
        @Override public void close() { revoked = true; }
    }
    private static void error(int errno, JSONObject result) throws Exception { assertFalse(result.getBoolean("ok")); assertEquals(errno, result.getInt("errno")); assertFalse(result.has("entries")); }

    @Test public void httpAndEachRootRequireTheirOwnCapabilities() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            HttpURLConnection unauthenticated = fixture.connection(null, "{\"op\":\"roots\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals(403, unauthenticated.getResponseCode()); unauthenticated.disconnect();
            JSONObject invalid = fixture.request("list", "").put("capability", "wrong"); error(13, fixture.call(invalid));
            assertEquals(0, fixture.disk.operations);
            assertTrue(fixture.call(new JSONObject().put("op", "roots")).getBoolean("ok"));
            assertTrue(fixture.call(fixture.request("list", "")).getBoolean("ok"));
        }
    }
    @Test public void traversalAndOversizeRequestsNeverReachStorage() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            for (String name : new String[]{"../home", "/absolute", "one/../../two", "one//two", "one/./two", "one\\two", "bad\0name"})
                error(22, fixture.call(fixture.request("stat", name)));
            assertEquals(0, fixture.disk.operations);
            byte[] large = new byte[AndroidFileBroker.MAX_BODY + 1]; Arrays.fill(large, (byte) ' ');
            HttpURLConnection tooLarge = fixture.connection(fixture.config.getString("token"), large);
            assertEquals(413, tooLarge.getResponseCode()); tooLarge.disconnect();
        }
    }
    @Test public void binaryOffsetsExclusiveCreateAndClosedHandlesCrossTheRealHttpBoundary() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            JSONObject opened = fixture.call(fixture.request("open", "percent % & unicode-λ.bin").put("write", true).put("create", true).put("exclusive", true));
            assertTrue(opened.getBoolean("ok")); String handle = opened.getString("handle");
            error(17, fixture.call(fixture.request("open", "percent % & unicode-λ.bin").put("write", true).put("create", true).put("exclusive", true)));
            byte[] bytes = new byte[]{0, 1, 2, (byte) 255, 36, 10};
            assertEquals(bytes.length, fixture.call(fixture.request("write", "").put("handle", handle).put("offset", 3)
                    .put("data", Base64.getEncoder().encodeToString(bytes))).getInt("written"));
            assertTrue(fixture.call(fixture.request("fsync", "").put("handle", handle)).getBoolean("ok"));
            byte[] received = Base64.getDecoder().decode(fixture.call(fixture.request("read", "").put("handle", handle).put("offset", 3).put("size", 128)).getString("data"));
            assertArrayEquals(bytes, received);
            error(22, fixture.call(fixture.request("read", "").put("handle", handle).put("offset", 0).put("size", AndroidFileBroker.CHUNK + 1)));
            assertTrue(fixture.call(fixture.request("release", "").put("handle", handle)).getBoolean("ok"));
            error(9, fixture.call(fixture.request("read", "").put("handle", handle).put("offset", 0).put("size", 10)));
            String writeOnly = fixture.call(fixture.request("open", "write-only").put("read", false).put("write", true).put("create", true).put("mode", 0200)).getString("handle");
            error(9, fixture.call(fixture.request("read", "").put("handle", writeOnly).put("offset", 0).put("size", 10)));
            assertEquals(1, fixture.call(fixture.request("write", "").put("handle", writeOnly).put("offset", 0).put("data", "YQ==")).getInt("written"));
            assertTrue(fixture.call(fixture.request("release", "").put("handle", writeOnly)).getBoolean("ok"));
        }
    }
    @Test public void readOnlyAndRevokedRootsCannotMutateOrRetainOpenHandles() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            error(30, fixture.call(fixture.request("open", "new").put("write", true).put("create", true)));
            error(30, fixture.call(fixture.request("mkdir", "directory")));
            assertEquals(0, fixture.disk.operations);
            Files.write(fixture.directory.resolve("existing"), "readable".getBytes(StandardCharsets.UTF_8));
            String handle = fixture.call(fixture.request("open", "existing")).getString("handle");
            fixture.broker.revoke("project"); assertEquals(1, fixture.disk.closedFiles);
            error(116, fixture.call(fixture.request("read", "").put("handle", handle).put("size", 10).put("offset", 0)));
            assertEquals(0, fixture.call(new JSONObject().put("op", "roots")).getJSONArray("roots").length());
        }
    }
    @Test public void storageOutagesAndGrantRevocationReturnErrorsInsteadOfAnEmptyDirectory() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.disk.failList = true; error(5, fixture.call(fixture.request("list", "")));
            Files.write(fixture.directory.resolve("existing"), new byte[]{1});
            String handle = fixture.call(fixture.request("open", "existing")).getString("handle");
            fixture.disk.revoked = true; error(13, fixture.call(fixture.request("list", "")));
            assertTrue(fixture.call(fixture.request("release", "").put("handle", handle)).getBoolean("ok"));
            assertEquals(1, fixture.disk.closedFiles);
        }
    }
    @Test public void directoryPagingDoesNotDropOrDuplicateStableEntries() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            for (int i = 0; i < 517; i++) Files.write(fixture.directory.resolve(String.format(Locale.ROOT, "file-%04d", i)), new byte[0]);
            Set<String> names = new HashSet<>(); int offset = 0;
            do { JSONObject page = fixture.call(fixture.request("list", "").put("offset", offset));
                for (int i = 0; i < page.getJSONArray("entries").length(); i++) assertTrue(names.add(page.getJSONArray("entries").getJSONObject(i).getString("name")));
                offset = page.getInt("next");
            } while (offset >= 0);
            assertEquals(517, names.size());
        }
    }
    @Test public void rootRevocationCancelsBlockedProviderWorkBeforeWaitingForItsLock() throws Exception {
        Path directory = Files.createTempDirectory("vstab-broker-cancel-");
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1), cancelled = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        AndroidFileBroker broker = new AndroidFileBroker(null);
        try {
            Disk blocked = new Disk(directory) {
                @Override public List<AndroidFileBroker.Entry> list(String name) throws Exception { started.countDown(); cancelled.await(); throw new SecurityException("cancelled"); }
                @Override public void cancel() { revoked = true; cancelled.countDown(); }
            };
            JSONObject root = broker.addRoot("blocked", "/mnt/android/folders/blocked", false, blocked);
            java.util.concurrent.Future<?> request = workers.submit(() -> {
                try { broker.dispatch(new JSONObject().put("op", "list").put("root", "blocked").put("capability", root.getString("capability"))); fail("Cancelled provider completed"); }
                catch (SecurityException expected) { } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            workers.submit(() -> broker.revoke("blocked")).get(2, java.util.concurrent.TimeUnit.SECONDS);
            request.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            cancelled.countDown(); broker.close(); workers.shutdownNow(); Files.delete(directory);
        }
    }
}
