package app.plyvanta.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SubscriptionStoreTest {
    private static final String CHANNEL_A = "UC1234567890123456789012";
    private static final String CHANNEL_B = "UCabcdefghijklmnopqrstuv";
    private static final String VIDEO_A = "abcdefghijk";
    private static final String VIDEO_B = "lmnopqrstuv";
    private static final String VIDEO_C = "wxyzABCDEFG";
    private static final String VIDEO_D = "HIJKLMNOPQR";
    private static final long CUTOFF = 1_700_000_000_000L;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void cutoffIsStrictAndScheduledVideoCanBeDiscoveredLater() throws Exception {
        SubscriptionStore store = newStore("boundary");
        store.subscribe(CHANNEL_A, "A channel", CUTOFF, false);
        List<SubscriptionVideo> response = List.of(
                video(CHANNEL_A, VIDEO_A, CUTOFF - 1),
                video(CHANNEL_A, VIDEO_B, CUTOFF),
                video(CHANNEL_A, VIDEO_C, CUTOFF + 1),
                video(CHANNEL_A, VIDEO_D, CUTOFF + 100)
        );

        assertEquals(1, store.mergeFeed(CHANNEL_A, CUTOFF, response, CUTOFF + 99));
        assertEquals(List.of(VIDEO_C), ids(store.getFeed()));
        assertEquals(1, store.mergeFeed(CHANNEL_A, CUTOFF, response, CUTOFF + 100));
        assertEquals(List.of(VIDEO_D, VIDEO_C), ids(store.getFeed()));
    }

    @Test
    public void duplicatesAndOtherChannelItemsAreIgnoredWithNewestFirstAcrossChannels()
            throws Exception {
        SubscriptionStore store = newStore("dedup");
        store.subscribe(CHANNEL_A, "A channel", CUTOFF, false);
        store.subscribe(CHANNEL_B, "B channel", CUTOFF, false);
        SubscriptionVideo first = video(CHANNEL_A, VIDEO_A, CUTOFF + 1);
        assertEquals(2, store.mergeFeed(CHANNEL_A, CUTOFF, List.of(
                first,
                first,
                video(CHANNEL_A, VIDEO_B, CUTOFF + 3),
                video(CHANNEL_B, VIDEO_C, CUTOFF + 2)
        ), CUTOFF + 4));
        assertEquals(1, store.mergeFeed(CHANNEL_B, CUTOFF, List.of(
                video(CHANNEL_B, VIDEO_C, CUTOFF + 2),
                video(CHANNEL_B, VIDEO_A, CUTOFF + 4)
        ), CUTOFF + 4));
        assertEquals(List.of(VIDEO_B, VIDEO_C, VIDEO_A), ids(store.getFeed()));
        assertEquals(0, store.mergeFeed(CHANNEL_A, CUTOFF, List.of(first), CUTOFF + 5));
        assertEquals(0, store.mergeFeed(CHANNEL_A, CUTOFF, List.of(), CUTOFF + 6));
        assertEquals(3, store.getFeed().size());
    }

    @Test
    public void repeatedSubscribePreservesCutoffAndOfflineChoice() throws Exception {
        SubscriptionStore store = newStore("idempotent");
        ChannelSubscription original = store.subscribe(CHANNEL_A, "Original", CUTOFF, true);
        ChannelSubscription repeated = store.subscribe(CHANNEL_A, "Changed", CUTOFF + 100, false);
        assertEquals(original, repeated);
        assertEquals(List.of(original), store.getSubscriptions());
        assertEquals(1, store.mergeFeed(CHANNEL_A, CUTOFF,
                List.of(video(CHANNEL_A, VIDEO_A, CUTOFF + 1)), CUTOFF + 100));
    }

    @Test
    public void subscriptionsFeedAndDownloadCompletionSurviveRestart() throws Exception {
        Path root = newRoot("restart");
        SubscriptionStore store = new SubscriptionStore(root);
        ChannelSubscription subscription = store.subscribe(CHANNEL_A, "A \"quoted\" channel", CUTOFF, true);
        SubscriptionVideo video = new SubscriptionVideo(CHANNEL_A, VIDEO_A, "An upload ☀", CUTOFF + 1);
        store.mergeFeed(CHANNEL_A, CUTOFF, List.of(video), CUTOFF + 2);
        store.markDownloaded(VIDEO_A);

        SubscriptionStore restarted = new SubscriptionStore(root);
        assertEquals(List.of(subscription), restarted.getSubscriptions());
        assertEquals(List.of(video), restarted.getFeed());
        assertTrue(restarted.getPendingDownloads().isEmpty());
        assertTrue(restarted.isAutoDownloadEnabled(CHANNEL_A));
        String catalog = new String(Files.readAllBytes(root.resolve("catalog.json")), StandardCharsets.UTF_8);
        assertFalse(catalog.contains("https://"));
        assertFalse(catalog.contains("googlevideo"));
        assertFalse(catalog.contains("plyvanta-vault"));
    }

    @Test
    public void toggleQueuesUndownloadedUploadsAndCompletionIsNeverResetByRefresh()
            throws Exception {
        Path root = newRoot("toggle");
        SubscriptionStore store = new SubscriptionStore(root);
        store.subscribe(CHANNEL_A, "A channel", CUTOFF, false);
        List<SubscriptionVideo> uploads = List.of(
                video(CHANNEL_A, VIDEO_A, CUTOFF + 1),
                video(CHANNEL_A, VIDEO_B, CUTOFF + 2)
        );
        store.mergeFeed(CHANNEL_A, CUTOFF, uploads, CUTOFF + 3);
        assertTrue(store.getPendingDownloads().isEmpty());
        store.setAutoDownload(CHANNEL_A, true);
        assertEquals(List.of(VIDEO_B, VIDEO_A), ids(store.getPendingDownloads()));
        store.markDownloaded(VIDEO_B);
        store.setAutoDownload(CHANNEL_A, false);
        assertTrue(store.getPendingDownloads().isEmpty());
        store.setAutoDownload(CHANNEL_A, true);
        assertEquals(List.of(VIDEO_A), ids(store.getPendingDownloads()));

        SubscriptionStore restarted = new SubscriptionStore(root);
        restarted.mergeFeed(CHANNEL_A, CUTOFF, uploads, CUTOFF + 4);
        assertEquals(List.of(VIDEO_A), ids(restarted.getPendingDownloads()));
        assertEquals(CUTOFF, restarted.getSubscriptions().get(0).getSubscribedAtMs());
    }

    @Test
    public void unsubscribeClearsFeedAndRejectsInflightRefreshAfterSameMillisecondResubscribe()
            throws Exception {
        Path root = newRoot("race");
        SubscriptionStore store = new SubscriptionStore(root);
        store.subscribe(CHANNEL_A, "A channel", CUTOFF, true);
        store.subscribe(CHANNEL_B, "B channel", CUTOFF, true);
        store.mergeFeed(CHANNEL_A, CUTOFF,
                List.of(video(CHANNEL_A, VIDEO_A, CUTOFF + 1)), CUTOFF + 2);
        store.mergeFeed(CHANNEL_B, CUTOFF,
                List.of(video(CHANNEL_B, VIDEO_B, CUTOFF + 1)), CUTOFF + 2);
        store.unsubscribe(CHANNEL_A);
        assertEquals(List.of(VIDEO_B), ids(store.getFeed()));
        assertFalse(store.isAutoDownloadEnabled(CHANNEL_A));
        assertEquals(0, store.mergeFeed(CHANNEL_A, CUTOFF,
                List.of(video(CHANNEL_A, VIDEO_C, CUTOFF + 2)), CUTOFF + 3));

        SubscriptionStore restarted = new SubscriptionStore(root);
        ChannelSubscription resubscribed = restarted.subscribe(CHANNEL_A, "A channel", CUTOFF, true);
        assertEquals(CUTOFF + 1, resubscribed.getSubscribedAtMs());
        assertEquals(0, restarted.mergeFeed(CHANNEL_A, CUTOFF,
                List.of(video(CHANNEL_A, VIDEO_C, CUTOFF + 2)), CUTOFF + 3));
        assertEquals(1, restarted.mergeFeed(CHANNEL_A, CUTOFF + 1,
                List.of(video(CHANNEL_A, VIDEO_C, CUTOFF + 2)), CUTOFF + 3));
    }

    @Test
    public void concurrentChannelsMergeWithoutLosingCommittedUploads() throws Exception {
        Path root = newRoot("concurrent");
        SubscriptionStore store = new SubscriptionStore(root);
        store.subscribe(CHANNEL_A, "A channel", CUTOFF, false);
        store.subscribe(CHANNEL_B, "B channel", CUTOFF, false);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable first = mergeThread(store, CHANNEL_A, VIDEO_A, start, finished, failure);
        Runnable second = mergeThread(store, CHANNEL_B, VIDEO_B, start, finished, failure);
        Thread firstThread = new Thread(first);
        Thread secondThread = new Thread(second);
        firstThread.start();
        secondThread.start();
        start.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(null, failure.get());
        assertEquals(2, new SubscriptionStore(root).getFeed().size());
    }

    @Test
    public void queuedDownloadGuardChecksCutoffToggleAndCompletion() throws Exception {
        SubscriptionStore store = newStore("download-guard");
        store.subscribe(CHANNEL_A, "A channel", CUTOFF, true);
        store.mergeFeed(CHANNEL_A, CUTOFF,
                List.of(video(CHANNEL_A, VIDEO_A, CUTOFF + 1)), CUTOFF + 2);
        assertTrue(store.isDownloadPending(VIDEO_A, CUTOFF));
        assertFalse(store.isDownloadPending(VIDEO_A, CUTOFF + 1));
        assertFalse(store.markDownloaded(VIDEO_A, CUTOFF + 1));
        store.setAutoDownload(CHANNEL_A, false);
        assertFalse(store.isDownloadPending(VIDEO_A, CUTOFF));
        assertFalse(store.markDownloaded(VIDEO_A, CUTOFF));
        store.setAutoDownload(CHANNEL_A, true);
        assertTrue(store.markDownloaded(VIDEO_A, CUTOFF));
        assertFalse(store.isDownloadPending(VIDEO_A, CUTOFF));
        assertFalse(store.markDownloaded(VIDEO_A, CUTOFF));
        assertFalse(store.isDownloadPending(VIDEO_B, CUTOFF));
    }

    @Test
    public void failedAtomicWriteLeavesPreviousInMemoryStateUntouched() throws Exception {
        Path root = newRoot("failed-write");
        SubscriptionStore store = new SubscriptionStore(root);
        store.subscribe(CHANNEL_A, "A channel", CUTOFF, false);
        Path catalog = root.resolve("catalog.json");
        byte[] savedCatalog = Files.readAllBytes(catalog);
        Files.delete(catalog);
        Files.createDirectory(catalog);
        Files.write(catalog.resolve("blocker"), new byte[] { 1 });

        assertThrows(IOException.class, () -> store.setAutoDownload(CHANNEL_A, true));
        assertFalse(store.isAutoDownloadEnabled(CHANNEL_A));
        Files.delete(catalog.resolve("blocker"));
        Files.delete(catalog);
        Files.write(catalog, savedCatalog);
        assertFalse(new SubscriptionStore(root).isAutoDownloadEnabled(CHANNEL_A));
    }

    @Test
    public void returnedListsAreImmutableSnapshots() throws Exception {
        SubscriptionStore store = newStore("snapshots");
        store.subscribe(CHANNEL_A, "A channel", CUTOFF, true);
        store.mergeFeed(CHANNEL_A, CUTOFF,
                List.of(video(CHANNEL_A, VIDEO_A, CUTOFF + 1)), CUTOFF + 2);
        List<ChannelSubscription> subscriptions = store.getSubscriptions();
        List<SubscriptionVideo> feed = store.getFeed();
        assertThrows(UnsupportedOperationException.class, subscriptions::clear);
        assertThrows(UnsupportedOperationException.class, feed::clear);
        assertThrows(UnsupportedOperationException.class, () -> store.getPendingDownloads().clear());
        store.unsubscribe(CHANNEL_A);
        assertEquals(1, subscriptions.size());
        assertEquals(1, feed.size());
    }

    @Test
    public void invalidModelsAndCorruptCatalogAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new ChannelSubscription("https://youtube.com/channel/" + CHANNEL_A, "A", CUTOFF, false));
        assertThrows(IllegalArgumentException.class,
                () -> new SubscriptionVideo(CHANNEL_A, "../secret", "A", CUTOFF));
        assertThrows(IllegalArgumentException.class,
                () -> new SubscriptionVideo(CHANNEL_A, VIDEO_A, "A", -1));
        Path root = newRoot("corrupt");
        Files.write(root.resolve("catalog.json"), "broken JSON".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> new SubscriptionStore(root));
        assertEquals("https://www.youtube.com/channel/" + CHANNEL_A,
                new ChannelSubscription(CHANNEL_A, "A", CUTOFF, false).getChannelUrl());
        assertEquals("https://www.youtube.com/watch?v=" + VIDEO_A,
                video(CHANNEL_A, VIDEO_A, CUTOFF).getVideoUrl());
    }

    private static Runnable mergeThread(
            SubscriptionStore store,
            String channelId,
            String videoId,
            CountDownLatch start,
            CountDownLatch finished,
            AtomicReference<Throwable> failure
    ) {
        return () -> {
            try {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Merge was not started.");
                }
                store.mergeFeed(channelId, CUTOFF,
                        List.of(video(channelId, videoId, CUTOFF + 1)), CUTOFF + 2);
            } catch (Throwable exception) {
                failure.compareAndSet(null, exception);
            } finally {
                finished.countDown();
            }
        };
    }

    private SubscriptionStore newStore(String name) throws IOException {
        return new SubscriptionStore(newRoot(name));
    }

    private Path newRoot(String name) throws IOException {
        return temporaryFolder.newFolder(name).toPath();
    }

    private static SubscriptionVideo video(String channelId, String videoId, long publishedAtMs) {
        return new SubscriptionVideo(channelId, videoId, "Upload " + videoId, publishedAtMs);
    }

    private static List<String> ids(List<SubscriptionVideo> videos) {
        return videos.stream().map(SubscriptionVideo::getVideoId).toList();
    }
}
