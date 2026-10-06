package app.plyvanta.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SubscriptionRefreshCoordinatorTest {
    private static final String CHANNEL_A = "UCaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CHANNEL_B = "UCbbbbbbbbbbbbbbbbbbbbbb";
    private static final String CHANNEL_C = "UCcccccccccccccccccccccc";

    @Test
    public void acceptsOnlyThisChannelsVideosPublishedAfterSubscriptionAndAlreadyReleased() {
        FakeStore store = new FakeStore(subscription(CHANNEL_A, 100L));
        SubscriptionVideo released = video(CHANNEL_A, "video000003", 101L);
        SubscriptionVideo justReleased = video(CHANNEL_A, "video000004", 300L);
        SubscriptionRefreshCoordinator coordinator = coordinator(
                channelId -> Arrays.asList(
                        video(CHANNEL_A, "video000001", 99L),
                        video(CHANNEL_A, "video000002", 100L),
                        released,
                        justReleased,
                        video(CHANNEL_A, "video000005", 301L),
                        video(CHANNEL_B, "video000006", 200L),
                        null
                ),
                store,
                () -> 300L
        );

        SubscriptionRefreshCoordinator.Result result = coordinator.refresh();

        assertEquals(List.of(released, justReleased), store.getVideos());
        assertEquals(1, result.getSuccessfulChannelCount());
        assertEquals(2, result.getAddedVideoCount());
        assertTrue(result.getFailedChannelIds().isEmpty());
        assertFalse(result.isCancelled());
        assertEquals(100L, store.merges.get(0).expectedSubscribedAtMs);
        assertEquals(300L, store.merges.get(0).checkedAtMs);
    }

    @Test
    public void failedChannelPreservesFeedAndOtherChannelsStillRefresh() {
        FakeStore store = new FakeStore(
                subscription(CHANNEL_A, 100L),
                subscription(CHANNEL_B, 100L)
        );
        SubscriptionVideo retained = video(CHANNEL_A, "video000001", 110L);
        SubscriptionVideo newVideo = video(CHANNEL_B, "video000002", 200L);
        store.videos.put(retained.getVideoId(), retained);
        SubscriptionRefreshCoordinator coordinator = coordinator(channelId -> {
            if (CHANNEL_A.equals(channelId)) {
                throw new IOException("offline");
            }
            return List.of(newVideo);
        }, store, () -> 300L);

        SubscriptionRefreshCoordinator.Result result = coordinator.refresh();

        assertEquals(List.of(CHANNEL_A), result.getFailedChannelIds());
        assertEquals(1, result.getSuccessfulChannelCount());
        assertEquals(1, result.getAddedVideoCount());
        assertEquals(List.of(retained, newVideo), store.getVideos());
        assertFalse(result.isCancelled());
    }

    @Test
    public void failedStoreMergeDoesNotPreventFollowingChannelRefresh() {
        FakeStore store = new FakeStore(
                subscription(CHANNEL_A, 100L),
                subscription(CHANNEL_B, 100L),
                subscription(CHANNEL_C, 100L)
        );
        store.failedMergeChannel = CHANNEL_B;
        Map<String, SubscriptionVideo> fetched = Map.of(
                CHANNEL_A, video(CHANNEL_A, "video000001", 150L),
                CHANNEL_B, video(CHANNEL_B, "video000002", 150L),
                CHANNEL_C, video(CHANNEL_C, "video000003", 150L)
        );

        SubscriptionRefreshCoordinator.Result result = coordinator(
                channelId -> List.of(fetched.get(channelId)),
                store,
                () -> 300L
        ).refresh();

        assertEquals(List.of(CHANNEL_B), result.getFailedChannelIds());
        assertEquals(2, result.getSuccessfulChannelCount());
        assertEquals(2, result.getAddedVideoCount());
        assertEquals(List.of(fetched.get(CHANNEL_A), fetched.get(CHANNEL_C)), store.getVideos());
    }

    @Test
    public void keepsOriginalCutoffWhenLaterRefreshFindsPreviouslyMissingVideo() {
        FakeStore store = new FakeStore(subscription(CHANNEL_A, 100L));
        AtomicInteger checks = new AtomicInteger();
        AtomicLong now = new AtomicLong(300L);
        SubscriptionVideo delayedFeedEntry = video(CHANNEL_A, "video000001", 120L);
        SubscriptionRefreshCoordinator coordinator = coordinator(
                channelId -> checks.getAndIncrement() == 0
                        ? List.of()
                        : List.of(delayedFeedEntry),
                store,
                now::get
        );

        assertEquals(0, coordinator.refresh().getAddedVideoCount());
        now.set(500L);
        assertEquals(1, coordinator.refresh().getAddedVideoCount());

        assertEquals(List.of(delayedFeedEntry), store.getVideos());
        assertEquals(100L, store.merges.get(0).expectedSubscribedAtMs);
        assertEquals(100L, store.merges.get(1).expectedSubscribedAtMs);
    }

    @Test
    public void nullFeedFailsOneChannelAndRetainsPreviouslyStoredVideos() {
        FakeStore store = new FakeStore(
                subscription(CHANNEL_A, 100L),
                subscription(CHANNEL_B, 100L)
        );
        SubscriptionVideo retained = video(CHANNEL_A, "video000001", 110L);
        store.videos.put(retained.getVideoId(), retained);

        SubscriptionRefreshCoordinator.Result result = coordinator(
                channelId -> CHANNEL_A.equals(channelId) ? null : List.of(),
                store,
                () -> 300L
        ).refresh();

        assertEquals(List.of(CHANNEL_A), result.getFailedChannelIds());
        assertEquals(1, result.getSuccessfulChannelCount());
        assertEquals(List.of(retained), store.getVideos());
    }

    @Test
    public void cancellationAfterFetchAvoidsMergeAndFurtherNetworkRequests() {
        FakeStore store = new FakeStore(
                subscription(CHANNEL_A, 100L),
                subscription(CHANNEL_B, 100L)
        );
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger fetchCalls = new AtomicInteger();
        SubscriptionRefreshCoordinator coordinator = coordinator(channelId -> {
            fetchCalls.incrementAndGet();
            cancelled.set(true);
            return List.of(video(channelId, "video000001", 150L));
        }, store, () -> 300L);

        SubscriptionRefreshCoordinator.Result result = coordinator.refresh(cancelled::get);

        assertTrue(result.isCancelled());
        assertEquals(1, fetchCalls.get());
        assertEquals(0, result.getSuccessfulChannelCount());
        assertTrue(store.merges.isEmpty());
        assertTrue(store.getVideos().isEmpty());
    }

    @Test
    public void cancellationBeforeRefreshAvoidsReadingStoreOrFetching() {
        FakeStore store = new FakeStore(subscription(CHANNEL_A, 100L));
        AtomicInteger fetchCalls = new AtomicInteger();

        SubscriptionRefreshCoordinator.Result result = coordinator(channelId -> {
            fetchCalls.incrementAndGet();
            return List.of();
        }, store, () -> 300L).refresh(() -> true);

        assertTrue(result.isCancelled());
        assertEquals(0, store.subscriptionReads);
        assertEquals(0, fetchCalls.get());
    }

    @Test
    public void threadInterruptionStopsRefreshWithoutPersistingFetchedVideos() {
        FakeStore store = new FakeStore(subscription(CHANNEL_A, 100L));
        try {
            SubscriptionRefreshCoordinator.Result result = coordinator(channelId -> {
                Thread.currentThread().interrupt();
                return List.of(video(CHANNEL_A, "video000001", 150L));
            }, store, () -> 300L).refresh();

            assertTrue(result.isCancelled());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(store.merges.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void unsubscribeDuringFetchCannotRestoreChannelOrItsFeed() {
        FakeStore store = new FakeStore(subscription(CHANNEL_A, 100L));

        SubscriptionRefreshCoordinator.Result result = coordinator(channelId -> {
            store.subscriptions.remove(channelId);
            return List.of(video(CHANNEL_A, "video000001", 150L));
        }, store, () -> 300L).refresh();

        assertEquals(0, result.getAddedVideoCount());
        assertTrue(store.subscriptions.isEmpty());
        assertTrue(store.getVideos().isEmpty());
        assertEquals(100L, store.merges.get(0).expectedSubscribedAtMs);
    }

    @Test
    public void resubscribeDuringFetchRejectsResultsFromPreviousSubscription() {
        FakeStore store = new FakeStore(subscription(CHANNEL_A, 100L));

        SubscriptionRefreshCoordinator.Result result = coordinator(channelId -> {
            store.subscriptions.put(channelId, subscription(channelId, 200L));
            return List.of(video(CHANNEL_A, "video000001", 150L));
        }, store, () -> 300L).refresh();

        assertEquals(0, result.getAddedVideoCount());
        assertEquals(200L, store.subscriptions.get(CHANNEL_A).getSubscribedAtMs());
        assertTrue(store.getVideos().isEmpty());
        assertEquals(100L, store.merges.get(0).expectedSubscribedAtMs);
    }

    private static SubscriptionRefreshCoordinator coordinator(
            SubscriptionRefreshCoordinator.FeedSource source,
            FakeStore store,
            SubscriptionRefreshCoordinator.Clock clock
    ) {
        return new SubscriptionRefreshCoordinator(source, store, clock);
    }

    private static ChannelSubscription subscription(String channelId, long subscribedAtMs) {
        return new ChannelSubscription(channelId, "Channel", subscribedAtMs, false);
    }

    private static SubscriptionVideo video(String channelId, String videoId, long publishedAtMs) {
        return new SubscriptionVideo(channelId, videoId, "Video", publishedAtMs);
    }

    private static final class FakeStore implements SubscriptionRefreshCoordinator.Store {
        private final Map<String, ChannelSubscription> subscriptions = new LinkedHashMap<>();
        private final Map<String, SubscriptionVideo> videos = new LinkedHashMap<>();
        private final List<Merge> merges = new ArrayList<>();
        private String failedMergeChannel;
        private int subscriptionReads;

        private FakeStore(ChannelSubscription... subscriptions) {
            for (ChannelSubscription subscription : subscriptions) {
                this.subscriptions.put(subscription.getChannelId(), subscription);
            }
        }

        @Override
        public List<ChannelSubscription> getSubscriptions() {
            subscriptionReads++;
            return new ArrayList<>(subscriptions.values());
        }

        @Override
        public int mergeFeed(
                String channelId,
                long expectedSubscribedAtMs,
                List<SubscriptionVideo> newVideos,
                long checkedAtMs
        ) throws IOException {
            merges.add(new Merge(expectedSubscribedAtMs, checkedAtMs));
            if (channelId.equals(failedMergeChannel)) {
                throw new IOException("storage unavailable");
            }
            ChannelSubscription subscription = subscriptions.get(channelId);
            if (subscription == null || subscription.getSubscribedAtMs() != expectedSubscribedAtMs) {
                return 0;
            }
            int inserted = 0;
            for (SubscriptionVideo video : newVideos) {
                if (!videos.containsKey(video.getVideoId())) {
                    videos.put(video.getVideoId(), video);
                    inserted++;
                }
            }
            return inserted;
        }

        private List<SubscriptionVideo> getVideos() {
            return new ArrayList<>(videos.values());
        }
    }

    private static final class Merge {
        private final long expectedSubscribedAtMs;
        private final long checkedAtMs;

        private Merge(long expectedSubscribedAtMs, long checkedAtMs) {
            this.expectedSubscribedAtMs = expectedSubscribedAtMs;
            this.checkedAtMs = checkedAtMs;
        }
    }
}
