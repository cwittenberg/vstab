package app.vstab;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LinuxDiskCapacityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final long GIB = 1024L * 1024 * 1024;
    private static final byte[] PREFIX = "existing filesystem header and project bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TAIL = "existing final block".getBytes(StandardCharsets.UTF_8);

    private File disk(long length) throws Exception {
        File disk = temporary.newFile();
        try (RandomAccessFile file = new RandomAccessFile(disk, "rw")) {
            file.setLength(length); file.write(PREFIX); file.seek(length - TAIL.length); file.write(TAIL);
        }
        return disk;
    }

    private void preserved(File disk, long oldLength) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(disk, "r")) {
            byte[] prefix = new byte[PREFIX.length]; file.readFully(prefix); assertArrayEquals(PREFIX, prefix);
            byte[] tail = new byte[TAIL.length]; file.seek(oldLength - TAIL.length); file.readFully(tail); assertArrayEquals(TAIL, tail);
        }
    }

    private LinuxDiskCapacity.Result grow(File disk, boolean stopped, boolean capable, long available) throws Exception {
        return LinuxDiskCapacity.ensureMinimum(disk, LinuxDiskCapacity.DEFAULT_BYTES, stopped, capable,
                LinuxDiskCapacity.MINIMUM_HOST_FREE_BYTES, ignored -> available);
    }

    @Test public void eightGiBSparseGrowthPreservesExistingBytesWithoutAllocatingEightGiB() throws Exception {
        File disk = disk(2 * GIB);
        long blocksBefore = Long.parseLong(new String(new ProcessBuilder("stat", "-c", "%b", disk.getAbsolutePath())
                .start().getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim());
        LinuxDiskCapacity.Result result = grow(disk, true, true, 4 * GIB);
        assertTrue(result.grew); assertEquals(2 * GIB, result.previousBytes); assertEquals(8 * GIB, result.capacityBytes);
        assertEquals(8 * GIB, disk.length()); preserved(disk, 2 * GIB);
        try (RandomAccessFile file = new RandomAccessFile(disk, "r")) { file.seek(8 * GIB - 1); assertEquals(0, file.read()); }
        long blocksAfter = Long.parseLong(new String(new ProcessBuilder("stat", "-c", "%b", disk.getAbsolutePath())
                .start().getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim());
        assertTrue("Sparse growth must not allocate the new logical capacity", (blocksAfter - blocksBefore) * 512 <= 1024 * 1024);
    }

    @Test public void largerDiskIsNeverShrunkOrRequiringAnUnneededResize() throws Exception {
        File disk = disk(10 * GIB);
        LinuxDiskCapacity.Result result = grow(disk, false, false, 0);
        assertFalse(result.grew); assertEquals(10 * GIB, disk.length()); preserved(disk, 10 * GIB);
    }

    @Test public void runningGuestAndMissingCapabilityCannotChangeDisk() throws Exception {
        for (boolean stopped : new boolean[]{false, true}) {
            File disk = disk(2 * GIB);
            try { grow(disk, stopped, false, 4 * GIB); fail("Unsupported growth must fail"); }
            catch (IOException expected) { }
            assertEquals(2 * GIB, disk.length()); preserved(disk, 2 * GIB);
        }
    }

    @Test public void insufficientPhysicalFreeSpaceDoesNotTouchExistingCapacity() throws Exception {
        File disk = disk(2 * GIB);
        try { grow(disk, true, true, LinuxDiskCapacity.MINIMUM_HOST_FREE_BYTES - 1); fail("Insufficient host storage must fail"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("free Android storage")); }
        assertEquals(2 * GIB, disk.length()); preserved(disk, 2 * GIB);
    }

    @Test public void activeFileLockPreventsGrowthEvenIfCallerClaimsStopped() throws Exception {
        File disk = disk(2 * GIB);
        try (RandomAccessFile opened = new RandomAccessFile(disk, "rw"); FileLock held = opened.getChannel().lock()) {
            try { grow(disk, true, true, 4 * GIB); fail("Locked disk must fail"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("still in use")); }
        }
        assertEquals(2 * GIB, disk.length()); preserved(disk, 2 * GIB);
    }

    @Test public void concurrentLengthChangeIsPreservedWithoutRollbackOrOverwrite() throws Exception {
        File disk = disk(2 * GIB);
        try {
            LinuxDiskCapacity.ensureMinimum(disk, 8 * GIB, true, true, 0, ignored -> {
                try (RandomAccessFile changed = new RandomAccessFile(disk, "rw")) { changed.setLength(10 * GIB); }
                return 4 * GIB;
            });
            fail("A concurrent change must stop this operation");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("changed")); }
        assertEquals(10 * GIB, disk.length()); preserved(disk, 2 * GIB);
    }

    @Test public void missingEmptyAndSymlinkDisksAreNeverReplaced() throws Exception {
        File missing = new File(temporary.getRoot(), "missing.raw");
        try { grow(missing, true, true, 4 * GIB); fail("Missing disk must fail"); } catch (IOException expected) { }
        assertFalse(missing.exists());
        File empty = temporary.newFile();
        try { grow(empty, true, true, 4 * GIB); fail("Empty disk must fail"); } catch (IOException expected) { }
        assertEquals(0, empty.length());
        File original = disk(2 * GIB); File link = new File(temporary.getRoot(), "linked.raw");
        Files.createSymbolicLink(link.toPath(), original.toPath());
        try { grow(link, true, true, 4 * GIB); fail("Symlink must fail"); } catch (IOException expected) { }
        assertEquals(2 * GIB, original.length()); preserved(original, 2 * GIB);
    }
}
