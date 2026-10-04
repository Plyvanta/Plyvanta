package app.plyvanta.offline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import app.plyvanta.playback.ResolvedVideo;
import app.plyvanta.network.AppNetwork;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

public final class OfflineDownloadManagerTest {
    private static final String TRUSTED_MEDIA_URL =
            "https://r1---sn-fixture.googlevideo.com/videoplayback?id=test";
    private static final MediaType VIDEO_MEDIA_TYPE =
            MediaType.get("video/mp4");

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void exact200StreamsDirectlyIntoEncryptedStoreAndRoundTrips()
            throws Exception {
        Path root = newRoot("complete");
        TestContentKeyProtector protector = new TestContentKeyProtector();
        OfflineMediaStore store = new OfflineMediaStore(root, protector);
        byte[] media = patternedBytes(
                EncryptedChunkFile.CHUNK_SIZE_BYTES + 31_337
        );
        AtomicReference<Request> capturedRequest = new AtomicReference<>();
        List<Long> progress = new ArrayList<>();
        OkHttpClient client = responseClient(
                ResponseFixture.complete(media),
                capturedRequest
        );

        OfflineMediaRecord record = new OfflineDownloadManager(
                store,
                client
        ).download(
                progressiveVideo(),
                new OfflineDownloadManager.Cancellation(),
                (track, downloaded, total) -> {
                    assertEquals(
                            OfflineDownloadManager.Track.PROGRESSIVE,
                            track
                    );
                    assertEquals(media.length, total);
                    progress.add(downloaded);
                }
        );

        Request request = capturedRequest.get();
        assertNotNull(request);
        assertEquals(TRUSTED_MEDIA_URL, request.url().toString());
        assertEquals("identity", request.header("Accept-Encoding"));
        assertNotNull(request.header("User-Agent"));
        assertEquals(media.length, record.getVideoPlaintextLength());
        assertEquals(0L, record.getAudioPlaintextLength());
        assertFalse(progress.isEmpty());
        assertEquals(Long.valueOf(0L), progress.get(0));
        assertEquals(
                Long.valueOf(media.length),
                progress.get(progress.size() - 1)
        );

        OfflineMediaStore.ListResult listed = store.list();
        assertEquals(0, listed.getCorruptCount());
        assertEquals(List.of(record), listed.getRecords());
        try (OfflineMediaStore.PlaybackSession playback =
                     store.open(record.getItemId());
             EncryptedChunkFile.Reader reader = playback.openTrack(
                     EncryptedChunkFile.TrackRole.PROGRESSIVE
             )) {
            assertArrayEquals(media, readAll(reader));
        }
        assertEquals(
                List.of(".nomedia", record.getItemId().toString()),
                childNames(root)
        );
    }

    @Test
    public void rejectsIncompleteOrAmbiguousResponsesAndCleansVault()
            throws Exception {
        byte[] media = patternedBytes(41_777);

        assertRejectedAndEmpty(
                "status-206",
                ResponseFixture.withLength(206, media, media.length)
        );
        assertRejectedAndEmpty(
                "content-range",
                ResponseFixture.withLength(200, media, media.length)
                        .header("Content-Range", "bytes 0-41776/41777")
        );
        assertRejectedAndEmpty(
                "encoded",
                ResponseFixture.withLength(200, media, media.length)
                        .header("Content-Encoding", "gzip")
        );
        assertRejectedAndEmpty(
                "unknown-length",
                ResponseFixture.withLength(200, media, -1L)
        );
        assertRejectedAndEmpty(
                "zero-length",
                ResponseFixture.withLength(200, new byte[0], 0L)
        );
        assertRejectedAndEmpty(
                "oversized-length",
                ResponseFixture.withLength(
                        200,
                        media,
                        OfflineMediaRecord.MAX_PLAINTEXT_TRACK_BYTES + 1L
                )
        );
        assertRejectedAndEmpty(
                "short-body",
                ResponseFixture.withLength(200, media, media.length + 1L)
        );
    }

    @Test
    public void cancellationDuringStreamingAbandonsCiphertextOnlyStaging()
            throws Exception {
        Path root = newRoot("cancel-stream");
        OfflineMediaStore store = new OfflineMediaStore(
                root,
                new TestContentKeyProtector()
        );
        byte[] media = patternedBytes(
                EncryptedChunkFile.CHUNK_SIZE_BYTES * 2 + 111
        );
        OfflineDownloadManager.Cancellation cancellation =
                new OfflineDownloadManager.Cancellation();

        OfflineDownloadManager.DownloadCancelledException exception =
                assertThrows(
                        OfflineDownloadManager.DownloadCancelledException.class,
                        () -> new OfflineDownloadManager(
                                store,
                                responseClient(
                                        ResponseFixture.complete(media),
                                        new AtomicReference<>()
                                )
                        ).download(
                                progressiveVideo(),
                                cancellation,
                                (track, downloaded, total) -> {
                                    if (downloaded > 0L) {
                                        cancellation.cancel();
                                    }
                                }
                        )
                );

        assertEquals(
                "Offline download was cancelled.",
                exception.getMessage()
        );
        assertTrue(cancellation.isCancelled());
        assertVaultEmpty(store, root);
    }

    @Test
    public void cancellationAtCommitGuardNeverPublishesItem()
            throws Exception {
        Path root = newRoot("cancel-commit");
        OfflineDownloadManager.Cancellation cancellation =
                new OfflineDownloadManager.Cancellation();
        TestContentKeyProtector protector = new TestContentKeyProtector();
        protector.setStatusHook(cancellation::cancel);
        OfflineMediaStore store = new OfflineMediaStore(root, protector);
        byte[] media = patternedBytes(77_777);

        assertThrows(
                OfflineDownloadManager.DownloadCancelledException.class,
                () -> new OfflineDownloadManager(
                        store,
                        responseClient(
                                ResponseFixture.complete(media),
                                new AtomicReference<>()
                        )
                ).download(
                        progressiveVideo(),
                        cancellation,
                        (track, downloaded, total) -> {
                        }
                )
        );

        assertTrue(cancellation.isCancelled());
        assertVaultEmpty(store, root);
    }

    @Test
    public void productionRedirectBoundaryRejectsHostEscapeAndTlsDowngrade()
            throws Exception {
        Method buildClient = Class.forName("app.plyvanta.network.NetworkRouter")
                .getDeclaredMethod(
                "buildClients", boolean.class, int.class
        );
        buildClient.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<AppNetwork.Profile, OkHttpClient> clients =
                (Map<AppNetwork.Profile, OkHttpClient>) buildClient.invoke(null, false, 9050);
        OkHttpClient client = clients.get(AppNetwork.Profile.OFFLINE);

        assertTrue(client.followRedirects());
        assertFalse(client.followSslRedirects());
        assertEquals(1, client.networkInterceptors().size());
        Interceptor boundary = client.networkInterceptors().get(0);

        AtomicBoolean proceeded = new AtomicBoolean();
        Request trustedFollowUp = request(
                "https://redirect.googlevideo.com/videoplayback"
        );
        Response response = boundary.intercept(
                chainFor(trustedFollowUp, proceeded)
        );
        response.close();
        assertTrue(proceeded.get());

        Request lookalikeHost = request(
                "https://googlevideo.com.attacker.example/videoplayback"
        );
        proceeded.set(false);
        assertThrows(
                IOException.class,
                () -> boundary.intercept(chainFor(lookalikeHost, proceeded))
        );
        assertFalse(proceeded.get());

        Request downgradedTarget = request(
                "http://redirect.googlevideo.com/videoplayback"
        );
        proceeded.set(false);
        assertThrows(
                IOException.class,
                () -> boundary.intercept(
                        chainFor(downgradedTarget, proceeded)
                )
        );
        assertFalse(proceeded.get());
    }

    private void assertRejectedAndEmpty(
            String fixtureName,
            ResponseFixture fixture
    ) throws Exception {
        Path root = newRoot(fixtureName);
        OfflineMediaStore store = new OfflineMediaStore(
                root,
                new TestContentKeyProtector()
        );

        assertThrows(
                IOException.class,
                () -> new OfflineDownloadManager(
                        store,
                        responseClient(fixture, new AtomicReference<>())
                ).download(
                        progressiveVideo(),
                        new OfflineDownloadManager.Cancellation(),
                        (track, downloaded, total) -> {
                        }
                )
        );

        assertVaultEmpty(store, root);
    }

    private static void assertVaultEmpty(
            OfflineMediaStore store,
            Path root
    ) throws Exception {
        OfflineMediaStore.ListResult listed = store.list();
        assertEquals(0, listed.getRecords().size());
        assertEquals(0, listed.getCorruptCount());
        assertEquals(List.of(".nomedia"), childNames(root));
    }

    private Path newRoot(String name) throws IOException {
        return temporaryFolder.newFolder(name).toPath();
    }

    private static OkHttpClient responseClient(
            ResponseFixture fixture,
            AtomicReference<Request> capturedRequest
    ) {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    capturedRequest.set(request);
                    return fixture.response(request);
                })
                .build();
    }

    private static ResolvedVideo progressiveVideo() {
        return new ResolvedVideo(
                "dQw4w9WgXcQ",
                "A fixture title",
                "Fixture uploader",
                301L,
                null,
                ResolvedVideo.SourceType.PROGRESSIVE,
                TRUSTED_MEDIA_URL,
                "video/mp4",
                null,
                null,
                720
        );
    }

    private static Request request(String url) {
        return new Request.Builder().url(url).get().build();
    }

    private static Interceptor.Chain chainFor(
            Request request,
            AtomicBoolean proceeded
    ) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if ("request".equals(method.getName())) {
                return request;
            }
            if ("proceed".equals(method.getName())) {
                proceeded.set(true);
                return ResponseFixture.complete(new byte[] {1})
                        .response((Request) arguments[0]);
            }
            throw new AssertionError(
                    "Redirect boundary unexpectedly called " + method.getName()
            );
        };
        return (Interceptor.Chain) Proxy.newProxyInstance(
                Interceptor.Chain.class.getClassLoader(),
                new Class<?>[] {Interceptor.Chain.class},
                handler
        );
    }

    private static byte[] readAll(EncryptedChunkFile.Reader reader)
            throws IOException {
        if (reader.length() > Integer.MAX_VALUE) {
            throw new IOException("Test media is too large.");
        }
        byte[] result = new byte[(int) reader.length()];
        int offset = 0;
        while (offset < result.length) {
            int count = reader.read(
                    offset,
                    result,
                    offset,
                    result.length - offset
            );
            if (count <= 0) {
                throw new IOException("Authenticated media ended early.");
            }
            offset += count;
        }
        return result;
    }

    private static byte[] patternedBytes(int length) {
        byte[] result = new byte[length];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (
                    (index * 137 + (index >>> 2) * 19 + 7) & 0xff
            );
        }
        return result;
    }

    private static List<String> childNames(Path directory)
            throws IOException {
        List<String> names = new ArrayList<>();
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            entries.forEach(path -> names.add(path.getFileName().toString()));
        }
        names.sort(String::compareTo);
        return names;
    }

    private static final class ResponseFixture {
        private final int code;
        private final byte[] body;
        private final long reportedLength;
        private final Map<String, String> headers = new HashMap<>();

        private ResponseFixture(
                int code,
                byte[] body,
                long reportedLength
        ) {
            this.code = code;
            this.body = body.clone();
            this.reportedLength = reportedLength;
        }

        private static ResponseFixture complete(byte[] body) {
            return withLength(200, body, body.length)
                    .header("Content-Length", Long.toString(body.length));
        }

        private static ResponseFixture withLength(
                int code,
                byte[] body,
                long reportedLength
        ) {
            return new ResponseFixture(code, body, reportedLength);
        }

        private ResponseFixture header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        private Response response(Request request) {
            Buffer source = new Buffer();
            source.write(body);
            Response.Builder response = new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Fixture")
                    .body(ResponseBody.create(
                            VIDEO_MEDIA_TYPE,
                            reportedLength,
                            source
                    ));
            headers.forEach(response::header);
            return response.build();
        }
    }

    private static final class TestContentKeyProtector
            implements ContentKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();
        private Runnable statusHook;
        private boolean deleted;

        @Override
        public synchronized Envelope wrap(byte[] contentKey, String itemId)
                throws KeyProtectionException {
            requireAvailable();
            if (contentKey == null
                    || contentKey.length != EncryptedChunkFile.KEY_SIZE_BYTES) {
                throw new InvalidEnvelopeException(
                        "Test protector received an invalid content key."
                );
            }
            keys.put(itemId, contentKey.clone());
            return Envelope.create(new byte[12], new byte[48]);
        }

        @Override
        public synchronized byte[] unwrap(Envelope envelope, String itemId)
                throws KeyProtectionException {
            requireAvailable();
            byte[] contentKey = keys.get(itemId);
            if (contentKey == null) {
                throw new InvalidEnvelopeException(
                        "Test protector has no key for this item."
                );
            }
            return contentKey.clone();
        }

        @Override
        public synchronized OfflineSecurityPolicy.Decision status() {
            if (statusHook != null) {
                statusHook.run();
            }
            return OfflineSecurityPolicy.Decision.forReason(
                    OfflineSecurityPolicy.Reason.ALLOWED
            );
        }

        @Override
        public synchronized void deleteKey() {
            for (byte[] key : keys.values()) {
                Arrays.fill(key, (byte) 0);
            }
            keys.clear();
            deleted = true;
        }

        private synchronized void setStatusHook(Runnable statusHook) {
            this.statusHook = statusHook;
        }

        private void requireAvailable() throws KeyUnavailableException {
            if (deleted) {
                throw new KeyUnavailableException(
                        "Test protector has been deleted."
                );
            }
        }
    }
}
