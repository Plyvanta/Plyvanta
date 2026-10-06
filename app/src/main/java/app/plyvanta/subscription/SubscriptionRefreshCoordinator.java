package app.plyvanta.subscription;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Refreshes channel metadata without resolving or downloading any media. */
public final class SubscriptionRefreshCoordinator {
    public interface FeedSource {
        List<SubscriptionVideo> fetch(String channelId) throws IOException;
    }

    public interface Store {
        List<ChannelSubscription> getSubscriptions();

        int mergeFeed(
                String channelId,
                long expectedSubscribedAtMs,
                List<SubscriptionVideo> videos,
                long checkedAtMs
        ) throws IOException;
    }

    public interface Clock {
        long currentTimeMillis();
    }

    public static final class Result {
        private final int successfulChannelCount;
        private final int addedVideoCount;
        private final List<String> failedChannelIds;
        private final boolean cancelled;

        private Result(
                int successfulChannelCount,
                int addedVideoCount,
                List<String> failedChannelIds,
                boolean cancelled
        ) {
            this.successfulChannelCount = successfulChannelCount;
            this.addedVideoCount = addedVideoCount;
            this.failedChannelIds = Collections.unmodifiableList(
                    new ArrayList<>(failedChannelIds)
            );
            this.cancelled = cancelled;
        }

        public int getSuccessfulChannelCount() {
            return successfulChannelCount;
        }

        public int getAddedVideoCount() {
            return addedVideoCount;
        }

        public List<String> getFailedChannelIds() {
            return failedChannelIds;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private final FeedSource feedSource;
    private final Store store;
    private final Clock clock;

    public SubscriptionRefreshCoordinator(
            SubscriptionStore store,
            YouTubeChannelFeedClient feedClient
    ) {
        this(
                Objects.requireNonNull(feedClient, "feedClient")::fetch,
                adaptStore(store),
                System::currentTimeMillis
        );
    }

    public SubscriptionRefreshCoordinator(FeedSource feedSource, Store store, Clock clock) {
        this.feedSource = Objects.requireNonNull(feedSource, "feedSource");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Call off the main thread. Failed channels leave their previously stored videos intact. */
    public Result refresh() {
        return refresh(() -> false);
    }

    public Result refresh(BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        int successfulChannels = 0;
        int addedVideos = 0;
        List<String> failedChannels = new ArrayList<>();
        if (isCancelled(cancelled)) {
            return new Result(0, 0, failedChannels, true);
        }

        // Each captured cutoff identifies this subscription incarnation. The store must reject
        // this merge if the channel was unsubscribed or subscribed again while its feed loaded.
        List<ChannelSubscription> subscriptions = new ArrayList<>(store.getSubscriptions());
        for (ChannelSubscription subscription : subscriptions) {
            if (isCancelled(cancelled)) {
                return new Result(successfulChannels, addedVideos, failedChannels, true);
            }
            try {
                List<SubscriptionVideo> fetched = feedSource.fetch(subscription.getChannelId());
                if (isCancelled(cancelled)) {
                    return new Result(successfulChannels, addedVideos, failedChannels, true);
                }
                if (fetched == null) {
                    throw new IOException("Channel feed is unavailable.");
                }
                long checkedAtMs = clock.currentTimeMillis();
                List<SubscriptionVideo> newPublications = new ArrayList<>();
                for (SubscriptionVideo video : fetched) {
                    if (video != null
                            && subscription.getChannelId().equals(video.getChannelId())
                            && video.getPublishedAtMs() > subscription.getSubscribedAtMs()
                            && video.getPublishedAtMs() <= checkedAtMs) {
                        newPublications.add(video);
                    }
                }
                if (isCancelled(cancelled)) {
                    return new Result(successfulChannels, addedVideos, failedChannels, true);
                }
                addedVideos += store.mergeFeed(
                        subscription.getChannelId(),
                        subscription.getSubscribedAtMs(),
                        newPublications,
                        checkedAtMs
                );
                successfulChannels++;
            } catch (IOException | RuntimeException channelFailure) {
                if (isCancelled(cancelled)) {
                    return new Result(successfulChannels, addedVideos, failedChannels, true);
                }
                failedChannels.add(subscription.getChannelId());
            }
        }
        return new Result(
                successfulChannels,
                addedVideos,
                failedChannels,
                isCancelled(cancelled)
        );
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || cancelled.getAsBoolean();
    }

    private static Store adaptStore(SubscriptionStore subscriptionStore) {
        Objects.requireNonNull(subscriptionStore, "store");
        return new Store() {
            @Override
            public List<ChannelSubscription> getSubscriptions() {
                return subscriptionStore.getSubscriptions();
            }

            @Override
            public int mergeFeed(
                    String channelId,
                    long expectedSubscribedAtMs,
                    List<SubscriptionVideo> videos,
                    long checkedAtMs
            ) throws IOException {
                return subscriptionStore.mergeFeed(
                        channelId,
                        expectedSubscribedAtMs,
                        videos,
                        checkedAtMs
                );
            }
        };
    }
}
