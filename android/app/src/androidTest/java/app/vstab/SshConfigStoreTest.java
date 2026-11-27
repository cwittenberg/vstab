package app.vstab;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

import static org.junit.Assert.*;

/** Real files, parser semantics and save conflicts; also runnable with plain JUnit. */
public final class SshConfigStoreTest {
    private File home;
    private SshConfigStore store;
    @Before public void prepare() throws Exception { home = Files.createTempDirectory("vstab-ssh-config-").toFile(); store = new SshConfigStore(home); }
    @After public void cleanup() throws Exception { if (home != null) try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(home.toPath())) { files.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException error) { throw new RuntimeException(error); } }); } }
    private void config(String content) throws Exception { store.writeConfig(content, store.readConfig().revision); }

    @Test public void firstValueWinsAndMatchingDefaultsDoNotOverwriteSpecificValues() throws Exception {
        String text = "# exact comment\r\nHost prod stage\r\n  HostName example.test\r\n  User developer\r\n  Port 2222\r\nHost * !stage\r\n  Port 22\r\n  IdentityFile ~/.ssh/default\r\nHost prod\r\n  User ignored\r\n  IdentityFile \"~/.ssh/key with # space\" # retain\r\n";
        config(text);
        assertEquals(text, store.readConfig().content);
        SshConfigStore.Host prod = store.find("PROD");
        assertEquals("example.test", prod.hostname); assertEquals("developer", prod.user); assertEquals(2222, prod.port);
        assertEquals(1, prod.configLine); assertEquals(2, prod.identityPaths.size());
        assertEquals(new File(home, ".ssh/key with # space").getCanonicalPath(), prod.identityPaths.get(1));
        assertEquals(0, store.find("stage").identityPaths.size());
        assertEquals(2, store.profiles().size());
        assertTrue(prod.connectable);
    }

    @Test public void precedingWildcardDefaultsFollowOpenSshFirstValueRule() throws Exception {
        config("Host *\n User first\n Port 2200\nHost box\n HostName box.test\n User second\n Port 2222\n");
        assertEquals("first", store.find("box").user); assertEquals(2200, store.find("box").port);
    }

    @Test public void quotedEscapedPathsAndTokensAreExpandedWithoutExecution() throws Exception {
        config("Host dev\n HostName %h.example\n User alice\n Port 2022\n IdentityFile \"~/.ssh/%r-%h-%p-%%.pem\"\n IdentityFile ~/.ssh/a\\ b\\#c\n IdentitiesOnly yes\n PreferredAuthentications publickey,password\n");
        SshConfigStore.Host host = store.find("dev");
        assertEquals("dev.example", host.hostname); assertTrue(host.identitiesOnly);
        assertEquals(new File(home, ".ssh/alice-dev.example-2022-%.pem").getCanonicalPath(), host.identityPaths.get(0));
        assertEquals(new File(home, ".ssh/a b#c").getCanonicalPath(), host.identityPaths.get(1));
        assertTrue(host.connectable);
    }

    @Test public void invalidAndExecutionDependentOptionsSaveWithDiagnostics() throws Exception {
        String content = "Host bad\n User alice\n ProxyCommand touch /never-executed\n ProxyJump jump\n Port 99999\n IdentityFile \"unterminated\n";
        config(content);
        assertEquals(content, store.readConfig().content);
        assertTrue(store.readConfig().diagnostics.size() >= 4);
        assertEquals(2, store.readConfig().diagnostics.get(0).line);
        assertFalse(store.find("bad").connectable);
        assertFalse(new File("/never-executed").exists());
    }

    @Test public void twoReadersCannotOverwriteAConflictingSave() throws Exception {
        SshConfigStore second = new SshConfigStore(home);
        SshConfigStore.Snapshot initial = second.readConfig();
        config("# newer user text\n");
        try { second.writeConfig("# stale user text\n", initial.revision); fail("Expected conflict"); }
        catch (SshConfigStore.ConflictException expected) { assertTrue(expected.getMessage().contains("Reload")); }
        assertEquals("# newer user text\n", store.readConfig().content);
    }

    @Test public void includeStaysWithinPrivateSshAndRetainsSourceBoundaries() throws Exception {
        File fragments = new File(home, ".ssh/hosts"); assertTrue(fragments.mkdir());
        Files.write(new File(fragments, "a.conf").toPath(), "Host included\n HostName included.test\n User alice\n".getBytes(StandardCharsets.UTF_8));
        config("Include hosts/*.conf\nHost direct\n User bob\n");
        assertEquals(2, store.profiles().size()); assertFalse(store.find("included").editable); assertTrue(store.find("included").connectable);
        try { store.removeHost(store.find("included").id); fail("An include file must not be silently rewritten"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("Include")); }
        config("Include ../outside\nHost direct\n User bob\n");
        assertFalse(store.find("direct").connectable); assertTrue(store.readConfig().diagnostics.get(0).message.contains(".ssh"));
    }

    @Test public void conditionalAndRecursiveIncludesAreActionable() throws Exception {
        config("Host one\n User alice\n Include nested\n"); assertFalse(store.find("one").connectable);
        Files.write(new File(home, ".ssh/loop").toPath(), "Include config\n".getBytes(StandardCharsets.UTF_8));
        config("Include loop\nHost two\n User alice\n");
        assertFalse(store.find("two").connectable); assertTrue(store.readConfig().diagnostics.get(0).message.contains("Recursive"));
    }

    @Test public void aliasRemovalPreservesOtherSharedAliasesAndComments() throws Exception {
        config("# global\nHost one two # shared comment\n User alice\nHost one\n HostName one.test # endpoint comment\n # own explanatory comment\n\nHost three\n User bob\n");
        assertTrue(store.removeHost(store.find("one").id));
        assertNull(store.find("one")); assertNotNull(store.find("two")); assertNotNull(store.find("three"));
        String text = store.readConfig().content;
        assertTrue(text.contains("# global\n")); assertTrue(text.contains("# shared comment")); assertTrue(text.contains("# endpoint comment")); assertTrue(text.contains(" # own explanatory comment\n"));
        assertTrue(text.endsWith("Host three\n User bob\n"));
    }

    @Test public void normalHostEditKeepsOtherBlocksAndAllCommentText() throws Exception {
        config("# header\nHost one # host explanation\n HostName one.test # endpoint explanation\n User alice\n Port 22\n # retain in block\nHost two\n User bob\n");
        String stableId = store.find("one").id;
        SshConfigStore.Host result = store.saveHost(stableId, "one", "new.test", 2222, "alice", Arrays.asList("~/.ssh/key"), true, "publickey");
        assertEquals(stableId, result.id); assertEquals("new.test", result.hostname); assertEquals(2222, result.port);
        String text = store.readConfig().content; assertTrue(text.contains("# host explanation")); assertTrue(text.contains("# endpoint explanation")); assertTrue(text.contains(" # retain in block\n")); assertTrue(text.endsWith("Host two\n User bob\n"));
    }

    @Test public void commandImportParsesConnectionOnlyAndNeverRunsRemoteCommand() throws Exception {
        SshConfigStore.Host host = store.addFromCommand("ssh -p2222 -l alice -i '~/.ssh/key with space' -o IdentitiesOnly=yes example.test", "work");
        assertEquals("work", host.alias); assertEquals("example.test", host.hostname); assertEquals("alice", host.user); assertEquals(2222, host.port); assertTrue(host.identitiesOnly);
        assertEquals(new File(home, ".ssh/key with space").getCanonicalPath(), host.identityFile);
        String before = store.readConfig().content;
        for (String command : Arrays.asList("ssh alice@example.test touch /never-executed", "ssh -o ProxyCommand=evil example.test", "ssh -F other.conf example.test", "ssh example.test; touch /never-executed")) {
            try { store.addFromCommand(command); fail("Unsupported command accepted: " + command); } catch (IOException expected) { }
            assertEquals(before, store.readConfig().content);
        }
    }

    @Test public void ipv6AndCaseStableAliasesWorkAndAliasCollisionIsExplicit() throws Exception {
        SshConfigStore.Host host = store.addFromCommand("ssh alice@[2001:db8::1]", "Work");
        assertEquals("2001:db8::1", host.hostname); assertEquals(host.id, SshConfigStore.idForAlias("work"));
        try { store.addFromCommand("ssh bob@other.test", "work"); fail("Duplicate alias accepted"); } catch (IOException expected) { }
    }

    @Test public void connectionFlagsAfterHostAllowQuotedKeysButNeverRemoteExecution() throws Exception {
        SshConfigStore.Host host = store.addFromCommand("ssh alice@example.test -p 2222 -i '~/.ssh/key with # spaces' -o IdentitiesOnly=yes", "post-host");
        assertEquals(2222, host.port); assertEquals("alice", host.user); assertTrue(host.identitiesOnly);
        assertEquals(new File(home, ".ssh/key with # spaces").getCanonicalPath(), host.identityFile);
        String saved = store.readConfig().content;
        for (String command : Arrays.asList("ssh alice@example.test -p 2222 touch /never-executed", "ssh alice@example.test -i '~/.ssh/key' -- touch")) {
            try { store.addFromCommand(command); fail("Remote execution accepted"); } catch (IOException expected) { }
            assertEquals(saved, store.readConfig().content);
        }
    }

    @Test public void unsupportedKeyboardInteractiveCannotBeSilentlyNarrowed() throws Exception {
        config("Host default\n User alice\nHost otp\n User alice\n PreferredAuthentications keyboard-interactive\nHost mixed\n User bob\n PreferredAuthentications publickey,keyboard-interactive,password\n");
        assertEquals("publickey,password", store.find("default").preferredAuthentications);
        assertTrue(store.find("default").connectable);
        assertFalse(store.find("otp").connectable); assertFalse(store.find("mixed").connectable);
        assertTrue(store.find("otp").diagnostics.stream().anyMatch(message -> message.contains("keyboard-interactive")));
        assertTrue(store.readConfig().diagnostics.stream().anyMatch(issue -> issue.line == 4 && issue.message.contains("keyboard-interactive")));
    }

    @Test public void rawByteLimitAndNulAreEnforcedBeforeReplacingUserText() throws Exception {
        config("# original\n"); String revision = store.readConfig().revision;
        for (String invalid : Arrays.asList("#" + "界".repeat(90000), "Host bad\u0000")) {
            try { store.writeConfig(invalid, revision); fail("Invalid config saved"); } catch (IOException expected) { }
            assertEquals("# original\n", store.readConfig().content);
        }
    }
}
