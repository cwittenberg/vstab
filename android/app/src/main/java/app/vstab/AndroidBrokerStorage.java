package app.vstab;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/** Filesystem adapters. Raw paths are anchored by descriptors; SAF paths by their tree grant. */
final class AndroidBrokerStorage {
    private AndroidBrokerStorage() { }
    private static final AtomicLong STAGED = new AtomicLong();
    private static final long MAX_STAGED = 1024L * 1024 * 1024;
    private static String stagingPool;
    // Linux UAPI values on the supported aarch64/x86_64 ABIs. O_DIRECTORY is
    // not exported by Android's public OsConstants API.
    private static final int O_DIRECTORY = 0x10000;
    private static final int O_PATH = 0x200000;
    private static AndroidFileBroker.Failure failure(int errno) { return new AndroidFileBroker.Failure(errno, "Filesystem operation unavailable."); }
    private static AndroidFileBroker.Failure failure(ErrnoException error) { return failure(error.errno); }
    static AndroidFileBroker.Store directory(File root) throws Exception { return new Directory(root); }
    static AndroidFileBroker.Store documents(Context context, Uri root, boolean readOnly) throws Exception { return new Documents(context, root, readOnly); }
    private static JSONObject capabilities(boolean posix) throws Exception {
        return new JSONObject().put("atomicRename", posix).put("exclusiveCreate", posix).put("permissions", posix)
                .put("symlinks", false).put("durableFlush", posix).put("externalNotifications", "revalidate-on-access")
                .put("maxStagedFileBytes", posix ? JSONObject.NULL : AndroidFileBroker.MAX_STAGE);
    }
    private static long inode(String value) { return (long) value.hashCode() & 0x7fffffffL; }
    private static AndroidFileBroker.Entry entry(String name, StructStat value) {
        return new AndroidFileBroker.Entry(name, value.st_mode, value.st_size, value.st_mtime * 1000L, value.st_ino);
    }
    private static ParcelFileDescriptor own(FileDescriptor descriptor) throws Exception {
        try { return ParcelFileDescriptor.dup(descriptor); } finally { Os.close(descriptor); }
    }
    private static String proc(ParcelFileDescriptor descriptor) { return "/proc/self/fd/" + descriptor.getFd(); }

    private static final class Parent implements Closeable {
        final ParcelFileDescriptor directory; final String name;
        Parent(ParcelFileDescriptor directory, String name) { this.directory = directory; this.name = name; }
        String path() { return proc(directory) + "/" + name; }
        @Override public void close() throws IOException { directory.close(); }
    }
    private static final class Directory implements AndroidFileBroker.Store {
        private final File root;
        private final ParcelFileDescriptor descriptor;
        private boolean closed;
        Directory(File root) throws Exception {
            this.root = root;
            try { descriptor = own(Os.open(root.getAbsolutePath(), OsConstants.O_RDONLY | O_DIRECTORY
                    | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0)); }
            catch (ErrnoException error) { throw failure(error); }
        }
        @Override public synchronized void checkAccess(boolean write) throws Exception { if (closed) throw failure(116); }
        @Override public JSONObject capabilities() throws Exception { return AndroidBrokerStorage.capabilities(true); }
        private Parent parent(String value) throws Exception {
            AndroidFileBroker.path(value); checkAccess(false);
            ParcelFileDescriptor current = ParcelFileDescriptor.dup(descriptor.getFileDescriptor());
            try {
                String[] parts = value.isEmpty() ? new String[]{"."} : value.split("/");
                for (int i = 0; i < parts.length - 1; i++) {
                    ParcelFileDescriptor next;
                    try { next = own(Os.open(proc(current) + "/" + parts[i], OsConstants.O_RDONLY | O_DIRECTORY
                            | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0)); }
                    catch (ErrnoException error) { throw failure(error); }
                    current.close(); current = next;
                }
                Parent result = new Parent(current, parts[parts.length - 1]); current = null; return result;
            } finally { if (current != null) current.close(); }
        }
        @Override public AndroidFileBroker.Entry stat(String value) throws Exception {
            try (Parent p = parent(value)) { return entry(p.name, Os.lstat(p.path())); }
            catch (ErrnoException error) { throw failure(error); }
        }
        @Override public List<AndroidFileBroker.Entry> list(String value) throws Exception {
            try (Parent p = parent(value); ParcelFileDescriptor directory = own(Os.open(p.path(), OsConstants.O_RDONLY
                    | O_DIRECTORY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0));
                    DirectoryStream<Path> children = Files.newDirectoryStream(Paths.get(proc(directory)))) {
                List<AndroidFileBroker.Entry> result = new ArrayList<>();
                for (Path child : children) {
                    if (result.size() >= 50000) throw failure(75);
                    String name = child.getFileName().toString();
                    try { result.add(entry(name, Os.lstat(proc(directory) + "/" + name))); }
                    catch (ErrnoException removed) { if (removed.errno != OsConstants.ENOENT) throw removed; }
                }
                return result;
            } catch (ErrnoException error) { throw failure(error); }
        }
        @Override public AndroidFileBroker.FileHandle open(String value, boolean read, boolean write, boolean create, boolean exclusive, boolean truncate, int mode) throws Exception {
            try (Parent p = parent(value)) {
                int flags = (write ? read ? OsConstants.O_RDWR : OsConstants.O_WRONLY : OsConstants.O_RDONLY) | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC | OsConstants.O_NONBLOCK;
                if (create) flags |= OsConstants.O_CREAT; if (exclusive && create) flags |= OsConstants.O_EXCL; if (truncate) flags |= OsConstants.O_TRUNC;
                ParcelFileDescriptor fd = own(Os.open(p.path(), flags, mode));
                if (!OsConstants.S_ISREG(Os.fstat(fd.getFileDescriptor()).st_mode)) { fd.close(); throw failure(21); }
                return new RawHandle(fd, p.name);
            } catch (ErrnoException error) { throw failure(error); }
        }
        @Override public void mkdir(String value, int mode) throws Exception { try (Parent p = parent(value)) { Os.mkdir(p.path(), mode); } catch (ErrnoException error) { throw failure(error); } }
        @Override public void delete(String value, boolean directory) throws Exception { try (Parent p = parent(value)) {
            boolean actualDirectory = OsConstants.S_ISDIR(Os.lstat(p.path()).st_mode);
            if (actualDirectory != directory) throw failure(directory ? 20 : 21);
            Os.remove(p.path());
        } catch (ErrnoException error) { throw failure(error); } }
        @Override public void rename(String from, String to, boolean noReplace) throws Exception {
            // Java/Os has no renameat2(RENAME_NOREPLACE); do not emulate its atomic exclusion with a check.
            if (noReplace) throw failure(95);
            try (Parent a = parent(from); Parent b = parent(to)) { Os.rename(a.path(), b.path()); }
            catch (ErrnoException error) { throw failure(error); }
        }
        @Override public void chmod(String value, int mode) throws Exception {
            try (Parent p = parent(value); ParcelFileDescriptor fd = own(Os.open(p.path(), O_PATH
                    | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0))) {
                if (OsConstants.S_ISLNK(Os.fstat(fd.getFileDescriptor()).st_mode)) throw failure(40);
                Os.chmod(proc(fd), mode);
            }
            catch (ErrnoException error) { throw failure(error); }
        }
        @Override public void setTimes(String value, long accessMs, long modifiedMs) throws Exception {
            try (Parent p = parent(value); ParcelFileDescriptor fd = own(Os.open(p.path(), O_PATH
                    | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0))) {
                if (OsConstants.S_ISLNK(Os.fstat(fd.getFileDescriptor()).st_mode)) throw failure(40);
                Files.getFileAttributeView(Paths.get(proc(fd)), BasicFileAttributeView.class)
                        .setTimes(modifiedMs < 0 ? null : FileTime.fromMillis(modifiedMs), accessMs < 0 ? null : FileTime.fromMillis(accessMs), null);
            } catch (ErrnoException error) { throw failure(error); }
        }
        @Override public long available() { return root.getUsableSpace(); }
        @Override public synchronized void close() throws IOException { closed = true; descriptor.close(); }
    }
    private static final class RawHandle implements AndroidFileBroker.FileHandle {
        final ParcelFileDescriptor fd; final String name; boolean closed;
        RawHandle(ParcelFileDescriptor fd, String name) { this.fd = fd; this.name = name; }
        private void check() throws Exception { if (closed) throw failure(9); }
        @Override public synchronized byte[] read(long offset, int count) throws Exception { check(); byte[] data = new byte[count];
            try { int size = Os.pread(fd.getFileDescriptor(), data, 0, count, offset); return size <= 0 ? new byte[0] : Arrays.copyOf(data, size); }
            catch (ErrnoException error) { throw failure(error); } }
        @Override public synchronized int write(long offset, byte[] data) throws Exception { check();
            try { return Os.pwrite(fd.getFileDescriptor(), data, 0, data.length, offset); } catch (ErrnoException error) { throw failure(error); } }
        @Override public synchronized void truncate(long size) throws Exception { check(); try { Os.ftruncate(fd.getFileDescriptor(), size); } catch (ErrnoException error) { throw failure(error); } }
        @Override public synchronized void sync() throws Exception { check(); try { Os.fsync(fd.getFileDescriptor()); } catch (ErrnoException error) { throw failure(error); } }
        @Override public synchronized AndroidFileBroker.Entry stat() throws Exception { check(); try { return entry(name, Os.fstat(fd.getFileDescriptor())); } catch (ErrnoException error) { throw failure(error); } }
        @Override public synchronized void close() throws IOException { if (!closed) { closed = true; fd.close(); } }
    }

    private static final class Document {
        final Uri uri; final String name; final boolean directory; final long size, modified; final int flags;
        Document(Uri uri, String name, boolean directory, long size, long modified, int flags) {
            this.uri = uri; this.name = name; this.directory = directory; this.size = size; this.modified = modified; this.flags = flags;
        }
    }
    private static final class Documents implements AndroidFileBroker.Store {
        final Context context; final Uri tree, root; final boolean readOnly; final File staging, recovery;
        final CancellationSignal cancellation = new CancellationSignal();
        final Set<Closeable> streams = ConcurrentHashMap.newKeySet();
        volatile boolean closed;
        Documents(Context context, Uri tree, boolean readOnly) throws Exception {
            if (tree == null || !"content".equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree)) throw failure(22);
            this.context = context; this.tree = tree; this.readOnly = readOnly;
            root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            staging = new File(context.getFilesDir(), "file-broker/staging"); recovery = new File(context.getFilesDir(), "file-broker/recovery");
            for (File directory : new File[]{staging.getParentFile(), staging, recovery}) {
                if (Files.isSymbolicLink(directory.toPath())) throw failure(40);
                Files.createDirectories(directory.toPath());
            }
            initializePool(staging.getParentFile()); checkAccess(false);
        }
        @Override public void checkAccess(boolean write) throws Exception {
            if (closed) throw failure(116); if (write && readOnly) throw failure(30);
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | (write ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
            if (context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(), flags) != PackageManager.PERMISSION_GRANTED)
                throw failure(13);
        }
        @Override public JSONObject capabilities() throws Exception { return AndroidBrokerStorage.capabilities(false); }
        InputStream input(Uri uri) throws Exception {
            checkAccess(false); ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r", cancellation);
            if (fd == null) throw failure(5);
            InputStream stream = new FilterInputStream(new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                @Override public void close() throws IOException { streams.remove(this); super.close(); }
            };
            streams.add(stream); if (closed) { stream.close(); throw failure(125); } return stream;
        }
        OutputStream output(Uri uri) throws Exception {
            checkAccess(true); ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "rwt", cancellation);
            if (fd == null) throw failure(5);
            OutputStream stream = new FilterOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
                @Override public void close() throws IOException { streams.remove(this); super.close(); }
            };
            streams.add(stream); if (closed) { stream.close(); throw failure(125); } return stream;
        }
        private static final String[] COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS};
        private Document row(Cursor cursor) throws Exception {
            String name = cursor.getString(1); AndroidFileBroker.path(name);
            if (name.isEmpty() || name.contains("/")) throw failure(5);
            return new Document(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), name,
                    DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)), cursor.isNull(3) ? -1 : cursor.getLong(3),
                    cursor.isNull(4) ? 0 : cursor.getLong(4), cursor.isNull(5) ? 0 : cursor.getInt(5));
        }
        private Document query(Uri uri) throws Exception {
            try (Cursor cursor = context.getContentResolver().query(uri, COLUMNS, null, null, null, cancellation)) {
                if (cursor == null) throw failure(5); if (!cursor.moveToFirst()) throw failure(2); return row(cursor);
            }
        }
        private List<Document> children(Document directory) throws Exception {
            if (!directory.directory) throw failure(20);
            Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(directory.uri));
            try (Cursor cursor = context.getContentResolver().query(uri, COLUMNS, null, null, null, cancellation)) {
                if (cursor == null) throw failure(5); List<Document> result = new ArrayList<>(); Set<String> names = new HashSet<>();
                while (cursor.moveToNext()) { if (result.size() >= 50000) throw failure(75); Document item = row(cursor);
                    if (!names.add(item.name)) throw failure(5); result.add(item); }
                return result;
            }
        }
        private Document resolve(String value) throws Exception {
            AndroidFileBroker.path(value); checkAccess(false); Document current = query(root);
            if (!value.isEmpty()) for (String part : value.split("/")) {
                Document next = null; for (Document item : children(current)) if (item.name.equals(part)) { next = item; break; }
                if (next == null) throw failure(2); current = next;
            }
            return current;
        }
        private Document parent(String value) throws Exception { int at = value.lastIndexOf('/'); return resolve(at < 0 ? "" : value.substring(0, at)); }
        private String name(String value) { return value.substring(value.lastIndexOf('/') + 1); }
        private AndroidFileBroker.Entry info(Document document) throws Exception {
            long size = document.size;
            if (!document.directory && size < 0) { size = 0; try (InputStream input = input(document.uri)) {
                if (input == null) throw failure(5); byte[] bytes = new byte[65536]; int count;
                while ((count = input.read(bytes)) != -1) { size += count; if (size > AndroidFileBroker.MAX_STAGE) throw failure(27); }
            } }
            boolean writable = !readOnly && (document.flags & (document.directory ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE)) != 0;
            return new AndroidFileBroker.Entry(document.name, (document.directory ? 0040000 | 0555 : 0100000 | 0444)
                    | (writable ? 0200 : 0), document.directory ? 0 : Math.max(0, size), document.modified, inode(document.uri.toString()));
        }
        @Override public AndroidFileBroker.Entry stat(String value) throws Exception { return info(resolve(value)); }
        @Override public List<AndroidFileBroker.Entry> list(String value) throws Exception {
            List<AndroidFileBroker.Entry> result = new ArrayList<>(); for (Document document : children(resolve(value))) result.add(info(document)); return result;
        }
        @Override public AndroidFileBroker.FileHandle open(String value, boolean read, boolean write, boolean create, boolean exclusive, boolean truncate, int mode) throws Exception {
            checkAccess(write); if (create && exclusive) throw failure(95); Document document;
            try { document = resolve(value); }
            catch (AndroidFileBroker.Failure absent) {
                if (absent.errno != 2 || !create) throw absent;
                Document parent = parent(value); if ((parent.flags & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) throw failure(13);
                Uri made = DocumentsContract.createDocument(context.getContentResolver(), parent.uri, "application/octet-stream", name(value));
                if (made == null) throw failure(5); document = query(made);
                if (!document.name.equals(name(value))) { DocumentsContract.deleteDocument(context.getContentResolver(), made); throw failure(17); }
            }
            if (document.directory) throw failure(21);
            if (write && (document.flags & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) == 0) throw failure(13);
            return new StagedHandle(this, document, write, truncate);
        }
        @Override public void mkdir(String value, int mode) throws Exception {
            checkAccess(true); try { resolve(value); throw failure(17); } catch (AndroidFileBroker.Failure missing) { if (missing.errno != 2) throw missing; }
            Document parent = parent(value); if ((parent.flags & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) throw failure(13);
            Uri made = DocumentsContract.createDocument(context.getContentResolver(), parent.uri, DocumentsContract.Document.MIME_TYPE_DIR, name(value));
            if (made == null) throw failure(5); if (!query(made).name.equals(name(value))) { DocumentsContract.deleteDocument(context.getContentResolver(), made); throw failure(17); }
        }
        @Override public void delete(String value, boolean directory) throws Exception {
            checkAccess(true); Document document = resolve(value); if (document.directory != directory) throw failure(directory ? 20 : 21);
            if (directory && !children(document).isEmpty()) throw failure(39);
            if ((document.flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0) throw failure(13);
            if (!DocumentsContract.deleteDocument(context.getContentResolver(), document.uri)) throw failure(5);
        }
        @Override public void rename(String from, String to, boolean noReplace) throws Exception {
            checkAccess(true); if (!parent(from).uri.equals(parent(to).uri)) throw failure(18);
            try { resolve(to); throw failure(17); } catch (AndroidFileBroker.Failure missing) { if (missing.errno != 2) throw missing; }
            Document document = resolve(from); if ((document.flags & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) == 0) throw failure(95);
            Uri result = DocumentsContract.renameDocument(context.getContentResolver(), document.uri, name(to));
            if (result == null || !query(result).name.equals(name(to))) throw failure(5);
        }
        @Override public void chmod(String value, int mode) throws Exception { throw failure(95); }
        @Override public void setTimes(String value, long accessMs, long modifiedMs) throws Exception { throw failure(95); }
        @Override public long available() { return Math.max(0, Math.min(staging.getUsableSpace(), MAX_STAGED - STAGED.get())); }
        @Override public void cancel() { closed = true; cancellation.cancel();
            for (Closeable stream : streams) try { stream.close(); } catch (IOException ignored) { } streams.clear(); }
        @Override public void close() { cancel(); }
    }
    private static void reserve(long count) throws Exception { if (count <= 0) return;
        long total = STAGED.addAndGet(count); if (total > MAX_STAGED) { STAGED.addAndGet(-count); throw failure(28); } }
    private static synchronized void initializePool(File directory) throws Exception {
        String identity = directory.getCanonicalPath();
        if (stagingPool != null) { if (!stagingPool.equals(identity)) throw failure(5); return; }
        long total = 0;
        try (java.util.stream.Stream<Path> files = Files.walk(directory.toPath())) {
            Iterator<Path> iterator = files.iterator();
            while (iterator.hasNext()) { Path file = iterator.next(); if (Files.isSymbolicLink(file)) throw failure(40);
                if (Files.isRegularFile(file)) { total += Files.size(file); if (total > MAX_STAGED) { total = MAX_STAGED + 1; break; } }
            }
        }
        STAGED.set(total); stagingPool = identity;
    }
    private static final class StagedHandle implements AndroidFileBroker.FileHandle {
        final Documents owner; final Document document; final boolean writable; final File stage; final RandomAccessFile file;
        long charged; boolean dirty, closed, preserve; byte[] baseline;
        StagedHandle(Documents owner, Document document, boolean writable, boolean truncate) throws Exception {
            this.owner = owner; this.document = document; this.writable = writable;
            stage = File.createTempFile("open-", ".stage", owner.staging); file = new RandomAccessFile(stage, "rw");
            try {
                if (!truncate) try (InputStream input = owner.input(document.uri)) {
                    if (input == null) throw failure(5); byte[] data = new byte[65536]; int count;
                    while ((count = input.read(data)) != -1) { grow(file.length() + count); file.write(data, 0, count); }
                }
                baseline = truncate ? hash(owner.input(document.uri)) : hash(new FileInputStream(stage));
                dirty = writable && truncate;
            } catch (Exception failed) { file.close(); if (stage.delete()) STAGED.addAndGet(-charged); throw failed; }
        }
        private void grow(long size) throws Exception { if (size > AndroidFileBroker.MAX_STAGE) throw failure(27);
            if (size > charged) { reserve(size - charged); charged = size; } }
        private void check() throws Exception { if (closed) throw failure(9); owner.checkAccess(false); }
        @Override public synchronized byte[] read(long offset, int count) throws Exception { check(); byte[] bytes = new byte[count]; file.seek(offset);
            int size = file.read(bytes); return size < 0 ? new byte[0] : Arrays.copyOf(bytes, size); }
        @Override public synchronized int write(long offset, byte[] bytes) throws Exception { check(); owner.checkAccess(true); if (!writable) throw failure(9);
            grow(offset + bytes.length); file.seek(offset); file.write(bytes); dirty = true; return bytes.length; }
        @Override public synchronized void truncate(long size) throws Exception { check(); owner.checkAccess(true); if (!writable) throw failure(9);
            grow(size); file.setLength(size); dirty = true; }
        @Override public synchronized AndroidFileBroker.Entry stat() throws Exception { check(); return new AndroidFileBroker.Entry(document.name,
                0100000 | (writable ? 0644 : 0444), file.length(), document.modified, inode(document.uri.toString())); }
        @Override public synchronized void sync() throws Exception {
            check(); if (!dirty) return; owner.checkAccess(true); file.getFD().sync();
            if (!Arrays.equals(baseline, hash(owner.input(document.uri)))) {
                preserve = true; throw failure(11);
            }
            File backup = File.createTempFile("before-", ".recovery", owner.recovery);
            boolean backupComplete = false; long[] backupCharged = {0};
            try {
                try (InputStream input = owner.input(document.uri); OutputStream output = new FileOutputStream(backup)) {
                    if (input == null) throw failure(5); byte[] data = new byte[65536]; int size;
                    while ((size = input.read(data)) != -1) { if (backupCharged[0] + size > AndroidFileBroker.MAX_STAGE) throw failure(27);
                        reserve(size); backupCharged[0] += size; output.write(data, 0, size); }
                }
                backupComplete = true;
                copy(new FileInputStream(stage), owner.output(document.uri));
                if (!Arrays.equals(hash(new FileInputStream(stage)), hash(owner.input(document.uri)))) throw failure(5);
                baseline = hash(new FileInputStream(stage));
                dirty = false; preserve = false; if (backup.delete()) STAGED.addAndGet(-backupCharged[0]);
            } catch (Exception failed) {
                preserve = true;
                try { if (backupComplete && !owner.closed) copy(new FileInputStream(backup), owner.output(document.uri)); }
                catch (Exception ignored) { /* Both complete versions remain in app-private recovery/staging. */ }
                if (!backupComplete && backup.delete()) STAGED.addAndGet(-backupCharged[0]);
                throw failed;
            }
        }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            try { if (dirty && owner.closed) preserve = true; else if (dirty) sync(); }
            catch (Exception failed) { preserve = true; throw new IOException("The staged Android file could not be committed; its bytes were retained.", failed); }
            finally { closed = true; file.close(); if (!preserve && stage.delete()) STAGED.addAndGet(-charged); }
        }
        private static void copy(InputStream input, OutputStream output) throws Exception {
            if (input == null || output == null) { if (input != null) input.close(); if (output != null) output.close(); throw failure(5); }
            try (InputStream in = input; OutputStream out = output) { byte[] data = new byte[65536]; int size; long total = 0;
                while ((size = in.read(data)) != -1) { total += size; if (total > AndroidFileBroker.MAX_STAGE) throw failure(27); out.write(data, 0, size); } out.flush(); }
        }
        private static byte[] hash(InputStream input) throws Exception { if (input == null) throw failure(5);
            try (InputStream in = input) { MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] data = new byte[65536]; int size; long total = 0;
                while ((size = in.read(data)) != -1) { total += size; if (total > AndroidFileBroker.MAX_STAGE) throw failure(27); digest.update(data, 0, size); } return digest.digest(); }
        }
    }
}
