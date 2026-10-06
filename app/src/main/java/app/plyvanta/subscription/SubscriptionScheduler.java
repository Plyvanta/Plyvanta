package app.plyvanta.subscription;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Schedules public feed checks; opted-in offline saves use their own cancellable worker. */
public final class SubscriptionScheduler {
    private static final String PERIODIC_WORK_NAME = "plyvanta-subscription-refresh";
    private static final String IMMEDIATE_WORK_NAME = "plyvanta-subscription-refresh-now";

    private SubscriptionScheduler() {
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                SubscriptionRefreshWorker.class,
                30,
                TimeUnit.MINUTES
        )
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );
    }

    public static void refreshNow(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                SubscriptionRefreshWorker.class
        )
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
        );
    }

    private static Constraints networkConstraints() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }
}
