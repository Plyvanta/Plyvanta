package app.plyvanta.network;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Restores only the network preferences, including initially absent entries. */
final class NetworkPreferenceSnapshot implements AutoCloseable {
    static final String PREFERENCES_NAME = "plyvanta_network";

    private final SharedPreferences preferences;
    private final Map<String, ?> originalValues;
    private final boolean originalEnabled;
    private final int originalPort;

    NetworkPreferenceSnapshot(Context context) {
        assertTrue("Tor instrumentation must target the separate debug app",
                context.getPackageName().endsWith(".debug"));
        AppNetwork.initialize(context);
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        originalValues = new HashMap<>(preferences.getAll());
        originalEnabled = AppNetwork.isTorEnabled();
        originalPort = AppNetwork.torPort();
    }

    @Override
    public void close() {
        AppNetwork.setTorEnabled(originalEnabled, originalPort);
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : originalValues.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Set<?>) {
                Set<String> strings = new HashSet<>();
                for (Object item : (Set<?>) value) {
                    strings.add((String) item);
                }
                editor.putStringSet(key, strings);
            } else {
                throw new AssertionError("Unsupported network preference type for " + key);
            }
        }
        assertTrue("Could not restore original network preferences", editor.commit());
    }
}
