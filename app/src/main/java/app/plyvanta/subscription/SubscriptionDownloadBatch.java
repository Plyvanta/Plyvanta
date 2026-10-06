package app.plyvanta.subscription;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import app.plyvanta.playback.ResolvedVideo;

/** Testable queue processing; it never persists resolved streams or accesses decrypted media. */
public final class SubscriptionDownloadBatch {
    public interface QueueStore {
        List<ChannelSubscription> getSubscriptions() throws IOException;

        List<SubscriptionVideo> getPendingDownloads() throws IOException;

        boolean isDownloadPending(String videoId, long expectedSubscribedAtMs);

        boolean markDownloaded(String videoId, long expectedSubscribedAtMs) throws IOException;
    }

    public interface Resolver {
        ResolvedVideo resolve(SubscriptionVideo video) throws Exception;
    }

    public interface Downloader {
        boolean isAlreadyDownloaded(Request request) throws Exception;

        void download(Request request, ResolvedVideo video, BooleanSupplier operationGuard)
                throws Exception;
    }

    public static final class Request {
        private final SubscriptionVideo video;
        private final long subscribedAtMs;

        private Request(SubscriptionVideo video, long subscribedAtMs) {
            this.video = video;
            this.subscribedAtMs = subscribedAtMs;
        }

        public SubscriptionVideo getVideo() {
            return video;
        }

        public long getSubscribedAtMs() {
            return subscribedAtMs;
        }
    }

    public static final class Result {
        private final int completedCount;
        private final int skippedCount;
        private final List<String> failedVideoIds;
        private final boolean cancelled;
        private final boolean ineligible;
        private final boolean queueUnavailable;

        private Result(
                int completedCount,
                int skippedCount,
                List<String> failedVideoIds,
                boolean cancelled,
                boolean ineligible,
                boolean queueUnavailable
        ) {
            this.completedCount = completedCount;
            this.skippedCount = skippedCount;
            this.failedVideoIds = Collections.unmodifiableList(new ArrayList<>(failedVideoIds));
            this.cancelled = cancelled;
            this.ineligible = ineligible;
            this.queueUnavailable = queueUnavailable;
        }

        public int getCompletedCount() {
            return completedCount;
        }

        public int getSkippedCount() {
            return skippedCount;
        }

        public List<String> getFailedVideoIds() {
            return failedVideoIds;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean isIneligible() {
            return ineligible;
        }

        public boolean isQueueUnavailable() {
            return queueUnavailable;
        }
    }

    private final QueueStore store;
    private final Resolver resolver;
    private final Downloader downloader;
    private final BooleanSupplier eligibility;

    public SubscriptionDownloadBatch(
            QueueStore store,
            Resolver resolver,
            Downloader downloader,
            BooleanSupplier eligibility
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
    }

    public Result run(BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        List<String> failed = new ArrayList<>();
        if (isCancelled(cancelled) || !isEligible()) {
            return result(0, 0, failed, cancelled, false);
        }

        final List<SubscriptionVideo> pending;
        Map<String, ChannelSubscription> subscriptions = new HashMap<>();
        try {
            for (ChannelSubscription subscription : store.getSubscriptions()) {
                subscriptions.put(subscription.getChannelId(), subscription);
            }
            pending = new ArrayList<>(store.getPendingDownloads());
        } catch (IOException | RuntimeException unavailableQueue) {
            return result(0, 0, failed, cancelled, true);
        }

        int completedCount = 0;
        int skippedCount = 0;
        Set<String> attemptedVideoIds = new HashSet<>();
        for (SubscriptionVideo video : pending) {
            if (isCancelled(cancelled) || !isEligible()) {
                return result(completedCount, skippedCount, failed, cancelled, false);
            }
            ChannelSubscription subscription = subscriptions.get(video.getChannelId());
            if (!attemptedVideoIds.add(video.getVideoId())
                    || subscription == null
                    || !subscription.isAutoDownload()
                    || video.getPublishedAtMs() <= subscription.getSubscribedAtMs()) {
                skippedCount++;
                continue;
            }
            Request request = new Request(video, subscription.getSubscribedAtMs());
            BooleanSupplier operationGuard = () -> isOperationAllowed(request, cancelled);
            try {
                if (!operationGuard.getAsBoolean()) {
                    skippedCount++;
                    continue;
                }
                if (!downloader.isAlreadyDownloaded(request)) {
                    if (!operationGuard.getAsBoolean()) {
                        skippedCount++;
                        continue;
                    }
                    ResolvedVideo resolved = resolver.resolve(video);
                    if (!operationGuard.getAsBoolean()) {
                        skippedCount++;
                        continue;
                    }
                    if (resolved == null || !video.getVideoId().equals(resolved.getVideoId())) {
                        throw new IOException("Resolved video does not match the queued video.");
                    }
                    downloader.download(request, resolved, operationGuard);
                }
                if (operationGuard.getAsBoolean()
                        && store.markDownloaded(video.getVideoId(), request.subscribedAtMs)) {
                    completedCount++;
                } else {
                    skippedCount++;
                }
            } catch (Exception itemFailure) {
                if (isCancelled(cancelled) || !isEligible()) {
                    return result(completedCount, skippedCount, failed, cancelled, false);
                }
                if (!store.isDownloadPending(video.getVideoId(), request.subscribedAtMs)) {
                    skippedCount++;
                } else {
                    failed.add(video.getVideoId());
                }
            }
        }
        return result(completedCount, skippedCount, failed, cancelled, false);
    }

    private boolean isOperationAllowed(Request request, BooleanSupplier cancelled) {
        try {
            return !isCancelled(cancelled)
                    && isEligible()
                    && store.isDownloadPending(
                            request.video.getVideoId(),
                            request.subscribedAtMs
                    );
        } catch (RuntimeException unavailableGuard) {
            return false;
        }
    }

    private boolean isEligible() {
        try {
            return eligibility.getAsBoolean();
        } catch (RuntimeException unavailablePolicy) {
            return false;
        }
    }

    private Result result(
            int completedCount,
            int skippedCount,
            List<String> failed,
            BooleanSupplier cancelled,
            boolean queueUnavailable
    ) {
        return new Result(
                completedCount,
                skippedCount,
                failed,
                isCancelled(cancelled),
                !isEligible(),
                queueUnavailable
        );
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || cancelled.getAsBoolean();
    }
}
