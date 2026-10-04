package app.plyvanta.network;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** One routing policy for every network request made by Plyvanta. */
public final class AppNetwork {
    public static final int DEFAULT_TOR_PORT = 9050;

    public enum Profile {
        EXTRACTOR,
        PLAYBACK,
        OFFLINE,
        SPONSOR,
        UPDATE
    }

    private static final String PREFERENCES_FILE = "plyvanta_network";
    private static final String KEY_TOR_ENABLED = "tor_enabled";
    private static final String KEY_TOR_PORT = "tor_socks_port";
    private static final int MAX_CHECK_RESPONSE_BYTES = 16 * 1024;
    private static final NetworkRouter ROUTER = new NetworkRouter();
    private static SharedPreferences preferences;
    private static final Set<Runnable> routeListeners = new CopyOnWriteArraySet<>();

    private AppNetwork() {
    }

    /** Restores the saved policy before allowing any app request to execute. */
    public static synchronized void initialize(Context context) {
        Objects.requireNonNull(context, "context");
        if (preferences != null) {
            return;
        }
        SharedPreferences saved = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE);
        boolean enabled;
        try {
            enabled = saved.getBoolean(KEY_TOR_ENABLED, false);
        } catch (ClassCastException corruptedSetting) {
            // An unreadable saved policy must not silently permit a direct connection.
            enabled = true;
        }
        int port;
        try {
            port = saved.getInt(KEY_TOR_PORT, DEFAULT_TOR_PORT);
        } catch (ClassCastException corruptedPort) {
            port = DEFAULT_TOR_PORT;
        }
        if (port < 1 || port > 65535) {
            port = DEFAULT_TOR_PORT;
        }
        ROUTER.configure(enabled, port, () -> {
        });
        preferences = saved;
    }

    /**
     * Saves the setting synchronously, cancels old requests and streaming responses, and replaces
     * their connection pools. Throws if the setting cannot be saved; the old policy then remains.
     */
    public static synchronized void setTorEnabled(boolean enabled, int port) {
        if (preferences == null) {
            throw new IllegalStateException("App networking is not initialized.");
        }
        ROUTER.configure(enabled, port, () -> {
            if (!preferences.edit()
                    .putBoolean(KEY_TOR_ENABLED, enabled)
                    .putInt(KEY_TOR_PORT, port)
                    .commit()) {
                throw new IllegalStateException("Could not save the network setting.");
            }
        });
        for (Runnable listener : routeListeners) {
            listener.run();
        }
    }

    /** Observers should post to their UI thread and unregister when destroyed. */
    public static void addRouteChangeListener(Runnable listener) {
        routeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public static void removeRouteChangeListener(Runnable listener) {
        routeListeners.remove(listener);
    }

    public static boolean isTorEnabled() {
        return ROUTER.isTorEnabled();
    }

    public static int torPort() {
        return ROUTER.torPort();
    }

    /** A stable factory which consults the routing policy for each new call. */
    public static Call.Factory calls(Profile profile) {
        return ROUTER.calls(profile);
    }

    /**
     * Checks the actual Tor exit over HTTPS through the current SOCKS route. This never checks
     * over a direct connection and never falls back if Orbot is unavailable.
     */
    public static boolean checkTorConnection() throws IOException {
        Request request = new Request.Builder()
                .url("https://check.torproject.org/api/ip")
                .header("Accept", "application/json")
                .header("User-Agent", "Plyvanta-Tor-Check/1")
                .build();
        Call call = ROUTER.newTorCheckCall(request);
        try (Response response = call.execute()) {
            if (response.code() != 200) {
                throw new IOException("Tor connection check returned HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Tor connection check returned no response.");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = body.byteStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > MAX_CHECK_RESPONSE_BYTES) {
                        throw new IOException("Tor connection check response is too large.");
                    }
                    bytes.write(buffer, 0, count);
                }
            }
            final Object isTor;
            try {
                isTor = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()))
                        .get("IsTor");
            } catch (JSONException invalidResponse) {
                throw new IOException("Tor connection check returned invalid JSON.",
                        invalidResponse);
            }
            if (!(isTor instanceof Boolean)) {
                throw new IOException("Tor connection check returned an invalid result.");
            }
            return (Boolean) isTor;
        }
    }
}
