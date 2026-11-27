package app.vstab;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.Base64;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.Assert.*;

/** Exercises the actual Android filesystem boundary with isolated private files. */
public final class LocalWorkspaceBoundaryTest {
    private File isolated;
    private File workspace;
    private LocalWorkspaceBridge bridge;
    private Method dispatch;

    @Before public void prepare() throws Exception {
        Context application = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolated = new File(application.getCacheDir(), "workspace-boundary-" + UUID.randomUUID());
        assertTrue(isolated.mkdirs());
        Context context = new ContextWrapper(application) {
            @Override public File getFilesDir() { return isolated; }
        };
        bridge = new LocalWorkspaceBridge(context);
        workspace = new File(isolated, "workspaces/default");
        dispatch = LocalWorkspaceBridge.class.getDeclaredMethod("dispatch", String.class);
        dispatch.setAccessible(true);
    }

    @After public void cleanup() throws Exception {
        if (isolated != null && isolated.exists()) try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(isolated.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test public void saveReadRenameAndDeleteUseRealPrivateFiles() throws Exception {
        byte[] code = "console.log('saved on Android');\n".getBytes(StandardCharsets.UTF_8);
        JSONObject write = request("fs.writeFile", new JSONObject().put("path", "/workspace/demo.js")
                .put("dataBase64", Base64.encodeToString(code, Base64.NO_WRAP)).put("create", true).put("overwrite", false));
        assertTrue(write.toString(), write.getBoolean("ok"));
        assertArrayEquals(code, Files.readAllBytes(new File(workspace, "demo.js").toPath()));
        JSONObject read = request("fs.readFile", path("/workspace/demo.js"));
        assertTrue(read.toString(), read.getBoolean("ok"));
        assertArrayEquals(code, Base64.decode(read.getJSONObject("result").getString("dataBase64"), Base64.DEFAULT));
        assertTrue(request("fs.rename", path("/workspace/demo.js").put("newPath", "/workspace/renamed.js").put("overwrite", false)).getBoolean("ok"));
        assertFalse(new File(workspace, "demo.js").exists());
        assertArrayEquals(code, Files.readAllBytes(new File(workspace, "renamed.js").toPath()));
        assertTrue(request("fs.delete", path("/workspace/renamed.js").put("recursive", false)).getBoolean("ok"));
        assertFalse(new File(workspace, "renamed.js").exists());
    }

    @Test public void refusedOverwritePreservesExistingContent() throws Exception {
        File existing = new File(workspace, "existing.txt");
        Files.write(existing.toPath(), "keep this".getBytes(StandardCharsets.UTF_8));
        JSONObject reply = request("fs.writeFile", path("/workspace/existing.txt")
                .put("dataBase64", Base64.encodeToString("replacement".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                .put("create", true).put("overwrite", false));
        assertError(reply, "FileExists");
        assertEquals("keep this", new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void traversalAbsolutePathsAndRootDeletionAreBlocked() throws Exception {
        assertError(request("fs.readFile", path("/workspace/../../outside")), "NoPermissions");
        assertError(request("fs.readFile", path("/data/data/app.vstab/shared_prefs/last-profile.xml")), "NoPermissions");
        assertError(request("fs.delete", path("/workspace").put("recursive", true)), "NoPermissions");
        assertTrue(workspace.isDirectory());
    }

    @Test public void symlinkCannotEscapeWorkspace() throws Exception {
        File outside = new File(isolated, "outside.txt");
        Files.write(outside.toPath(), "private boundary fixture".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(workspace, "escape.txt").toPath(), outside.toPath());
        assertError(request("fs.readFile", path("/workspace/escape.txt")), "NoPermissions");
        assertTrue(outside.isFile());
    }

    @Test public void oversizedFileAndUnknownNativeOperationAreRejected() throws Exception {
        File huge = new File(workspace, "oversized.bin");
        try (RandomAccessFile output = new RandomAccessFile(huge, "rw")) { output.setLength(16L * 1024 * 1024 + 1); }
        assertError(request("fs.readFile", path("/workspace/oversized.bin")), "Unavailable");
        assertError(request("native.exec", new JSONObject().put("command", "echo prohibited")), "NoPermissions");
    }

    private JSONObject request(String method, JSONObject params) throws Exception {
        JSONObject call = new JSONObject().put("v", 1).put("id", 1).put("method", method).put("params", params);
        return new JSONObject((String) dispatch.invoke(bridge, call.toString()));
    }
    private JSONObject path(String value) throws Exception { return new JSONObject().put("path", value); }
    private void assertError(JSONObject reply, String code) throws Exception {
        assertFalse(reply.toString(), reply.getBoolean("ok"));
        assertEquals(reply.toString(), code, reply.getJSONObject("error").getString("code"));
    }
}
