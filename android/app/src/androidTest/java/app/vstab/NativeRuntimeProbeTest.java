package app.vstab;

import android.content.Context;
import android.os.Build;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.*;

/** Executes APK-delivered Bionic Node under the actual Android application UID. */
public final class NativeRuntimeProbeTest {
    @Test public void nodeRunsNativelyInsideAndroid() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File node = new File(context.getApplicationInfo().nativeLibraryDir, "libnode.so");
        assertTrue("APK-delivered Node is missing: " + node, node.isFile() && node.canExecute());
        String abi = Build.SUPPORTED_ABIS[0];
        File scratch = new File(context.getCacheDir(), "native-exec-probe-" + UUID.randomUUID());
        File usr = new File(scratch, "usr");
        assertTrue(usr.mkdirs());
        Process process = null;
        try {
            try (ZipInputStream input = new ZipInputStream(context.getAssets().open("local-runtime/usr-" + abi + ".zip"))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    File target = new File(usr, entry.getName()).getCanonicalFile();
                    assertTrue("Archive traversal rejected", target.getPath().startsWith(usr.getCanonicalPath() + File.separator));
                    if (entry.isDirectory()) { assertTrue(target.isDirectory() || target.mkdirs()); continue; }
                    assertTrue(target.getParentFile().isDirectory() || target.getParentFile().mkdirs());
                    try (FileOutputStream output = new FileOutputStream(target)) {
                        byte[] buffer = new byte[32768]; int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                }
            }
            ProcessBuilder builder = new ProcessBuilder(node.getAbsolutePath(), "-e",
                    "console.log(JSON.stringify({platform:process.platform,arch:process.arch,node:process.version,uid:process.getuid(),execPath:process.execPath}))");
            builder.redirectErrorStream(true);
            builder.directory(scratch);
            builder.environment().put("LD_LIBRARY_PATH", context.getApplicationInfo().nativeLibraryDir + ":" + new File(usr, "lib"));
            builder.environment().put("HOME", scratch.getAbsolutePath());
            builder.environment().put("TMPDIR", scratch.getAbsolutePath());
            builder.environment().put("PATH", context.getApplicationInfo().nativeLibraryDir + ":/system/bin");
            builder.environment().remove("NODE_OPTIONS");
            process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    assertTrue("Native process produced excessive output", output.size() + count < 65536);
                    output.write(buffer, 0, count);
                }
            }
            assertTrue("Native process timed out", process.waitFor(30, TimeUnit.SECONDS));
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            assertEquals("Native Node failed: " + text, 0, process.exitValue());
            JSONObject report = new JSONObject(text);
            assertEquals("android", report.getString("platform"));
            assertEquals(android.os.Process.myUid(), report.getInt("uid"));
            assertTrue(report.getInt("uid") > 0);
            assertEquals(node.getCanonicalPath(), report.getString("execPath"));
            System.out.println("VSTAB_NATIVE_NODE_PROBE " + report);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(scratch.toPath())) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
