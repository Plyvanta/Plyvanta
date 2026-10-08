package app.plyvanta.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import app.plyvanta.network.AppNetwork;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.HttpUrl;
import okhttp3.Response;

/**
 * Opt-in live update check. Pass -e verifyRealGitHub true with a running Orbot instance.
 * Optional arguments: useTor (default true), orbotPort, installedVersionCode,
 * installedVersionName, installedPackageName, and expectedUpdateVersion.
 */
@RunWith(AndroidJUnit4.class)
public final class RealGitHubUpdateInstrumentedTest {
    private static final String TAG = "PlyvantaLiveUpdate";

    @Test
    public void verifyRealGitHubUpdateCheck() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue("Live GitHub test requires explicit opt-in",
                "true".equalsIgnoreCase(arguments.getString("verifyRealGitHub")));
        boolean useTor = Boolean.parseBoolean(arguments.getString("useTor", "true"));
        int port = Integer.parseInt(arguments.getString("orbotPort", "9050"));
        long installedVersionCode = Long.parseLong(
                arguments.getString("installedVersionCode", "1005000"));
        String installedVersionName = arguments.getString("installedVersionName", "1.5.0");
        String installedPackageName = arguments.getString("installedPackageName", "app.plyvanta");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        try (NetworkSettingSnapshot ignored = new NetworkSettingSnapshot(context)) {
            AppNetwork.setTorEnabled(useTor, port);
            AtomicInteger requestCount = new AtomicInteger();
            Call.Factory productionCalls = AppNetwork.calls(AppNetwork.Profile.UPDATE);
            Call.Factory tracedCalls = request -> {
                int requestNumber = requestCount.incrementAndGet();
                Call call = productionCalls.newCall(request);
                call.addEventListener(new UpdateTrace(requestNumber, request.url()));
                return call;
            };
            // Use production routing and endpoints; the wrapper adds diagnostic events only.
            GitHubReleaseClient client = new GitHubReleaseClient(
                    tracedCalls,
                    HttpUrl.get(GitHubReleaseClient.REPOSITORY_API_URL),
                    HttpUrl.get(GitHubReleaseClient.RELEASES_API_URL),
                    useTor ? 60_000L : 30_000L);
            long startedAt = SystemClock.elapsedRealtime();
            Log.i(TAG, "Starting real GitHub check: route=" + (useTor ? "Tor" : "direct")
                    + ", port=" + port + ", installed=" + installedVersionName
                    + ", sdk=" + Build.VERSION.SDK_INT);
            try {
                UpdateRelease available = client.fetchLatestUpdate(
                        installedVersionCode,
                        installedVersionName,
                        installedPackageName,
                        UpdateChannel.STABLE,
                        Build.VERSION.SDK_INT);
                Log.i(TAG, "Real GitHub check completed in "
                        + (SystemClock.elapsedRealtime() - startedAt) + " ms after "
                        + requestCount.get() + " requests; available="
                        + (available == null ? "none" : available.getVersionName()));
                String expectedUpdateVersion = arguments.getString("expectedUpdateVersion");
                if (expectedUpdateVersion == null) {
                    assertNull("Installed stable release should be up to date", available);
                } else {
                    assertTrue("Expected a newer verified release", available != null);
                    assertEquals(expectedUpdateVersion, available.getVersionName());
                }
            } catch (IOException | RuntimeException | AssertionError failure) {
                Log.e(TAG, "Real GitHub check failed after "
                        + (SystemClock.elapsedRealtime() - startedAt) + " ms and "
                        + requestCount.get() + " requests", failure);
                throw failure;
            }
        }
    }

    private static final class UpdateTrace extends EventListener {
        private final int requestNumber;
        private final String target;
        private final long startedAt = SystemClock.elapsedRealtime();

        UpdateTrace(int requestNumber, HttpUrl url) {
            this.requestNumber = requestNumber;
            target = url.host() + url.encodedPath();
        }

        private void stage(String message) {
            Log.i(TAG, "Request " + requestNumber + " " + target + " +"
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms: " + message);
        }

        @Override
        public void callStart(Call call) {
            stage("started");
        }

        @Override
        public void dnsStart(Call call, String domainName) {
            stage("DNS " + domainName);
        }

        @Override
        public void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
            stage("connect " + address + " via " + proxy.type());
        }

        @Override
        public void secureConnectStart(Call call) {
            stage("TLS started");
        }

        @Override
        public void secureConnectEnd(Call call, Handshake handshake) {
            stage("TLS completed");
        }

        @Override
        public void responseHeadersEnd(Call call, Response response) {
            // Omit redirect queries, which can contain signed asset download parameters.
            stage("HTTP " + response.code() + " from "
                    + response.request().url().host() + response.request().url().encodedPath());
        }

        @Override
        public void responseBodyEnd(Call call, long byteCount) {
            stage("read " + byteCount + " bytes");
        }

        @Override
        public void callEnd(Call call) {
            stage("finished");
        }

        @Override
        public void callFailed(Call call, IOException failure) {
            Log.e(TAG, "Request " + requestNumber + " " + target + " failed after "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms", failure);
        }
    }

    /** Restores only the two settings this check changes, including initially absent keys. */
    private static final class NetworkSettingSnapshot implements AutoCloseable {
        private static final String ENABLED_KEY = "tor_enabled";
        private static final String PORT_KEY = "tor_socks_port";

        private final SharedPreferences preferences;
        private final Map<String, ?> originalValues;
        private final boolean originalEnabled;
        private final int originalPort;

        NetworkSettingSnapshot(Context context) {
            assertTrue("Live update instrumentation must target the separate debug app",
                    context.getPackageName().endsWith(".debug"));
            AppNetwork.initialize(context);
            preferences = context.getSharedPreferences("plyvanta_network", Context.MODE_PRIVATE);
            originalValues = preferences.getAll();
            originalEnabled = AppNetwork.isTorEnabled();
            originalPort = AppNetwork.torPort();
        }

        @Override
        public void close() {
            try {
                AppNetwork.setTorEnabled(originalEnabled, originalPort);
            } finally {
                SharedPreferences.Editor editor = preferences.edit();
                restore(editor, ENABLED_KEY);
                restore(editor, PORT_KEY);
                assertTrue("Could not restore original network preferences", editor.commit());
            }
        }

        private void restore(SharedPreferences.Editor editor, String key) {
            if (!originalValues.containsKey(key)) {
                editor.remove(key);
                return;
            }
            Object value = originalValues.get(key);
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
    }
}
