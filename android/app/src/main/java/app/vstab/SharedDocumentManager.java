package app.vstab;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Owns independent SAF mirror sessions. Only user-granted document URIs are opened. */
final class SharedDocumentManager implements AutoCloseable {
    interface Listener {
        void onOpened(Session session);
        void onChanged(Session session, DocumentSyncEngine.Result result);
        void onFailure(String message);
    }
    static final class Session {
        final String id, name;
        final File directory, documentFile;
        final Uri source;
        final boolean singleDocument;
        private final DocumentSyncEngine engine;
        private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
        private String lastFailure;
        private String lastWarnings = "";
        Session(String id, String name, File directory, File documentFile, Uri source,
                boolean singleDocument, DocumentSyncEngine engine, Listener listener) {
            this.id = id; this.name = name; this.directory = directory; this.documentFile = documentFile;
            this.source = source; this.singleDocument = singleDocument; this.engine = engine; this.listeners.add(listener);
        }
        List<DocumentSyncEngine.Conflict> conflicts() { return engine.conflicts(); }
    }
    static final class SavedSource {
        final String id, name;
        final Uri source;
        final boolean singleDocument;
        SavedSource(String id, String name, Uri source, boolean singleDocument) {
            this.id = id; this.name = name; this.source = source; this.singleDocument = singleDocument;
        }
    }
    private final Context context;
    private final ContentResolver resolver;
    private final SharedPreferences remembered;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    SharedDocumentManager(Context context) {
        this.context = context.getApplicationContext(); this.resolver = context.getContentResolver();
        remembered = this.context.getSharedPreferences("shared-document-sources", Context.MODE_PRIVATE);
        worker.scheduleWithFixedDelay(() -> {
            if (closed) return;
            for (Session session : new ArrayList<>(sessions.values())) if (!session.listeners.isEmpty()) synchronize(session);
        }, 3, 3, TimeUnit.SECONDS);
    }

    void openTree(Uri source, int grantedFlags, Listener listener) { open(source, grantedFlags, false, listener); }
    void openDocument(Uri source, int grantedFlags, Listener listener) { open(source, grantedFlags, true, listener); }
    Session session(String id) { return sessions.get(id); }
    List<SavedSource> recentSources() {
        List<SavedSource> result = new ArrayList<>();
        for (Map.Entry<String, ?> item : remembered.getAll().entrySet()) if (item.getKey().startsWith("uri.") && item.getValue() instanceof String) {
            String id = item.getKey().substring(4);
            result.add(new SavedSource(id, remembered.getString("name." + id, "Shared project"),
                    Uri.parse((String) item.getValue()), remembered.getBoolean("single." + id, false)));
        }
        result.sort(Comparator.comparing(source -> source.name)); return result;
    }
    void restore(String id, Listener listener) {
        String uri = remembered.getString("uri." + id, null);
        if (uri == null) { main.post(() -> listener.onFailure("The shared project is no longer remembered. Choose it again.")); return; }
        open(Uri.parse(uri), 0, remembered.getBoolean("single." + id, false), listener);
    }
    void removeListener(Listener listener) {
        for (Session session : sessions.values()) if (session.listeners.remove(listener) && session.listeners.isEmpty() && !closed)
            worker.execute(() -> synchronize(session)); // Deliver the final saved bytes before retiring the window.
    }

    private void open(Uri source, int flags, boolean single, Listener listener) {
        if (source == null || !"content".equals(source.getScheme())) {
            main.post(() -> listener.onFailure("Choose a file or folder through Android's document picker.")); return;
        }
        worker.execute(() -> {
            try {
                if (closed) return;
                int permissions = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 && permissions != 0) {
                    try { resolver.takePersistableUriPermission(source, permissions); }
                    catch (SecurityException ignored) { /* Some Open with providers grant a temporary URI only. */ }
                }
                String id = identity((single ? "file:" : "tree:") + source);
                Session existing = sessions.get(id);
                if (existing != null) {
                    existing.listeners.addIfAbsent(listener); synchronize(existing);
                    main.post(() -> listener.onOpened(existing)); return;
                }
                File parent = new File(context.getFilesDir(), "shared-documents/" + id);
                File directory = new File(parent, "workspace");
                for (File component : new File[]{new File(context.getFilesDir(), "shared-documents"), parent, directory})
                    if (Files.isSymbolicLink(component.toPath())) throw new IOException("A shared workspace path was redirected by a symbolic link. Synchronization was stopped.");
                Files.createDirectories(directory.toPath());
                String name = documentName(source, single);
                File document = single ? new File(directory, name) : null;
                AndroidStorage storage = single ? new SingleStorage(source, name, new File(parent, "recovery")) :
                        new TreeStorage(source, new File(parent, "recovery"));
                DocumentSyncEngine engine = new DocumentSyncEngine(new DocumentSyncEngine.FileStorage(directory),
                        storage, new File(parent, "history.bin"), single);
                Session session = new Session(id, name, directory, document, source, single, engine, listener);
                DocumentSyncEngine.Result initial = engine.sync();
                sessions.put(id, session);
                remembered.edit().putString("uri." + id, source.toString()).putString("name." + id, name)
                        .putBoolean("single." + id, single).apply();
                main.post(() -> {
                    if (!closed) { listener.onOpened(session); if (initial.changed() || !initial.conflicts.isEmpty()) listener.onChanged(session, initial); }
                });
            } catch (Exception error) { report(listener, error); }
        });
    }

    void syncNow(String id) {
        worker.execute(() -> { Session session = sessions.get(id); if (session != null) synchronize(session); });
    }
    void resolve(String id, String path, boolean keepLocal) {
        worker.execute(() -> {
            Session session = sessions.get(id);
            if (session == null) return;
            try {
                session.engine.resolve(path, keepLocal);
                session.lastFailure = null;
                DocumentSyncEngine.Result resolved = new DocumentSyncEngine.Result();
                if (keepLocal) resolved.pushed++; else resolved.pulled++;
                resolved.conflicts.addAll(session.engine.conflicts());
                main.post(() -> { if (!closed) for (Listener listener : session.listeners) listener.onChanged(session, resolved); });
                synchronize(session);
            }
            catch (Exception error) { for (Listener listener : session.listeners) report(listener, error); }
        });
    }
    void stop(String id) { worker.execute(() -> sessions.remove(id)); }
    private void synchronize(Session session) {
        if (closed) return;
        try {
            DocumentSyncEngine.Result result = session.engine.sync(); session.lastFailure = null;
            String warnings = result.warnings.toString();
            if (warnings.equals(session.lastWarnings)) result.warnings.clear();
            else session.lastWarnings = warnings;
            if (result.changed() || !result.conflicts.isEmpty() || !result.warnings.isEmpty()) {
                result.conflicts.clear(); result.conflicts.addAll(session.engine.conflicts());
                main.post(() -> { if (!closed) for (Listener listener : session.listeners) listener.onChanged(session, result); });
            }
        } catch (Exception error) {
            String message = safeMessage(error);
            if (!message.equals(session.lastFailure)) {
                session.lastFailure = message;
                main.post(() -> { if (!closed) for (Listener listener : session.listeners) listener.onFailure(message); });
            }
        }
    }
    private void report(Listener listener, Exception error) {
        String message = safeMessage(error);
        main.post(() -> { if (!closed) listener.onFailure(message); });
    }
    private static String safeMessage(Exception error) {
        if (error instanceof SecurityException) return "Folder access was revoked or is read only. Your edits remain inside vstab. Grant access again to resume saving.";
        String detail = error.getMessage();
        if (detail == null || detail.contains("/data/") || detail.contains("content://"))
            detail = "The document provider could not complete synchronization. Your local edits were kept. Check access and available storage, then retry.";
        return detail;
    }
    @Override public void close() { closed = true; worker.shutdownNow(); }

    private String documentName(Uri source, boolean single) throws IOException {
        Uri document = single ? source : DocumentsContract.buildDocumentUriUsingTree(source, DocumentsContract.getTreeDocumentId(source));
        try (Cursor cursor = resolver.query(document, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)) throw new IOException("The document provider did not return a file name.");
            String name = cursor.getString(0);
            if (single) DocumentSyncEngine.validatePath(name);
            if (single && name.contains("/")) throw new IOException("The document provider returned an unsafe file name.");
            return name;
        }
    }
    private static String identity(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder id = new StringBuilder(); for (int i = 0; i < 12; i++) id.append(String.format(Locale.ROOT, "%02x", hash[i] & 255));
        return id.toString();
    }

    private abstract class AndroidStorage implements DocumentSyncEngine.Storage {
        final File recovery;
        AndroidStorage(File recovery) { this.recovery = recovery; }
        void replace(Uri destination, File completeSource, String key) throws IOException {
            Files.createDirectories(recovery.toPath());
            File backup = new File(recovery, UUID.randomUUID() + "-before-save.bin");
            // A provider write is not an atomic rename. Keep the complete original in
            // private storage before opening any output, and restore on a failed write.
            DocumentSyncEngine.copyBounded(resolver.openInputStream(destination), Files.newOutputStream(backup.toPath()));
            try {
                DocumentSyncEngine.copyBounded(Files.newInputStream(completeSource.toPath()), resolver.openOutputStream(destination, "rwt"));
                if (!sameContent(completeSource, destination)) throw new IOException("The provider retained an incomplete save.");
            } catch (IOException | RuntimeException failure) {
                try { DocumentSyncEngine.copyBounded(Files.newInputStream(backup.toPath()), resolver.openOutputStream(destination, "rwt")); }
                catch (Exception restore) { failure.addSuppressed(restore); }
                throw new IOException("The provider could not save the complete file. Both the local edit and the pre-save recovery copy were kept inside vstab.", failure);
            }
            Files.deleteIfExists(backup.toPath());
        }
        private boolean sameContent(File source, Uri destination) throws IOException {
            try (InputStream local = Files.newInputStream(source.toPath()); InputStream remote = resolver.openInputStream(destination)) {
                if (remote == null) return false;
                byte[] a = new byte[64 * 1024], b = new byte[64 * 1024];
                int count;
                while ((count = local.read(a)) != -1) {
                    int offset = 0;
                    while (offset < count) { int size = remote.read(b, offset, count - offset); if (size < 0) return false; offset += size; }
                    for (int i = 0; i < count; i++) if (a[i] != b[i]) return false;
                }
                return remote.read() == -1;
            }
        }
        Map<String, DocumentSyncEngine.Kind> one(String name) {
            Map<String, DocumentSyncEngine.Kind> result = new TreeMap<>(); result.put(name, DocumentSyncEngine.Kind.FILE); return result;
        }
    }
    private final class SingleStorage extends AndroidStorage {
        final Uri document; final String name;
        SingleStorage(Uri document, String name, File recovery) { super(recovery); this.document = document; this.name = name; }
        @Override public Map<String, DocumentSyncEngine.Kind> list() throws IOException {
            try (Cursor cursor = resolver.query(document, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IOException("The opened document no longer exists. The local edit was kept.");
                return one(name);
            }
        }
        void check(String path) throws IOException { if (!name.equals(path)) throw new IOException("Only the file opened from Android can be saved back through this grant."); }
        @Override public InputStream read(String path) throws IOException { check(path); return resolver.openInputStream(document); }
        @Override public DocumentSyncEngine.Kind kind(String path) throws IOException { check(path); return list().get(path); }
        @Override public void write(String path, File source) throws IOException { check(path); replace(document, source, name); }
        @Override public void mkdir(String path) throws IOException { throw new IOException("An individual file grant cannot create folders."); }
        @Override public void delete(String path) throws IOException { throw new IOException("Delete an externally opened file from Android Files. Its original was kept."); }
    }
    private final class TreeStorage extends AndroidStorage {
        final Uri tree, rootDocument;
        final Map<String, Uri> documents = new TreeMap<>();
        final Map<String, DocumentSyncEngine.Kind> kinds = new TreeMap<>();
        TreeStorage(Uri tree, File recovery) {
            super(recovery); this.tree = tree;
            rootDocument = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        }
        @Override public Map<String, DocumentSyncEngine.Kind> list() throws IOException {
            documents.clear(); kinds.clear();
            visit(rootDocument, "", new HashSet<>(), 0);
            return new TreeMap<>(kinds);
        }
        private void visit(Uri directory, String prefix, Set<String> ancestors, int depth) throws IOException {
            if (depth > 64) throw new IOException("The shared folder is too deeply nested to synchronize safely.");
            String id = DocumentsContract.getDocumentId(directory);
            if (!ancestors.add(id)) throw new IOException("The document provider returned a recursive folder. Synchronization was stopped.");
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null) throw new IOException("The document provider could not list a folder. Existing files were kept.");
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    DocumentSyncEngine.validatePath(name);
                    if (name.contains("/")) throw new IOException("The document provider returned an unsafe file name.");
                    String path = prefix + name;
                    Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                    if (documents.put(path, document) != null) throw new IOException("The document provider returned duplicate file names. Both copies were kept.");
                    if (documents.size() > DocumentSyncEngine.MAX_ENTRIES) throw new IOException("The shared folder exceeds the 20,000-entry sync limit.");
                    boolean folder = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
                    kinds.put(path, folder ? DocumentSyncEngine.Kind.DIRECTORY : DocumentSyncEngine.Kind.FILE);
                    if (folder) visit(document, path + "/", ancestors, depth + 1);
                }
            } finally { ancestors.remove(id); }
        }
        private Uri document(String path) throws IOException {
            DocumentSyncEngine.validatePath(path);
            Uri document = documents.get(path);
            if (document == null) throw new FileNotFoundException("The provider file no longer exists. Refresh the folder.");
            return document;
        }
        private Uri parent(String path) throws IOException {
            int slash = path.lastIndexOf('/'); return slash < 0 ? rootDocument : document(path.substring(0, slash));
        }
        private String name(String path) { return path.substring(path.lastIndexOf('/') + 1); }
        @Override public DocumentSyncEngine.Kind kind(String path) throws IOException {
            DocumentSyncEngine.validatePath(path);
            Uri existing = documents.get(path);
            if (existing != null) {
                try (Cursor cursor = resolver.query(existing, new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                    if (cursor == null) throw new IOException("The provider file could not be checked. Both copies were kept.");
                    if (!cursor.moveToFirst()) return null;
                    return DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0)) ? DocumentSyncEngine.Kind.DIRECTORY : DocumentSyncEngine.Kind.FILE;
                }
            }
            // A file may have appeared after the inventory. Check siblings before a
            // create, rather than relying on providers to reject duplicate names.
            int slash = path.lastIndexOf('/');
            Uri parentDocument = slash < 0 ? rootDocument : documents.get(path.substring(0, slash));
            if (parentDocument == null) return null;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parentDocument));
            try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null) throw new IOException("The provider folder could not be checked. Both copies were kept.");
                while (cursor.moveToNext()) if (name(path).equals(cursor.getString(1))) {
                    Uri found = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                    DocumentSyncEngine.Kind value = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)) ? DocumentSyncEngine.Kind.DIRECTORY : DocumentSyncEngine.Kind.FILE;
                    documents.put(path, found); kinds.put(path, value); return value;
                }
                return null;
            }
        }
        @Override public InputStream read(String path) throws IOException { return resolver.openInputStream(document(path)); }
        @Override public void write(String path, File completeSource) throws IOException {
            DocumentSyncEngine.validatePath(path);
            if (documents.containsKey(path)) {
                replace(document(path), completeSource, path); return;
            }
            Uri created = DocumentsContract.createDocument(resolver, parent(path), "application/octet-stream", name(path));
            if (created == null) throw new IOException("The document provider could not create the file. The local edit was kept.");
            try (Cursor cursor = resolver.query(created, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst() || !name(path).equals(cursor.getString(0))) {
                    if (!documents.containsValue(created)) DocumentsContract.deleteDocument(resolver, created);
                    throw new IOException("A file appeared during creation. Both existing copies were kept; refresh the folder.");
                }
            }
            documents.put(path, created); kinds.put(path, DocumentSyncEngine.Kind.FILE);
            try { DocumentSyncEngine.copyBounded(Files.newInputStream(completeSource.toPath()), resolver.openOutputStream(created, "rwt")); }
            catch (IOException | RuntimeException error) {
                try { DocumentsContract.deleteDocument(resolver, created); } catch (Exception ignored) { }
                throw new IOException("A new file could not be saved completely. Its complete local copy was kept.", error);
            }
        }
        @Override public void mkdir(String path) throws IOException {
            DocumentSyncEngine.validatePath(path);
            if (documents.containsKey(path)) {
                if (kinds.get(path) != DocumentSyncEngine.Kind.DIRECTORY) throw new IOException("A file is in the way of a shared folder.");
                return;
            }
            Uri created = DocumentsContract.createDocument(resolver, parent(path), DocumentsContract.Document.MIME_TYPE_DIR, name(path));
            if (created == null) throw new IOException("The document provider could not create the folder.");
            documents.put(path, created); kinds.put(path, DocumentSyncEngine.Kind.DIRECTORY);
        }
        @Override public void delete(String path) throws IOException {
            Uri destination = document(path);
            if (kinds.get(path) == DocumentSyncEngine.Kind.DIRECTORY) {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(destination));
                try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                    if (cursor == null) throw new IOException("The provider folder could not be checked. It was kept.");
                    if (cursor.moveToFirst()) throw new DirectoryNotEmptyException(path);
                }
            }
            if (!DocumentsContract.deleteDocument(resolver, destination)) throw new IOException("The document provider refused the deletion. The original was kept.");
            documents.remove(path); kinds.remove(path);
        }
    }
}
