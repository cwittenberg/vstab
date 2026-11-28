package app.vstab;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.file.Files;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class LinuxGuestInstallationTest {
    private static void write(File file, String text) throws IOException { Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static JSONObject guest(char marker) throws Exception {
        return new JSONObject().put("kernelSha256", String.valueOf(marker).repeat(64))
                .put("diskUnpackedSha256", "b".repeat(64)).put("guestBuildId", "original");
    }
    @Test public void appUpdateKeepsInstalledGuestAndKernel() throws Exception {
        File directory = Files.createTempDirectory("vstab-guest").toFile();
        JSONObject original = guest('a');
        LinuxGuestInstallation.record(directory, original, "4.146.0");
        write(new File(directory, "system.ext4"), "user projects and packages");
        JSONObject update = guest('c').put("guestBuildId", "update");
        assertEquals("original", LinuxGuestInstallation.select(directory, update, "4.146.0").getString("guestBuildId"));
        assertEquals("user projects and packages", read(new File(directory, "system.ext4")));
        LinuxGuestInstallation.record(directory, update, "4.146.0");
        assertEquals("a".repeat(64), LinuxGuestInstallation.select(directory, update, "4.146.0").getString("kernelSha256"));
    }
    @Test public void incompatibleEditorPreservesDisk() throws Exception {
        File directory = Files.createTempDirectory("vstab-guest").toFile();
        LinuxGuestInstallation.record(directory, guest('a'), "4.145.0");
        File disk = new File(directory, "system.ext4"); write(disk, "keep");
        assertThrows(IOException.class, () -> LinuxGuestInstallation.select(directory, guest('c'), "4.146.0"));
        assertEquals("keep", read(disk));
    }
    @Test public void unknownExistingDiskIsNeverReinitialized() throws Exception {
        File directory = Files.createTempDirectory("vstab-guest").toFile();
        File disk = new File(directory, "system.ext4"); write(disk, "keep");
        assertThrows(IOException.class, () -> LinuxGuestInstallation.select(directory, guest('a'), "4.146.0"));
        assertFalse(new File(directory, "installed-guest.json").exists());
        assertEquals("keep", read(disk));
    }
    @Test public void interruptedFirstExtractionKeepsMatchingMetadata() throws Exception {
        File directory = Files.createTempDirectory("vstab-guest").toFile();
        LinuxGuestInstallation.record(directory, guest('a'), "4.146.0");
        assertEquals("a".repeat(64), LinuxGuestInstallation.select(directory, guest('c'), "4.146.0").getString("kernelSha256"));
    }
}
