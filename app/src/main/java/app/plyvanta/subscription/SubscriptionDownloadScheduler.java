package app.plyvanta.subscription;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class SubscriptionDownloadScheduler {
    private static final String WORK_NAME = "plyvanta-subscription-downloads";

    private SubscriptionDownloadScheduler() {
    }

    public interface ResetAction {
        void run() throws Exception;
    }

    public static void schedulePending(Context context) {
        try {
            if (SubscriptionStore.forApplication(context).getPendingDownloads().isEmpty()) {
                return;
            }
        } catch (IOException unavailableQueue) {
            // The next feed refresh or user change will try again without weakening the queue.
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                BackgroundSubscriptionDownloadWorker.class
        )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
        );
    }

    public static void cancelIfIdle(Context context) {
        try {
            if (SubscriptionStore.forApplication(context).getPendingDownloads().isEmpty()) {
                WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(WORK_NAME);
            }
        } catch (IOException unavailableQueue) {
            // Running downloads still fail closed when their per-item queue guard is unavailable.
        }
    }

    /** Stops active work and holds the batch exclusion lock through the complete reset action. */
    public static void runWithDownloadsPaused(Context context, ResetAction action) throws Exception {
        Objects.requireNonNull(action, "action");
        try {
            WorkManager.getInstance(context.getApplicationContext())
                    .cancelUniqueWork(WORK_NAME).getResult().get(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Cancelling subscription saves was interrupted.");
        } catch (Exception unavailableCancellation) {
            throw new IOException("Subscription saves could not be stopped.");
        }
        BackgroundSubscriptionDownloadWorker.runWithDownloadsPaused(action);
    }
}
