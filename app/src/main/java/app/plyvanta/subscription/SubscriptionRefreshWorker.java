package app.plyvanta.subscription;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;

import app.plyvanta.network.AppNetwork;

/** Background work is limited to public channel/video metadata, never offline media. */
public final class SubscriptionRefreshWorker extends Worker {
    private static final Object WORKER_LOCK = new Object();

    private volatile YouTubeChannelFeedClient feedClient;

    public SubscriptionRefreshWorker(
            @NonNull Context applicationContext,
            @NonNull WorkerParameters workerParameters
    ) {
        super(applicationContext, workerParameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        synchronized (WORKER_LOCK) {
            if (isStopped() || Thread.currentThread().isInterrupted()) {
                return Result.retry();
            }
            Context context = getApplicationContext();
            AppNetwork.initialize(context);
            try {
                SubscriptionStore store = SubscriptionStore.forApplication(context);
                YouTubeChannelFeedClient client = new YouTubeChannelFeedClient();
                feedClient = client;
                SubscriptionRefreshCoordinator.Result result =
                        new SubscriptionRefreshCoordinator(store, client).refresh(this::isStopped);
                if (!isStopped()) {
                    SubscriptionDownloadScheduler.schedulePending(context);
                }
                if (result.isCancelled() || !result.getFailedChannelIds().isEmpty()) {
                    return Result.retry();
                }
                return Result.success();
            } catch (IOException storageFailure) {
                return Result.retry();
            } finally {
                feedClient = null;
            }
        }
    }

    @Override
    public void onStopped() {
        YouTubeChannelFeedClient client = feedClient;
        if (client != null) {
            client.cancel();
        }
        super.onStopped();
    }
}
