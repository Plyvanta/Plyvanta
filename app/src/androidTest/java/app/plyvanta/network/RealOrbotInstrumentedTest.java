package app.plyvanta.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import okhttp3.Request;
import okhttp3.Response;

/** Opt-in live check: start Orbot, then pass -e verifyRealOrbot true to instrumentation. */
@RunWith(AndroidJUnit4.class)
public final class RealOrbotInstrumentedTest {
    @Test
    public void verifyRealOrbotExit() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue("Live Orbot test requires explicit opt-in and a running Orbot instance",
                "true".equalsIgnoreCase(arguments.getString("verifyRealOrbot")));
        int port = Integer.parseInt(arguments.getString("orbotPort", "9050"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (NetworkPreferenceSnapshot ignored = new NetworkPreferenceSnapshot(context)) {
            AppNetwork.setTorEnabled(true, port);
            assertTrue("Orbot did not produce a verified Tor exit", AppNetwork.checkTorConnection());

            // Exercise the same Call.Factory used by Media3, retaining HTTPS certificate checks.
            Request request = new Request.Builder()
                    .url("https://check.torproject.org/api/ip")
                    .build();
            try (Response response = AppNetwork.calls(AppNetwork.Profile.PLAYBACK)
                    .newCall(request).execute()) {
                assertEquals(200, response.code());
                assertNotNull(response.body());
                JSONObject result = new JSONObject(response.body().string());
                assertTrue("Playback networking did not reach the Tor network",
                        result.getBoolean("IsTor"));
                assertTrue("Tor exit API returned no public exit address",
                        !result.getString("IP").trim().isEmpty());
            }
        }
    }
}
