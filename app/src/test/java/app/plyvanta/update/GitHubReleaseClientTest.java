package app.plyvanta.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class GitHubReleaseClientTest {
    private static final String SHA256 =
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
    private static final String OTHER_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CANONICAL_REPOSITORY = "Plyvanta/Plyvanta";
    private static final String LEGACY_REPOSITORY = "culpen90/Plyvanta";
    private static final long METADATA_ASSET_ID = 492_922_383L;
    private static final long APK_ASSET_ID = 492_922_423L;

    @Test
    public void productionEndpointsUseTheFixedNumericRepositoryIdentity() {
        assertEquals(1_313_062_669L, GitHubReleaseClient.TRUSTED_REPOSITORY_ID);
        assertEquals(
                "https://api.github.com/repositories/1313062669",
                GitHubReleaseClient.REPOSITORY_API_URL
        );
        assertEquals(
                "https://api.github.com/repositories/1313062669/releases?per_page=100",
                GitHubReleaseClient.RELEASES_API_URL
        );
        assertFalse(GitHubReleaseClient.REPOSITORY_API_URL.contains(CANONICAL_REPOSITORY));
        assertFalse(GitHubReleaseClient.REPOSITORY_API_URL.contains(LEGACY_REPOSITORY));
    }

    @Test
    public void finds120From110AfterOwnerTransferUsingCanonicalFullName()
            throws IOException {
        String metadataApiUrl = assetApiUrl(
                CANONICAL_REPOSITORY,
                METADATA_ASSET_ID
        );
        String metadataDownloadUrl = assetDownloadUrl(
                CANONICAL_REPOSITORY,
                "1.2.0",
                "Plyvanta-1.2.0-update.json"
        );
        FixtureInterceptor fixture = standardFixture(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v1.2.0",
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                "1.2.0",
                                SHA256,
                                METADATA_ASSET_ID,
                                APK_ASSET_ID
                        )
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_002_000L,
                        "1.2.0",
                        "Plyvanta-1.2.0.apk",
                        SHA256
                )
        );

        UpdateRelease update = client(fixture).fetchLatestUpdate(
                1_001_000L,
                "1.1.0",
                "app.plyvanta",
                UpdateChannel.STABLE,
                36
        );

        assertNotNull(update);
        assertEquals(1_002_000L, update.getVersionCode());
        assertEquals("1.2.0", update.getVersionName());
        assertEquals(CANONICAL_REPOSITORY, update.getRepositoryFullName());
        assertEquals(
                assetDownloadUrl(
                        CANONICAL_REPOSITORY,
                        "1.2.0",
                        "Plyvanta-1.2.0.apk"
                ),
                update.getApkUrl()
        );
        assertEquals(3, fixture.calls.get());
        assertEquals(1, fixture.metadataCalls.get());
        assertFalse(fixture.requestedUrls.contains(metadataApiUrl));
        assertTrue(fixture.requestedUrls.contains(metadataDownloadUrl));
        assertEquals(
                "application/octet-stream, application/json",
                fixture.acceptByUrl.get(metadataDownloadUrl)
        );
    }

    @Test
    public void equalVerifiedVersionIsARealUpToDateResult() throws IOException {
        FixtureInterceptor fixture = standardFixture(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v1.2.0",
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                "1.2.0",
                                SHA256,
                                METADATA_ASSET_ID,
                                APK_ASSET_ID
                        )
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_002_000L,
                        "1.2.0",
                        "Plyvanta-1.2.0.apk",
                        SHA256
                )
        );

        UpdateRelease update = client(fixture).fetchLatestUpdate(
                1_002_000L,
                "1.2.0",
                "app.plyvanta",
                UpdateChannel.STABLE,
                36
        );

        assertNull(update);
        assertEquals(3, fixture.calls.get());
        assertEquals(1, fixture.metadataCalls.get());
    }

    @Test
    public void newerReleaseSkipsCurrentAndIrrelevantHistoricalMetadata()
            throws IOException {
        String olderVersion = "1.0.0";
        String currentVersion = "1.1.0";
        String newerVersion = "1.2.0";
        String releases = combineReleaseLists(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v" + olderVersion,
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                olderVersion,
                                SHA256,
                                101L,
                                102L
                        )
                ),
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v" + newerVersion,
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                newerVersion,
                                SHA256,
                                301L,
                                302L
                        )
                ),
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v" + currentVersion,
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                currentVersion,
                                SHA256,
                                201L,
                                202L
                        )
                )
        );
        String olderMetadataUrl = metadataDownloadUrl(olderVersion);
        String currentMetadataUrl = metadataDownloadUrl(currentVersion);
        String newerMetadataUrl = metadataDownloadUrl(newerVersion);
        FixtureInterceptor fixture = new FixtureInterceptor(
                repositoryIdentity(
                        GitHubReleaseClient.TRUSTED_REPOSITORY_ID,
                        CANONICAL_REPOSITORY
                ),
                releases,
                Map.of(
                        newerMetadataUrl,
                        metadata(
                                "app.plyvanta",
                                "stable",
                                1_002_000L,
                                newerVersion,
                                "Plyvanta-" + newerVersion + ".apk",
                                SHA256
                        )
                )
        );

        UpdateRelease update = client(fixture).fetchLatestUpdate(
                1_001_000L,
                currentVersion,
                "app.plyvanta",
                UpdateChannel.STABLE,
                36
        );

        assertNotNull(update);
        assertEquals(newerVersion, update.getVersionName());
        assertEquals(3, fixture.calls.get());
        assertEquals(1, fixture.metadataCalls.get());
        assertFalse(fixture.requestedUrls.contains(currentMetadataUrl));
        assertTrue(fixture.requestedUrls.contains(newerMetadataUrl));
        assertFalse(fixture.requestedUrls.contains(olderMetadataUrl));
    }

    @Test
    public void missingCurrentVerifiesOnlyHighestOlderRegardlessOfApiOrder()
            throws IOException {
        String oldestVersion = "1.0.0";
        String middleVersion = "1.1.0";
        String highestOlderVersion = "1.2.0";
        String releases = combineReleaseLists(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v" + middleVersion,
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                middleVersion,
                                SHA256,
                                201L,
                                202L
                        )
                ),
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v" + highestOlderVersion,
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                highestOlderVersion,
                                SHA256,
                                301L,
                                302L
                        )
                ),
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v" + oldestVersion,
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                oldestVersion,
                                SHA256,
                                101L,
                                102L
                        )
                )
        );
        String oldestMetadataUrl = metadataDownloadUrl(oldestVersion);
        String middleMetadataUrl = metadataDownloadUrl(middleVersion);
        String highestOlderMetadataUrl = metadataDownloadUrl(highestOlderVersion);
        FixtureInterceptor fixture = new FixtureInterceptor(
                repositoryIdentity(
                        GitHubReleaseClient.TRUSTED_REPOSITORY_ID,
                        CANONICAL_REPOSITORY
                ),
                releases,
                Map.of(
                        highestOlderMetadataUrl,
                        metadata(
                                "app.plyvanta",
                                "stable",
                                1_002_000L,
                                highestOlderVersion,
                                "Plyvanta-" + highestOlderVersion + ".apk",
                                SHA256
                        )
                )
        );

        assertNull(client(fixture).fetchLatestUpdate(
                1_003_000L,
                "1.3.0",
                "app.plyvanta",
                UpdateChannel.STABLE,
                36
        ));

        assertEquals(3, fixture.calls.get());
        assertEquals(1, fixture.metadataCalls.get());
        assertTrue(fixture.requestedUrls.contains(highestOlderMetadataUrl));
        assertFalse(fixture.requestedUrls.contains(middleMetadataUrl));
        assertFalse(fixture.requestedUrls.contains(oldestMetadataUrl));
    }

    @Test
    public void newerMetadataHttpFailureRemainsFailClosedAfterFiltering() {
        String olderVersion = "1.0.0";
        String currentVersion = "1.1.0";
        String newerVersion = "1.2.0";
        String releases = combineReleaseLists(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v" + currentVersion,
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                currentVersion,
                                SHA256,
                                201L,
                                202L
                        )
                ),
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v" + olderVersion,
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                olderVersion,
                                SHA256,
                                101L,
                                102L
                        )
                ),
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v" + newerVersion,
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                newerVersion,
                                SHA256,
                                301L,
                                302L
                        )
                )
        );
        String olderMetadataUrl = metadataDownloadUrl(olderVersion);
        String currentMetadataUrl = metadataDownloadUrl(currentVersion);
        String newerMetadataUrl = metadataDownloadUrl(newerVersion);
        FixtureInterceptor fixture = new FixtureInterceptor(
                repositoryIdentity(
                        GitHubReleaseClient.TRUSTED_REPOSITORY_ID,
                        CANONICAL_REPOSITORY
                ),
                releases,
                Map.of(
                        currentMetadataUrl,
                        metadata(
                                "app.plyvanta",
                                "stable",
                                1_001_000L,
                                currentVersion,
                                "Plyvanta-" + currentVersion + ".apk",
                                SHA256
                        )
                )
        );

        assertThrows(
                IOException.class,
                () -> client(fixture).fetchLatestUpdate(
                        1_001_000L,
                        currentVersion,
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );

        assertEquals(3, fixture.calls.get());
        assertTrue(fixture.requestedUrls.contains(newerMetadataUrl));
        assertFalse(fixture.requestedUrls.contains(olderMetadataUrl));
    }

    @Test
    public void selectsHighestSemanticVersionAcrossVerifiedReleases()
            throws IOException {
        String versionOne = "1.1.0";
        String versionTwo = "1.2.0";
        long firstMetadataId = 101L;
        long firstApkId = 102L;
        long secondMetadataId = 201L;
        long secondApkId = 202L;
        String releases = combineReleaseLists(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v" + versionOne,
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                versionOne,
                                SHA256,
                                firstMetadataId,
                                firstApkId
                        )
                ),
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v" + versionTwo,
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                versionTwo,
                                SHA256,
                                secondMetadataId,
                                secondApkId
                        )
                )
        );
        Map<String, String> metadataByDownloadUrl = Map.of(
                assetDownloadUrl(
                        CANONICAL_REPOSITORY,
                        versionOne,
                        "Plyvanta-" + versionOne + "-update.json"
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_001_000L,
                        versionOne,
                        "Plyvanta-" + versionOne + ".apk",
                        SHA256
                ),
                assetDownloadUrl(
                        CANONICAL_REPOSITORY,
                        versionTwo,
                        "Plyvanta-" + versionTwo + "-update.json"
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_002_000L,
                        versionTwo,
                        "Plyvanta-" + versionTwo + ".apk",
                        SHA256
                )
        );
        FixtureInterceptor fixture = new FixtureInterceptor(
                repositoryIdentity(
                        GitHubReleaseClient.TRUSTED_REPOSITORY_ID,
                        CANONICAL_REPOSITORY
                ),
                releases,
                metadataByDownloadUrl
        );

        UpdateRelease update = client(fixture).fetchLatestUpdate(
                1_000_000L,
                "1.0.0",
                "app.plyvanta",
                UpdateChannel.STABLE,
                36
        );

        assertNotNull(update);
        assertEquals(1_002_000L, update.getVersionCode());
        assertEquals(versionTwo, update.getVersionName());
        assertEquals(4, fixture.calls.get());
        assertEquals(2, fixture.metadataCalls.get());
    }

    @Test
    public void semanticAndVersionCodeInversionIsUnverifiedRegardlessOfApiOrder() {
        for (long higherVersionCode : new long[]{1_002_000L, 1_003_000L}) {
            for (boolean higherReleaseFirst : new boolean[]{false, true}) {
                FixtureInterceptor fixture = twoNewerStableReleaseFixture(
                        higherReleaseFirst,
                        1_003_000L,
                        higherVersionCode,
                        26
                );

                assertThrows(
                        GitHubReleaseClient.UnverifiedReleaseException.class,
                        () -> client(fixture).fetchLatestUpdate(
                                1_001_000L,
                                "1.1.0",
                                "app.plyvanta",
                                UpdateChannel.STABLE,
                                36
                        )
                );
                assertEquals(4, fixture.calls.get());
                assertEquals(2, fixture.metadataCalls.get());
            }
        }
    }

    @Test
    public void highestCompatibleSemanticReleaseIsSelectedRegardlessOfApiOrder()
            throws IOException {
        for (boolean higherReleaseFirst : new boolean[]{false, true}) {
            FixtureInterceptor fixture = twoNewerStableReleaseFixture(
                    higherReleaseFirst,
                    1_002_000L,
                    1_003_000L,
                    26
            );

            UpdateRelease update = client(fixture).fetchLatestUpdate(
                    1_001_000L,
                    "1.1.0",
                    "app.plyvanta",
                    UpdateChannel.STABLE,
                    36
            );

            assertNotNull(update);
            assertEquals("1.3.0", update.getVersionName());
            assertEquals(4, fixture.calls.get());
            assertEquals(2, fixture.metadataCalls.get());
        }
    }

    @Test
    public void higherMinimumSdkFallsBackToCompatibleSemanticRelease()
            throws IOException {
        for (boolean higherReleaseFirst : new boolean[]{false, true}) {
            FixtureInterceptor fixture = twoNewerStableReleaseFixture(
                    higherReleaseFirst,
                    1_002_000L,
                    1_003_000L,
                    99
            );

            UpdateRelease update = client(fixture).fetchLatestUpdate(
                    1_001_000L,
                    "1.1.0",
                    "app.plyvanta",
                    UpdateChannel.STABLE,
                    36
            );

            assertNotNull(update);
            assertEquals("1.2.0", update.getVersionName());
            assertEquals(4, fixture.calls.get());
            assertEquals(2, fixture.metadataCalls.get());
        }
    }

    @Test
    public void canonicalRepositoryMismatchIsRejectedAsUnverifiedNewerRelease() {
        FixtureInterceptor fixture = standardFixture(
                releaseList(
                        LEGACY_REPOSITORY,
                        false,
                        false,
                        "v1.2.0",
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                "1.2.0",
                                SHA256,
                                METADATA_ASSET_ID,
                                APK_ASSET_ID
                        )
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_002_000L,
                        "1.2.0",
                        "Plyvanta-1.2.0.apk",
                        SHA256
                )
        );

        assertThrows(
                GitHubReleaseClient.UnverifiedReleaseException.class,
                () -> client(fixture).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );
        assertEquals(2, fixture.calls.get());
        assertEquals(0, fixture.metadataCalls.get());
    }

    @Test
    public void recognizableNewerTagWithInvalidPublishedShapeIsUnverified() {
        String newerRelease = stableRelease(
                "1.2.0",
                SHA256,
                301L,
                302L
        );

        assertInvalidNewerPublishedShapeIsUnverified(
                newerRelease.replace("\"draft\":false,", "")
        );
        assertInvalidNewerPublishedShapeIsUnverified(
                newerRelease.replace("\"prerelease\":false,", "")
        );
        assertInvalidNewerPublishedShapeIsUnverified(
                newerRelease.replace("\"prerelease\":false", "\"prerelease\":true")
        );
    }

    @Test
    public void explicitNewerDraftRemainsIgnored() throws IOException {
        String currentVersion = "1.1.0";
        String newerVersion = "1.2.0";
        String releases = combineReleaseLists(
                stableRelease(currentVersion, SHA256, 201L, 202L),
                stableRelease(newerVersion, SHA256, 301L, 302L)
                        .replace("\"draft\":false", "\"draft\":true")
        );
        String currentMetadataUrl = metadataDownloadUrl(currentVersion);
        String newerMetadataUrl = metadataDownloadUrl(newerVersion);
        FixtureInterceptor fixture = repositoryFixture(
                releases,
                Map.of(
                        currentMetadataUrl,
                        stableMetadata(currentVersion, 1_001_000L, SHA256)
                )
        );

        assertNull(client(fixture).fetchLatestUpdate(
                1_001_000L,
                currentVersion,
                "app.plyvanta",
                UpdateChannel.STABLE,
                36
        ));

        assertEquals(3, fixture.calls.get());
        assertEquals(1, fixture.metadataCalls.get());
        assertFalse(fixture.requestedUrls.contains(newerMetadataUrl));
    }

    @Test
    public void higherMalformedPublishedShapeBlocksLowerVerifiedCandidate() {
        String lowerVersion = "1.2.0";
        String higherVersion = "1.3.0";
        String releases = combineReleaseLists(
                stableRelease(lowerVersion, SHA256, 201L, 202L),
                stableRelease(higherVersion, SHA256, 301L, 302L)
                        .replace("\"draft\":false,", "")
        );
        FixtureInterceptor fixture = repositoryFixture(
                releases,
                Map.of(
                        metadataDownloadUrl(lowerVersion),
                        stableMetadata(lowerVersion, 1_002_000L, SHA256)
                )
        );

        assertThrows(
                GitHubReleaseClient.UnverifiedReleaseException.class,
                () -> client(fixture).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );

        assertEquals(3, fixture.calls.get());
        assertEquals(1, fixture.metadataCalls.get());
    }

    @Test
    public void higherRejectedReleaseBlocksLowerVerifiedCandidate() {
        String lowerVersion = "1.2.0";
        String higherVersion = "1.3.0";
        String releases = combineReleaseLists(
                stableRelease(lowerVersion, SHA256, 201L, 202L),
                stableRelease(higherVersion, OTHER_SHA256, 301L, 302L)
        );
        FixtureInterceptor fixture = repositoryFixture(
                releases,
                Map.of(
                        metadataDownloadUrl(lowerVersion),
                        stableMetadata(lowerVersion, 1_002_000L, SHA256),
                        metadataDownloadUrl(higherVersion),
                        stableMetadata(higherVersion, 1_003_000L, SHA256)
                )
        );

        assertThrows(
                GitHubReleaseClient.UnverifiedReleaseException.class,
                () -> client(fixture).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );

        assertEquals(4, fixture.calls.get());
        assertEquals(2, fixture.metadataCalls.get());
    }

    @Test
    public void lowerRejectedReleaseDoesNotBlockHigherVerifiedCandidate()
            throws IOException {
        String lowerVersion = "1.2.0";
        String higherVersion = "1.3.0";
        String releases = combineReleaseLists(
                stableRelease(lowerVersion, OTHER_SHA256, 201L, 202L),
                stableRelease(higherVersion, SHA256, 301L, 302L)
        );
        FixtureInterceptor fixture = repositoryFixture(
                releases,
                Map.of(
                        metadataDownloadUrl(lowerVersion),
                        stableMetadata(lowerVersion, 1_002_000L, SHA256),
                        metadataDownloadUrl(higherVersion),
                        stableMetadata(higherVersion, 1_003_000L, SHA256)
                )
        );

        UpdateRelease update = client(fixture).fetchLatestUpdate(
                1_001_000L,
                "1.1.0",
                "app.plyvanta",
                UpdateChannel.STABLE,
                36
        );

        assertNotNull(update);
        assertEquals(higherVersion, update.getVersionName());
        assertEquals(4, fixture.calls.get());
        assertEquals(2, fixture.metadataCalls.get());
    }

    @Test
    public void foreignMetadataAssetCoordinatesAreRejectedBeforeFetch() {
        String assets = standardAssets(
                CANONICAL_REPOSITORY,
                "1.2.0",
                SHA256,
                METADATA_ASSET_ID,
                APK_ASSET_ID
        ).replace(
                assetApiUrl(CANONICAL_REPOSITORY, METADATA_ASSET_ID),
                assetApiUrl("attacker/Plyvanta", METADATA_ASSET_ID)
        );
        FixtureInterceptor fixture = standardFixture(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v1.2.0",
                        assets
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_002_000L,
                        "1.2.0",
                        "Plyvanta-1.2.0.apk",
                        SHA256
                )
        );

        assertThrows(
                GitHubReleaseClient.UnverifiedReleaseException.class,
                () -> client(fixture).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );
        assertEquals(2, fixture.calls.get());
        assertEquals(0, fixture.metadataCalls.get());
    }

    @Test
    public void foreignApkDownloadCoordinatesAreRejectedAfterMetadataFetch() {
        String apkName = "Plyvanta-1.2.0.apk";
        String assets = standardAssets(
                CANONICAL_REPOSITORY,
                "1.2.0",
                SHA256,
                METADATA_ASSET_ID,
                APK_ASSET_ID
        ).replace(
                assetDownloadUrl(CANONICAL_REPOSITORY, "1.2.0", apkName),
                assetDownloadUrl("attacker/Plyvanta", "1.2.0", apkName)
        );
        FixtureInterceptor fixture = standardFixture(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v1.2.0",
                        assets
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_002_000L,
                        "1.2.0",
                        apkName,
                        SHA256
                )
        );

        assertThrows(
                GitHubReleaseClient.UnverifiedReleaseException.class,
                () -> client(fixture).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );
        assertEquals(3, fixture.calls.get());
        assertEquals(1, fixture.metadataCalls.get());
    }

    @Test
    public void foreignApkAssetApiCoordinatesAreRejectedAfterMetadataFetch() {
        String assets = standardAssets(
                CANONICAL_REPOSITORY,
                "1.2.0",
                SHA256,
                METADATA_ASSET_ID,
                APK_ASSET_ID
        ).replace(
                assetApiUrl(CANONICAL_REPOSITORY, APK_ASSET_ID),
                assetApiUrl("attacker/Plyvanta", APK_ASSET_ID)
        );
        FixtureInterceptor fixture = standardFixture(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v1.2.0",
                        assets
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_002_000L,
                        "1.2.0",
                        "Plyvanta-1.2.0.apk",
                        SHA256
                )
        );

        assertThrows(
                GitHubReleaseClient.UnverifiedReleaseException.class,
                () -> client(fixture).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );
        assertEquals(3, fixture.calls.get());
        assertEquals(1, fixture.metadataCalls.get());
    }

    @Test
    public void unexpectedRepositoryIdentityFailsBeforeReleaseLookup() {
        FixtureInterceptor wrongId = new FixtureInterceptor(
                repositoryIdentity(
                        GitHubReleaseClient.TRUSTED_REPOSITORY_ID + 1L,
                        CANONICAL_REPOSITORY
                ),
                "[]",
                Map.of()
        );
        FixtureInterceptor malformedFullName = new FixtureInterceptor(
                repositoryIdentity(
                        GitHubReleaseClient.TRUSTED_REPOSITORY_ID,
                        "Plyvanta/Plyvanta/extra"
                ),
                "[]",
                Map.of()
        );

        assertThrows(
                IOException.class,
                () -> client(wrongId).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );
        assertThrows(
                IOException.class,
                () -> client(malformedFullName).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );
        assertEquals(1, wrongId.calls.get());
        assertEquals(1, malformedFullName.calls.get());
    }

    @Test
    public void invalidNewerReleaseThrowsUnverifiedReleaseException() {
        FixtureInterceptor fixture = standardFixture(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v1.2.0",
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                "1.2.0",
                                OTHER_SHA256,
                                METADATA_ASSET_ID,
                                APK_ASSET_ID
                        )
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_002_000L,
                        "1.2.0",
                        "Plyvanta-1.2.0.apk",
                        SHA256
                )
        );

        GitHubReleaseClient.UnverifiedReleaseException error = assertThrows(
                GitHubReleaseClient.UnverifiedReleaseException.class,
                () -> client(fixture).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );

        assertTrue(error.getMessage().contains("newer"));
        assertEquals(3, fixture.calls.get());
    }

    @Test
    public void newerTagWithNonMonotonicVersionCodeIsUnverified() {
        FixtureInterceptor fixture = standardFixture(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v1.2.0",
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                "1.2.0",
                                SHA256,
                                METADATA_ASSET_ID,
                                APK_ASSET_ID
                        )
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_001_000L,
                        "1.2.0",
                        "Plyvanta-1.2.0.apk",
                        SHA256
                )
        );

        assertThrows(
                GitHubReleaseClient.UnverifiedReleaseException.class,
                () -> client(fixture).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );
        assertEquals(3, fixture.calls.get());
    }

    @Test
    public void verifiedNewerReleaseForUnsupportedSdkIsNotReportedAsTampered()
            throws IOException {
        FixtureInterceptor fixture = standardFixture(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v1.2.0",
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                "1.2.0",
                                SHA256,
                                METADATA_ASSET_ID,
                                APK_ASSET_ID
                        )
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_002_000L,
                        "1.2.0",
                        "Plyvanta-1.2.0.apk",
                        SHA256
                ).replace("\"minimumSdk\":26", "\"minimumSdk\":99")
        );

        assertNull(client(fixture).fetchLatestUpdate(
                1_001_000L,
                "1.1.0",
                "app.plyvanta",
                UpdateChannel.STABLE,
                36
        ));
        assertEquals(3, fixture.calls.get());
    }

    @Test
    public void mutableNewerReleaseThrowsWithoutFetchingMetadata() {
        String mutableRelease = releaseList(
                CANONICAL_REPOSITORY,
                false,
                false,
                "v1.2.0",
                standardAssets(
                        CANONICAL_REPOSITORY,
                        "1.2.0",
                        SHA256,
                        METADATA_ASSET_ID,
                        APK_ASSET_ID
                )
        ).replace("\"immutable\":true", "\"immutable\":false");
        FixtureInterceptor fixture = standardFixture(
                mutableRelease,
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_002_000L,
                        "1.2.0",
                        "Plyvanta-1.2.0.apk",
                        SHA256
                )
        );

        assertThrows(
                GitHubReleaseClient.UnverifiedReleaseException.class,
                () -> client(fixture).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );
        assertEquals(2, fixture.calls.get());
        assertEquals(0, fixture.metadataCalls.get());
    }

    @Test
    public void malformedAndOversizedReleaseResponsesFailClosed() {
        FixtureInterceptor malformed = new FixtureInterceptor(
                repositoryIdentity(
                        GitHubReleaseClient.TRUSTED_REPOSITORY_ID,
                        CANONICAL_REPOSITORY
                ),
                "{not-json",
                Map.of()
        );
        char[] oversized = new char[GitHubReleaseClient.MAX_RELEASE_RESPONSE_BYTES + 1];
        Arrays.fill(oversized, ' ');
        FixtureInterceptor tooLarge = new FixtureInterceptor(
                repositoryIdentity(
                        GitHubReleaseClient.TRUSTED_REPOSITORY_ID,
                        CANONICAL_REPOSITORY
                ),
                new String(oversized),
                Map.of()
        );

        assertThrows(
                IOException.class,
                () -> client(malformed).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );
        assertThrows(
                IOException.class,
                () -> client(tooLarge).fetchLatestUpdate(
                        1_001_000L,
                        "1.1.0",
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );
        assertEquals(2, malformed.calls.get());
        assertEquals(2, tooLarge.calls.get());
    }

    @Test
    public void requestsAreAnonymousAndIdentifyTheReadOnlyClient() throws IOException {
        FixtureInterceptor fixture = standardFixture(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v1.2.0",
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                "1.2.0",
                                SHA256,
                                METADATA_ASSET_ID,
                                APK_ASSET_ID
                        )
                ),
                metadata(
                        "app.plyvanta",
                        "stable",
                        1_002_000L,
                        "1.2.0",
                        "Plyvanta-1.2.0.apk",
                        SHA256
                )
        );

        assertNull(client(fixture).fetchLatestUpdate(
                1_002_000L,
                "1.2.0",
                "app.plyvanta",
                UpdateChannel.STABLE,
                36
        ));

        assertFalse(fixture.requestedUrls.isEmpty());
        assertFalse(fixture.authorizationObserved);
        for (String userAgent : fixture.userAgents) {
            assertTrue(userAgent.startsWith("Plyvanta-Update-Checker/"));
        }
    }

    @Test
    public void retriesATransientConnectionFailureOnceOnTheOriginalCall() throws IOException {
        FixtureInterceptor fixture = currentReleaseFixture();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger createdCalls = new AtomicInteger();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new ConnectException("Temporary connection failure");
                    }
                    return fixture.intercept(chain);
                })
                .build();
        Call.Factory factory = request -> {
            createdCalls.incrementAndGet();
            return httpClient.newCall(request);
        };

        assertNull(fetchCurrent(new GitHubReleaseClient(
                factory,
                HttpUrl.get(GitHubReleaseClient.REPOSITORY_API_URL),
                HttpUrl.get(GitHubReleaseClient.RELEASES_API_URL)
        )));

        assertEquals(4, attempts.get());
        assertEquals(3, createdCalls.get());
        assertEquals(3, fixture.calls.get());
    }

    @Test
    public void doesNotKeepRetryingAConnectionFailure() {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    attempts.incrementAndGet();
                    throw new ConnectException("Connection unavailable");
                })
                .build();

        assertThrows(ConnectException.class, () -> fetchCurrent(new GitHubReleaseClient(
                httpClient,
                HttpUrl.get(GitHubReleaseClient.REPOSITORY_API_URL),
                HttpUrl.get(GitHubReleaseClient.RELEASES_API_URL)
        )));

        assertEquals(2, attempts.get());
    }

    @Test
    public void doesNotRetryGitHubRateLimitResponses() {
        for (int statusCode : new int[] {403, 429}) {
            AtomicInteger attempts = new AtomicInteger();
            OkHttpClient httpClient = new OkHttpClient.Builder()
                    .addInterceptor(chain -> {
                        attempts.incrementAndGet();
                        return new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(statusCode)
                                .message("Rate limited")
                                .body(ResponseBody.create("{}", FixtureInterceptor.JSON))
                                .build();
                    })
                    .build();

            GitHubReleaseClient.HttpException failure = assertThrows(
                    GitHubReleaseClient.HttpException.class,
                    () -> fetchCurrent(new GitHubReleaseClient(
                            httpClient,
                            HttpUrl.get(GitHubReleaseClient.REPOSITORY_API_URL),
                            HttpUrl.get(GitHubReleaseClient.RELEASES_API_URL)
                    ))
            );

            assertEquals(statusCode, failure.getStatusCode());
            assertEquals(1, attempts.get());
        }
    }

    @Test
    public void doesNotRetryACanceledCallOntoANewRoute() {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    attempts.incrementAndGet();
                    chain.call().cancel();
                    throw new ConnectException("Old network route canceled");
                })
                .build();

        assertThrows(IOException.class, () -> fetchCurrent(new GitHubReleaseClient(
                httpClient,
                HttpUrl.get(GitHubReleaseClient.REPOSITORY_API_URL),
                HttpUrl.get(GitHubReleaseClient.RELEASES_API_URL)
        )));

        assertEquals(1, attempts.get());
    }

    @Test
    public void doesNotRetryWhenTheCheckingThreadIsInterrupted() {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    attempts.incrementAndGet();
                    Thread.currentThread().interrupt();
                    throw new ConnectException("Interrupted connection");
                })
                .build();
        try {
            assertThrows(IOException.class, () -> fetchCurrent(new GitHubReleaseClient(
                    httpClient,
                    HttpUrl.get(GitHubReleaseClient.REPOSITORY_API_URL),
                    HttpUrl.get(GitHubReleaseClient.RELEASES_API_URL)
            )));
            assertEquals(1, attempts.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void anAlreadyInterruptedCheckCreatesNoCalls() {
        AtomicInteger createdCalls = new AtomicInteger();
        Call.Factory factory = request -> {
            createdCalls.incrementAndGet();
            throw new AssertionError("Interrupted check must not create a call");
        };
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> fetchCurrent(
                    new GitHubReleaseClient(
                            factory,
                            HttpUrl.get(GitHubReleaseClient.REPOSITORY_API_URL),
                            HttpUrl.get(GitHubReleaseClient.RELEASES_API_URL)
                    )
            ));
            assertEquals(0, createdCalls.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void exhaustedSharedBudgetStopsBeforeRequestingMetadata() {
        FixtureInterceptor fixture = currentReleaseFixture();
        AtomicLong clock = new AtomicLong();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Response response = fixture.intercept(chain);
                    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(600L));
                    return response;
                })
                .build();

        assertThrows(SocketTimeoutException.class, () -> fetchCurrent(new GitHubReleaseClient(
                httpClient,
                HttpUrl.get(GitHubReleaseClient.REPOSITORY_API_URL),
                HttpUrl.get(GitHubReleaseClient.RELEASES_API_URL),
                1_000L,
                clock::get
        )));

        assertEquals(2, fixture.calls.get());
        assertEquals(0, fixture.metadataCalls.get());
    }

    @Test
    public void cumulativeSocketRequestsShareOneDeadline() throws Exception {
        FixtureInterceptor fixture = currentReleaseFixture();
        try (DelayedJsonServer server = new DelayedJsonServer(
                fixture.repository,
                fixture.releases,
                fixture.metadataByDownloadUrl.get(metadataDownloadUrl("1.2.0")),
                250L,
                650L
        )) {
            OkHttpClient httpClient = new OkHttpClient.Builder()
                    .retryOnConnectionFailure(false)
                    .addInterceptor(chain -> chain.proceed(
                            chain.request().url().host().equals("github.com")
                                    ? chain.request().newBuilder()
                                            .url(server.url("/metadata"))
                                            .build()
                                    : chain.request()
                    ))
                    .build();
            GitHubReleaseClient releaseClient = new GitHubReleaseClient(
                    httpClient,
                    server.url("/repository"),
                    server.url("/releases"),
                    900L
            );
            long started = System.nanoTime();

            assertThrows(SocketTimeoutException.class, () -> fetchCurrent(releaseClient));

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(3, server.requests.get());
            assertTrue("Deadline fired too early: " + elapsedMillis, elapsedMillis >= 700L);
            assertTrue("Sequential requests exceeded their budget: " + elapsedMillis,
                    elapsedMillis < 1_800L);
        }
    }

    @Test
    public void checkBudgetDoesNotExtendAShorterExistingCallTimeout() throws Exception {
        FixtureInterceptor fixture = currentReleaseFixture();
        try (DelayedJsonServer server = new DelayedJsonServer(
                fixture.repository,
                fixture.releases,
                fixture.metadataByDownloadUrl.get(metadataDownloadUrl("1.2.0")),
                700L,
                0L
        )) {
            OkHttpClient httpClient = new OkHttpClient.Builder()
                    .callTimeout(250L, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false)
                    .build();
            GitHubReleaseClient releaseClient = new GitHubReleaseClient(
                    httpClient,
                    server.url("/repository"),
                    server.url("/releases"),
                    2_000L
            );
            long started = System.nanoTime();

            assertThrows(IOException.class, () -> fetchCurrent(releaseClient));

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(1, server.requests.get());
            assertTrue("Existing call timeout was extended: " + elapsedMillis,
                    elapsedMillis < 1_000L);
        }
    }

    private static UpdateRelease fetchCurrent(GitHubReleaseClient client) throws IOException {
        return client.fetchLatestUpdate(
                1_002_000L,
                "1.2.0",
                "app.plyvanta",
                UpdateChannel.STABLE,
                36
        );
    }

    private static FixtureInterceptor currentReleaseFixture() {
        return standardFixture(
                releaseList(
                        CANONICAL_REPOSITORY,
                        false,
                        false,
                        "v1.2.0",
                        standardAssets(
                                CANONICAL_REPOSITORY,
                                "1.2.0",
                                SHA256,
                                METADATA_ASSET_ID,
                                APK_ASSET_ID
                        )
                ),
                stableMetadata("1.2.0", 1_002_000L, SHA256)
        );
    }

    private static GitHubReleaseClient client(FixtureInterceptor fixture) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(fixture)
                .build();
        return new GitHubReleaseClient(
                httpClient,
                HttpUrl.get(GitHubReleaseClient.REPOSITORY_API_URL),
                HttpUrl.get(GitHubReleaseClient.RELEASES_API_URL)
        );
    }

    private static FixtureInterceptor standardFixture(
            String releases,
            String metadata
    ) {
        return repositoryFixture(
                releases,
                Map.of(
                        assetDownloadUrl(
                                CANONICAL_REPOSITORY,
                                "1.2.0",
                                "Plyvanta-1.2.0-update.json"
                        ),
                        metadata
                )
        );
    }

    private static FixtureInterceptor repositoryFixture(
            String releases,
            Map<String, String> metadataByDownloadUrl
    ) {
        return new FixtureInterceptor(
                repositoryIdentity(
                        GitHubReleaseClient.TRUSTED_REPOSITORY_ID,
                        CANONICAL_REPOSITORY
                ),
                releases,
                metadataByDownloadUrl
        );
    }

    private static FixtureInterceptor twoNewerStableReleaseFixture(
            boolean higherReleaseFirst,
            long lowerVersionCode,
            long higherVersionCode,
            int higherMinimumSdk
    ) {
        String lowerVersion = "1.2.0";
        String higherVersion = "1.3.0";
        String lowerRelease = stableRelease(lowerVersion, SHA256, 201L, 202L);
        String higherRelease = stableRelease(higherVersion, SHA256, 301L, 302L);
        String releases = higherReleaseFirst
                ? combineReleaseLists(higherRelease, lowerRelease)
                : combineReleaseLists(lowerRelease, higherRelease);
        return repositoryFixture(
                releases,
                Map.of(
                        metadataDownloadUrl(lowerVersion),
                        stableMetadata(lowerVersion, lowerVersionCode, SHA256),
                        metadataDownloadUrl(higherVersion),
                        stableMetadata(
                                higherVersion,
                                higherVersionCode,
                                SHA256,
                                higherMinimumSdk
                        )
                )
        );
    }

    private static void assertInvalidNewerPublishedShapeIsUnverified(
            String invalidNewerRelease
    ) {
        String currentVersion = "1.1.0";
        String newerVersion = "1.2.0";
        FixtureInterceptor fixture = repositoryFixture(
                combineReleaseLists(
                        stableRelease(currentVersion, SHA256, 201L, 202L),
                        invalidNewerRelease
                ),
                Map.of(
                        metadataDownloadUrl(currentVersion),
                        stableMetadata(currentVersion, 1_001_000L, SHA256)
                )
        );

        assertThrows(
                GitHubReleaseClient.UnverifiedReleaseException.class,
                () -> client(fixture).fetchLatestUpdate(
                        1_001_000L,
                        currentVersion,
                        "app.plyvanta",
                        UpdateChannel.STABLE,
                        36
                )
        );
        assertEquals(3, fixture.calls.get());
        assertEquals(1, fixture.metadataCalls.get());
        assertFalse(
                fixture.requestedUrls.contains(metadataDownloadUrl(newerVersion))
        );
    }

    private static String repositoryIdentity(long id, String fullName) {
        return "{"
                + "\"id\":" + id + ","
                + "\"full_name\":\"" + fullName + "\""
                + "}";
    }

    private static String releaseList(
            String repositoryFullName,
            boolean draft,
            boolean prerelease,
            String tag,
            String assets
    ) {
        return "[{"
                + "\"draft\":" + draft + ","
                + "\"prerelease\":" + prerelease + ","
                + "\"immutable\":true,"
                + "\"tag_name\":\"" + tag + "\","
                + "\"html_url\":\"https://github.com/" + repositoryFullName
                + "/releases/tag/" + tag + "\","
                + "\"assets\":[" + assets + "]"
                + "}]";
    }

    private static String stableRelease(
            String versionName,
            String digest,
            long metadataAssetId,
            long apkAssetId
    ) {
        return releaseList(
                CANONICAL_REPOSITORY,
                false,
                false,
                "v" + versionName,
                standardAssets(
                        CANONICAL_REPOSITORY,
                        versionName,
                        digest,
                        metadataAssetId,
                        apkAssetId
                )
        );
    }

    private static String standardAssets(
            String repositoryFullName,
            String versionName,
            String digest,
            long metadataAssetId,
            long apkAssetId
    ) {
        String metadataName = "Plyvanta-" + versionName + "-update.json";
        String apkName = "Plyvanta-" + versionName + ".apk";
        return asset(
                metadataName,
                "application/json",
                assetApiUrl(repositoryFullName, metadataAssetId),
                assetDownloadUrl(repositoryFullName, versionName, metadataName),
                null
        ) + "," + asset(
                apkName,
                "application/vnd.android.package-archive",
                assetApiUrl(repositoryFullName, apkAssetId),
                assetDownloadUrl(repositoryFullName, versionName, apkName),
                "sha256:" + digest
        );
    }

    private static String assetApiUrl(String repositoryFullName, long assetId) {
        return "https://api.github.com/repos/" + repositoryFullName
                + "/releases/assets/" + assetId;
    }

    private static String assetDownloadUrl(
            String repositoryFullName,
            String versionName,
            String assetName
    ) {
        return "https://github.com/" + repositoryFullName
                + "/releases/download/v" + versionName + "/" + assetName;
    }

    private static String metadataDownloadUrl(String versionName) {
        return assetDownloadUrl(
                CANONICAL_REPOSITORY,
                versionName,
                "Plyvanta-" + versionName + "-update.json"
        );
    }

    private static String combineReleaseLists(String... releaseLists) {
        StringBuilder combined = new StringBuilder("[");
        for (int index = 0; index < releaseLists.length; index++) {
            if (index > 0) {
                combined.append(',');
            }
            String releases = releaseLists[index];
            combined.append(releases, 1, releases.length() - 1);
        }
        return combined.append(']').toString();
    }

    private static String asset(
            String name,
            String contentType,
            String apiUrl,
            String downloadUrl,
            String digest
    ) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"content_type\":\"" + contentType + "\","
                + "\"url\":\"" + apiUrl + "\","
                + "\"browser_download_url\":\"" + downloadUrl + "\","
                + "\"digest\":" + (digest == null ? "null" : "\"" + digest + "\"")
                + "}";
    }

    private static String metadata(
            String packageName,
            String channel,
            long versionCode,
            String versionName,
            String apkName,
            String sha256
    ) {
        return "{"
                + "\"schemaVersion\":1,"
                + "\"packageName\":\"" + packageName + "\","
                + "\"channel\":\"" + channel + "\","
                + "\"versionCode\":" + versionCode + ","
                + "\"versionName\":\"" + versionName + "\","
                + "\"minimumSdk\":26,"
                + "\"apkName\":\"" + apkName + "\","
                + "\"sha256\":\"" + sha256 + "\""
                + "}";
    }

    private static String stableMetadata(
            String versionName,
            long versionCode,
            String sha256
    ) {
        return metadata(
                "app.plyvanta",
                "stable",
                versionCode,
                versionName,
                "Plyvanta-" + versionName + ".apk",
                sha256
        );
    }

    private static String stableMetadata(
            String versionName,
            long versionCode,
            String sha256,
            int minimumSdk
    ) {
        return stableMetadata(versionName, versionCode, sha256).replace(
                "\"minimumSdk\":26",
                "\"minimumSdk\":" + minimumSdk
        );
    }

    private static final class DelayedJsonServer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread worker;
        private final AtomicInteger requests = new AtomicInteger();
        private volatile Socket activeSocket;

        private DelayedJsonServer(
                String repository,
                String releases,
                String metadata,
                long apiDelayMillis,
                long metadataDelayMillis
        ) throws IOException {
            listener = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        activeSocket = socket;
                        socket.setSoTimeout(2_000);
                        BufferedReader request = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII
                        ));
                        String requestLine = request.readLine();
                        if (requestLine == null) {
                            continue;
                        }
                        String header;
                        do {
                            header = request.readLine();
                        } while (header != null && !header.isEmpty());
                        String path = requestLine.split(" ")[1];
                        requests.incrementAndGet();
                        Thread.sleep(path.equals("/metadata")
                                ? metadataDelayMillis : apiDelayMillis);
                        String json = path.equals("/repository") ? repository
                                : path.equals("/releases") ? releases : metadata;
                        byte[] body = json.getBytes(StandardCharsets.UTF_8);
                        String responseHeaders = "HTTP/1.1 200 OK\r\n"
                                + "Content-Type: application/json\r\n"
                                + "Content-Length: " + body.length + "\r\n"
                                + "Connection: close\r\n\r\n";
                        socket.getOutputStream().write(
                                responseHeaders.getBytes(StandardCharsets.US_ASCII)
                        );
                        socket.getOutputStream().write(body);
                        socket.getOutputStream().flush();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (IOException closedConnection) {
                        // Deadline tests deliberately close their sockets before a reply.
                        if (listener.isClosed()) {
                            return;
                        }
                    } finally {
                        activeSocket = null;
                    }
                }
            }, "Plyvanta update deadline test server");
            worker.setDaemon(true);
            worker.start();
        }

        private HttpUrl url(String path) {
            return new HttpUrl.Builder()
                    .scheme("http")
                    .host("127.0.0.1")
                    .port(listener.getLocalPort())
                    .encodedPath(path)
                    .build();
        }

        @Override
        public void close() throws Exception {
            listener.close();
            Socket socket = activeSocket;
            if (socket != null) {
                socket.close();
            }
            worker.interrupt();
            worker.join(1_000L);
        }
    }

    private static final class FixtureInterceptor implements Interceptor {
        private static final MediaType JSON =
                MediaType.get("application/json; charset=utf-8");

        private final String repository;
        private final String releases;
        private final Map<String, String> metadataByDownloadUrl;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger metadataCalls = new AtomicInteger();
        private final List<String> requestedUrls = new ArrayList<>();
        private final List<String> userAgents = new ArrayList<>();
        private final Map<String, String> acceptByUrl = new LinkedHashMap<>();
        private boolean authorizationObserved;

        private FixtureInterceptor(
                String repository,
                String releases,
                Map<String, String> metadataByDownloadUrl
        ) {
            this.repository = repository;
            this.releases = releases;
            this.metadataByDownloadUrl = metadataByDownloadUrl;
        }

        @Override
        public Response intercept(Chain chain) {
            calls.incrementAndGet();
            String requestUrl = chain.request().url().toString();
            requestedUrls.add(requestUrl);
            userAgents.add(chain.request().header("User-Agent"));
            acceptByUrl.put(requestUrl, chain.request().header("Accept"));
            authorizationObserved |= chain.request().header("Authorization") != null;

            String body;
            int status;
            if (GitHubReleaseClient.REPOSITORY_API_URL.equals(requestUrl)) {
                body = repository;
                status = 200;
            } else if (GitHubReleaseClient.RELEASES_API_URL.equals(requestUrl)) {
                body = releases;
                status = 200;
            } else if (metadataByDownloadUrl.containsKey(requestUrl)) {
                metadataCalls.incrementAndGet();
                body = metadataByDownloadUrl.get(requestUrl);
                status = 200;
            } else {
                body = "{}";
                status = 404;
            }
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(status == 200 ? "fixture" : "unexpected fixture URL")
                    .body(ResponseBody.create(body, JSON))
                    .build();
        }
    }
}
