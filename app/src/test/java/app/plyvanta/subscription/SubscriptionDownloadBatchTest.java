package app.plyvanta.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import app.plyvanta.playback.ResolvedVideo;

public final class SubscriptionDownloadBatchTest {
    private static final String CHANNEL = "UCaaaaaaaaaaaaaaaaaaaaaa";
    private static final String VIDEO_A = "video000001";
    private static final String VIDEO_B = "video000002";

    @Test
    public void savesPendingVideoAndMarksCompletionOnlyAfterDownload() {
        FakeStore store = new FakeStore(video(VIDEO_A));
        FakeDownloader downloader = new FakeDownloader();
        List<String> resolved = new ArrayList<>();

        SubscriptionDownloadBatch.Result result = batch(
                store,
                video -> {
                    resolved.add(video.getVideoId());
                    return resolved(video.getVideoId());
                },
                downloader,
                () -> true
        ).run(() -> false);

        assertEquals(1, result.getCompletedCount());
        assertTrue(result.getFailedVideoIds().isEmpty());
        assertFalse(result.isCancelled());
        assertEquals(List.of(VIDEO_A), resolved);
        assertEquals(List.of(VIDEO_A), downloader.downloaded);
        assertTrue(store.completed.contains(VIDEO_A));
        assertEquals(100L, store.lastCompletionCutoff);
    }

    @Test
    public void previouslyCommittedVideoIsMarkedWithoutResolverOrAnotherDownload() {
        FakeStore store = new FakeStore(video(VIDEO_A));
        FakeDownloader downloader = new FakeDownloader();
        downloader.committed.add(VIDEO_A);

        SubscriptionDownloadBatch.Result result = batch(
                store,
                video -> {
                    throw new AssertionError("Already saved videos must not resolve streams.");
                },
                downloader,
                () -> true
        ).run(() -> false);

        assertEquals(1, result.getCompletedCount());
        assertTrue(downloader.downloaded.isEmpty());
        assertTrue(store.completed.contains(VIDEO_A));
    }

    @Test
    public void markerFailureRetriesExistingCommitWithoutDownloadingAgain() {
        FakeStore store = new FakeStore(video(VIDEO_A));
        store.failCompletion = true;
        FakeDownloader downloader = new FakeDownloader();
        SubscriptionDownloadBatch batch = batch(
                store,
                video -> resolved(video.getVideoId()),
                downloader,
                () -> true
        );

        SubscriptionDownloadBatch.Result first = batch.run(() -> false);
        assertEquals(List.of(VIDEO_A), first.getFailedVideoIds());
        assertEquals(0, first.getCompletedCount());
        assertTrue(downloader.committed.contains(VIDEO_A));
        store.failCompletion = false;
        SubscriptionDownloadBatch.Result second = batch.run(() -> false);

        assertEquals(1, second.getCompletedCount());
        assertEquals(List.of(VIDEO_A), downloader.downloaded);
        assertTrue(store.completed.contains(VIDEO_A));
    }

    @Test
    public void oneResolverFailureDoesNotPreventOtherPendingVideos() {
        FakeStore store = new FakeStore(video(VIDEO_A), video(VIDEO_B));
        FakeDownloader downloader = new FakeDownloader();

        SubscriptionDownloadBatch.Result result = batch(
                store,
                video -> {
                    if (VIDEO_A.equals(video.getVideoId())) {
                        throw new IOException("unsupported source");
                    }
                    return resolved(video.getVideoId());
                },
                downloader,
                () -> true
        ).run(() -> false);

        assertEquals(List.of(VIDEO_A), result.getFailedVideoIds());
        assertEquals(1, result.getCompletedCount());
        assertEquals(List.of(VIDEO_B), downloader.downloaded);
        assertFalse(store.completed.contains(VIDEO_A));
    }

    @Test
    public void duplicateSnapshotEntriesNeverDownloadTwice() {
        FakeStore store = new FakeStore(video(VIDEO_A), video(VIDEO_A));
        FakeDownloader downloader = new FakeDownloader();

        SubscriptionDownloadBatch.Result result = batch(
                store,
                video -> resolved(video.getVideoId()),
                downloader,
                () -> true
        ).run(() -> false);

        assertEquals(1, result.getCompletedCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(List.of(VIDEO_A), downloader.downloaded);
    }

    @Test
    public void ineligibleDeviceNeverReadsQueueOrTouchesStreams() {
        FakeStore store = new FakeStore(video(VIDEO_A));
        FakeDownloader downloader = new FakeDownloader();

        SubscriptionDownloadBatch.Result result = batch(
                store,
                video -> resolved(video.getVideoId()),
                downloader,
                () -> false
        ).run(() -> false);

        assertTrue(result.isIneligible());
        assertEquals(0, store.snapshotReads);
        assertTrue(downloader.downloaded.isEmpty());
    }

    @Test
    public void failedQueueSnapshotAvoidsAllMediaWork() {
        FakeStore store = new FakeStore(video(VIDEO_A));
        store.failSnapshot = true;
        FakeDownloader downloader = new FakeDownloader();

        SubscriptionDownloadBatch.Result result = batch(
                store,
                video -> {
                    throw new AssertionError("A failed queue cannot resolve media.");
                },
                downloader,
                () -> true
        ).run(() -> false);

        assertTrue(result.isQueueUnavailable());
        assertEquals(0, result.getCompletedCount());
        assertTrue(downloader.downloaded.isEmpty());
    }

    @Test
    public void disablingAutoDownloadDuringResolutionPreventsDownloadAndCompletion() {
        FakeStore store = new FakeStore(video(VIDEO_A));
        FakeDownloader downloader = new FakeDownloader();

        SubscriptionDownloadBatch.Result result = batch(
                store,
                video -> {
                    store.subscriptions.put(CHANNEL, subscription(100L, false));
                    return resolved(video.getVideoId());
                },
                downloader,
                () -> true
        ).run(() -> false);

        assertEquals(0, result.getCompletedCount());
        assertEquals(1, result.getSkippedCount());
        assertTrue(downloader.downloaded.isEmpty());
        assertTrue(store.completed.isEmpty());
    }

    @Test
    public void resubscribeDuringResolutionRejectsOldGeneration() {
        FakeStore store = new FakeStore(video(VIDEO_A));
        FakeDownloader downloader = new FakeDownloader();

        SubscriptionDownloadBatch.Result result = batch(
                store,
                video -> {
                    store.subscriptions.put(CHANNEL, subscription(101L, true));
                    return resolved(video.getVideoId());
                },
                downloader,
                () -> true
        ).run(() -> false);

        assertEquals(0, result.getCompletedCount());
        assertTrue(downloader.downloaded.isEmpty());
        assertTrue(store.completed.isEmpty());
    }

    @Test
    public void cancellationGuardDuringDownloadPreventsCompletionAndStopsBatch() {
        FakeStore store = new FakeStore(video(VIDEO_A), video(VIDEO_B));
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeDownloader downloader = new FakeDownloader() {
            @Override
            public void download(
                    SubscriptionDownloadBatch.Request request,
                    ResolvedVideo video,
                    BooleanSupplier operationGuard
            ) throws IOException {
                cancelled.set(true);
                assertFalse(operationGuard.getAsBoolean());
                throw new IOException("cancelled");
            }
        };

        SubscriptionDownloadBatch.Result result = batch(
                store,
                video -> resolved(video.getVideoId()),
                downloader,
                () -> true
        ).run(cancelled::get);

        assertTrue(result.isCancelled());
        assertEquals(0, result.getCompletedCount());
        assertTrue(result.getFailedVideoIds().isEmpty());
        assertTrue(store.completed.isEmpty());
    }

    @Test
    public void securityFailureDuringDownloadStopsWithoutCompletion() {
        FakeStore store = new FakeStore(video(VIDEO_A));
        AtomicBoolean eligible = new AtomicBoolean(true);
        FakeDownloader downloader = new FakeDownloader() {
            @Override
            public void download(
                    SubscriptionDownloadBatch.Request request,
                    ResolvedVideo video,
                    BooleanSupplier operationGuard
            ) throws IOException {
                eligible.set(false);
                assertFalse(operationGuard.getAsBoolean());
                throw new IOException("device no longer eligible");
            }
        };

        SubscriptionDownloadBatch.Result result = batch(
                store,
                video -> resolved(video.getVideoId()),
                downloader,
                eligible::get
        ).run(() -> false);

        assertTrue(result.isIneligible());
        assertEquals(0, result.getCompletedCount());
        assertTrue(store.completed.isEmpty());
    }

    @Test
    public void mismatchedResolvedVideoCannotBeDownloadedOrMarked() {
        FakeStore store = new FakeStore(video(VIDEO_A));
        FakeDownloader downloader = new FakeDownloader();

        SubscriptionDownloadBatch.Result result = batch(
                store,
                video -> resolved(VIDEO_B),
                downloader,
                () -> true
        ).run(() -> false);

        assertEquals(List.of(VIDEO_A), result.getFailedVideoIds());
        assertTrue(downloader.downloaded.isEmpty());
        assertTrue(store.completed.isEmpty());
    }

    private static SubscriptionDownloadBatch batch(
            FakeStore store,
            SubscriptionDownloadBatch.Resolver resolver,
            FakeDownloader downloader,
            BooleanSupplier eligible
    ) {
        return new SubscriptionDownloadBatch(store, resolver, downloader, eligible);
    }

    private static ChannelSubscription subscription(long cutoff, boolean autoDownload) {
        return new ChannelSubscription(CHANNEL, "Channel", cutoff, autoDownload);
    }

    private static SubscriptionVideo video(String videoId) {
        return new SubscriptionVideo(CHANNEL, videoId, "Video", 150L);
    }

    private static ResolvedVideo resolved(String videoId) {
        return new ResolvedVideo(
                videoId,
                "Video",
                "Channel",
                60L,
                null,
                ResolvedVideo.SourceType.PROGRESSIVE,
                "https://media.googlevideo.com/video",
                "video/mp4",
                null,
                null,
                720
        );
    }

    private static class FakeDownloader implements SubscriptionDownloadBatch.Downloader {
        private final Set<String> committed = new HashSet<>();
        private final List<String> downloaded = new ArrayList<>();

        @Override
        public boolean isAlreadyDownloaded(SubscriptionDownloadBatch.Request request) {
            return committed.contains(request.getVideo().getVideoId());
        }

        @Override
        public void download(
                SubscriptionDownloadBatch.Request request,
                ResolvedVideo video,
                BooleanSupplier operationGuard
        ) throws IOException {
            assertTrue(operationGuard.getAsBoolean());
            downloaded.add(video.getVideoId());
            committed.add(video.getVideoId());
        }
    }

    private static final class FakeStore implements SubscriptionDownloadBatch.QueueStore {
        private final Map<String, ChannelSubscription> subscriptions = new LinkedHashMap<>();
        private final List<SubscriptionVideo> pending = new ArrayList<>();
        private final Set<String> completed = new HashSet<>();
        private boolean failSnapshot;
        private boolean failCompletion;
        private int snapshotReads;
        private long lastCompletionCutoff;

        private FakeStore(SubscriptionVideo... videos) {
            subscriptions.put(CHANNEL, subscription(100L, true));
            pending.addAll(List.of(videos));
        }

        @Override
        public List<ChannelSubscription> getSubscriptions() {
            snapshotReads++;
            return new ArrayList<>(subscriptions.values());
        }

        @Override
        public List<SubscriptionVideo> getPendingDownloads() throws IOException {
            snapshotReads++;
            if (failSnapshot) {
                throw new IOException("queue unreadable");
            }
            List<SubscriptionVideo> result = new ArrayList<>();
            for (SubscriptionVideo video : pending) {
                if (!completed.contains(video.getVideoId())) {
                    result.add(video);
                }
            }
            return result;
        }

        @Override
        public boolean isDownloadPending(String videoId, long expectedSubscribedAtMs) {
            ChannelSubscription current = subscriptions.get(CHANNEL);
            return current != null
                    && current.isAutoDownload()
                    && current.getSubscribedAtMs() == expectedSubscribedAtMs
                    && !completed.contains(videoId)
                    && pending.stream().anyMatch(video -> video.getVideoId().equals(videoId));
        }

        @Override
        public boolean markDownloaded(String videoId, long expectedSubscribedAtMs)
                throws IOException {
            if (failCompletion) {
                throw new IOException("marker not persisted");
            }
            if (!isDownloadPending(videoId, expectedSubscribedAtMs)) {
                return false;
            }
            lastCompletionCutoff = expectedSubscribedAtMs;
            completed.add(videoId);
            return true;
        }
    }
}
