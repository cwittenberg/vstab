package app.vstab;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;

/** Sparse raw-disk capacity only; the advertised guest resizes its own filesystem. */
final class LinuxDiskCapacity {
    static final long DEFAULT_BYTES = 8L * 1024 * 1024 * 1024;
    static final long MINIMUM_HOST_FREE_BYTES = 2L * 1024 * 1024 * 1024;

    interface FreeSpace { long available(File disk) throws IOException; }

    static final class Result {
        final long previousBytes;
        final long capacityBytes;
        final long hostFreeBytes;
        final boolean grew;
        Result(long previousBytes, long capacityBytes, long hostFreeBytes) {
            this.previousBytes = previousBytes; this.capacityBytes = capacityBytes;
            this.hostFreeBytes = hostFreeBytes; this.grew = capacityBytes > previousBytes;
        }
    }

    /**
     * Call for an app-private raw disk after confirming that its guest stopped.
     * No allocation reservation or filesystem resize is implied by its length.
     */
    static Result ensureMinimum(File disk, long targetBytes, boolean guestStopped,
                                boolean guestCanGrow) throws IOException {
        return ensureMinimum(disk, targetBytes, guestStopped, guestCanGrow,
                MINIMUM_HOST_FREE_BYTES, File::getUsableSpace);
    }

    static Result ensureMinimum(File disk, long targetBytes, boolean guestStopped,
                                boolean guestCanGrow, long minimumHostFreeBytes,
                                FreeSpace freeSpace) throws IOException {
        if (targetBytes <= 0 || targetBytes % 512 != 0 || minimumHostFreeBytes < 0)
            throw new IOException("The Linux disk capacity setting is invalid.");
        if (Files.isSymbolicLink(disk.toPath()) || !disk.isFile())
            throw new IOException("The existing Linux disk is missing or is not a regular file.");
        long knownBytes = disk.length();
        if (knownBytes <= 0) throw new IOException("The existing Linux disk is empty; it was preserved for recovery.");
        if (knownBytes >= targetBytes) return new Result(knownBytes, knownBytes, -1);
        if (!guestStopped) throw new IOException("Stop the Linux environment before expanding its disk.");
        if (!guestCanGrow) throw new IOException("This saved Linux runtime does not support safe filesystem expansion.");
        try (RandomAccessFile file = new RandomAccessFile(disk, "rw")) {
            final FileLock lock;
            try { lock = file.getChannel().tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("The Linux disk is still in use; its capacity was preserved.", busy); }
            if (lock == null) throw new IOException("The Linux disk is still in use; its capacity was preserved.");
            try (FileLock held = lock) {
                long before = file.length();
                if (before >= targetBytes) return new Result(before, before, -1);
                if (before != knownBytes) throw new IOException("The Linux disk changed while preparing expansion; retry after stopping it.");
                long available = freeSpace.available(disk);
                if (available < minimumHostFreeBytes)
                    throw new IOException("Linux needs at least " + (minimumHostFreeBytes / (1024 * 1024))
                            + " MiB of free Android storage before expanding its disk.");
                // Recheck after the space probe. Never shrink a file changed by
                // another operation, including a larger concurrent expansion.
                if (file.length() != before) throw new IOException("The Linux disk changed while preparing expansion; its current capacity was preserved.");
                // ftruncate creates a sparse zero-filled tail on Android's
                // private ext4/f2fs storage; it does not write an 8 GiB blob.
                file.setLength(targetBytes);
                file.getFD().sync();
                long after = file.length();
                if (after < targetBytes) throw new IOException("Linux disk expansion did not complete; retry before starting it.");
                return new Result(before, after, available);
            }
        }
    }
}
