package app.vstab;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;

/** Standard OpenSSH trust shared by Remote Explorer and local Git. */
final class SshKnownHosts {
    private final File file;

    SshKnownHosts(File home) { file = new File(home, ".ssh/known_hosts"); }

    private static String address(String host, int port) throws IOException {
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (port < 1 || port > 65535) throw new IOException("Invalid SSH port.");
        if (host.isEmpty() || host.matches(".*[\\s,\\[\\]].*")) throw new IOException("Invalid SSH host name.");
        return port == 22 ? host.toLowerCase(java.util.Locale.ROOT)
                : "[" + host.toLowerCase(java.util.Locale.ROOT) + "]:" + port;
    }

    int check(String host, int port, byte[] key) throws IOException {
        synchronized (SshKnownHosts.class) {
            if (!file.isFile()) return HostKeyRepository.NOT_INCLUDED;
            try {
                JSch jsch = new JSch();
                jsch.setKnownHosts(file.getAbsolutePath());
                return jsch.getHostKeyRepository().check(address(host, port), key);
            } catch (JSchException error) { throw new IOException("SSH known_hosts could not be read.", error); }
        }
    }

    HostKey[] entries(String host, String type) {
        synchronized (SshKnownHosts.class) {
            if (!file.isFile()) return new HostKey[0];
            try {
                JSch jsch = new JSch();
                jsch.setKnownHosts(file.getAbsolutePath());
                HostKey[] keys = host == null ? jsch.getHostKeyRepository().getHostKey()
                        : jsch.getHostKeyRepository().getHostKey(host, type);
                return keys == null ? new HostKey[0] : keys;
            } catch (JSchException error) { return new HostKey[0]; }
        }
    }

    void remember(String host, int port, byte[] key) throws IOException {
        synchronized (SshKnownHosts.class) {
            int current = check(host, port, key);
            if (current == HostKeyRepository.OK) return;
            if (current == HostKeyRepository.CHANGED) throw new IOException("The SSH host key has changed.");
            String algorithm;
            try { algorithm = new HostKey(address(host, port), key).getType(); }
            catch (JSchException error) { throw new IOException("Invalid SSH host key.", error); }
            File directory = file.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("SSH home could not be created.");
            directory.setReadable(false, false); directory.setWritable(false, false); directory.setExecutable(false, false);
            directory.setReadable(true, true); directory.setWritable(true, true); directory.setExecutable(true, true);
            boolean newline = false;
            if (file.isFile() && file.length() > 0) {
                try (java.io.RandomAccessFile previous = new java.io.RandomAccessFile(file, "r")) {
                    previous.seek(previous.length() - 1); newline = previous.read() != '\n';
                }
            }
            String line = (newline ? "\n" : "") + address(host, port) + " " + algorithm + " "
                    + Base64.getEncoder().encodeToString(key) + "\n";
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                file.setReadable(false, false); file.setWritable(false, false);
                file.setReadable(true, true); file.setWritable(true, true);
                output.write(line.getBytes(StandardCharsets.US_ASCII)); output.getFD().sync();
            }
        }
    }

    void migrate(Map<String, ?> previous) throws IOException {
        for (Map.Entry<String, ?> entry : previous.entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            int colon = entry.getKey().lastIndexOf(':');
            if (colon < 1) continue;
            try {
                String host = entry.getKey().substring(0, colon);
                int port = Integer.parseInt(entry.getKey().substring(colon + 1));
                byte[] key = Base64.getDecoder().decode((String) entry.getValue());
                if (check(host, port, key) == HostKeyRepository.NOT_INCLUDED) remember(host, port, key);
            } catch (IllegalArgumentException invalid) { throw new IOException("Saved SSH trust could not be migrated.", invalid); }
        }
    }
}
