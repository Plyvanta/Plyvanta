package app.plyvanta.playback;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the production media source factory, including its choice of data source. */
@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class OnlineMediaSchemeInstrumentedTest {
    @Test
    public void onlineMediaRejectsUdpInsteadOfOpeningAnUnproxiedSocket() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<ExoPlayer> player = new AtomicReference<>();
        AtomicReference<PlaybackException> failure = new AtomicReference<>();
        CountDownLatch rejected = new CountDownLatch(1);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ExoPlayer created = new ExoPlayer.Builder(context).build();
                player.set(created);
                created.addListener(new Player.Listener() {
                    @Override
                    public void onPlayerError(PlaybackException error) {
                        failure.set(error);
                        rejected.countDown();
                    }
                });
                ResolvedVideo unsupported = new ResolvedVideo(
                        "fixture0001", "Unsupported online scheme", "Fixture", 1L, null,
                        ResolvedVideo.SourceType.PROGRESSIVE,
                        "udp://127.0.0.1:0", MimeTypes.VIDEO_MP4, null, null, 360);
                created.setMediaSource(new PlaybackSourceFactory(context).create(unsupported));
                created.prepare();
            });
            assertTrue("Online media tried to use a UDP data source instead of rejecting it",
                    rejected.await(5L, TimeUnit.SECONDS));
            Throwable cause = failure.get();
            HttpDataSource.HttpDataSourceException malformedUrl = null;
            while (cause != null) {
                if (cause instanceof HttpDataSource.HttpDataSourceException) {
                    malformedUrl = (HttpDataSource.HttpDataSourceException) cause;
                    break;
                }
                cause = cause.getCause();
            }
            assertNotNull("The HTTP stack must reject unsupported network URL schemes",
                    malformedUrl);
            assertEquals("Malformed URL", malformedUrl.getMessage());
            assertEquals(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                    malformedUrl.reason);
            assertEquals(HttpDataSource.HttpDataSourceException.TYPE_OPEN, malformedUrl.type);
            assertEquals("udp", malformedUrl.dataSpec.uri.getScheme());
        } finally {
            ExoPlayer created = player.get();
            if (created != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(created::release);
            }
        }
    }
}
