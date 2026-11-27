package app.vstab;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.Assert.*;

/** Actual bytes and filesystem races, shared by the Android adapters' portable core. */
public final class DocumentSyncEngineTest {
    private File root, local, device, history;
    private DocumentSyncEngine engine;
    @Before public void prepare() throws Exception {
        root = Files.createTempDirectory("vstab-document-sync-").toFile();
        local = new File(root, "local"); device = new File(root, "device"); history = new File(root, "state/history.bin");
        Files.createDirectories(local.toPath()); Files.createDirectories(device.toPath());
        engine = create(false);
    }
    @After public void cleanup() throws Exception {
        if (root != null) try (java.util.stream.Stream<Path> files = Files.walk(root.toPath())) {
            for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }
    private DocumentSyncEngine create(boolean single) throws IOException {
        return new DocumentSyncEngine(new DocumentSyncEngine.FileStorage(local), new DocumentSyncEngine.FileStorage(device), history, single);
    }
    private static void write(File root, String path, String value) throws IOException {
        Path destination = root.toPath().resolve(path); Files.createDirectories(destination.getParent());
        Files.write(destination, value.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(File root, String path) throws IOException { return new String(Files.readAllBytes(root.toPath().resolve(path)), StandardCharsets.UTF_8); }

    @Test public void openingProjectPreservesGitHistoryDotFilesAndBinaryObjects() throws Exception {
        write(device, "README.md", "Shared project\n"); write(device, ".git/HEAD", "ref: refs/heads/main\n");
        write(device, ".git/refs/heads/main", "a123\n"); write(device, ".env", "APP_MODE=local\n");
        byte[] object = {0, -1, 3, 0, 127}; Path objectPath = device.toPath().resolve(".git/objects/aa/bb");
        Files.createDirectories(objectPath.getParent()); Files.write(objectPath, object);
        DocumentSyncEngine.Result result = engine.sync();
        assertTrue(result.pulled > 4); assertEquals("ref: refs/heads/main\n", read(local, ".git/HEAD"));
        assertArrayEquals(object, Files.readAllBytes(local.toPath().resolve(".git/objects/aa/bb")));
        assertEquals("APP_MODE=local\n", read(local, ".env")); assertTrue(result.conflicts.isEmpty());
    }
    @Test public void realGitCommitHistoryAndObjectIntegritySurviveSharedFolderImport() throws Exception {
        git(device, "init", "--initial-branch=main");
        write(device, "code.js", "console.log('history is retained');\n");
        git(device, "add", "code.js");
        git(device, "-c", "user.name=vstab Test", "-c", "user.email=test@example.invalid", "commit", "-m", "Retain shared project history");
        String expected = git(device, "rev-parse", "HEAD");
        engine.sync();
        assertEquals(expected, git(local, "rev-parse", "HEAD"));
        assertTrue(git(local, "log", "-1", "--format=%s").contains("Retain shared project history"));
        assertEquals("", git(local, "status", "--porcelain"));
        git(local, "fsck", "--full");
    }
    private static String git(File directory, String... args) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("git", "-C", directory.getAbsolutePath()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor()); return output.trim();
    }
    @Test public void localSaveAndExternalSaveFlowBothWaysWithoutReopening() throws Exception {
        write(device, "app.js", "old"); engine.sync();
        write(local, "app.js", "saved from editor"); assertEquals(1, engine.sync().pushed);
        assertEquals("saved from editor", read(device, "app.js"));
        write(device, "app.js", "changed by Files"); assertEquals(1, engine.sync().pulled);
        assertEquals("changed by Files", read(local, "app.js"));
    }
    @Test public void contentChecksDetectExternalChangesWithUnchangedSizeAndTimestamp() throws Exception {
        write(device, "same.txt", "AAAA"); engine.sync();
        Path file = new File(device, "same.txt").toPath(); FileTime time = Files.getLastModifiedTime(file);
        write(device, "same.txt", "BBBB"); Files.setLastModifiedTime(file, time);
        assertEquals(1, engine.sync().pulled); assertEquals("BBBB", read(local, "same.txt"));
    }
    @Test public void divergedSavesKeepBothVersionsUntilExplicitResolution() throws Exception {
        write(device, "app.py", "base"); engine.sync();
        write(local, "app.py", "local edit"); write(device, "app.py", "external edit");
        DocumentSyncEngine.Result result = engine.sync(); assertEquals(1, result.conflicts.size());
        DocumentSyncEngine.Conflict conflict = result.conflicts.get(0);
        assertEquals("local edit", read(local, "app.py")); assertEquals("external edit", read(device, "app.py"));
        assertEquals("external edit", read(local, conflict.recoveryPath));
        assertTrue(engine.sync().conflicts.isEmpty()); // No repeated alert, no repeated upload.
        engine.resolve("app.py", true);
        assertEquals("local edit", read(device, "app.py")); assertTrue(engine.conflicts().isEmpty());
        engine.sync(); assertEquals(1, Files.list(device.toPath()).count());
    }
    @Test public void choosingDeviceVersionAndStaleResolutionAreSafe() throws Exception {
        write(device, "file", "base"); engine.sync(); write(local, "file", "ours"); write(device, "file", "theirs"); engine.sync();
        write(device, "file", "changed again");
        try { engine.resolve("file", true); fail("A stale conflict must not overwrite a newer file"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("changed again")); }
        assertEquals("changed again", read(device, "file"));
        engine.sync(); engine.resolve("file", false);
        assertEquals("changed again", read(local, "file"));
    }
    @Test public void editingARecoveryCopyDoesNotLoseThatWorkOnTheNextConflict() throws Exception {
        write(device, "file", "base"); engine.sync(); write(local, "file", "ours"); write(device, "file", "theirs");
        DocumentSyncEngine.Conflict first = engine.sync().conflicts.get(0);
        write(local, first.recoveryPath, "manual merge in recovery copy");
        write(local, "file", "updated local edit");
        DocumentSyncEngine.Conflict second = engine.sync().conflicts.get(0);
        assertNotEquals(first.recoveryPath, second.recoveryPath);
        assertEquals("manual merge in recovery copy", read(local, first.recoveryPath));
        assertEquals("theirs", read(local, second.recoveryPath));
        assertFalse(new File(device, first.recoveryPath).exists());
    }
    @Test public void renameCreateAndDeleteAreSynchronizedAsFilesystemOperations() throws Exception {
        write(device, "old/sub.txt", "content"); engine.sync();
        Files.move(local.toPath().resolve("old"), local.toPath().resolve("renamed"));
        engine.sync(); assertFalse(new File(device, "old").exists()); assertEquals("content", read(device, "renamed/sub.txt"));
        Files.delete(device.toPath().resolve("renamed/sub.txt")); Files.delete(device.toPath().resolve("renamed"));
        engine.sync(); assertFalse(new File(local, "renamed").exists());
    }
    @Test public void directoryDeletionCannotEraseAChildChangedOnTheOtherSide() throws Exception {
        write(device, "folder/code", "base"); engine.sync();
        Files.delete(local.toPath().resolve("folder/code")); Files.delete(local.toPath().resolve("folder"));
        write(device, "folder/code", "external change");
        DocumentSyncEngine.Result result = engine.sync();
        assertFalse(result.conflicts.isEmpty()); assertEquals("external change", read(device, "folder/code"));
    }
    @Test public void failedProviderListingNeverMeansAllItsFilesWereDeleted() throws Exception {
        write(device, "important", "keep me"); engine.sync();
        DocumentSyncEngine.Storage broken = new Proxy(new DocumentSyncEngine.FileStorage(device)) {
            @Override public Map<String, DocumentSyncEngine.Kind> list() throws IOException { throw new IOException("permission revoked"); }
        };
        DocumentSyncEngine failure = new DocumentSyncEngine(new DocumentSyncEngine.FileStorage(local), broken, history, false);
        try { failure.sync(); fail("Should stop before changing files"); } catch (IOException expected) { }
        assertEquals("keep me", read(local, "important")); assertEquals("keep me", read(device, "important"));
    }
    @Test public void savedBaselineSurvivesRestartAndMissingHistoryDoesNotOverwriteDivergence() throws Exception {
        write(device, "state", "base"); engine.sync(); engine = create(false);
        write(device, "state", "new external"); engine.sync(); assertEquals("new external", read(local, "state"));
        Files.delete(history.toPath()); write(local, "state", "unsent local"); engine = create(false);
        assertEquals(1, engine.sync().conflicts.size()); assertEquals("new external", read(device, "state"));
    }
    @Test public void symlinkEscapeNeverReadsOrExportsTheTarget() throws Exception {
        write(device, "safe", "allowed"); engine.sync(); File secret = new File(root, "secret"); Files.write(secret.toPath(), "private".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(local.toPath().resolve("escape"), secret.toPath());
        DocumentSyncEngine.Result result = engine.sync(); assertEquals(1, result.warnings.size());
        assertFalse(new File(device, "escape").exists()); assertEquals("private", new String(Files.readAllBytes(secret.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void redirectedMirrorRootIsRejectedInsteadOfPublishingItsTarget() throws Exception {
        File redirected = new File(root, "redirected-root"); Files.createSymbolicLink(redirected.toPath(), device.toPath());
        try { new DocumentSyncEngine.FileStorage(redirected); fail("A redirected workspace must not become a provider mirror"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("symbolic link")); }
    }
    @Test public void maliciousProviderPathsAreRejectedBeforeAnyWrite() throws Exception {
        DocumentSyncEngine.Storage unsafe = new Proxy(new DocumentSyncEngine.FileStorage(device)) {
            @Override public Map<String, DocumentSyncEngine.Kind> list() { return Collections.singletonMap("../outside", DocumentSyncEngine.Kind.FILE); }
        };
        engine = new DocumentSyncEngine(new DocumentSyncEngine.FileStorage(local), unsafe, history, false);
        try { engine.sync(); fail("Traversal must be rejected"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("unsafe")); }
        assertFalse(new File(root, "outside").exists());
    }
    @Test public void deletingAnOpenedSingleFileMirrorRestoresRatherThanDeletingItsOriginal() throws Exception {
        write(device, "README.md", "external original"); engine = create(true); engine.sync();
        Files.delete(local.toPath().resolve("README.md")); engine.sync();
        assertEquals("external original", read(device, "README.md")); assertEquals("external original", read(local, "README.md"));
    }
    @Test public void destinationChangingAfterInventoryIsNotOverwritten() throws Exception {
        write(device, "racy", "base"); engine.sync(); write(local, "racy", "local edit");
        DocumentSyncEngine.Storage changing = new Proxy(new DocumentSyncEngine.FileStorage(device)) {
            boolean raced;
            @Override public DocumentSyncEngine.Kind kind(String path) throws IOException {
                if (!raced) { raced = true; DocumentSyncEngineTest.write(device, "racy", "external race"); }
                return delegate.kind(path);
            }
        };
        engine = new DocumentSyncEngine(new DocumentSyncEngine.FileStorage(local), changing, history, false);
        try { engine.sync(); fail("Race must stop the save"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("changed again")); }
        assertEquals("external race", read(device, "racy")); assertEquals("local edit", read(local, "racy"));
    }
    @Test public void fileFolderReplacementPreservesBothTreesInsteadOfRecursivelyDeleting() throws Exception {
        write(device, "name/file", "keep child"); engine.sync();
        Files.delete(local.toPath().resolve("name/file")); Files.delete(local.toPath().resolve("name")); write(local, "name", "replacement file");
        assertEquals(1, engine.sync().conflicts.size()); assertEquals("keep child", read(device, "name/file")); assertEquals("replacement file", read(local, "name"));
    }
    private static class Proxy implements DocumentSyncEngine.Storage {
        final DocumentSyncEngine.Storage delegate;
        Proxy(DocumentSyncEngine.Storage delegate) { this.delegate = delegate; }
        @Override public Map<String, DocumentSyncEngine.Kind> list() throws IOException { return delegate.list(); }
        @Override public DocumentSyncEngine.Kind kind(String path) throws IOException { return delegate.kind(path); }
        @Override public InputStream read(String path) throws IOException { return delegate.read(path); }
        @Override public void write(String path, File source) throws IOException { delegate.write(path, source); }
        @Override public void mkdir(String path) throws IOException { delegate.mkdir(path); }
        @Override public void delete(String path) throws IOException { delegate.delete(path); }
    }
}
