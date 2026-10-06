package app.plyvanta.subscription;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import app.plyvanta.R;
import app.plyvanta.network.AppNetwork;
import app.plyvanta.offline.OfflineDownloadManager;
import app.plyvanta.offline.OfflineMediaStore;
import app.plyvanta.offline.OfflineSecurityPolicy;
import app.plyvanta.playback.NewPipeVideoResolver;
import app.plyvanta.playback.ResolvedVideo;
import app.plyvanta.settings.PreferenceStore;

/** Explicitly opted-in subscription saves using a visible, cancellable data-sync worker. */
public final class BackgroundSubscriptionDownloadWorker extends Worker {
    private static final ReentrantLock BATCH_LOCK = new ReentrantLock();
    private static final String CHANNEL_ID = "plyvanta_subscription_downloads";
    private static final int NOTIFICATION_ID = 0x50565344;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_BATCH_MS = TimeUnit.MINUTES.toMillis(30);
    private static volatile BackgroundSubscriptionDownloadWorker activeWorker;

    private final AtomicBoolean explicitlyCancelled = new AtomicBoolean();
    private volatile OfflineDownloadManager.Cancellation activeCancellation;
    private volatile Thread workerThread;
    private long lastProgressAtMs;

    public BackgroundSubscriptionDownloadWorker(
            @NonNull Context applicationContext,
            @NonNull WorkerParameters workerParameters
    ) {
        super(applicationContext, workerParameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!BATCH_LOCK.tryLock()) {
            return boundedRetry();
        }
        activeWorker = this;
        synchronized (this) {
            workerThread = Thread.currentThread();
        }
        try {
            if (isStopped() || explicitlyCancelled.get() || Thread.currentThread().isInterrupted()) {
                return boundedRetry();
            }
            Context context = getApplicationContext();
            AppNetwork.initialize(context);
            OfflineSecurityPolicy securityPolicy = new OfflineSecurityPolicy();
            BooleanSupplier eligible = () -> securityPolicy.evaluate(context).isAllowed();
            if (!eligible.getAsBoolean()) {
                return Result.success();
            }
            SubscriptionStore subscriptions = SubscriptionStore.forApplication(context);
            if (subscriptions.getPendingDownloads().isEmpty()) {
                return Result.success();
            }

            // Android must accept the foreground service before resolving streams or creating keys.
            setForegroundAsync(foregroundInfo(-1)).get(15, TimeUnit.SECONDS);
            long deadlineMs = SystemClock.elapsedRealtime() + MAX_BATCH_MS;
            BooleanSupplier cancelled = () -> isStopped()
                    || explicitlyCancelled.get()
                    || Thread.currentThread().isInterrupted()
                    || SystemClock.elapsedRealtime() >= deadlineMs;
            OfflineMediaStore vault = OfflineMediaStore.forApplication(context);
            vault.cleanupPartials();
            OfflineDownloadManager downloadManager = new OfflineDownloadManager(vault);
            NewPipeVideoResolver resolver = new NewPipeVideoResolver();
            int preferredMaxHeight = new PreferenceStore(context).maxHeight();
            SubscriptionDownloadBatch batch = new SubscriptionDownloadBatch(
                    queueStore(subscriptions),
                    video -> resolver.resolve(video.getVideoUrl(), preferredMaxHeight),
                    downloader(vault, downloadManager),
                    eligible
            );
            SubscriptionDownloadBatch.Result result = batch.run(cancelled);
            if (result.isIneligible()) {
                return Result.success();
            }
            if (result.isCancelled()
                    || result.isQueueUnavailable()
                    || !result.getFailedVideoIds().isEmpty()) {
                return boundedRetry();
            }
            return Result.success();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return boundedRetry();
        } catch (Exception unavailableOperation) {
            // URLs, vault locations and cryptographic failures never enter diagnostics or work data.
            return boundedRetry();
        } finally {
            OfflineDownloadManager.Cancellation cancellation = activeCancellation;
            if (cancellation != null) {
                cancellation.cancel();
            }
            synchronized (this) {
                activeCancellation = null;
                workerThread = null;
            }
            activeWorker = null;
            BATCH_LOCK.unlock();
        }
    }

    @Override
    public void onStopped() {
        requestCancellation();
        super.onStopped();
    }

    private synchronized void requestCancellation() {
        explicitlyCancelled.set(true);
        OfflineDownloadManager.Cancellation cancellation = activeCancellation;
        if (cancellation != null) {
            cancellation.cancel();
        }
        Thread thread = workerThread;
        if (thread != null) {
            // The extractor's HTTP adapter cancels its in-flight call on thread interruption.
            thread.interrupt();
        }
    }

    /** Off-main-thread reset barrier: hold exclusion after active partial-file cleanup completes. */
    static void runWithDownloadsPaused(SubscriptionDownloadScheduler.ResetAction action)
            throws Exception {
        BackgroundSubscriptionDownloadWorker worker = activeWorker;
        if (worker != null) {
            worker.requestCancellation();
        }
        try {
            if (!BATCH_LOCK.tryLock(30, TimeUnit.SECONDS)) {
                throw new IOException("Subscription saves have not stopped yet.");
            }
            try {
                action.run();
            } finally {
                BATCH_LOCK.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Waiting for subscription saves was interrupted.");
        }
    }

    private SubscriptionDownloadBatch.Downloader downloader(
            OfflineMediaStore vault,
            OfflineDownloadManager downloadManager
    ) {
        // Device-private identities exist only in this operation and the protected vault.
        Map<String, UUID> itemIds = new HashMap<>();
        return new SubscriptionDownloadBatch.Downloader() {
            @Override
            public boolean isAlreadyDownloaded(SubscriptionDownloadBatch.Request request)
                    throws Exception {
                SubscriptionVideo video = request.getVideo();
                UUID itemId = vault.subscriptionDownloadItemId(
                        video.getChannelId(),
                        request.getSubscribedAtMs(),
                        video.getVideoId()
                );
                itemIds.put(video.getVideoId(), itemId);
                return vault.hasCommittedDownload(itemId);
            }

            @Override
            public void download(
                    SubscriptionDownloadBatch.Request request,
                    ResolvedVideo video,
                    BooleanSupplier operationGuard
            ) throws Exception {
                UUID itemId = itemIds.get(request.getVideo().getVideoId());
                if (itemId == null) {
                    throw new IOException("The subscription download is unavailable.");
                }
                OfflineDownloadManager.Cancellation cancellation =
                        new OfflineDownloadManager.Cancellation(operationGuard);
                activeCancellation = cancellation;
                try {
                    downloadManager.download(
                            video,
                            itemId,
                            cancellation,
                            (track, downloadedBytes, totalBytes) -> {
                                if (cancellation.isCancelled()) {
                                    return;
                                }
                                long nowMs = SystemClock.elapsedRealtime();
                                if (nowMs - lastProgressAtMs < 1_000L) {
                                    return;
                                }
                                lastProgressAtMs = nowMs;
                                int percent = totalBytes <= 0L
                                        ? -1
                                        : (int) Math.min(100d, 100d * downloadedBytes / totalBytes);
                                try {
                                    setForegroundAsync(foregroundInfo(percent))
                                            .get(15, TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    cancellation.cancel();
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException("Subscription save was stopped.");
                                } catch (Exception unavailableNotification) {
                                    cancellation.cancel();
                                    throw new IllegalStateException(
                                            "Subscription save notification is unavailable.");
                                }
                            }
                    );
                } finally {
                    cancellation.cancel();
                    activeCancellation = null;
                }
            }
        };
    }

    private ForegroundInfo foregroundInfo(int percent) throws IOException {
        Context context = getApplicationContext();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            throw new IOException("Subscription save notification is unavailable.");
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.subscription_download_notification_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(
                R.string.subscription_download_notification_channel_detail
        ));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_update_notification)
                .setContentTitle(context.getString(R.string.subscription_download_notification_title))
                .setContentText(context.getString(R.string.subscription_download_notification_text))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, Math.max(0, percent), percent < 0)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        context.getString(R.string.subscription_download_notification_cancel),
                        WorkManager.getInstance(context).createCancelPendingIntent(getId())
                ).build())
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        }
        return new ForegroundInfo(NOTIFICATION_ID, notification);
    }

    private Result boundedRetry() {
        return getRunAttemptCount() + 1 < MAX_ATTEMPTS ? Result.retry() : Result.success();
    }

    private static SubscriptionDownloadBatch.QueueStore queueStore(SubscriptionStore store) {
        return new SubscriptionDownloadBatch.QueueStore() {
            @Override
            public List<ChannelSubscription> getSubscriptions() {
                return store.getSubscriptions();
            }

            @Override
            public List<SubscriptionVideo> getPendingDownloads() {
                return store.getPendingDownloads();
            }

            @Override
            public boolean isDownloadPending(String videoId, long expectedSubscribedAtMs) {
                return store.isDownloadPending(videoId, expectedSubscribedAtMs);
            }

            @Override
            public boolean markDownloaded(String videoId, long expectedSubscribedAtMs)
                    throws IOException {
                return store.markDownloaded(videoId, expectedSubscribedAtMs);
            }
        };
    }
}
