package app.vstab;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Native-only metadata overlay. The real ~/.ssh/config is the host source. */
final class SavedHostStore {
    static final class Host {
        final SshConfigStore.Host configured;
        final String id, label, host, user, folder;
        final int port;
        final boolean useKey;
        final Uri key;
        final long lastConnected;
        final List<String> recentFolders;

        Host(SshConfigStore.Host configured, JSONObject overlay) throws Exception {
            this.configured = configured;
            this.id = configured.id; this.label = configured.alias; this.host = configured.hostname;
            this.port = configured.port; this.user = configured.user;
            this.folder = overlay.optString("folder", "");
            String uri = overlay.optString("keyUri", "");
            this.key = uri.isEmpty() ? null : Uri.parse(uri);
            this.useKey = key != null || !configured.identityPaths.isEmpty()
                    || "publickey".equals(configured.preferredAuthentications);
            this.lastConnected = overlay.optLong("lastConnected");
            this.recentFolders = new ArrayList<>();
            JSONArray recent = overlay.optJSONArray("recentFolders");
            if (recent != null) for (int at = 0; at < Math.min(12, recent.length()); at++) recentFolders.add(recent.getString(at));
        }

        JSONObject publicMetadata(boolean connected) throws Exception {
            JSONObject value = configured.publicMetadata();
            boolean allowsPassword = configured.preferredAuthentications.isEmpty()
                    || java.util.Arrays.asList(configured.preferredAuthentications.split(",")).contains("password");
            boolean allowsKey = configured.preferredAuthentications.isEmpty()
                    || java.util.Arrays.asList(configured.preferredAuthentications.split(",")).contains("publickey");
            return value.put("label", label).put("host", host).put("folder", folder)
                    .put("authMethod", allowsKey && useKey ? (allowsPassword ? "automatic" : "key") : "password")
                    .put("hasIdentity", key != null || !configured.identityPaths.isEmpty()).put("hasImportedKey", key != null)
                    .put("lastConnected", lastConnected).put("recentFolders", new JSONArray(recentFolders))
                    .put("connected", connected);
        }
    }

    private final SharedPreferences storage;
    final SshConfigStore config;

    SavedHostStore(Context context) throws Exception {
        storage = context.getSharedPreferences("saved-ssh-hosts", Context.MODE_PRIVATE);
        config = new SshConfigStore(context);
        if (!storage.getBoolean("config-migrated", false)) migrate(context.getSharedPreferences("last-profile", Context.MODE_PRIVATE));
    }

    synchronized List<Host> list() throws Exception {
        JSONObject metadata = metadata();
        List<Host> result = new ArrayList<>();
        for (SshConfigStore.Host configured : config.profiles()) result.add(new Host(configured, overlay(metadata, configured.id)));
        result.sort(Comparator.comparingLong((Host host) -> host.lastConnected).reversed()
                .thenComparing(host -> host.label.toLowerCase(Locale.ROOT)));
        return result;
    }

    synchronized Host find(String id) throws Exception {
        SshConfigStore.Host configured = config.find(id);
        return configured == null ? null : new Host(configured, overlay(metadata(), configured.id));
    }

    synchronized Host findEndpoint(String host, int port, String user) throws Exception {
        for (Host saved : list()) if (saved.port == port && saved.host.equalsIgnoreCase(host) && saved.user.equals(user)) return saved;
        return null;
    }

    synchronized Host save(String id, String alias, String host, int port, String user, String folder,
                           boolean useKey, Uri key) throws Exception {
        validateFolder(folder);
        if (useKey && key != null && !"content".equals(key.getScheme())) throw new IllegalArgumentException("Select a key through Android's document picker.");
        SshConfigStore.Host previous = id == null || id.isEmpty() ? null : config.find(id);
        String chosenAlias = alias.trim();
        if (chosenAlias.isEmpty()) chosenAlias = host.replaceAll("[^a-zA-Z0-9_.-]", "_");
        SshConfigStore.Host configured = config.saveHost(id, chosenAlias, host, port, user,
                previous == null ? new ArrayList<>() : previous.identityPaths,
                previous != null && previous.identitiesOnly, useKey ? "publickey" : "password");
        JSONObject metadata = metadata();
        JSONObject values = overlay(metadata, id == null ? configured.id : id);
        values.put("folder", folder).put("keyUri", useKey && key != null ? key.toString() : "");
        if (id != null && !id.equals(configured.id)) metadata.remove(id);
        metadata.put(configured.id, values); write(metadata);
        return new Host(configured, values);
    }

    synchronized boolean remove(String id) throws Exception {
        boolean removed = config.removeHost(id);
        if (removed) { JSONObject metadata = metadata(); metadata.remove(id); write(metadata); }
        return removed;
    }

    synchronized void markConnected(String id, String folder) throws Exception {
        SshConfigStore.Host configured = config.find(id);
        if (configured == null) return;
        validateFolder(folder);
        JSONObject metadata = metadata();
        JSONObject values = overlay(metadata, configured.id);
        List<String> recent = new Host(configured, values).recentFolders;
        if (folder.startsWith("/")) {
            recent.remove(folder); recent.add(0, folder);
            while (recent.size() > 12) recent.remove(recent.size() - 1);
        }
        values.put("folder", folder).put("recentFolders", new JSONArray(recent)).put("lastConnected", System.currentTimeMillis());
        metadata.put(configured.id, values); write(metadata);
    }

    synchronized void clearMigrationKey(String id) throws Exception {
        JSONObject metadata = metadata(); JSONObject values = overlay(metadata, id);
        values.remove("keyUri"); metadata.put(id, values); write(metadata);
    }

    private void migrate(SharedPreferences previous) throws Exception {
        String host = previous.getString("host", "").trim();
        String user = previous.getString("user", "").trim();
        if (!host.isEmpty() && !user.isEmpty() && config.profiles().isEmpty()) {
            int port = 22;
            try { port = Integer.parseInt(previous.getString("port", "22")); } catch (NumberFormatException ignored) { }
            String uri = previous.getString("key-uri", "");
            boolean key = previous.getBoolean("use-key", false);
            save(null, "", host, port, user, previous.getString("folder", ""), key, uri.isEmpty() ? null : Uri.parse(uri));
        }
        if (!storage.edit().putBoolean("config-migrated", true).commit()) throw new IOException("The saved SSH profile migration could not be recorded.");
    }

    private JSONObject metadata() throws IOException {
        try { return new JSONObject(storage.getString("metadata", "{}")); }
        catch (Exception error) { throw new IOException("Saved SSH metadata could not be read; existing data has not been replaced.", error); }
    }
    private static JSONObject overlay(JSONObject metadata, String id) {
        JSONObject values = metadata.optJSONObject(id);
        return values == null ? new JSONObject() : values;
    }
    private void write(JSONObject metadata) throws IOException {
        if (!storage.edit().putString("metadata", metadata.toString()).commit()) throw new IOException("SSH metadata could not be saved. Free device storage and retry.");
    }
    private static void validateFolder(String folder) {
        if (folder == null || folder.length() > 4096 || folder.indexOf('\0') >= 0
                || (!folder.isEmpty() && !folder.startsWith("/") && !folder.equals("~") && !folder.startsWith("~/")))
            throw new IllegalArgumentException("Use an absolute remote directory or ~/project.");
    }
}
