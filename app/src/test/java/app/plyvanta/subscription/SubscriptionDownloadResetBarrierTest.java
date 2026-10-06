package app.plyvanta.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SubscriptionDownloadResetBarrierTest {
    @Test
    public void anotherBatchCannotEnterWhileResetActionOwnsExclusionLock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch resetEntered = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        Future<?> reset = null;
        Future<?> second = null;
        try {
            reset = executor.submit(() -> {
                BackgroundSubscriptionDownloadWorker.runWithDownloadsPaused(() -> {
                    resetEntered.countDown();
                    assertTrue(releaseReset.await(5, TimeUnit.SECONDS));
                });
                return null;
            });
            assertTrue(resetEntered.await(5, TimeUnit.SECONDS));
            second = executor.submit(() -> {
                secondAttempted.countDown();
                BackgroundSubscriptionDownloadWorker.runWithDownloadsPaused(
                        secondEntered::countDown
                );
                return null;
            });
            assertTrue(secondAttempted.await(5, TimeUnit.SECONDS));
            assertFalse(secondEntered.await(250, TimeUnit.MILLISECONDS));

            releaseReset.countDown();
            reset.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(0L, secondEntered.getCount());
        } finally {
            releaseReset.countDown();
            if (reset != null) {
                reset.cancel(true);
            }
            if (second != null) {
                second.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    @Test
    public void failingResetStillReleasesLockForWorkOnAnotherThread() throws Exception {
        try {
            BackgroundSubscriptionDownloadWorker.runWithDownloadsPaused(() -> {
                throw new IOException("reset failed");
            });
            fail("The reset failure must reach the caller.");
        } catch (IOException expected) {
            assertEquals("reset failed", expected.getMessage());
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean nextActionRan = new AtomicBoolean();
        try {
            executor.submit(() -> {
                BackgroundSubscriptionDownloadWorker.runWithDownloadsPaused(
                        () -> nextActionRan.set(true)
                );
                return null;
            }).get(5, TimeUnit.SECONDS);
            assertTrue(nextActionRan.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
