package app.plyvanta.update;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import app.plyvanta.network.AppNetwork;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class GitHubReleaseClient {
    static final int MAX_REPOSITORY_RESPONSE_BYTES = 256 * 1024;
    static final int MAX_RELEASE_RESPONSE_BYTES = 4 * 1024 * 1024;
    static final int MAX_METADATA_RESPONSE_BYTES = 32 * 1024;
    static final int MAX_RELEASES = 100;
    static final int MAX_ASSETS_PER_RELEASE = 100;
    static final long TRUSTED_REPOSITORY_ID = 1_313_062_669L;

    static final String REPOSITORY_API_URL =
            "https://api.github.com/repositories/1313062669";
    static final String RELEASES_API_URL =
            "https://api.github.com/repositories/1313062669/releases?per_page=100";
    private static final String USER_AGENT = "Plyvanta-Update-Checker/1 (Android)";
    private static final String GITHUB_JSON_ACCEPT =
            "application/vnd.github+json, application/json";
    private static final String GITHUB_ASSET_ACCEPT = "application/octet-stream";
    private static final String APK_CONTENT_TYPE =
            "application/vnd.android.package-archive";
    private static final long DIRECT_CHECK_TIMEOUT_MILLIS = 30_000L;
    private static final long TOR_CHECK_TIMEOUT_MILLIS = 60_000L;

    private final Call.Factory httpClient;
    private final HttpUrl repositoryApiUrl;
    private final HttpUrl releasesApiUrl;
    private final LongSupplier checkTimeoutMillis;
    private final LongSupplier nanoTime;

    public GitHubReleaseClient() {
        this(
                AppNetwork.calls(AppNetwork.Profile.UPDATE),
                HttpUrl.get(REPOSITORY_API_URL),
                HttpUrl.get(RELEASES_API_URL),
                () -> AppNetwork.isTorEnabled()
                        ? TOR_CHECK_TIMEOUT_MILLIS : DIRECT_CHECK_TIMEOUT_MILLIS,
                System::nanoTime
        );
    }

    GitHubReleaseClient(
            Call.Factory httpClient,
            HttpUrl repositoryApiUrl,
            HttpUrl releasesApiUrl
    ) {
        this(httpClient, repositoryApiUrl, releasesApiUrl,
                DIRECT_CHECK_TIMEOUT_MILLIS);
    }

    GitHubReleaseClient(
            Call.Factory httpClient,
            HttpUrl repositoryApiUrl,
            HttpUrl releasesApiUrl,
            long checkTimeoutMillis
    ) {
        this(httpClient, repositoryApiUrl, releasesApiUrl,
                checkTimeoutMillis, System::nanoTime);
    }

    GitHubReleaseClient(
            Call.Factory httpClient,
            HttpUrl repositoryApiUrl,
            HttpUrl releasesApiUrl,
            long checkTimeoutMillis,
            LongSupplier nanoTime
    ) {
        this(httpClient, repositoryApiUrl, releasesApiUrl,
                () -> checkTimeoutMillis, nanoTime);
        if (checkTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("checkTimeoutMillis must be positive");
        }
    }

    private GitHubReleaseClient(
            Call.Factory httpClient,
            HttpUrl repositoryApiUrl,
            HttpUrl releasesApiUrl,
            LongSupplier checkTimeoutMillis,
            LongSupplier nanoTime
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.repositoryApiUrl =
                Objects.requireNonNull(repositoryApiUrl, "repositoryApiUrl");
        this.releasesApiUrl = Objects.requireNonNull(releasesApiUrl, "releasesApiUrl");
        this.checkTimeoutMillis =
                Objects.requireNonNull(checkTimeoutMillis, "checkTimeoutMillis");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public UpdateRelease fetchLatestUpdate(
            long installedVersionCode,
            String installedVersionName,
            String installedPackageName,
            UpdateChannel channel,
            int deviceSdk
    ) throws IOException {
        if (installedVersionCode < 0L) {
            throw new IllegalArgumentException("installedVersionCode cannot be negative");
        }
        if (installedPackageName == null || installedPackageName.isBlank()) {
            throw new IllegalArgumentException("installedPackageName is required");
        }
        Objects.requireNonNull(channel, "channel");
        if (!channel.matchesVersion(installedVersionName)) {
            throw new IllegalArgumentException(
                    "installedVersionName does not match the update channel"
            );
        }

        CheckBudget budget = new CheckBudget(
                TimeUnit.MILLISECONDS.toNanos(checkTimeoutMillis.getAsLong()),
                nanoTime
        );
        TrustedRepository repository = parseTrustedRepository(
                getJson(repositoryApiUrl, MAX_REPOSITORY_RESPONSE_BYTES, budget),
                TRUSTED_REPOSITORY_ID
        );
        String releasesJson = getJson(releasesApiUrl, MAX_RELEASE_RESPONSE_BYTES, budget);
        ParsedReleaseList parsedReleases = parseReleaseList(
                releasesJson,
                channel,
                repository.fullName,
                installedVersionName
        );
        UpdateRelease bestCandidate = null;
        boolean newerReleaseRejected = parsedReleases.newerReleaseRejected;
        String highestRejectedNewerVersion =
                parsedReleases.highestRejectedNewerVersion;
        boolean verifiedReleaseFound = false;
        List<UpdateRelease> verifiedNewerReleases = new ArrayList<>();
        List<ReleaseDescriptor> releasesToVerify = selectReleasesForMetadataVerification(
                parsedReleases.releases,
                channel,
                installedVersionName
        );
        for (ReleaseDescriptor release : releasesToVerify) {
            budget.requireRemaining();
            AssetDescriptor metadataAsset = release.singleMetadataAsset();
            if (metadataAsset == null
                    || !UpdateRelease.isTrustedAssetApiUrl(
                            metadataAsset.apiUrl,
                            repository.fullName
                    )
                    || !UpdateRelease.isTrustedMetadataUrlForRelease(
                            metadataAsset.downloadUrl,
                            repository.fullName,
                            release.tagName,
                            metadataAsset.name
                    )) {
                if (release.newerThanInstalled) {
                    highestRejectedNewerVersion = semanticallyLaterVersion(
                            channel,
                            highestRejectedNewerVersion,
                            release.versionName
                    );
                }
                newerReleaseRejected |= release.newerThanInstalled;
                continue;
            }

            String metadataJson = getJson(
                    HttpUrl.get(metadataAsset.downloadUrl),
                    MAX_METADATA_RESPONSE_BYTES,
                    GITHUB_ASSET_ACCEPT + ", application/json",
                    budget
            );
            UpdateMetadata metadata = parseMetadata(metadataJson);
            if (metadata == null
                    || metadata.schemaVersion != 1L
                    || !installedPackageName.equals(metadata.packageName)
                    || !channel.matchesMetadata(metadata.channel)
                    || !channel.matchesVersion(metadata.versionName)
                    || !release.tagName.equals("v" + metadata.versionName)
                    || !metadataAsset.name.equals(
                            "Plyvanta-" + metadata.versionName + "-update.json"
                    )) {
                if (release.newerThanInstalled) {
                    highestRejectedNewerVersion = semanticallyLaterVersion(
                            channel,
                            highestRejectedNewerVersion,
                            release.versionName
                    );
                }
                newerReleaseRejected |= release.newerThanInstalled;
                continue;
            }

            AssetDescriptor apkAsset = release.singleAssetNamed(metadata.apkName);
            if (apkAsset == null
                    || !APK_CONTENT_TYPE.equals(apkAsset.contentType)
                    || !UpdateRelease.isTrustedAssetApiUrl(
                            apkAsset.apiUrl,
                            repository.fullName
                    )
                    || !UpdateRelease.isTrustedApkUrlForRelease(
                            apkAsset.downloadUrl,
                            repository.fullName,
                            release.tagName,
                            metadata.apkName
                    )
                    || !("sha256:" + metadata.sha256).equalsIgnoreCase(apkAsset.digest)) {
                if (release.newerThanInstalled) {
                    highestRejectedNewerVersion = semanticallyLaterVersion(
                            channel,
                            highestRejectedNewerVersion,
                            release.versionName
                    );
                }
                newerReleaseRejected |= release.newerThanInstalled
                        || metadata.versionCode > installedVersionCode;
                continue;
            }

            UpdateRelease candidate;
            try {
                candidate = new UpdateRelease(
                        metadata.versionCode,
                        metadata.versionName,
                        repository.fullName,
                        apkAsset.downloadUrl,
                        release.releaseUrl,
                        metadata.sha256
                );
            } catch (IllegalArgumentException invalidRelease) {
                if (release.newerThanInstalled) {
                    highestRejectedNewerVersion = semanticallyLaterVersion(
                            channel,
                            highestRejectedNewerVersion,
                            release.versionName
                    );
                }
                newerReleaseRejected |= release.newerThanInstalled
                        || metadata.versionCode > installedVersionCode;
                continue;
            }
            boolean newerVersionCode = candidate.isNewerThan(installedVersionCode);
            if (release.newerThanInstalled != newerVersionCode) {
                if (release.newerThanInstalled) {
                    highestRejectedNewerVersion = semanticallyLaterVersion(
                            channel,
                            highestRejectedNewerVersion,
                            release.versionName
                    );
                }
                newerReleaseRejected = true;
                continue;
            }
            verifiedReleaseFound = true;
            if (newerVersionCode) {
                for (UpdateRelease existingRelease : verifiedNewerReleases) {
                    String laterVersion;
                    long laterVersionCode;
                    long earlierVersionCode;
                    if (channel.isNewerVersion(
                            candidate.getVersionName(),
                            existingRelease.getVersionName()
                    )) {
                        laterVersion = candidate.getVersionName();
                        laterVersionCode = candidate.getVersionCode();
                        earlierVersionCode = existingRelease.getVersionCode();
                    } else if (channel.isNewerVersion(
                            existingRelease.getVersionName(),
                            candidate.getVersionName()
                    )) {
                        laterVersion = existingRelease.getVersionName();
                        laterVersionCode = existingRelease.getVersionCode();
                        earlierVersionCode = candidate.getVersionCode();
                    } else {
                        if (candidate.getVersionCode()
                                != existingRelease.getVersionCode()) {
                            newerReleaseRejected = true;
                            highestRejectedNewerVersion = semanticallyLaterVersion(
                                    channel,
                                    highestRejectedNewerVersion,
                                    candidate.getVersionName()
                            );
                        }
                        continue;
                    }
                    if (laterVersionCode <= earlierVersionCode) {
                        newerReleaseRejected = true;
                        highestRejectedNewerVersion = semanticallyLaterVersion(
                                channel,
                                highestRejectedNewerVersion,
                                laterVersion
                        );
                    }
                }
                verifiedNewerReleases.add(candidate);
            }
            if (metadata.minimumSdk <= deviceSdk
                    && newerVersionCode
                    && (bestCandidate == null
                    || channel.isNewerVersion(
                            candidate.getVersionName(),
                            bestCandidate.getVersionName()
                    ))) {
                bestCandidate = candidate;
            }
        }
        budget.requireRemaining();
        if (bestCandidate != null) {
            boolean candidateSupersedesRejectedRelease =
                    highestRejectedNewerVersion == null
                            || channel.isNewerVersion(
                                    bestCandidate.getVersionName(),
                                    highestRejectedNewerVersion
                            );
            if (candidateSupersedesRejectedRelease) {
                return bestCandidate;
            }
        }
        if (newerReleaseRejected) {
            throw new UnverifiedReleaseException(
                    "GitHub reported a newer Plyvanta release that could not be verified"
            );
        }
        if (verifiedReleaseFound) {
            return null;
        }
        throw new IOException(
                "GitHub did not return a verifiable release for this app and channel"
        );
    }

    private String getJson(HttpUrl url, int maximumBytes, CheckBudget budget)
            throws IOException {
        return getJson(url, maximumBytes, GITHUB_JSON_ACCEPT, budget);
    }

    private String getJson(HttpUrl url, int maximumBytes, String accept, CheckBudget budget)
            throws IOException {
        budget.requireRemaining();
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", USER_AGENT)
                .build();
        Call call = httpClient.newCall(request);
        for (int attempt = 0; attempt < 2; attempt++) {
            long remainingNanos = budget.requireRemaining();
            if (call.isCanceled()) {
                throw new IOException("Plyvanta update request was canceled");
            }
            long existingTimeoutNanos = call.timeout().timeoutNanos();
            call.timeout().timeout(
                    existingTimeoutNanos == 0L
                            ? remainingNanos
                            : Math.min(existingTimeoutNanos, remainingNanos),
                    TimeUnit.NANOSECONDS
            );
            try (Response response = call.execute()) {
                if (response.code() != 200) {
                    throw new HttpException(response.code());
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("Plyvanta update request returned no response body");
                }
                String json = readBoundedBody(body, maximumBytes);
                budget.requireRemaining();
                return json;
            } catch (IOException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw failure;
                }
                if (budget.remainingNanos() <= 0L) {
                    SocketTimeoutException timedOut = CheckBudget.timedOut();
                    timedOut.initCause(failure);
                    throw timedOut;
                }
                if (attempt != 0 || call.isCanceled() || !isTransientConnectionFailure(failure)) {
                    throw failure;
                }
                // RoutedCall.clone keeps the original route generation. A setting change
                // between failure and retry therefore cancels the retry as well.
                call = call.clone();
            }
        }
        throw new AssertionError("Update request exceeded its retry limit");
    }

    private static boolean isTransientConnectionFailure(IOException failure) {
        return failure instanceof ConnectException
                || failure instanceof UnknownHostException
                || failure instanceof SocketTimeoutException;
    }

    static TrustedRepository parseTrustedRepository(
            String responseBody,
            long expectedRepositoryId
    ) throws IOException {
        if (responseBody == null) {
            throw new IOException("GitHub returned no repository identity");
        }
        final JSONObject object;
        try {
            object = new JSONObject(responseBody);
        } catch (JSONException malformedJson) {
            throw new IOException(
                    "GitHub returned malformed repository JSON",
                    malformedJson
            );
        }
        Long repositoryId = exactLong(object, "id");
        String fullName = exactString(object, "full_name");
        if (repositoryId == null
                || repositoryId != expectedRepositoryId
                || !UpdateRelease.isTrustedRepositoryFullName(fullName)) {
            throw new IOException("GitHub returned an unexpected repository identity");
        }
        return new TrustedRepository(repositoryId, fullName);
    }

    static ParsedReleaseList parseReleaseList(
            String responseBody,
            UpdateChannel channel,
            String repositoryFullName,
            String installedVersionName
    ) throws IOException {
        Objects.requireNonNull(responseBody, "responseBody");
        Objects.requireNonNull(channel, "channel");
        if (!UpdateRelease.isTrustedRepositoryFullName(repositoryFullName)) {
            throw new IllegalArgumentException("repositoryFullName is invalid");
        }
        if (!channel.matchesVersion(installedVersionName)) {
            throw new IllegalArgumentException("installedVersionName is invalid");
        }
        final JSONArray releases;
        try {
            releases = new JSONArray(responseBody);
        } catch (JSONException malformedJson) {
            throw new IOException("GitHub returned malformed release JSON", malformedJson);
        }
        if (releases.length() > MAX_RELEASES) {
            throw new IOException("GitHub returned too many releases");
        }

        List<ReleaseDescriptor> parsed = new ArrayList<>();
        boolean newerReleaseRejected = false;
        String highestRejectedNewerVersion = null;
        for (int releaseIndex = 0; releaseIndex < releases.length(); releaseIndex++) {
            JSONObject object = releases.optJSONObject(releaseIndex);
            if (object == null) {
                continue;
            }
            Boolean draft = exactBoolean(object, "draft");
            Boolean prerelease = exactBoolean(object, "prerelease");
            Boolean immutable = exactBoolean(object, "immutable");
            String tagName = exactString(object, "tag_name");
            String releaseUrl = exactString(object, "html_url");
            JSONArray assets = object.optJSONArray("assets");
            String releaseVersion = tagName != null && tagName.startsWith("v")
                    ? tagName.substring(1)
                    : null;
            if (!channel.matchesVersion(releaseVersion)) {
                continue;
            }
            boolean newerThanInstalled = channel.isNewerVersion(
                    releaseVersion,
                    installedVersionName
            );
            if (Boolean.TRUE.equals(draft)) {
                continue;
            }
            if (draft == null
                    || prerelease == null
                    || !channel.matchesRelease(prerelease)) {
                if (newerThanInstalled) {
                    newerReleaseRejected = true;
                    highestRejectedNewerVersion = semanticallyLaterVersion(
                            channel,
                            highestRejectedNewerVersion,
                            releaseVersion
                    );
                }
                continue;
            }
            if (immutable == null
                    || !immutable
                    || releaseUrl == null
                    || !UpdateRelease.isTrustedReleasePageUrlForRelease(
                            releaseUrl,
                            repositoryFullName,
                            tagName
                    )
                    || assets == null
                    || assets.length() > MAX_ASSETS_PER_RELEASE) {
                if (newerThanInstalled) {
                    newerReleaseRejected = true;
                    highestRejectedNewerVersion = semanticallyLaterVersion(
                            channel,
                            highestRejectedNewerVersion,
                            releaseVersion
                    );
                }
                continue;
            }

            List<AssetDescriptor> parsedAssets = new ArrayList<>();
            for (int assetIndex = 0; assetIndex < assets.length(); assetIndex++) {
                JSONObject asset = assets.optJSONObject(assetIndex);
                if (asset == null) {
                    continue;
                }
                String name = exactString(asset, "name");
                String contentType = exactString(asset, "content_type");
                String apiUrl = exactString(asset, "url");
                String downloadUrl = exactString(asset, "browser_download_url");
                String digest = exactString(asset, "digest");
                if (name == null
                        || contentType == null
                        || apiUrl == null
                        || downloadUrl == null) {
                    continue;
                }
                parsedAssets.add(new AssetDescriptor(
                        name,
                        contentType,
                        apiUrl,
                        downloadUrl,
                        digest
                ));
            }
            parsed.add(new ReleaseDescriptor(
                    tagName,
                    releaseVersion,
                    releaseUrl,
                    newerThanInstalled,
                    Collections.unmodifiableList(parsedAssets)
            ));
        }
        return new ParsedReleaseList(
                Collections.unmodifiableList(parsed),
                newerReleaseRejected,
                highestRejectedNewerVersion
        );
    }

    private static String semanticallyLaterVersion(
            UpdateChannel channel,
            String currentVersion,
            String candidateVersion
    ) {
        if (currentVersion == null
                || channel.isNewerVersion(candidateVersion, currentVersion)) {
            return candidateVersion;
        }
        return currentVersion;
    }

    private static List<ReleaseDescriptor> selectReleasesForMetadataVerification(
            List<ReleaseDescriptor> releases,
            UpdateChannel channel,
            String installedVersionName
    ) {
        List<ReleaseDescriptor> newerReleases = new ArrayList<>();
        List<ReleaseDescriptor> currentReleases = new ArrayList<>();
        ReleaseDescriptor highestOlderRelease = null;
        for (ReleaseDescriptor release : releases) {
            if (release.newerThanInstalled) {
                newerReleases.add(release);
                continue;
            }
            if (!channel.isNewerVersion(installedVersionName, release.versionName)) {
                currentReleases.add(release);
                continue;
            }
            if (highestOlderRelease == null
                    || channel.isNewerVersion(
                            release.versionName,
                            highestOlderRelease.versionName
                    )) {
                highestOlderRelease = release;
            }
        }

        if (!newerReleases.isEmpty()) {
            return newerReleases;
        }

        List<ReleaseDescriptor> selected = new ArrayList<>(
                Math.max(currentReleases.size(), 1)
        );
        if (!currentReleases.isEmpty()) {
            selected.addAll(currentReleases);
        } else if (highestOlderRelease != null) {
            selected.add(highestOlderRelease);
        }
        return selected;
    }

    static UpdateMetadata parseMetadata(String responseBody) {
        if (responseBody == null) {
            return null;
        }
        final JSONObject object;
        try {
            object = new JSONObject(responseBody);
        } catch (JSONException malformedJson) {
            return null;
        }

        Long schemaVersion = exactLong(object, "schemaVersion");
        Long versionCode = exactLong(object, "versionCode");
        Long minimumSdk = exactLong(object, "minimumSdk");
        String packageName = exactString(object, "packageName");
        String channel = exactString(object, "channel");
        String versionName = exactString(object, "versionName");
        String apkName = exactString(object, "apkName");
        String sha256 = exactString(object, "sha256");
        if (schemaVersion == null
                || versionCode == null
                || versionCode <= 0L
                || minimumSdk == null
                || minimumSdk < 1L
                || minimumSdk > Integer.MAX_VALUE
                || packageName == null
                || channel == null
                || versionName == null
                || apkName == null
                || !apkName.endsWith(".apk")
                || sha256 == null
                || !sha256.matches("[0-9a-fA-F]{64}")) {
            return null;
        }
        return new UpdateMetadata(
                schemaVersion,
                packageName,
                channel,
                versionCode,
                versionName,
                (int) (long) minimumSdk,
                apkName,
                sha256.toLowerCase(Locale.ROOT)
        );
    }

    private static String readBoundedBody(ResponseBody body, int maximumBytes)
            throws IOException {
        long declaredLength = body.contentLength();
        if (declaredLength > maximumBytes) {
            throw new IOException("Plyvanta update response exceeds the size limit");
        }

        int initialCapacity = declaredLength >= 0L
                ? (int) Math.min(declaredLength, maximumBytes)
                : 8_192;
        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity)) {
            byte[] buffer = new byte[8_192];
            int totalBytes = 0;
            while (true) {
                int permittedRead = Math.min(
                        buffer.length,
                        maximumBytes - totalBytes + 1
                );
                int bytesRead = input.read(buffer, 0, permittedRead);
                if (bytesRead == -1) {
                    break;
                }
                totalBytes += bytesRead;
                if (totalBytes > maximumBytes) {
                    throw new IOException("Plyvanta update response exceeds the size limit");
                }
                output.write(buffer, 0, bytesRead);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String exactString(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private static Boolean exactBoolean(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static Long exactLong(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Number)) {
            return null;
        }
        Number number = (Number) value;
        long longValue = number.longValue();
        double doubleValue = number.doubleValue();
        if (!Double.isFinite(doubleValue) || doubleValue != (double) longValue) {
            return null;
        }
        return longValue;
    }

    public static final class HttpException extends IOException {
        private final int statusCode;

        HttpException(int statusCode) {
            super("Plyvanta update request failed with HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    private static final class CheckBudget {
        private final long startedNanos;
        private final long timeoutNanos;
        private final LongSupplier nanoTime;

        private CheckBudget(long timeoutNanos, LongSupplier nanoTime) {
            this.nanoTime = nanoTime;
            this.startedNanos = nanoTime.getAsLong();
            this.timeoutNanos = timeoutNanos;
        }

        private long remainingNanos() {
            return timeoutNanos - (nanoTime.getAsLong() - startedNanos);
        }

        private long requireRemaining() throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Plyvanta update check was interrupted");
            }
            long remainingNanos = remainingNanos();
            if (remainingNanos <= 0L) {
                throw timedOut();
            }
            return remainingNanos;
        }

        private static SocketTimeoutException timedOut() {
            return new SocketTimeoutException("Plyvanta update check timed out");
        }
    }

    public static final class UnverifiedReleaseException extends IOException {
        UnverifiedReleaseException(String message) {
            super(message);
        }
    }

    static final class TrustedRepository {
        private final long id;
        private final String fullName;

        private TrustedRepository(long id, String fullName) {
            this.id = id;
            this.fullName = fullName;
        }
    }

    static final class ParsedReleaseList {
        private final List<ReleaseDescriptor> releases;
        private final boolean newerReleaseRejected;
        private final String highestRejectedNewerVersion;

        private ParsedReleaseList(
                List<ReleaseDescriptor> releases,
                boolean newerReleaseRejected,
                String highestRejectedNewerVersion
        ) {
            this.releases = releases;
            this.newerReleaseRejected = newerReleaseRejected;
            this.highestRejectedNewerVersion = highestRejectedNewerVersion;
        }
    }

    static final class ReleaseDescriptor {
        private final String tagName;
        private final String versionName;
        private final String releaseUrl;
        private final boolean newerThanInstalled;
        private final List<AssetDescriptor> assets;

        private ReleaseDescriptor(
                String tagName,
                String versionName,
                String releaseUrl,
                boolean newerThanInstalled,
                List<AssetDescriptor> assets
        ) {
            this.tagName = tagName;
            this.versionName = versionName;
            this.releaseUrl = releaseUrl;
            this.newerThanInstalled = newerThanInstalled;
            this.assets = assets;
        }

        private AssetDescriptor singleMetadataAsset() {
            AssetDescriptor match = null;
            for (AssetDescriptor asset : assets) {
                if (!asset.name.endsWith("-update.json")) {
                    continue;
                }
                if (match != null) {
                    return null;
                }
                match = asset;
            }
            return match;
        }

        private AssetDescriptor singleAssetNamed(String name) {
            AssetDescriptor match = null;
            for (AssetDescriptor asset : assets) {
                if (!asset.name.equals(name)) {
                    continue;
                }
                if (match != null) {
                    return null;
                }
                match = asset;
            }
            return match;
        }
    }

    private static final class AssetDescriptor {
        private final String name;
        private final String contentType;
        private final String apiUrl;
        private final String downloadUrl;
        private final String digest;

        private AssetDescriptor(
                String name,
                String contentType,
                String apiUrl,
                String downloadUrl,
                String digest
        ) {
            this.name = name;
            this.contentType = contentType;
            this.apiUrl = apiUrl;
            this.downloadUrl = downloadUrl;
            this.digest = digest;
        }
    }

    static final class UpdateMetadata {
        private final long schemaVersion;
        private final String packageName;
        private final String channel;
        private final long versionCode;
        private final String versionName;
        private final int minimumSdk;
        private final String apkName;
        private final String sha256;

        private UpdateMetadata(
                long schemaVersion,
                String packageName,
                String channel,
                long versionCode,
                String versionName,
                int minimumSdk,
                String apkName,
                String sha256
        ) {
            this.schemaVersion = schemaVersion;
            this.packageName = packageName;
            this.channel = channel;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.minimumSdk = minimumSdk;
            this.apkName = apkName;
            this.sha256 = sha256;
        }
    }
}
