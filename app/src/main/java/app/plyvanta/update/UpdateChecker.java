package app.plyvanta.update;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class UpdateChecker {
    private static final ReentrantLock CHECK_LOCK = new ReentrantLock();
    private static volatile ReleaseSource debugReleaseSourceOverride;

    public enum Status {
        SUCCESS,
        UNVERIFIED_RELEASE,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    public enum FailureReason {
        NONE,
        CONNECTION,
        TIMEOUT,
        RATE_LIMIT,
        STORAGE,
        CHECK_IN_PROGRESS
    }

    public static final class Result {
        private final Status status;
        private final UpdateRelease checkedRelease;
        private final UpdateRelease availableRelease;
        private final FailureReason failureReason;

        private Result(
                Status status,
                UpdateRelease checkedRelease,
                UpdateRelease availableRelease
        ) {
            this(status, checkedRelease, availableRelease, FailureReason.NONE);
        }

        private Result(
                Status status,
                UpdateRelease checkedRelease,
                UpdateRelease availableRelease,
                FailureReason failureReason
        ) {
            this.status = Objects.requireNonNull(status, "status");
            this.checkedRelease = checkedRelease;
            this.availableRelease = availableRelease;
            this.failureReason = Objects.requireNonNull(failureReason, "failureReason");
        }

        public Status getStatus() {
            return status;
        }

        public UpdateRelease getCheckedRelease() {
            return checkedRelease;
        }

        public UpdateRelease getAvailableRelease() {
            return availableRelease;
        }

        public FailureReason getFailureReason() {
            return failureReason;
        }
    }

    interface InstalledAppSource {
        InstalledApp load() throws InstalledAppUnavailableException;
    }

    interface ReleaseSource {
        UpdateRelease fetchLatestUpdate(
                long installedVersionCode,
                String installedVersionName,
                String installedPackageName,
                UpdateChannel channel,
                int deviceSdk
        ) throws IOException;
    }

    interface ReleaseStore {
        UpdateRelease availableRelease();

        void clearAvailableRelease();

        boolean storeAvailableRelease(UpdateRelease release);
    }

    interface NotificationCanceller {
        void cancel();
    }

    interface Completion<T> {
        T complete(Result result);
    }

    static final class InstalledApp {
        private final long versionCode;
        private final String versionName;
        private final String packageName;
        private final UpdateChannel channel;
        private final int deviceSdk;

        InstalledApp(
                long versionCode,
                String versionName,
                String packageName,
                UpdateChannel channel,
                int deviceSdk
        ) {
            this.versionCode = versionCode;
            this.versionName = Objects.requireNonNull(versionName, "versionName");
            this.packageName = Objects.requireNonNull(packageName, "packageName");
            this.channel = Objects.requireNonNull(channel, "channel");
            this.deviceSdk = deviceSdk;
        }
    }

    static final class InstalledAppUnavailableException extends Exception {
        InstalledAppUnavailableException(Throwable cause) {
            super(cause);
        }
    }

    private final InstalledAppSource installedAppSource;
    private final ReleaseSource releaseSource;
    private final ReleaseStore releaseStore;
    private final NotificationCanceller notificationCanceller;

    public UpdateChecker(Context context) {
        this(
                new AndroidInstalledAppSource(applicationContext(context)),
                releaseSource(applicationContext(context)),
                new PreferencesReleaseStore(
                        new UpdatePreferences(applicationContext(context))
                ),
                () -> UpdateNotificationManager.cancel(applicationContext(context))
        );
    }

    UpdateChecker(
            InstalledAppSource installedAppSource,
            ReleaseSource releaseSource,
            ReleaseStore releaseStore,
            NotificationCanceller notificationCanceller
    ) {
        this.installedAppSource =
                Objects.requireNonNull(installedAppSource, "installedAppSource");
        this.releaseSource = Objects.requireNonNull(releaseSource, "releaseSource");
        this.releaseStore = Objects.requireNonNull(releaseStore, "releaseStore");
        this.notificationCanceller =
                Objects.requireNonNull(notificationCanceller, "notificationCanceller");
    }

    public Result check() {
        return checkAndComplete(result -> result);
    }

    /** Manual checks must not queue behind a slow background network transaction. */
    public Result checkManually() {
        if (!CHECK_LOCK.tryLock()) {
            return new Result(Status.RETRYABLE_FAILURE, null, null,
                    FailureReason.CHECK_IN_PROGRESS);
        }
        try {
            return runSerializedCheck();
        } finally {
            CHECK_LOCK.unlock();
        }
    }

    <T> T checkAndComplete(Completion<T> completion) {
        Objects.requireNonNull(completion, "completion");
        CHECK_LOCK.lock();
        try {
            return completion.complete(runSerializedCheck());
        } finally {
            CHECK_LOCK.unlock();
        }
    }

    private Result runSerializedCheck() {
        final InstalledApp installedApp;
        try {
            installedApp = installedAppSource.load();
        } catch (InstalledAppUnavailableException missingOwnPackage) {
            return new Result(Status.PERMANENT_FAILURE, null, null);
        }

        UpdateRelease storedRelease = releaseStore.availableRelease();
        if (storedRelease != null
                && !storedRelease.isNewerThan(installedApp.versionCode)) {
            releaseStore.clearAvailableRelease();
            notificationCanceller.cancel();
            storedRelease = null;
        }
        UpdateRelease availableStoredRelease = storedRelease;

        final UpdateRelease fetchedRelease;
        try {
            fetchedRelease = releaseSource.fetchLatestUpdate(
                    installedApp.versionCode,
                    installedApp.versionName,
                    installedApp.packageName,
                    installedApp.channel,
                    installedApp.deviceSdk
            );
        } catch (GitHubReleaseClient.UnverifiedReleaseException unverifiedRelease) {
            return new Result(
                    Status.UNVERIFIED_RELEASE,
                    null,
                    availableStoredRelease
            );
        } catch (IOException transientFailure) {
            return new Result(
                    Status.RETRYABLE_FAILURE,
                    null,
                    availableStoredRelease,
                    failureReason(transientFailure)
            );
        }

        if (fetchedRelease == null) {
            if (storedRelease != null) {
                releaseStore.clearAvailableRelease();
                notificationCanceller.cancel();
            }
            return new Result(Status.SUCCESS, null, null);
        }
        if (fetchedRelease.equals(storedRelease)) {
            return new Result(Status.SUCCESS, fetchedRelease, fetchedRelease);
        }
        if (!releaseStore.storeAvailableRelease(fetchedRelease)) {
            return new Result(
                    Status.RETRYABLE_FAILURE,
                    fetchedRelease,
                    availableStoredRelease,
                    FailureReason.STORAGE
            );
        }
        if (storedRelease == null
                || storedRelease.getVersionCode() != fetchedRelease.getVersionCode()
                || !storedRelease.getVersionName().equals(
                        fetchedRelease.getVersionName()
                )) {
            notificationCanceller.cancel();
        }
        return new Result(Status.SUCCESS, fetchedRelease, fetchedRelease);
    }

    private static FailureReason failureReason(IOException failure) {
        if (failure instanceof GitHubReleaseClient.HttpException) {
            int statusCode = ((GitHubReleaseClient.HttpException) failure).getStatusCode();
            if (statusCode == 403 || statusCode == 429) {
                return FailureReason.RATE_LIMIT;
            }
        }
        if (failure instanceof InterruptedIOException && !Thread.currentThread().isInterrupted()) {
            return FailureReason.TIMEOUT;
        }
        return FailureReason.CONNECTION;
    }

    private static Context applicationContext(Context context) {
        Context checkedContext = Objects.requireNonNull(context, "context");
        Context applicationContext = checkedContext.getApplicationContext();
        return applicationContext == null ? checkedContext : applicationContext;
    }

    private static ReleaseSource releaseSource(Context context) {
        ReleaseSource override = debugReleaseSourceOverride;
        boolean debuggable =
                (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable && override != null) {
            return override;
        }
        return new GitHubReleaseClient()::fetchLatestUpdate;
    }

    static void setDebugReleaseSourceOverrideForTests(ReleaseSource releaseSource) {
        debugReleaseSourceOverride = releaseSource;
    }

    private static final class AndroidInstalledAppSource implements InstalledAppSource {
        private final Context context;

        private AndroidInstalledAppSource(Context context) {
            this.context = context;
        }

        @Override
        public InstalledApp load() throws InstalledAppUnavailableException {
            final PackageInfo packageInfo;
            try {
                packageInfo = context.getPackageManager().getPackageInfo(
                        context.getPackageName(),
                        0
                );
            } catch (PackageManager.NameNotFoundException missingOwnPackage) {
                throw new InstalledAppUnavailableException(missingOwnPackage);
            }

            long installedVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? packageInfo.getLongVersionCode()
                    : packageInfo.versionCode;
            boolean debuggable =
                    (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            UpdateChannel channel = UpdateChannel.forDebuggableApp(debuggable);
            String installedVersionName = packageInfo.versionName;
            if (!channel.matchesVersion(installedVersionName)) {
                throw new InstalledAppUnavailableException(
                        new IllegalStateException("Installed version name is invalid")
                );
            }
            return new InstalledApp(
                    installedVersionCode,
                    installedVersionName,
                    context.getPackageName(),
                    channel,
                    Build.VERSION.SDK_INT
            );
        }
    }

    private static final class PreferencesReleaseStore implements ReleaseStore {
        private final UpdatePreferences preferences;

        private PreferencesReleaseStore(UpdatePreferences preferences) {
            this.preferences = preferences;
        }

        @Override
        public UpdateRelease availableRelease() {
            return preferences.availableRelease();
        }

        @Override
        public void clearAvailableRelease() {
            preferences.clearAvailableRelease();
        }

        @Override
        public boolean storeAvailableRelease(UpdateRelease release) {
            return preferences.storeAvailableRelease(release);
        }
    }
}
