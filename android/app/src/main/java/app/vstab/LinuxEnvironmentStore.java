package app.vstab;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** The explicit on-device environment choice survives activity and process restarts. */
final class LinuxEnvironmentStore {
    static final String BUNDLED = "bundled";
    static final String TERMUX = "termux";
    private final SharedPreferences preferences;

    LinuxEnvironmentStore(Context context) {
        preferences = context.getSharedPreferences("linux-development-environment", Context.MODE_PRIVATE);
        String provider = provider(), legacy = preferences.getString("environment-id", "");
        if (!provider.isEmpty() && legacy.matches("[a-f0-9-]{36}")
                && !preferences.contains("environment-id-" + provider)) {
            if (!preferences.edit().putString("environment-id-" + provider, legacy).remove("environment-id").commit())
                throw new IllegalStateException("Cannot preserve the Linux environment identity.");
        }
    }

    String provider() {
        String value = preferences.getString("provider", "");
        return BUNDLED.equals(value) || TERMUX.equals(value) ? value : "";
    }

    synchronized String environmentId() {
        String provider = provider();
        if (provider.isEmpty()) throw new IllegalStateException("Choose a local environment first.");
        String key = "environment-id-" + provider;
        String id = preferences.getString(key, "");
        if (!id.matches("[a-f0-9-]{36}")) {
            id = UUID.randomUUID().toString();
            if (!preferences.edit().putString(key, id).commit())
                throw new IllegalStateException("Cannot save the Linux environment identity.");
        }
        return id;
    }

    void choose(String provider) {
        if (!BUNDLED.equals(provider) && !TERMUX.equals(provider))
            throw new IllegalArgumentException("Choose Termux or built-in Linux.");
        if (!preferences.edit().putString("provider", provider).commit())
            throw new IllegalStateException("Cannot save the development environment choice.");
    }
}
