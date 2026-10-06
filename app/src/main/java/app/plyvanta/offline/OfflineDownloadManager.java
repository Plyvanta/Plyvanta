package app.plyvanta.offline;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import app.plyvanta.extractor.OkHttpDownloader;
import app.plyvanta.network.AppNetwork;
import app.plyvanta.playback.ResolvedVideo;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Direct-to-ciphertext downloader for finite media tracks. Callers enforce their
 * foreground or opted-in background job lifetime through Cancellation.
 */
public final class OfflineDownloadManager {
    public enum Track {
        PROGRESSIVE,
        VIDEO,
        AUDIO
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(Track track, long downloadedBytes, long totalBytes);
    }

    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Call> activeCall = new AtomicReference<>();
        private final BooleanSupplier operationGuard;

        public Cancellation() {
            this(() -> true);
        }

        /**
         * Binds network work and the final commit to the caller's foreground lifetime.
         * A failed or throwing guard permanently cancels this operation.
         */
        public Cancellation(BooleanSupplier operationGuard) {
            this.operationGuard = Objects.requireNonNull(operationGuard, "operationGuard");
        }

        public void cancel() {
            cancelled.set(true);
            Call call = activeCall.get();
            if (call != null) {
                call.cancel();
            }
        }

        public boolean isCancelled() {
            if (!cancelled.get()) {
                boolean active;
                try {
                    active = operationGuard.getAsBoolean();
                } catch (RuntimeException exception) {
                    active = false;
                }
                if (!active) {
                    cancel();
                }
            }
            return cancelled.get();
        }

        private void attach(Call call) {
            activeCall.set(call);
            if (isCancelled()) {
                call.cancel();
            }
        }

        private void detach(Call call) {
            activeCall.compareAndSet(call, null);
        }

        private void throwIfCancelled() throws DownloadCancelledException {
            if (isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new DownloadCancelledException();
            }
        }
    }

    public static final class DownloadCancelledException
            extends InterruptedIOException {
        private DownloadCancelledException() {
            super("Offline download was cancelled.");
        }
    }

    public static final class UnsupportedDownloadException extends IOException {
        public UnsupportedDownloadException(String message) {
            super(message);
        }
    }

    private final OfflineMediaStore store;
    private final Call.Factory httpClient;

    public OfflineDownloadManager(OfflineMediaStore store) {
        this(store, AppNetwork.calls(AppNetwork.Profile.OFFLINE));
    }

    OfflineDownloadManager(
            OfflineMediaStore store,
            Call.Factory httpClient
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /**
     * Downloads one resolved VOD synchronously on the caller's worker thread.
     */
    public OfflineMediaRecord download(
            ResolvedVideo video,
            Cancellation cancellation,
            ProgressListener listener
    ) throws IOException, ContentKeyProtector.KeyProtectionException {
        return download(video, UUID.randomUUID(), cancellation, listener);
    }

    /**
     * Downloads with a private stable identity so a stopped subscription job can
     * recognize an already published item without decrypting the offline catalog.
     */
    public OfflineMediaRecord download(
            ResolvedVideo video,
            UUID itemId,
            Cancellation cancellation,
            ProgressListener listener
    ) throws IOException, ContentKeyProtector.KeyProtectionException {
        Objects.requireNonNull(video, "video");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(listener, "listener");
        OfflineDownloadEligibility.Decision eligibility =
                OfflineDownloadEligibility.evaluate(video);
        if (!eligibility.isAllowed()) {
            throw new UnsupportedDownloadException(
                    "Resolved media is not eligible for secure offline storage: "
                            + eligibility.getReason()
            );
        }
        cancellation.throwIfCancelled();

        try (OfflineMediaStore.DownloadSession session = store.begin(itemId)) {
            long videoLength;
            long audioLength = 0L;
            if (video.getSourceType() == ResolvedVideo.SourceType.PROGRESSIVE) {
                videoLength = downloadTrack(
                        video.getVideoUrl(),
                        session,
                        EncryptedChunkFile.TrackRole.PROGRESSIVE,
                        Track.PROGRESSIVE,
                        cancellation,
                        listener
                );
            } else {
                videoLength = downloadTrack(
                        video.getVideoUrl(),
                        session,
                        EncryptedChunkFile.TrackRole.VIDEO,
                        Track.VIDEO,
                        cancellation,
                        listener
                );
                audioLength = downloadTrack(
                        video.getAudioUrl(),
                        session,
                        EncryptedChunkFile.TrackRole.AUDIO,
                        Track.AUDIO,
                        cancellation,
                        listener
                );
            }
            cancellation.throwIfCancelled();

            OfflineMediaRecord record = new OfflineMediaRecord(
                    session.getItemId(),
                    video.getVideoId(),
                    sanitizeText(
                            video.getTitle(),
                            "Offline video",
                            OfflineMediaRecord.MAX_TITLE_UTF8_BYTES
                    ),
                    sanitizeText(
                            video.getUploader(),
                            "",
                            OfflineMediaRecord.MAX_UPLOADER_UTF8_BYTES
                    ),
                    video.getDurationSeconds(),
                    video.getSourceType() == ResolvedVideo.SourceType.PROGRESSIVE
                            ? OfflineMediaRecord.SourceType.PROGRESSIVE
                            : OfflineMediaRecord.SourceType.MERGED,
                    video.getVideoMimeType(),
                    video.getSourceType() == ResolvedVideo.SourceType.MERGED
                            ? video.getAudioMimeType()
                            : null,
                    video.getSelectedHeight(),
                    videoLength,
                    audioLength,
                    System.currentTimeMillis()
            );
            try {
                session.commit(
                        record,
                        () -> !cancellation.isCancelled()
                                && !Thread.currentThread().isInterrupted()
                );
            } catch (IOException exception) {
                if (cancellation.isCancelled()
                        || Thread.currentThread().isInterrupted()) {
                    throw new DownloadCancelledException();
                }
                throw exception;
            }
            return record;
        }
    }

    private long downloadTrack(
            String url,
            OfflineMediaStore.DownloadSession session,
            EncryptedChunkFile.TrackRole storageRole,
            Track progressTrack,
            Cancellation cancellation,
            ProgressListener listener
    ) throws IOException {
        if (!OfflineDownloadEligibility.isTrustedMediaUrl(url)) {
            throw new UnsupportedDownloadException(
                    "The resolved media host is not trusted for offline storage."
            );
        }
        cancellation.throwIfCancelled();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", OkHttpDownloader.DESKTOP_USER_AGENT)
                .header("Accept-Encoding", "identity")
                .header("Accept-Language", "en-US,en;q=0.8")
                .get()
                .build();
        Call call = httpClient.newCall(request);
        cancellation.attach(call);
        try (Response response = call.execute()) {
            cancellation.throwIfCancelled();
            if (response.code() != 200 || response.header("Content-Range") != null) {
                throw new IOException(
                        "Media host did not return one complete track (HTTP "
                                + response.code() + ")."
                );
            }
            String encoding = response.header("Content-Encoding");
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                throw new IOException(
                        "Media host returned an encoded body with ambiguous length."
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Media host returned no response body.");
            }
            long contentLength = body.contentLength();
            if (contentLength <= 0L
                    || contentLength
                    > OfflineMediaRecord.MAX_PLAINTEXT_TRACK_BYTES) {
                throw new IOException(
                        "Media host did not provide a safe bounded content length."
                );
            }
            listener.onProgress(progressTrack, 0L, contentLength);
            try (InputStream progressInput = new ProgressInputStream(
                    body.byteStream(),
                    progressTrack,
                    contentLength,
                    cancellation,
                    listener
            )) {
                session.writeTrack(storageRole, progressInput, contentLength);
            }
            cancellation.throwIfCancelled();
            return contentLength;
        } catch (IOException exception) {
            if (cancellation.isCancelled()
                    || Thread.currentThread().isInterrupted()) {
                throw new DownloadCancelledException();
            }
            throw exception;
        } finally {
            cancellation.detach(call);
        }
    }

    private static String sanitizeText(
            String value,
            String fallback,
            int maximumUtf8Bytes
    ) {
        String source = value == null ? "" : value;
        StringBuilder cleaned = new StringBuilder(source.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < source.length();) {
            int codePoint = source.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            boolean disallowed = Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR;
            if (disallowed || Character.isWhitespace(codePoint)) {
                pendingSpace = cleaned.length() > 0;
                continue;
            }
            if (pendingSpace) {
                cleaned.append(' ');
                pendingSpace = false;
            }
            cleaned.appendCodePoint(codePoint);
        }

        String candidate = cleaned.toString().trim();
        if (candidate.isEmpty()) {
            candidate = fallback;
        }
        StringBuilder bounded = new StringBuilder(candidate.length());
        int bytes = 0;
        for (int offset = 0; offset < candidate.length();) {
            int codePoint = candidate.codePointAt(offset);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + unitBytes > maximumUtf8Bytes) {
                break;
            }
            bounded.append(unit);
            bytes += unitBytes;
            offset += Character.charCount(codePoint);
        }
        String result = bounded.toString().trim();
        return result.isEmpty() ? fallback : result;
    }

    private static final class ProgressInputStream extends FilterInputStream {
        private final Track track;
        private final long totalBytes;
        private final Cancellation cancellation;
        private final ProgressListener listener;
        private long downloadedBytes;

        private ProgressInputStream(
                InputStream input,
                Track track,
                long totalBytes,
                Cancellation cancellation,
                ProgressListener listener
        ) {
            super(input);
            this.track = track;
            this.totalBytes = totalBytes;
            this.cancellation = cancellation;
            this.listener = listener;
        }

        @Override
        public int read() throws IOException {
            cancellation.throwIfCancelled();
            int value = super.read();
            if (value >= 0) {
                report(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length)
                throws IOException {
            cancellation.throwIfCancelled();
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                report(count);
            }
            return count;
        }

        private void report(int count) {
            downloadedBytes += count;
            listener.onProgress(track, downloadedBytes, totalBytes);
        }
    }
}
