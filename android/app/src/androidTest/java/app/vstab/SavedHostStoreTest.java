package app.vstab;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.Assert.*;

/** Real Android preferences: metadata migration and browser exposure boundaries. */
public final class SavedHostStoreTest {
    private Context context;
    private File directory;

    @Before public void isolatePreferences() {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "host-boundary-test-" + UUID.randomUUID() + "-";
        directory = new File(base.getCacheDir(), prefix);
        assertTrue(directory.mkdirs());
        context = new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return base.getSharedPreferences(prefix + name, mode);
            }
            @Override public File getFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
        };
    }

    @After public void removePreferences() throws Exception {
        for (String name : new String[]{"last-profile", "saved-ssh-hosts"})
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            for (java.nio.file.Path path : paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) Files.deleteIfExists(path);
        }
    }

    @Test public void migrationRetainsMetadataAndKeepsCredentialReferencesNative() throws Exception {
        String uri = "content://com.vstab.test.documents/private-key";
        context.getSharedPreferences("last-profile", Context.MODE_PRIVATE).edit()
                .putString("host", "example.test").putString("port", "2222").putString("user", "developer")
                .putBoolean("use-key", true).putString("key-uri", uri).putString("folder", "~/project")
                .putString("password", "discard-this-password").putString("passphrase", "discard-this-passphrase").commit();
        SavedHostStore store = new SavedHostStore(context);
        assertEquals(1, store.list().size());
        SavedHostStore.Host host = store.list().get(0);
        assertEquals(Uri.parse(uri), host.key);
        assertEquals("~/project", host.folder);
        String persisted = context.getSharedPreferences("saved-ssh-hosts", Context.MODE_PRIVATE).getString("metadata", "");
        assertFalse(persisted.contains("discard-this"));
        assertFalse(store.config.readConfig().content.contains("discard-this"));
        String browser = host.publicMetadata(false).toString();
        assertFalse(browser.contains(uri));
        assertFalse(browser.contains("keyUri"));
        assertFalse(browser.contains("discard-this"));
        assertEquals("key", host.publicMetadata(false).getString("authMethod"));
    }

    @Test public void editsKeepIdentityAndRecentFoldersWithoutRememberingSecrets() throws Exception {
        SavedHostStore store = new SavedHostStore(context);
        SavedHostStore.Host first = store.save(null, "Work", "example.test", 22, "developer", "", false, null);
        store.markConnected(first.id, "/home/developer/project");
        store.markConnected(first.id, "/home/developer/other");
        store.markConnected(first.id, "/home/developer/project");
        SavedHostStore.Host edited = store.save(first.id, "Work", "example.test", 2222, "developer", "~", false, null);
        assertEquals(first.id, edited.id);
        assertEquals(2, edited.recentFolders.size());
        assertEquals("/home/developer/project", edited.recentFolders.get(0));
        assertNotNull(store.findEndpoint("EXAMPLE.TEST", 2222, "developer"));
        assertTrue(store.remove(first.id));
        assertNull(store.find(first.id));
        try {
            store.save(null, "Bad", "example.test", 22, "developer", "relative/path", false, null);
            fail("Relative remote directories must be rejected.");
        } catch (IllegalArgumentException expected) { }
        try {
            store.save(null, "Bad key", "example.test", 22, "developer", "", true, Uri.parse("file:///private/key"));
            fail("Private keys must come through Android document grants.");
        } catch (IllegalArgumentException expected) { }
    }
}
