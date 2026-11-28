package app.vstab;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;

/** Production HTTP broker with a host filesystem test adapter; not Android storage proof. */
public final class FileBrokerHostHarness {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Use fixture-directory private-config-file");
        Path base = Paths.get(args[0]).toAbsolutePath(); Files.createDirectories(base);
        AndroidFileBroker broker = new AndroidFileBroker(null);
        Path projects = base.resolve("projects"), readOnly = base.resolve("read-only");
        Files.createDirectories(projects); Files.createDirectories(readOnly);
        Files.writeString(readOnly.resolve("readable.txt"), "read-only Android export fixture\n");
        broker.addRoot("projects", "/mnt/android/app/projects", false, new AndroidFileBrokerTest.Disk(projects));
        broker.addRoot("read_only", "/mnt/android/folders/read-only", true, new AndroidFileBrokerTest.Disk(readOnly));
        Path config = Paths.get(args[1]);
        Files.createFile(config, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(config, broker.manifest("10.0.2.2").toString(), StandardOpenOption.TRUNCATE_EXISTING);
        Runtime.getRuntime().addShutdownHook(new Thread(broker::close));
        System.out.println("Filesystem fixture ready on loopback port " + broker.port());
        new CountDownLatch(1).await();
    }
}
