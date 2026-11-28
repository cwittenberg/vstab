package app.vstab;

import static org.junit.Assert.*;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.Test;

public class SshKnownHostsTest {
    @Test public void sharedTrustRecognizesPortsAndRejectsChangedKeys() throws Exception {
        Path home = Files.createTempDirectory("vstab-host-trust");
        KeyPair first = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 1024);
        KeyPair changed = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 1024);
        try {
            SshKnownHosts trust = new SshKnownHosts(home.toFile());
            byte[] key = first.getPublicKeyBlob();
            assertEquals(HostKeyRepository.NOT_INCLUDED, trust.check("example.com", 2222, key));
            trust.remember("example.com", 2222, key);
            assertTrue(new String(Files.readAllBytes(home.resolve(".ssh/known_hosts")), java.nio.charset.StandardCharsets.US_ASCII)
                    .startsWith("[example.com]:2222 ssh-rsa "));
            assertEquals(HostKeyRepository.OK, trust.check("example.com", 2222, key));
            assertEquals(1, trust.entries("[example.com]:2222", "ssh-rsa").length);
            assertEquals(HostKeyRepository.NOT_INCLUDED, trust.check("example.com", 22, key));
            assertEquals(HostKeyRepository.CHANGED, trust.check("example.com", 2222, changed.getPublicKeyBlob()));
            assertThrows(java.io.IOException.class, () -> trust.remember("example.com", 2222, changed.getPublicKeyBlob()));
            long size = Files.size(home.resolve(".ssh/known_hosts"));
            trust.remember("example.com", 2222, key);
            assertEquals(size, Files.size(home.resolve(".ssh/known_hosts")));
        } finally {
            first.dispose(); changed.dispose();
            Files.deleteIfExists(home.resolve(".ssh/known_hosts")); Files.deleteIfExists(home.resolve(".ssh")); Files.deleteIfExists(home);
        }
    }

    @Test public void previousRemoteExplorerConsentMigratesToOpenSshFormat() throws Exception {
        Path home = Files.createTempDirectory("vstab-host-trust");
        KeyPair pair = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 1024);
        try {
            SshKnownHosts trust = new SshKnownHosts(home.toFile());
            trust.migrate(Map.of("example.com:22", Base64.getEncoder().encodeToString(pair.getPublicKeyBlob())));
            assertEquals(HostKeyRepository.OK, trust.check("example.com", 22, pair.getPublicKeyBlob()));
        } finally {
            pair.dispose();
            Files.deleteIfExists(home.resolve(".ssh/known_hosts")); Files.deleteIfExists(home.resolve(".ssh")); Files.deleteIfExists(home);
        }
    }
}
