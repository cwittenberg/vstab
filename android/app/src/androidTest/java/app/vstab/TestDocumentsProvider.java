package app.vstab;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

/** Test APK fixture exercising the actual ContentResolver/SAF transport. */
public final class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "app.vstab.test.documents";
    private static final String[] DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    private File root;
    @Override public boolean onCreate() {
        try {
            root = new File(getContext().getFilesDir(), "document-fixtures").getCanonicalFile();
            return root.isDirectory() || root.mkdirs();
        } catch (IOException error) { return false; }
    }
    private File file(String id) throws FileNotFoundException {
        try {
            if (!id.equals("root") && !id.startsWith("root/")) throw new IOException("Unknown fixture root");
            String relative = id.equals("root") ? "" : id.substring(5);
            if (!relative.isEmpty()) validatePath(relative);
            File value = new File(root, relative).getCanonicalFile();
            if (!value.equals(root.getCanonicalFile()) && !value.getPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Unsafe fixture path");
            return value;
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    private String id(File file) { return file.equals(root) ? "root" : "root/" + root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'); }
    private void row(MatrixCursor output, File file) {
        MatrixCursor.RowBuilder row = output.newRow();
        for (String column : output.getColumnNames()) {
            Object value = null;
            if (column.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) value = id(file);
            else if (column.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) value = file.equals(root) ? "vstab Test Projects" : file.getName();
            else if (column.equals(DocumentsContract.Document.COLUMN_MIME_TYPE)) value = file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR :
                    file.getName().endsWith(".js") ? "text/javascript" : file.getName().endsWith(".md") ? "text/markdown" : "text/plain";
            else if (column.equals(DocumentsContract.Document.COLUMN_FLAGS)) value = DocumentsContract.Document.FLAG_SUPPORTS_WRITE |
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME |
                    (file.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : 0);
            else if (column.equals(DocumentsContract.Document.COLUMN_SIZE)) value = file.length();
            else if (column.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) value = file.lastModified();
            row.add(value);
        }
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES} : projection;
        MatrixCursor cursor = new MatrixCursor(columns); MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            Object value = column.equals(DocumentsContract.Root.COLUMN_ROOT_ID) ? "vstab-tests" :
                    column.equals(DocumentsContract.Root.COLUMN_TITLE) ? "vstab Test Projects" :
                    column.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID) ? "root" :
                    column.equals(DocumentsContract.Root.COLUMN_FLAGS) ? DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_CREATE :
                    column.equals(DocumentsContract.Root.COLUMN_MIME_TYPES) ? "*/*" : null;
            row.add(value);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        File file = file(documentId); if (file.exists()) row(cursor, file); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        File[] files = file(parentDocumentId).listFiles();
        if (files == null) throw new FileNotFoundException("The fixture folder is unavailable");
        Arrays.sort(files, Comparator.comparing(File::getName)); for (File child : files) row(cursor, child);
        return cursor;
    }
    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        try {
            validatePath(displayName); if (displayName.contains("/")) throw new IOException("Unsafe fixture file");
            File created = new File(file(parentDocumentId), displayName);
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) Files.createDirectory(created.toPath());
            else Files.createFile(created.toPath());
            return id(created);
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        try { Files.delete(file(documentId).toPath()); } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    @Override public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        try {
            validatePath(displayName); if (displayName.contains("/")) throw new IOException("Unsafe fixture name");
            File existing = file(documentId), destination = new File(existing.getParentFile(), displayName);
            Files.move(existing.toPath(), destination.toPath()); return id(destination);
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        try { return file(documentId).getPath().startsWith(file(parentDocumentId).getPath() + File.separator); }
        catch (FileNotFoundException error) { return false; }
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!Set.of("seed", "read", "write", "delete").contains(method)) return super.call(method, arg, extras);
        try {
            Bundle reply = new Bundle();
            if (method.equals("seed")) {
                String project = arg == null ? "demo" : arg;
                validatePath(project); if (project.contains("/")) throw new IOException("Unsafe project name");
                File directory = file("root/" + project); Files.createDirectories(new File(directory, ".git/refs/heads").toPath());
                writeUtf8(new File(directory, "README.md").toPath(), "# Shared Android project\n\nThis project belongs to Android's document provider.\n");
                writeUtf8(new File(directory, "app.js").toPath(), "console.log('shared project on Android');\n");
                writeUtf8(new File(directory, ".git/HEAD").toPath(), "ref: refs/heads/main\n");
                writeUtf8(new File(directory, ".git/config").toPath(), "[core]\n repositoryformatversion = 0\n bare = false\n");
                seedGitHistory(directory);
                reply.putString("treeUri", DocumentsContract.buildTreeDocumentUri(AUTHORITY, id(directory)).toString());
                reply.putString("fileUri", DocumentsContract.buildDocumentUri(AUTHORITY, id(new File(directory, "app.js"))).toString());
                reply.putString("project", project);
            } else {
                File destination = file("root/" + arg);
                if (method.equals("read")) reply.putString("content", readUtf8(destination.toPath()));
                else if (method.equals("write")) {
                    Files.createDirectories(destination.getParentFile().toPath());
                    writeUtf8(destination.toPath(), extras == null ? "" : extras.getString("content", ""));
                } else Files.delete(destination.toPath());
            }
            return reply;
        } catch (IOException error) { throw new IllegalArgumentException(error.getMessage(), error); }
    }
    private static void writeUtf8(Path file, String content) throws IOException { Files.write(file, content.getBytes(StandardCharsets.UTF_8)); }
    private static String readUtf8(Path file) throws IOException { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
    private static void validatePath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) throw new IOException("Unsafe fixture path");
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe fixture path");
    }
    private static void seedGitHistory(File directory) throws IOException {
        ByteArrayOutputStream tree = new ByteArrayOutputStream();
        for (String name : new String[]{"README.md", "app.js"}) {
            byte[] object = gitObject(directory, "blob", Files.readAllBytes(new File(directory, name).toPath()));
            tree.write(("100644 " + name + "\0").getBytes(StandardCharsets.UTF_8)); tree.write(object);
        }
        byte[] treeId = gitObject(directory, "tree", tree.toByteArray());
        String commit = "tree " + hex(treeId) + "\nauthor vstab Test <test@example.invalid> 1795737600 +0000\n" +
                "committer vstab Test <test@example.invalid> 1795737600 +0000\n\nShared provider history survives import\n";
        byte[] commitId = gitObject(directory, "commit", commit.getBytes(StandardCharsets.UTF_8));
        writeUtf8(new File(directory, ".git/refs/heads/main").toPath(), hex(commitId) + "\n");
    }
    private static byte[] gitObject(File directory, String type, byte[] content) throws IOException {
        try {
            ByteArrayOutputStream object = new ByteArrayOutputStream();
            object.write((type + " " + content.length + "\0").getBytes(StandardCharsets.US_ASCII)); object.write(content);
            byte[] bytes = object.toByteArray(), hash = MessageDigest.getInstance("SHA-1").digest(bytes);
            String id = hex(hash); File destination = new File(directory, ".git/objects/" + id.substring(0, 2) + "/" + id.substring(2));
            Files.createDirectories(destination.getParentFile().toPath());
            try (DeflaterOutputStream stream = new DeflaterOutputStream(Files.newOutputStream(destination.toPath()))) { stream.write(bytes); }
            return hash;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255)); return value.toString();
    }
}
