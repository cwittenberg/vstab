package app.vstab;

import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Pins a persistent guest disk to its installed platform and kernel. */
final class LinuxGuestInstallation {
    private static File metadata(File directory) { return new File(directory, "installed-guest.json"); }

    static JSONObject select(File directory, JSONObject packaged, String serverVersion) throws IOException {
        File disk = new File(directory, "system.ext4");
        File record = metadata(directory);
        if (!record.isFile()) {
            if (disk.exists()) throw new IOException("An existing Linux disk has no installation record. It has been preserved; recover its matching runtime before starting it.");
            return packaged;
        }
        try {
            JSONObject installed = new JSONObject(new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8));
            if (installed.getInt("schema") != 1 || !serverVersion.equals(installed.getString("serverVersion")))
                throw new IOException("This Linux disk needs a compatible editor runtime. Its projects and installed tools have been preserved.");
            JSONObject guest = installed.getJSONObject("guest");
            if (packaged.has("codeServerArchiveSha256") && !packaged.optString("codeServerArchiveSha256").equals(guest.optString("codeServerArchiveSha256")))
                throw new IOException("This Linux disk needs its matching editor runtime. Its files and installed tools have been preserved.");
            if (!guest.getString("kernelSha256").matches("[a-f0-9]{64}")
                    || !guest.getString("diskUnpackedSha256").matches("[a-f0-9]{64}"))
                throw new IOException("The Linux installation record is invalid. Its disk has been preserved.");
            return guest;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("The Linux installation record could not be read. Its disk has been preserved.", error); }
    }

    /** Save before first disk creation, so interrupted extraction can safely resume. */
    static void record(File directory, JSONObject guest, String serverVersion) throws IOException {
        if (metadata(directory).exists()) return;
        JSONObject installed;
        try { installed = new JSONObject().put("schema", 1).put("serverVersion", serverVersion).put("guest", guest); }
        catch (Exception invalid) { throw new IOException("The Linux installation record could not be created.", invalid); }
        File pending = new File(directory, "installed-guest.json.partial");
        Files.write(pending.toPath(), installed.toString().getBytes(StandardCharsets.UTF_8));
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(pending, "rw")) { file.getFD().sync(); }
        Files.move(pending.toPath(), metadata(directory).toPath(), StandardCopyOption.ATOMIC_MOVE);
    }
}
