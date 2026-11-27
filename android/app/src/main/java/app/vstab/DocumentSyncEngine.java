package app.vstab;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Three-way, content-based synchronization. Never treats a failed listing as a deletion. */
final class DocumentSyncEngine {
    static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    static final long MAX_SCAN_BYTES = 1024L * 1024 * 1024;
    static final int MAX_ENTRIES = 20000;
    enum Kind { FILE, DIRECTORY, SKIPPED }
    interface Storage {
        Map<String, Kind> list() throws IOException;
        default Kind kind(String path) throws IOException { return list().get(path); }
        InputStream read(String path) throws IOException;
        void write(String path, File completeSource) throws IOException;
        void mkdir(String path) throws IOException;
        /** Must reject a non-empty directory rather than recursively removing it. */
        void delete(String path) throws IOException;
    }
    static final class Conflict {
        final String path, localFingerprint, deviceFingerprint, recoveryPath;
        Conflict(String path, String localFingerprint, String deviceFingerprint, String recoveryPath) {
            this.path = path; this.localFingerprint = localFingerprint;
            this.deviceFingerprint = deviceFingerprint; this.recoveryPath = recoveryPath;
        }
    }
    static final class Result {
        int pulled, pushed, deleted;
        final List<Conflict> conflicts = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        boolean changed() { return pulled + pushed + deleted > 0; }
    }

    private final Storage local, device;
    private final File stateFile, scratch;
    private final boolean singleFile;
    private final Map<String, String> baseline = new TreeMap<>();
    private final Map<String, Conflict> conflicts = new TreeMap<>();

    DocumentSyncEngine(Storage local, Storage device, File stateFile, boolean singleFile) throws IOException {
        this.local = local; this.device = device; this.stateFile = stateFile;
        this.singleFile = singleFile; this.scratch = new File(stateFile.getParentFile(), "transfers");
        Files.createDirectories(scratch.toPath());
        load();
    }

    synchronized Result sync() throws IOException {
        // Finish both complete inventories before making any change. Unreadable providers,
        // excessive trees, and revoked permissions leave both sides and the baseline intact.
        Map<String, String> left = fingerprints(local), right = fingerprints(device);
        TreeSet<String> paths = new TreeSet<>();
        paths.addAll(baseline.keySet()); paths.addAll(left.keySet()); paths.addAll(right.keySet());
        Result result = new Result();
        List<String> blocked = new ArrayList<>();
        for (String path : paths) if ("X".equals(left.get(path)) || "X".equals(right.get(path))) {
            blocked.add(path); result.warnings.add("Symbolic link was not synchronized: " + path);
        }
        // A file/directory replacement is a conflict: never recursively delete a tree to
        // install a file, even when its parent metadata appears unchanged.
        for (String path : paths) {
            String l = left.get(path), r = right.get(path), b = baseline.get(path);
            if (blocked(path, blocked)) continue;
            if (differentKinds(l, r) || differentKinds(l, b) || differentKinds(r, b)) {
                blocked.add(path); conflict(path, l, r, result);
            }
        }
        List<String> deletions = new ArrayList<>();
        for (String path : paths) {
            if (blocked(path, blocked)) continue;
            String l = left.get(path), r = right.get(path), b = baseline.get(path);
            if (Objects.equals(l, r)) {
                setBaseline(path, l); conflicts.remove(path); continue;
            }
            if (singleFile && l == null && r != null) {
                checkedTransfer(device, local, path, r, l); setBaseline(path, r); result.pulled++; continue;
            }
            if (Objects.equals(l, b)) {
                if (r == null) { deletions.add(path); continue; }
                checkedTransfer(device, local, path, r, l); setBaseline(path, r); result.pulled++; conflicts.remove(path);
            } else if (Objects.equals(r, b)) {
                if (l == null) { deletions.add(path); continue; }
                checkedTransfer(local, device, path, l, r); setBaseline(path, l); result.pushed++; conflicts.remove(path);
            } else conflict(path, l, r, result);
        }
        // Children first; a directory is only deleted after all children were removed.
        deletions.sort(Comparator.comparingInt(String::length).reversed());
        for (String path : deletions) {
            String l = left.get(path), r = right.get(path), b = baseline.get(path);
            if ("D".equals(b) && hasConflictBelow(path)) {
                conflict(path, l, r, result); continue;
            }
            Storage destination = l == null ? device : local;
            String expected = l == null ? r : l;
            if (!Objects.equals(fingerprintIfPresent(destination, path), expected)) {
                conflict(path, fingerprintIfPresent(local, path), fingerprintIfPresent(device, path), result); continue;
            }
            try { destination.delete(path); }
            catch (java.nio.file.DirectoryNotEmptyException error) {
                conflict(path, l, r, result); continue;
            }
            baseline.remove(path); conflicts.remove(path); result.deleted++;
        }
        save();
        return result;
    }

    synchronized List<Conflict> conflicts() { return new ArrayList<>(conflicts.values()); }

    /** The UI must show both versions and explicitly ask which one to keep. */
    synchronized void resolve(String path, boolean keepLocal) throws IOException {
        Conflict conflict = conflicts.get(path);
        if (conflict == null) throw new IOException("This conflict was already resolved. Refresh the folder.");
        String l = fingerprintIfPresent(local, path), r = fingerprintIfPresent(device, path);
        if (!Objects.equals(l, conflict.localFingerprint) || !Objects.equals(r, conflict.deviceFingerprint))
            throw new IOException("The file changed again. Refresh the folder before resolving it.");
        String chosen = keepLocal ? l : r;
        if ("D".equals(l) || "D".equals(r))
            throw new IOException("A file and folder conflict needs to be resolved in the file explorer; both were kept.");
        Storage destination = keepLocal ? device : local;
        if (chosen == null) destination.delete(path);
        else transfer(keepLocal ? local : device, destination, path, chosen);
        setBaseline(path, chosen); conflicts.remove(path); save();
    }

    private boolean hasConflictBelow(String path) {
        for (String key : conflicts.keySet()) if (key.startsWith(path + "/")) return true;
        return false;
    }
    private static boolean blocked(String path, List<String> blocked) {
        for (String key : blocked) if (path.equals(key) || path.startsWith(key + "/")) return true;
        return false;
    }
    private static boolean differentKinds(String a, String b) {
        return a != null && b != null && (a.equals("D") != b.equals("D"));
    }
    private void setBaseline(String path, String value) {
        if (value == null) baseline.remove(path); else baseline.put(path, value);
    }
    private void conflict(String path, String l, String r, Result result) throws IOException {
        Conflict existing = conflicts.get(path);
        if (existing != null && Objects.equals(existing.localFingerprint, l) && Objects.equals(existing.deviceFingerprint, r)) return;
        String recovery = null;
        // Keep the external version beside the local edit so it can be inspected in Code
        // OSS. It is not written to the provider or uploaded by subsequent sync passes.
        if (r != null && r.startsWith("F:")) {
            recovery = path + ".device-conflict-" + r.substring(2, Math.min(r.length(), 14));
            if (local instanceof FileStorage) {
                String baseName = recovery; int suffix = 1;
                String existingFingerprint = fingerprintIfPresent(local, recovery);
                while (existingFingerprint != null && !existingFingerprint.equals(r)) {
                    recovery = baseName + "-" + suffix++;
                    existingFingerprint = fingerprintIfPresent(local, recovery);
                }
                if (existingFingerprint == null) transfer(device, local, path, r, recovery);
            }
        }
        Conflict value = new Conflict(path, l, r, recovery);
        conflicts.put(path, value); result.conflicts.add(value);
    }
    private Map<String, String> fingerprints(Storage storage) throws IOException {
        Map<String, Kind> entries = storage.list();
        if (entries.size() > MAX_ENTRIES) throw new IOException("The shared folder exceeds the 20,000-entry sync limit. Choose a smaller project folder.");
        Map<String, String> output = new TreeMap<>(); long[] scanned = {0};
        for (Map.Entry<String, Kind> entry : entries.entrySet()) {
            validatePath(entry.getKey());
            // Recovery copies stay private and never become additional provider files.
            if (entry.getKey().contains(".device-conflict-")) continue;
            output.put(entry.getKey(), entry.getValue() == Kind.DIRECTORY ? "D" :
                    entry.getValue() == Kind.SKIPPED ? "X" : digest(storage.read(entry.getKey()), scanned));
        }
        return output;
    }
    private String fingerprintIfPresent(Storage storage, String path) throws IOException {
        Kind kind = storage.kind(path);
        return kind == null ? null : kind == Kind.DIRECTORY ? "D" : kind == Kind.SKIPPED ? "X" : digest(storage.read(path), new long[]{0});
    }
    private void transfer(Storage source, Storage destination, String path, String fingerprint) throws IOException {
        transfer(source, destination, path, fingerprint, path);
    }
    private void checkedTransfer(Storage source, Storage destination, String path, String fingerprint, String expectedDestination) throws IOException {
        if (!Objects.equals(fingerprintIfPresent(destination, path), expectedDestination))
            throw new IOException("A file changed again before it could be synchronized. Both copies were kept; the next refresh will compare them.");
        transfer(source, destination, path, fingerprint);
    }
    private void transfer(Storage source, Storage destination, String path, String fingerprint, String destinationPath) throws IOException {
        if ("D".equals(fingerprint)) { destination.mkdir(destinationPath); return; }
        File staged = File.createTempFile("sync-", ".complete", scratch);
        try {
            copyBounded(source.read(path), Files.newOutputStream(staged.toPath()));
            if (!Objects.equals(digest(Files.newInputStream(staged.toPath()), new long[]{0}), fingerprint))
                throw new IOException("A file changed while it was being read. The save will be retried.");
            destination.write(destinationPath, staged);
            if (!Objects.equals(fingerprintIfPresent(destination, destinationPath), fingerprint))
                throw new IOException("The document provider did not retain the complete save. The local copy was kept.");
        } finally { Files.deleteIfExists(staged.toPath()); }
    }
    private static String digest(InputStream stream, long[] scanned) throws IOException {
        if (stream == null) throw new IOException("The document provider did not open the file.");
        try (InputStream input = stream) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024]; long bytes = 0; int count;
            while ((count = input.read(buffer)) != -1) {
                bytes += count; scanned[0] += count;
                if (bytes > MAX_FILE_BYTES || scanned[0] > MAX_SCAN_BYTES)
                    throw new IOException("Shared-folder sync is limited to 256 MiB per file and 1 GiB per scan. Both copies were kept.");
                hash.update(buffer, 0, count);
            }
            StringBuilder value = new StringBuilder("F:");
            for (byte b : hash.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static void copyBounded(InputStream source, OutputStream target) throws IOException {
        if (source == null || target == null) throw new IOException("The document provider could not open the file.");
        try (InputStream input = source; OutputStream output = target) {
            byte[] buffer = new byte[64 * 1024]; long size = 0; int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > MAX_FILE_BYTES) throw new IOException("The file exceeds the 256 MiB synchronization limit.");
                output.write(buffer, 0, count);
            }
            output.flush();
        }
    }
    static void validatePath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0)
            throw new IOException("An unsafe shared-folder path was refused.");
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals(".."))
            throw new IOException("An unsafe shared-folder path was refused.");
    }
    private void load() throws IOException {
        if (!stateFile.exists()) return;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(stateFile.toPath())))) {
            if (input.readInt() != 0x56534431) throw new IOException("The shared-folder history is unreadable. Both copies were kept.");
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("The shared-folder history is too large.");
            for (int i = 0; i < count; i++) { String path = input.readUTF(); validatePath(path); baseline.put(path, input.readUTF()); }
        }
    }
    private void save() throws IOException {
        File temporary = new File(stateFile.getParentFile(), stateFile.getName() + ".new");
        try (FileOutputStream file = new FileOutputStream(temporary);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(file))) {
            output.writeInt(0x56534431); output.writeInt(baseline.size());
            for (Map.Entry<String, String> item : baseline.entrySet()) { output.writeUTF(item.getKey()); output.writeUTF(item.getValue()); }
            output.flush(); file.getFD().sync();
        }
        Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Real filesystem adapter, used by the Android backend and the host unit tests. */
    static final class FileStorage implements Storage {
        private final Path root;
        FileStorage(File directory) throws IOException {
            if (Files.isSymbolicLink(directory.toPath())) throw new IOException("A shared workspace cannot be a symbolic link.");
            Files.createDirectories(directory.toPath());
            root = directory.getCanonicalFile().toPath();
        }
        private Path path(String relative) throws IOException {
            validatePath(relative); Path result = root.resolve(relative);
            for (Path current = result; !current.equals(root); current = current.getParent())
                if (Files.isSymbolicLink(current)) throw new IOException("A symbolic link cannot grant access outside the shared project.");
            if (!result.normalize().startsWith(root)) throw new IOException("The shared-folder path escapes its workspace.");
            return result;
        }
        @Override public Map<String, Kind> list() throws IOException {
            Map<String, Kind> result = new TreeMap<>();
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                private void record(Path file, Kind kind) throws IOException {
                    result.put(root.relativize(file).toString().replace(File.separatorChar, '/'), kind);
                    if (result.size() > MAX_ENTRIES) throw new IOException("The shared folder exceeds the 20,000-entry sync limit.");
                }
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    if (!directory.equals(root)) record(directory, Kind.DIRECTORY);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    record(file, attrs.isSymbolicLink() ? Kind.SKIPPED : Kind.FILE); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException { throw error; }
            });
            return result;
        }
        @Override public InputStream read(String relative) throws IOException { return Files.newInputStream(path(relative)); }
        @Override public Kind kind(String relative) throws IOException {
            Path value = path(relative);
            return !Files.exists(value, LinkOption.NOFOLLOW_LINKS) ? null :
                    Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS) ? Kind.DIRECTORY : Kind.FILE;
        }
        @Override public void write(String relative, File completeSource) throws IOException {
            Path destination = path(relative); Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), ".vstab-save-", ".tmp");
            try {
                Files.copy(completeSource.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
                try (FileOutputStream output = new FileOutputStream(temporary.toFile(), true)) { output.getFD().sync(); }
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
        @Override public void mkdir(String relative) throws IOException { Files.createDirectories(path(relative)); }
        @Override public void delete(String relative) throws IOException { Files.deleteIfExists(path(relative)); }
    }
}
