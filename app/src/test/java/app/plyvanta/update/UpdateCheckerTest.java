package app.plyvanta.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class UpdateCheckerTest {
    private static final long INSTALLED_VERSION_CODE = 4L;
    private static final String INSTALLED_VERSION_NAME = "9.0.0-debug.4";
    private static final String PACKAGE_NAME = "app.plyvanta.debug";
    private static final int DEVICE_SDK = 36;
    private static final String SHA256 =
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

    @Test
    public void compatibleReleaseIsStoredAndReturned() {
        UpdateRelease fetchedRelease = release(5L);
        FakeReleaseStore store = new FakeReleaseStore(null, true);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> {
                    assertEquals(INSTALLED_VERSION_CODE, installedVersionCode);
                    assertEquals(INSTALLED_VERSION_NAME, installedVersionName);
                    assertEquals(PACKAGE_NAME, packageName);
                    assertSame(UpdateChannel.PREVIEW, channel);
                    assertEquals(DEVICE_SDK, deviceSdk);
                    return fetchedRelease;
                },
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertSame(fetchedRelease, result.getCheckedRelease());
        assertSame(fetchedRelease, result.getAvailableRelease());
        assertSame(fetchedRelease, store.release);
        assertEquals(1, store.storeCalls);
        assertTrue(notificationCancelled.get());
    }

    @Test
    public void upToDateHasNoCheckedOrAvailableRelease() {
        FakeReleaseStore store = new FakeReleaseStore(null, true);
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> null,
                store,
                new AtomicBoolean()
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertNull(result.getCheckedRelease());
        assertNull(result.getAvailableRelease());
        assertEquals(0, store.storeCalls);
    }

    @Test
    public void successfulEmptyFetchRetiresStoredReleaseAndNotification() {
        UpdateRelease storedRelease = release(5L);
        FakeReleaseStore store = new FakeReleaseStore(storedRelease, true);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> null,
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertNull(result.getCheckedRelease());
        assertNull(result.getAvailableRelease());
        assertNull(store.release);
        assertEquals(1, store.clearCalls);
        assertEquals(0, store.storeCalls);
        assertTrue(notificationCancelled.get());
    }

    @Test
    public void ioFailureIsRetryableAndPreservesStoredRelease() {
        UpdateRelease storedRelease = release(5L);
        FakeReleaseStore store = new FakeReleaseStore(storedRelease, true);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> {
                    throw new IOException("offline");
                },
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.RETRYABLE_FAILURE, result.getStatus());
        assertSame(UpdateChecker.FailureReason.CONNECTION, result.getFailureReason());
        assertNull(result.getCheckedRelease());
        assertSame(storedRelease, result.getAvailableRelease());
        assertSame(storedRelease, store.release);
        assertEquals(0, store.clearCalls);
        assertEquals(0, store.storeCalls);
        assertFalse(notificationCancelled.get());
    }

    @Test
    public void socketTimeoutHasSpecificFeedbackAndPreservesStoredRelease() {
        assertFailurePreservesStoredRelease(
                new SocketTimeoutException("GitHub did not respond"),
                UpdateChecker.FailureReason.TIMEOUT
        );
    }

    @Test
    public void forbiddenAndRateLimitedResponsesHaveSpecificFeedback() {
        for (int statusCode : new int[] {403, 429}) {
            assertFailurePreservesStoredRelease(
                    new GitHubReleaseClient.HttpException(statusCode),
                    UpdateChecker.FailureReason.RATE_LIMIT
            );
        }
    }

    @Test
    public void otherHttpFailureIsNotReportedAsRateLimited() {
        assertFailurePreservesStoredRelease(
                new GitHubReleaseClient.HttpException(503),
                UpdateChecker.FailureReason.CONNECTION
        );
    }

    @Test
    public void outdatedStoredReleaseIsRetiredBeforeFetchFailure() {
        UpdateRelease storedRelease = release(INSTALLED_VERSION_CODE);
        FakeReleaseStore store = new FakeReleaseStore(storedRelease, true);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> {
                    throw new IOException("offline");
                },
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.RETRYABLE_FAILURE, result.getStatus());
        assertNull(result.getCheckedRelease());
        assertNull(result.getAvailableRelease());
        assertNull(store.release);
        assertEquals(1, store.clearCalls);
        assertEquals(0, store.storeCalls);
        assertTrue(notificationCancelled.get());
    }

    @Test
    public void unverifiedReleasePreservesStoredReleaseWithoutReplacingIt() {
        UpdateRelease storedRelease = release(5L);
        FakeReleaseStore store = new FakeReleaseStore(storedRelease, true);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> {
                    throw new GitHubReleaseClient.UnverifiedReleaseException(
                            "unverified release"
                    );
                },
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.UNVERIFIED_RELEASE, result.getStatus());
        assertNull(result.getCheckedRelease());
        assertSame(storedRelease, result.getAvailableRelease());
        assertSame(storedRelease, store.release);
        assertEquals(0, store.clearCalls);
        assertEquals(0, store.storeCalls);
        assertFalse(notificationCancelled.get());
    }

    @Test
    public void missingInstalledPackageIsPermanentFailure() {
        AtomicInteger fetchCalls = new AtomicInteger();
        FakeReleaseStore store = new FakeReleaseStore(null, true);
        UpdateChecker checker = checker(
                () -> {
                    throw new UpdateChecker.InstalledAppUnavailableException(
                            new IllegalStateException("missing package")
                    );
                },
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> {
                    fetchCalls.incrementAndGet();
                    return release(5L);
                },
                store,
                new AtomicBoolean()
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.PERMANENT_FAILURE, result.getStatus());
        assertNull(result.getCheckedRelease());
        assertNull(result.getAvailableRelease());
        assertEquals(0, fetchCalls.get());
        assertEquals(0, store.readCalls);
    }

    @Test
    public void successfulFetchReplacesHigherStoredReleaseAndCancelsNotification() {
        UpdateRelease storedRelease = release(7L);
        UpdateRelease fetchedRelease = release(6L);
        FakeReleaseStore store = new FakeReleaseStore(storedRelease, true);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> fetchedRelease,
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertSame(fetchedRelease, result.getCheckedRelease());
        assertSame(fetchedRelease, result.getAvailableRelease());
        assertSame(fetchedRelease, store.release);
        assertEquals(1, store.storeCalls);
        assertTrue(notificationCancelled.get());
    }

    @Test
    public void exactStoredReleaseNeedsNoRewriteOrNotificationCancellation() {
        UpdateRelease release = release(5L);
        FakeReleaseStore store = new FakeReleaseStore(release, true);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> release,
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertSame(release, result.getCheckedRelease());
        assertSame(release, result.getAvailableRelease());
        assertSame(release, store.release);
        assertEquals(0, store.storeCalls);
        assertFalse(notificationCancelled.get());
    }

    @Test
    public void canonicalCoordinateRefreshKeepsSameVersionNotification() {
        UpdateRelease storedRelease = release(5L, "culpen90/Plyvanta");
        UpdateRelease fetchedRelease = release(5L, "Plyvanta/Plyvanta");
        FakeReleaseStore store = new FakeReleaseStore(storedRelease, true);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> fetchedRelease,
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertSame(fetchedRelease, result.getCheckedRelease());
        assertSame(fetchedRelease, result.getAvailableRelease());
        assertSame(fetchedRelease, store.release);
        assertEquals(1, store.storeCalls);
        assertFalse(notificationCancelled.get());
    }

    @Test
    public void staleStoredReleaseIsClearedAndNotificationCancelled() {
        FakeReleaseStore store = new FakeReleaseStore(
                release(INSTALLED_VERSION_CODE),
                true
        );
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> null,
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.SUCCESS, result.getStatus());
        assertNull(result.getCheckedRelease());
        assertNull(result.getAvailableRelease());
        assertNull(store.release);
        assertEquals(1, store.clearCalls);
        assertTrue(notificationCancelled.get());
    }

    @Test
    public void storeFailureIsRetryableAndDoesNotExposeUnpersistedRelease() {
        UpdateRelease fetchedRelease = release(5L);
        FakeReleaseStore store = new FakeReleaseStore(null, false);
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> fetchedRelease,
                store,
                new AtomicBoolean()
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.RETRYABLE_FAILURE, result.getStatus());
        assertSame(UpdateChecker.FailureReason.STORAGE, result.getFailureReason());
        assertSame(fetchedRelease, result.getCheckedRelease());
        assertNull(result.getAvailableRelease());
        assertNull(store.release);
        assertEquals(1, store.storeCalls);
    }

    @Test
    public void replacementStoreFailurePreservesStoredReleaseAndNotification() {
        UpdateRelease storedRelease = release(7L);
        UpdateRelease fetchedRelease = release(6L);
        FakeReleaseStore store = new FakeReleaseStore(storedRelease, false);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> fetchedRelease,
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.RETRYABLE_FAILURE, result.getStatus());
        assertSame(UpdateChecker.FailureReason.STORAGE, result.getFailureReason());
        assertSame(fetchedRelease, result.getCheckedRelease());
        assertSame(storedRelease, result.getAvailableRelease());
        assertSame(storedRelease, store.release);
        assertEquals(0, store.clearCalls);
        assertEquals(1, store.storeCalls);
        assertFalse(notificationCancelled.get());
    }

    @Test
    public void manualCheckReturnsPromptlyWithoutSideEffectsDuringWorkerCompletion()
            throws Exception {
        CountDownLatch workerCompletionEntered = new CountDownLatch(1);
        CountDownLatch releaseWorkerCompletion = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<UpdateChecker.Result> workerFuture = null;
        Future<UpdateChecker.Result> manualFuture = null;
        UpdateChecker workerChecker = checker(
                installedAppSource(),
                (code, version, packageName, channel, sdk) -> null,
                new FakeReleaseStore(null, true),
                new AtomicBoolean()
        );
        UpdateRelease storedRelease = release(5L);
        FakeReleaseStore manualStore = new FakeReleaseStore(storedRelease, true);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        AtomicInteger installedAppReads = new AtomicInteger();
        AtomicInteger fetchCalls = new AtomicInteger();
        UpdateChecker manualChecker = checker(
                () -> {
                    installedAppReads.incrementAndGet();
                    return installedAppSource().load();
                },
                (code, version, packageName, channel, sdk) -> {
                    fetchCalls.incrementAndGet();
                    return storedRelease;
                },
                manualStore,
                notificationCancelled
        );

        try {
            workerFuture = executor.submit(() -> workerChecker.checkAndComplete(result -> {
                workerCompletionEntered.countDown();
                awaitLatch(releaseWorkerCompletion);
                return result;
            }));
            assertTrue(workerCompletionEntered.await(5, TimeUnit.SECONDS));

            manualFuture = executor.submit(manualChecker::checkManually);
            // The worker still owns the lock; waiting for it would time out here.
            UpdateChecker.Result busy = manualFuture.get(1, TimeUnit.SECONDS);
            assertSame(UpdateChecker.Status.RETRYABLE_FAILURE, busy.getStatus());
            assertSame(UpdateChecker.FailureReason.CHECK_IN_PROGRESS, busy.getFailureReason());
            assertNull(busy.getCheckedRelease());
            assertNull(busy.getAvailableRelease());
            assertEquals(0, installedAppReads.get());
            assertEquals(0, fetchCalls.get());
            assertEquals(0, manualStore.readCalls);
            assertEquals(0, manualStore.clearCalls);
            assertEquals(0, manualStore.storeCalls);
            assertSame(storedRelease, manualStore.release);
            assertFalse(notificationCancelled.get());

            releaseWorkerCompletion.countDown();
            assertSame(UpdateChecker.Status.SUCCESS,
                    workerFuture.get(5, TimeUnit.SECONDS).getStatus());
            UpdateChecker.Result retried = manualChecker.checkManually();
            assertSame(UpdateChecker.Status.SUCCESS, retried.getStatus());
            assertSame(UpdateChecker.FailureReason.NONE, retried.getFailureReason());
            assertSame(storedRelease, retried.getAvailableRelease());
            assertEquals(1, installedAppReads.get());
            assertEquals(1, fetchCalls.get());
            assertEquals(1, manualStore.readCalls);
            assertEquals(0, manualStore.clearCalls);
            assertEquals(0, manualStore.storeCalls);
            assertFalse(notificationCancelled.get());
        } finally {
            releaseWorkerCompletion.countDown();
            if (workerFuture != null) {
                workerFuture.cancel(true);
            }
            if (manualFuture != null) {
                manualFuture.cancel(true);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void completionKeepsSharedLockUntilTransactionFinishes() throws Exception {
        CountDownLatch firstCompletionEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCompletion = new CountDownLatch(1);
        CountDownLatch secondAttemptStarted = new CountDownLatch(1);
        CountDownLatch secondReleaseSourceEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<UpdateChecker.Result> firstFuture = null;
        Future<UpdateChecker.Result> secondFuture = null;

        UpdateChecker firstChecker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> null,
                new FakeReleaseStore(null, true),
                new AtomicBoolean()
        );
        UpdateChecker secondChecker = checker(
                installedAppSource(),
                (
                        installedVersionCode,
                        installedVersionName,
                        packageName,
                        channel,
                        deviceSdk
                ) -> {
                    secondReleaseSourceEntered.countDown();
                    return null;
                },
                new FakeReleaseStore(null, true),
                new AtomicBoolean()
        );

        try {
            firstFuture = executor.submit(() -> firstChecker.checkAndComplete(result -> {
                firstCompletionEntered.countDown();
                awaitLatch(releaseFirstCompletion);
                return result;
            }));
            assertTrue(firstCompletionEntered.await(5, TimeUnit.SECONDS));

            secondFuture = executor.submit(() -> {
                secondAttemptStarted.countDown();
                return secondChecker.check();
            });
            assertTrue(secondAttemptStarted.await(5, TimeUnit.SECONDS));
            assertFalse(
                    "Second release source entered while the first completion held the lock",
                    secondReleaseSourceEntered.await(500, TimeUnit.MILLISECONDS)
            );

            releaseFirstCompletion.countDown();
            assertSame(
                    UpdateChecker.Status.SUCCESS,
                    firstFuture.get(5, TimeUnit.SECONDS).getStatus()
            );
            assertTrue(secondReleaseSourceEntered.await(5, TimeUnit.SECONDS));
            assertSame(
                    UpdateChecker.Status.SUCCESS,
                    secondFuture.get(5, TimeUnit.SECONDS).getStatus()
            );
        } finally {
            releaseFirstCompletion.countDown();
            if (firstFuture != null) {
                firstFuture.cancel(true);
            }
            if (secondFuture != null) {
                secondFuture.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    private static void assertFailurePreservesStoredRelease(
            IOException failure,
            UpdateChecker.FailureReason expectedReason
    ) {
        UpdateRelease storedRelease = release(5L);
        FakeReleaseStore store = new FakeReleaseStore(storedRelease, true);
        AtomicBoolean notificationCancelled = new AtomicBoolean();
        UpdateChecker checker = checker(
                installedAppSource(),
                (code, version, packageName, channel, sdk) -> {
                    throw failure;
                },
                store,
                notificationCancelled
        );

        UpdateChecker.Result result = checker.check();

        assertSame(UpdateChecker.Status.RETRYABLE_FAILURE, result.getStatus());
        assertSame(expectedReason, result.getFailureReason());
        assertNull(result.getCheckedRelease());
        assertSame(storedRelease, result.getAvailableRelease());
        assertSame(storedRelease, store.release);
        assertEquals(0, store.clearCalls);
        assertEquals(0, store.storeCalls);
        assertFalse(notificationCancelled.get());
    }

    private static UpdateChecker checker(
            UpdateChecker.InstalledAppSource installedAppSource,
            UpdateChecker.ReleaseSource releaseSource,
            FakeReleaseStore store,
            AtomicBoolean notificationCancelled
    ) {
        return new UpdateChecker(
                installedAppSource,
                releaseSource,
                store,
                () -> notificationCancelled.set(true)
        );
    }

    private static UpdateChecker.InstalledAppSource installedAppSource() {
        return () -> new UpdateChecker.InstalledApp(
                INSTALLED_VERSION_CODE,
                INSTALLED_VERSION_NAME,
                PACKAGE_NAME,
                UpdateChannel.PREVIEW,
                DEVICE_SDK
        );
    }

    private static UpdateRelease release(long versionCode) {
        return release(versionCode, "Plyvanta/Plyvanta");
    }

    private static UpdateRelease release(long versionCode, String repository) {
        String versionName = "9.0.0-debug." + versionCode;
        return new UpdateRelease(
                versionCode,
                versionName,
                repository,
                "https://github.com/" + repository + "/releases/download/v"
                        + versionName + "/Plyvanta-" + versionName + ".apk",
                "https://github.com/" + repository + "/releases/tag/v" + versionName,
                SHA256
        );
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", interrupted);
        }
    }

    private static final class FakeReleaseStore implements UpdateChecker.ReleaseStore {
        private UpdateRelease release;
        private final boolean storeSucceeds;
        private int readCalls;
        private int clearCalls;
        private int storeCalls;

        private FakeReleaseStore(UpdateRelease release, boolean storeSucceeds) {
            this.release = release;
            this.storeSucceeds = storeSucceeds;
        }

        @Override
        public UpdateRelease availableRelease() {
            readCalls++;
            return release;
        }

        @Override
        public void clearAvailableRelease() {
            clearCalls++;
            release = null;
        }

        @Override
        public boolean storeAvailableRelease(UpdateRelease newRelease) {
            storeCalls++;
            if (storeSucceeds) {
                release = newRelease;
            }
            return storeSucceeds;
        }
    }
}
