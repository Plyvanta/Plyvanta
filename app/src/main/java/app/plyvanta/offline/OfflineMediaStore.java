package app.plyvanta.offline;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Crash-safe catalog and private storage for encrypted offline media.
 *
 * <p>Every published item is an atomically renamed directory named with a canonical random UUID.
 * Media, metadata, and the per-item content key are encrypted before they reach persistent
 * storage. Plaintext media is only returned through authenticated, seekable readers.
 */
public final class OfflineMediaStore {
    public static final String DIRECTORY_NAME = "offline_media_v1";

    private static volatile OfflineMediaStore applicationInstance;

    private static final String NO_MEDIA_FILE = ".nomedia";
    private static final String PARTIAL_PREFIX = ".partial-";
    private static final String KEY_FILE = "key.pvk";
    private static final String RECORD_FILE = "record.pvm";
    private static final String VIDEO_FILE = "video.pvc";
    private static final String AUDIO_FILE = "audio.pvc";
    private static final int MAX_KEY_FILE_BYTES = 264;
    private static final int MAX_RECORD_FILE_BYTES = 16 * 1024;

    private static final Set<String> BASE_ITEM_FILES = Set.of(
            KEY_FILE,
            RECORD_FILE,
            VIDEO_FILE
    );

    private final Path root;
    private final ContentKeyProtector keyProtector;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Set<Path> activeStagingDirectories = new HashSet<>();
    private final Set<UUID> activeItemIds = new HashSet<>();
    private final Map<UUID, Set<PlaybackSession>> activePlaybackSessions =
            new HashMap<>();

    /**
     * Caller-owned liveness check for foreground-only key operations.
     */
    @FunctionalInterface
    public interface OperationGuard {
        boolean canContinue();
    }

    /**
     * Returns the process-wide production store in Android's no-backup directory.
     */
    public static OfflineMediaStore forApplication(Context context)
            throws IOException {
        Objects.requireNonNull(context, "context");
        OfflineMediaStore current = applicationInstance;
        if (current != null) {
            return current;
        }
        synchronized (OfflineMediaStore.class) {
            current = applicationInstance;
            if (current == null) {
                Context applicationContext = context.getApplicationContext();
                current = new OfflineMediaStore(
                        applicationContext == null
                                ? context
                                : applicationContext
                );
                applicationInstance = current;
            }
            return current;
        }
    }

    private OfflineMediaStore(Context context) throws IOException {
        this(contextRoot(context), new DeviceBoundKeyManager(context));
    }

    /**
     * Creates a store rooted at a caller-owned directory.
     *
     * <p>This constructor exists for isolated storage tests and dependency injection. Production
     * callers should use {@link #forApplication(Context)}.
     */
    OfflineMediaStore(
            Path root,
            ContentKeyProtector keyProtector
    ) throws IOException {
        this.keyProtector = Objects.requireNonNull(
                keyProtector,
                "keyProtector"
        );
        Path requestedRoot = Objects.requireNonNull(root, "root")
                .toAbsolutePath()
                .normalize();
        if (Files.isSymbolicLink(requestedRoot)) {
            throw new IOException("Offline store root must not be a symbolic link.");
        }
        Files.createDirectories(requestedRoot);
        if (Files.isSymbolicLink(requestedRoot)
                || !Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Offline store root is not a private directory.");
        }
        this.root = requestedRoot.toRealPath();
        ensureRoot();
        ensureNoMediaMarker();
    }

    /**
     * Begins a new item with a freshly generated canonical random UUID.
     */
    public DownloadSession begin()
            throws IOException, ContentKeyProtector.KeyProtectionException {
        return begin(UUID.randomUUID());
    }

    /** Returns a device-private, stable identity without opening a saved item. */
    public synchronized UUID subscriptionDownloadItemId(
            String channelId, long subscribedAtMs, String videoId
    ) throws ContentKeyProtector.KeyProtectionException {
        if (!(keyProtector instanceof DeviceBoundKeyManager)) {
            throw new ContentKeyProtector.KeyUnavailableException(
                    "Subscription identities require the device-bound key manager.");
        }
        return ((DeviceBoundKeyManager) keyProtector)
                .subscriptionItemId(channelId, subscribedAtMs, videoId);
    }

    /**
     * Recognizes an atomic publication without unwrapping its key or decrypting
     * metadata. Playback still performs full authentication after device consent.
     */
    public synchronized boolean hasCommittedDownload(UUID itemId) throws IOException {
        ensureRoot();
        Path directory = finalDirectory(itemId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Offline item directory is invalid.");
        }
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Offline item layout is invalid.");
                }
                names.add(child.getFileName().toString());
            }
        }
        if (!names.equals(BASE_ITEM_FILES)
                && !names.equals(Set.of(KEY_FILE, RECORD_FILE, VIDEO_FILE, AUDIO_FILE))) {
            throw new IOException("Offline item layout is incomplete.");
        }
        byte[] envelope = readBounded(directory.resolve(KEY_FILE), MAX_KEY_FILE_BYTES);
        try {
            ContentKeyProtector.Envelope.fromByteArray(envelope);
        } catch (ContentKeyProtector.InvalidEnvelopeException exception) {
            throw new IOException("Offline item key envelope is invalid.");
        } finally {
            OfflineCrypto.wipe(envelope);
        }
        long metadataLength = Files.size(directory.resolve(RECORD_FILE));
        if (metadataLength <= 0 || metadataLength > MAX_RECORD_FILE_BYTES
                || Files.size(directory.resolve(VIDEO_FILE)) <= 0
                || (names.contains(AUDIO_FILE)
                    && Files.size(directory.resolve(AUDIO_FILE)) <= 0)) {
            throw new IOException("Offline item file length is invalid.");
        }
        return true;
    }

    /**
     * Begins a new encrypted item. Nothing becomes visible to {@link #list()} until commit.
     */
    public synchronized DownloadSession begin(UUID itemId)
            throws IOException, ContentKeyProtector.KeyProtectionException {
        requireRandomUuid(itemId);
        ensureRoot();
        if (activeItemIds.contains(itemId)) {
            throw new IOException("An offline download for this item is already active.");
        }

        Path finalDirectory = finalDirectory(itemId);
        if (Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(finalDirectory.toString());
        }

        Path staging = directChild(
                PARTIAL_PREFIX + itemId + "-" + UUID.randomUUID()
        );
        Files.createDirectory(staging);

        byte[] contentKey = new byte[EncryptedChunkFile.KEY_SIZE_BYTES];
        byte[] encodedEnvelope = null;
        boolean ready = false;
        secureRandom.nextBytes(contentKey);
        try {
            ContentKeyProtector.Envelope envelope =
                    keyProtector.wrap(contentKey, itemId.toString());
            encodedEnvelope = envelope.toByteArray();
            writeNewAndSync(staging.resolve(KEY_FILE), encodedEnvelope);
            forceDirectory(staging);

            DownloadSession session = new DownloadSession(
                    this,
                    itemId,
                    staging,
                    contentKey
            );
            activeStagingDirectories.add(staging);
            activeItemIds.add(itemId);
            ready = true;
            return session;
        } finally {
            OfflineCrypto.wipe(encodedEnvelope);
            if (!ready) {
                OfflineCrypto.wipe(contentKey);
                deleteItemCryptographically(staging);
            }
        }
    }

    /**
     * Lists authenticated records. Corrupt final items are counted and left untouched.
     */
    synchronized ListResult list()
            throws IOException, ContentKeyProtector.KeyProtectionException {
        return list(() -> true);
    }

    /**
     * Lists authenticated records only while the caller's operation remains active.
     */
    public synchronized ListResult list(OperationGuard operationGuard)
            throws IOException, ContentKeyProtector.KeyProtectionException {
        Objects.requireNonNull(operationGuard, "operationGuard");
        requireOperationActive(operationGuard);
        ensureRoot();
        List<OfflineMediaRecord> records = new ArrayList<>();
        int corruptCount = 0;

        List<Path> children = listDirectChildren();
        for (Path child : children) {
            requireOperationActive(operationGuard);
            String name = child.getFileName().toString();
            if (NO_MEDIA_FILE.equals(name) || name.startsWith(PARTIAL_PREFIX)) {
                continue;
            }

            UUID itemId = parseCanonicalRandomUuid(name);
            if (itemId == null
                    || Files.isSymbolicLink(child)
                    || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                corruptCount++;
                continue;
            }

            LoadedItem loaded = null;
            try {
                loaded = loadItem(itemId, child, operationGuard);
                records.add(loaded.record);
            } catch (ContentKeyProtector.InvalidEnvelopeException exception) {
                corruptCount++;
            } catch (ContentKeyProtector.KeyProtectionException exception) {
                throw exception;
            } catch (InterruptedIOException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                corruptCount++;
            } finally {
                if (loaded != null) {
                    loaded.close();
                }
            }
        }
        requireOperationActive(operationGuard);

        records.sort(
                Comparator.comparingLong(
                        OfflineMediaRecord::getCreatedAtEpochMillis
                ).reversed().thenComparing(
                        record -> record.getItemId().toString()
                )
        );
        return new ListResult(records, corruptCount);
    }

    /**
     * Opens one authenticated catalog item and retains its content key only for this session.
     */
    synchronized PlaybackSession open(UUID itemId)
            throws IOException, ContentKeyProtector.KeyProtectionException {
        return open(itemId, () -> true);
    }

    /**
     * Opens one item only while the caller's foreground operation remains active.
     */
    public synchronized PlaybackSession open(
            UUID itemId,
            OperationGuard operationGuard
    ) throws IOException, ContentKeyProtector.KeyProtectionException {
        Objects.requireNonNull(operationGuard, "operationGuard");
        requireOperationActive(operationGuard);
        requireRandomUuid(itemId);
        ensureRoot();
        Path directory = finalDirectory(itemId);
        LoadedItem loaded = loadItem(itemId, directory, operationGuard);
        try {
            requireOperationActive(operationGuard);
            PlaybackSession session = new PlaybackSession(
                    this,
                    loaded.record,
                    directory,
                    loaded.takeContentKey(),
                    operationGuard
            );
            activePlaybackSessions.computeIfAbsent(
                    itemId,
                    ignored -> new HashSet<>()
            ).add(session);
            try {
                requireOperationActive(operationGuard);
            } catch (IOException exception) {
                session.close();
                throw exception;
            }
            return session;
        } finally {
            loaded.close();
        }
    }

    /**
     * Removes one item without following links outside the vault.
     */
    public synchronized boolean delete(UUID itemId) throws IOException {
        requireRandomUuid(itemId);
        ensureRoot();
        if (hasActivePlayback(itemId)) {
            throw new IOException(
                    "Close offline playback before deleting this item."
            );
        }
        Path directory = finalDirectory(itemId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        deleteItemCryptographically(directory);
        forceDirectoryAfterMutation();
        return true;
    }

    /**
     * Deletes abandoned staging directories while preserving live sessions in this store object.
     */
    public synchronized int cleanupPartials() throws IOException {
        ensureRoot();
        int removed = 0;
        for (Path child : listDirectChildren()) {
            String name = child.getFileName().toString();
            if (!name.startsWith(PARTIAL_PREFIX)
                    || activeStagingDirectories.contains(child)) {
                continue;
            }
            deleteItemCryptographically(child);
            removed++;
        }
        if (removed > 0) {
            forceDirectoryAfterMutation();
        }
        return removed;
    }

    /**
     * Performs cryptographic erasure and removes every stored item.
     *
     * <p>Callers must close active download sessions before resetting the vault.
     */
    public synchronized void reset()
            throws IOException, ContentKeyProtector.KeyUnavailableException {
        ensureRoot();
        if (!activeStagingDirectories.isEmpty()
                || !activePlaybackSessions.isEmpty()) {
            throw new IOException(
                    "Close offline downloads and playback before resetting the vault."
            );
        }

        // Delete the wrapping key first so any files surviving an I/O failure remain unusable.
        keyProtector.deleteKey();
        for (Path child : listDirectChildren()) {
            if (NO_MEDIA_FILE.equals(child.getFileName().toString())) {
                continue;
            }
            deleteDirectChild(child);
        }
        ensureNoMediaMarker();
        forceDirectoryAfterMutation();
    }

    /**
     * Synchronously revokes every reader and in-memory content key owned by this store.
     */
    public void revokeAllPlayback() {
        List<PlaybackSession> sessions;
        synchronized (this) {
            sessions = new ArrayList<>();
            for (Set<PlaybackSession> itemSessions
                    : activePlaybackSessions.values()) {
                sessions.addAll(itemSessions);
            }
        }
        for (PlaybackSession session : sessions) {
            session.close();
        }
    }

    private synchronized void publish(
            DownloadSession session,
            OfflineMediaRecord record,
            CommitGuard commitGuard
    ) throws IOException {
        requireActiveSession(session);
        Objects.requireNonNull(commitGuard, "commitGuard");
        session.validateRecord(record);

        byte[] encodedRecord = null;
        byte[] encryptedRecord = null;
        try {
            encodedRecord = OfflineMediaRecordCodec.encodeBinary(record);
            encryptedRecord = OfflineCrypto.encryptMetadata(
                    encodedRecord,
                    session.contentKey,
                    session.itemId
            );
            writeNewAndSync(
                    session.stagingDirectory.resolve(RECORD_FILE),
                    encryptedRecord
            );
            authenticateTrackFiles(session, record);
            requireExactItemLayout(session.stagingDirectory, record);
            forceDirectory(session.stagingDirectory);

            Path destination = finalDirectory(session.itemId);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(destination.toString());
            }

            requireCommitAllowed(commitGuard);
            Files.move(
                    session.stagingDirectory,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE
            );
            activeStagingDirectories.remove(session.stagingDirectory);
            activeItemIds.remove(session.itemId);
            try {
                requireCommitAllowed(commitGuard);
            } catch (IOException rejection) {
                try {
                    deleteItemCryptographically(destination);
                    forceDirectoryAfterMutation();
                } catch (IOException cleanupFailure) {
                    rejection.addSuppressed(cleanupFailure);
                }
                throw rejection;
            }
            try {
                forceDirectory(root);
            } catch (IOException ignored) {
                // Once the atomic rename succeeds the item is committed and visible. Reporting
                // failure here would leave the caller believing an existing item was abandoned.
                // All files and the staging directory were already synchronized before rename.
            }
        } finally {
            OfflineCrypto.wipe(encodedRecord);
            OfflineCrypto.wipe(encryptedRecord);
        }
    }

    private void requireCommitAllowed(CommitGuard commitGuard)
            throws IOException {
        OfflineSecurityPolicy.Decision securityDecision;
        boolean callerAllowsCommit;
        try {
            securityDecision = keyProtector.status();
            callerAllowsCommit = commitGuard.canCommit();
        } catch (RuntimeException exception) {
            throw new IOException(
                    "Offline commit security recheck failed.",
                    exception
            );
        }
        if (securityDecision == null || !securityDecision.isAllowed()) {
            throw new IOException(
                    "Offline security requirements changed before commit."
            );
        }
        if (!callerAllowsCommit) {
            throw new IOException("Offline commit was cancelled.");
        }
    }

    private synchronized void abandon(DownloadSession session) throws IOException {
        boolean wasActive = activeStagingDirectories.remove(
                session.stagingDirectory
        );
        activeItemIds.remove(session.itemId);
        if (wasActive
                || Files.exists(
                        session.stagingDirectory,
                        LinkOption.NOFOLLOW_LINKS
                )) {
            deleteItemCryptographically(session.stagingDirectory);
            forceDirectoryAfterMutation();
        }
    }

    private synchronized void validateWritableSession(
            DownloadSession session
    ) throws IOException {
        requireActiveSession(session);
    }

    private synchronized void releasePlayback(PlaybackSession session) {
        Set<PlaybackSession> sessions = activePlaybackSessions.get(
                session.record.getItemId()
        );
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            activePlaybackSessions.remove(session.record.getItemId());
        }
    }

    private boolean hasActivePlayback(UUID itemId) {
        Set<PlaybackSession> sessions = activePlaybackSessions.get(itemId);
        return sessions != null && !sessions.isEmpty();
    }

    private void forceDirectoryAfterMutation() {
        try {
            forceDirectory(root);
        } catch (IOException ignored) {
            // The mutation has already happened. Do not report it as if no state changed.
        }
    }

    private void requireActiveSession(DownloadSession session) throws IOException {
        if (session.store != this
                || !activeStagingDirectories.contains(session.stagingDirectory)
                || !activeItemIds.contains(session.itemId)) {
            throw new IOException("Offline download session is no longer active.");
        }
        requireDirectChild(session.stagingDirectory);
        if (Files.isSymbolicLink(session.stagingDirectory)
                || !Files.isDirectory(
                        session.stagingDirectory,
                        LinkOption.NOFOLLOW_LINKS
                )) {
            throw new IOException("Offline staging directory is invalid.");
        }
    }

    private LoadedItem loadItem(
            UUID expectedItemId,
            Path directory,
            OperationGuard operationGuard
    )
            throws IOException, ContentKeyProtector.KeyProtectionException {
        requireOperationActive(operationGuard);
        requireRandomUuid(expectedItemId);
        requireDirectChild(directory);
        if (!directory.equals(finalDirectory(expectedItemId))
                || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Offline item directory is missing or invalid.");
        }

        byte[] encodedEnvelope = null;
        byte[] contentKey = null;
        byte[] encryptedRecord = null;
        byte[] encodedRecord = null;
        boolean success = false;
        try {
            encodedEnvelope = readBounded(
                    directory.resolve(KEY_FILE),
                    MAX_KEY_FILE_BYTES
            );
            ContentKeyProtector.Envelope envelope =
                    ContentKeyProtector.Envelope.fromByteArray(encodedEnvelope);
            requireOperationActive(operationGuard);
            contentKey = keyProtector.unwrap(
                    envelope,
                    expectedItemId.toString()
            );
            requireOperationActive(operationGuard);
            if (contentKey == null
                    || contentKey.length != EncryptedChunkFile.KEY_SIZE_BYTES) {
                throw new ContentKeyProtector.InvalidEnvelopeException(
                        "Unwrapped offline content key has an invalid length."
                );
            }

            encryptedRecord = readBounded(
                    directory.resolve(RECORD_FILE),
                    MAX_RECORD_FILE_BYTES
            );
            encodedRecord = OfflineCrypto.decryptMetadata(
                    encryptedRecord,
                    contentKey,
                    expectedItemId
            );
            requireOperationActive(operationGuard);
            OfflineMediaRecord record =
                    OfflineMediaRecordCodec.decodeBinary(encodedRecord);
            if (!record.getItemId().equals(expectedItemId)) {
                throw new IOException(
                        "Offline metadata does not match its item directory."
                );
            }

            requireExactItemLayout(directory, record);
            authenticateTrackFiles(
                    directory,
                    record,
                    contentKey,
                    operationGuard
            );
            requireOperationActive(operationGuard);
            LoadedItem loaded = new LoadedItem(record, contentKey);
            contentKey = null;
            success = true;
            return loaded;
        } finally {
            OfflineCrypto.wipe(encodedEnvelope);
            OfflineCrypto.wipe(encryptedRecord);
            OfflineCrypto.wipe(encodedRecord);
            if (!success) {
                OfflineCrypto.wipe(contentKey);
            }
        }
    }

    private static void authenticateTrackFiles(
            DownloadSession session,
            OfflineMediaRecord record
    ) throws IOException {
        authenticateTrackFiles(
                session.stagingDirectory,
                record,
                session.contentKey,
                () -> true
        );
    }

    private static void authenticateTrackFiles(
            Path directory,
            OfflineMediaRecord record,
            byte[] contentKey,
            OperationGuard operationGuard
    ) throws IOException {
        requireOperationActive(operationGuard);
        if (record.getSourceType() == OfflineMediaRecord.SourceType.PROGRESSIVE) {
            authenticateTrack(
                    directory.resolve(VIDEO_FILE),
                    record.getItemId(),
                    EncryptedChunkFile.TrackRole.PROGRESSIVE,
                    record.getVideoPlaintextLength(),
                    contentKey,
                    operationGuard
            );
        } else {
            authenticateTrack(
                    directory.resolve(VIDEO_FILE),
                    record.getItemId(),
                    EncryptedChunkFile.TrackRole.VIDEO,
                    record.getVideoPlaintextLength(),
                    contentKey,
                    operationGuard
            );
            authenticateTrack(
                    directory.resolve(AUDIO_FILE),
                    record.getItemId(),
                    EncryptedChunkFile.TrackRole.AUDIO,
                    record.getAudioPlaintextLength(),
                    contentKey,
                    operationGuard
            );
        }
        requireOperationActive(operationGuard);
    }

    private static void authenticateTrack(
            Path path,
            UUID itemId,
            EncryptedChunkFile.TrackRole role,
            long plaintextLength,
            byte[] contentKey,
            OperationGuard operationGuard
    ) throws IOException {
        byte[] trackKey = null;
        try {
            requireOperationActive(operationGuard);
            trackKey = deriveTrackKey(contentKey, role);
            try (EncryptedChunkFile.Reader ignored = EncryptedChunkFile.open(
                    path,
                    trackKey,
                    itemId,
                    role,
                    plaintextLength
            )) {
                // Opening authenticates the header and exact encrypted file size.
            }
            requireOperationActive(operationGuard);
        } finally {
            OfflineCrypto.wipe(trackKey);
        }
    }

    private static void requireOperationActive(OperationGuard operationGuard)
            throws IOException {
        Objects.requireNonNull(operationGuard, "operationGuard");
        boolean allowed;
        try {
            allowed = operationGuard.canContinue();
        } catch (RuntimeException exception) {
            throw new IOException(
                    "Offline operation liveness check failed.",
                    exception
            );
        }
        if (Thread.currentThread().isInterrupted() || !allowed) {
            throw new InterruptedIOException(
                    "Offline operation is no longer active."
            );
        }
    }

    private static byte[] deriveTrackKey(
            byte[] contentKey,
            EncryptedChunkFile.TrackRole role
    ) throws IOException {
        String purpose;
        switch (role) {
            case PROGRESSIVE:
                purpose = OfflineCrypto.PURPOSE_PROGRESSIVE;
                break;
            case VIDEO:
                purpose = OfflineCrypto.PURPOSE_VIDEO;
                break;
            case AUDIO:
                purpose = OfflineCrypto.PURPOSE_AUDIO;
                break;
            default:
                throw new IOException("Unsupported offline track role.");
        }
        try {
            return OfflineCrypto.deriveKey(contentKey, purpose);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IOException("Unable to derive an offline track key.", exception);
        }
    }

    private static void requireExactItemLayout(
            Path directory,
            OfflineMediaRecord record
    ) throws IOException {
        Set<String> expected = new HashSet<>(BASE_ITEM_FILES);
        if (record.getSourceType() == OfflineMediaRecord.SourceType.MERGED) {
            expected.add(AUDIO_FILE);
        }

        Set<String> actual = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                requireImmediateChild(directory, entry);
                String name = entry.getFileName().toString();
                if (!actual.add(name)
                        || Files.isSymbolicLink(entry)
                        || !Files.isRegularFile(
                                entry,
                                LinkOption.NOFOLLOW_LINKS
                        )) {
                    throw new IOException("Offline item layout is invalid.");
                }
            }
        }
        if (!actual.equals(expected)) {
            throw new IOException("Offline item has missing or unexpected files.");
        }
    }

    private List<Path> listDirectChildren() throws IOException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                Path normalized = child.toAbsolutePath().normalize();
                requireDirectChild(normalized);
                children.add(normalized);
            }
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return children;
    }

    private void ensureRoot() throws IOException {
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Offline store root is unavailable.");
        }
    }

    private void ensureNoMediaMarker() throws IOException {
        Path marker = directChild(NO_MEDIA_FILE);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(marker)
                    || !Files.isRegularFile(
                            marker,
                            LinkOption.NOFOLLOW_LINKS
                    )
                    || Files.size(marker) != 0L) {
                throw new IOException("Offline .nomedia marker is invalid.");
            }
            return;
        }
        writeNewAndSync(marker, new byte[0]);
        forceDirectory(root);
    }

    private Path finalDirectory(UUID itemId) throws IOException {
        requireRandomUuid(itemId);
        return directChild(itemId.toString());
    }

    private Path directChild(String fileName) throws IOException {
        if (fileName.isEmpty()
                || ".".equals(fileName)
                || "..".equals(fileName)
                || fileName.indexOf('/') >= 0
                || fileName.indexOf('\\') >= 0) {
            throw new IOException("Offline vault child name is invalid.");
        }
        Path child = root.resolve(fileName).toAbsolutePath().normalize();
        requireDirectChild(child);
        return child;
    }

    private void requireDirectChild(Path child) throws IOException {
        requireImmediateChild(root, child);
    }

    private static void requireImmediateChild(Path parent, Path child)
            throws IOException {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (normalizedChild.getParent() == null
                || !normalizedChild.getParent().equals(normalizedParent)
                || normalizedChild.equals(normalizedParent)) {
            throw new IOException("Offline path escaped its expected directory.");
        }
    }

    private void deleteDirectChild(Path child) throws IOException {
        requireDirectChild(child);
        deleteTreeIfPresent(child);
    }

    private void deleteItemCryptographically(Path directory)
            throws IOException {
        requireDirectChild(directory);
        if (!Files.isSymbolicLink(directory)
                && Files.isDirectory(
                        directory,
                        LinkOption.NOFOLLOW_LINKS
                )) {
            Path wrappedKey = directory.resolve(KEY_FILE)
                    .toAbsolutePath()
                    .normalize();
            requireImmediateChild(directory, wrappedKey);
            if (Files.exists(wrappedKey, LinkOption.NOFOLLOW_LINKS)) {
                // Remove the only per-item content-key envelope first. If a
                // later metadata/ciphertext deletion fails, the residue is no
                // longer decryptable through this vault.
                Files.delete(wrappedKey);
                forceDirectory(directory);
            }
        }
        deleteDirectChild(directory);
    }

    private static void deleteTreeIfPresent(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
            ) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    Path directory,
                    IOException failure
            ) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static byte[] readBounded(Path path, int maximumBytes)
            throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Offline vault file is missing or invalid.");
        }
        long size = Files.size(path);
        if (size <= 0L || size > maximumBytes || size > Integer.MAX_VALUE) {
            throw new IOException("Offline vault file has an invalid size.");
        }

        byte[] result = new byte[(int) size];
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            readFully(channel, ByteBuffer.wrap(result));
            if (channel.size() != size) {
                throw new IOException("Offline vault file changed while reading.");
            }
        } catch (IOException | RuntimeException exception) {
            OfflineCrypto.wipe(result);
            throw exception;
        }
        return result;
    }

    private static void writeNewAndSync(Path path, byte[] bytes)
            throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Offline vault file has no parent.");
        }
        requireImmediateChild(parent, path);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
        )) {
            writeFully(channel, ByteBuffer.wrap(bytes));
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(
                directory,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            channel.force(true);
        } catch (UnsupportedOperationException exception) {
            throw new IOException("Unable to synchronize offline directory.", exception);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer)
            throws IOException {
        int noProgress = 0;
        while (buffer.hasRemaining()) {
            int count = channel.write(buffer);
            if (count == 0) {
                if (++noProgress > 16) {
                    throw new IOException("Offline vault write made no progress.");
                }
            } else {
                noProgress = 0;
            }
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer)
            throws IOException {
        int noProgress = 0;
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer);
            if (count < 0) {
                throw new IOException("Offline vault file is truncated.");
            }
            if (count == 0) {
                if (++noProgress > 16) {
                    throw new IOException("Offline vault read made no progress.");
                }
            } else {
                noProgress = 0;
            }
        }
    }

    private static Path contextRoot(Context context) throws IOException {
        Context supplied = Objects.requireNonNull(context, "context");
        java.io.File noBackupDirectory = supplied.getNoBackupFilesDir();
        if (noBackupDirectory == null) {
            throw new IOException("Android no-backup directory is unavailable.");
        }
        return noBackupDirectory.toPath().resolve(DIRECTORY_NAME);
    }

    private static void requireRandomUuid(UUID itemId) {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.version() != 4 || itemId.variant() != 2) {
            throw new IllegalArgumentException(
                    "Offline item ID must be an RFC 4122 random UUID."
            );
        }
    }

    private static UUID parseCanonicalRandomUuid(String text) {
        try {
            UUID itemId = UUID.fromString(text);
            if (!itemId.toString().equals(text)
                    || itemId.version() != 4
                    || itemId.variant() != 2) {
                return null;
            }
            return itemId;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static final class ListResult {
        private final List<OfflineMediaRecord> records;
        private final int corruptCount;

        private ListResult(
                List<OfflineMediaRecord> records,
                int corruptCount
        ) {
            this.records = Collections.unmodifiableList(
                    new ArrayList<>(records)
            );
            this.corruptCount = corruptCount;
        }

        public List<OfflineMediaRecord> getRecords() {
            return records;
        }

        public int getCorruptCount() {
            return corruptCount;
        }
    }

    /**
     * Rechecks cancellation or other caller-owned integrity state at the atomic publish boundary.
     */
    @FunctionalInterface
    public interface CommitGuard {
        boolean canCommit();
    }

    /**
     * Owns the unwrapped per-item content key while an item is being written.
     */
    public static final class DownloadSession implements Closeable {
        private final OfflineMediaStore store;
        private final UUID itemId;
        private final Path stagingDirectory;
        private final byte[] contentKey;
        private final Map<EncryptedChunkFile.TrackRole, Long> trackLengths =
                new EnumMap<>(EncryptedChunkFile.TrackRole.class);

        private boolean committed;
        private boolean closed;

        private DownloadSession(
                OfflineMediaStore store,
                UUID itemId,
                Path stagingDirectory,
                byte[] contentKey
        ) {
            this.store = store;
            this.itemId = itemId;
            this.stagingDirectory = stagingDirectory;
            this.contentKey = contentKey;
        }

        public UUID getItemId() {
            return itemId;
        }

        /**
         * Encrypts exactly {@code expectedPlaintextLength} bytes from the caller-owned stream.
         */
        public synchronized void writeTrack(
                EncryptedChunkFile.TrackRole role,
                InputStream plaintext,
                long expectedPlaintextLength
        ) throws IOException {
            ensureOpen();
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(plaintext, "plaintext");
            if (expectedPlaintextLength <= 0L
                    || expectedPlaintextLength
                    > OfflineMediaRecord.MAX_PLAINTEXT_TRACK_BYTES) {
                throw new IllegalArgumentException(
                        "Offline track length is outside the supported range."
                );
            }
            if (trackLengths.containsKey(role)) {
                throw new IOException("Offline track was already written.");
            }
            if (role == EncryptedChunkFile.TrackRole.PROGRESSIVE
                    && !trackLengths.isEmpty()) {
                throw new IOException(
                        "A progressive item cannot contain separate tracks."
                );
            }
            if (role != EncryptedChunkFile.TrackRole.PROGRESSIVE
                    && trackLengths.containsKey(
                            EncryptedChunkFile.TrackRole.PROGRESSIVE
                    )) {
                throw new IOException(
                        "A progressive item cannot contain separate tracks."
                );
            }

            String fileName = role == EncryptedChunkFile.TrackRole.AUDIO
                    ? AUDIO_FILE
                    : VIDEO_FILE;
            byte[] trackKey = null;
            try {
                store.validateWritableSession(this);
                trackKey = deriveTrackKey(contentKey, role);
                EncryptedChunkFile.write(
                        stagingDirectory.resolve(fileName),
                        plaintext,
                        expectedPlaintextLength,
                        itemId,
                        role,
                        trackKey
                );
                store.validateWritableSession(this);
                trackLengths.put(role, expectedPlaintextLength);
            } finally {
                OfflineCrypto.wipe(trackKey);
            }
        }

        /**
         * Authenticates the record and track layout, then atomically publishes the item.
         */
        synchronized void commit(OfflineMediaRecord record)
                throws IOException {
            commit(record, () -> true);
        }

        /**
         * Commits only if {@code commitGuard} still permits publication at the rename boundary.
         */
        public synchronized void commit(
                OfflineMediaRecord record,
                CommitGuard commitGuard
        ) throws IOException {
            ensureOpen();
            boolean published = false;
            try {
                store.publish(
                        this,
                        Objects.requireNonNull(record, "record"),
                        Objects.requireNonNull(commitGuard, "commitGuard")
                );
                published = true;
                committed = true;
            } finally {
                closed = true;
                OfflineCrypto.wipe(contentKey);
                if (!published) {
                    try {
                        store.abandon(this);
                    } catch (IOException cleanupFailure) {
                        // If publish itself failed, cleanup is still attempted. A later
                        // cleanupPartials() call can remove any ciphertext-only residue.
                    }
                }
            }
        }

        public synchronized void cancel() throws IOException {
            close();
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            OfflineCrypto.wipe(contentKey);
            if (!committed) {
                store.abandon(this);
            }
        }

        private void validateRecord(OfflineMediaRecord record)
                throws IOException {
            if (!record.getItemId().equals(itemId)) {
                throw new IOException(
                        "Offline record item ID does not match its session."
                );
            }
            if (record.getSourceType()
                    == OfflineMediaRecord.SourceType.PROGRESSIVE) {
                if (trackLengths.size() != 1
                        || !Objects.equals(
                                trackLengths.get(
                                        EncryptedChunkFile.TrackRole.PROGRESSIVE
                                ),
                                record.getVideoPlaintextLength()
                        )) {
                    throw new IOException(
                            "Progressive record length does not match its track."
                    );
                }
            } else if (trackLengths.size() != 2
                    || !Objects.equals(
                            trackLengths.get(EncryptedChunkFile.TrackRole.VIDEO),
                            record.getVideoPlaintextLength()
                    )
                    || !Objects.equals(
                            trackLengths.get(EncryptedChunkFile.TrackRole.AUDIO),
                            record.getAudioPlaintextLength()
                    )) {
                throw new IOException(
                        "Merged record lengths do not match their tracks."
                );
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Offline download session is closed.");
            }
        }
    }

    /**
     * Owns the unwrapped content key while an offline item is available to Media3.
     */
    @OptIn(markerClass = UnstableApi.class)
    public static final class PlaybackSession
            implements EncryptedMediaDataSource.ResourceResolver,
            EncryptedMediaDataSource.ReaderObserver,
            Closeable {
        private final OfflineMediaStore store;
        private final OfflineMediaRecord record;
        private final Path itemDirectory;
        private final byte[] contentKey;
        private final OperationGuard operationGuard;
        private final Set<EncryptedChunkFile.Reader> activeReaders =
                new HashSet<>();
        private int inFlightReaderOpens;
        private boolean closed;
        private boolean closeComplete;

        private PlaybackSession(
                OfflineMediaStore store,
                OfflineMediaRecord record,
                Path itemDirectory,
                byte[] contentKey,
                OperationGuard operationGuard
        ) {
            this.store = store;
            this.record = record;
            this.itemDirectory = itemDirectory;
            this.contentKey = contentKey;
            this.operationGuard = operationGuard;
        }

        public OfflineMediaRecord getRecord() {
            return record;
        }

        /**
         * Returns the exact opaque URI for a role permitted by this record.
         */
        public synchronized String uriFor(
                EncryptedChunkFile.TrackRole role
        ) throws IOException {
            ensureOpen();
            requirePermittedRole(role);
            return expectedUri(role);
        }

        @Override
        public EncryptedMediaDataSource.Resource resolve(Uri uri)
                throws IOException {
            Objects.requireNonNull(uri, "uri");
            return resolve(uri.toString());
        }

        /**
         * Strict string overload used before Android Uri creation and by local storage tests.
         */
        synchronized EncryptedMediaDataSource.Resource resolve(
                String uriText
        ) throws IOException {
            ensureOpen();
            EncryptedChunkFile.TrackRole role = parseRole(uriText);
            return resourceFor(role);
        }

        /**
         * Opens a direct authenticated reader without creating a Media3 data source.
         */
        synchronized EncryptedChunkFile.Reader openTrack(
                EncryptedChunkFile.TrackRole role
        ) throws IOException {
            onBeforeRead();
            ensureOpen();
            requirePermittedRole(role);
            byte[] trackKey = null;
            try {
                trackKey = deriveTrackKey(contentKey, role);
                EncryptedChunkFile.Reader reader = EncryptedChunkFile.open(
                        trackPath(role),
                        trackKey,
                        record.getItemId(),
                        role,
                        trackLength(role)
                );
                boolean registered = false;
                try {
                    onReaderOpened(reader);
                    registered = true;
                    return reader;
                } finally {
                    if (!registered) {
                        reader.close();
                    }
                }
            } finally {
                OfflineCrypto.wipe(trackKey);
            }
        }

        private EncryptedMediaDataSource.Resource resourceFor(
                EncryptedChunkFile.TrackRole role
        ) throws IOException {
            requirePermittedRole(role);
            return new EncryptedMediaDataSource.Resource(
                    trackPath(role),
                    record.getItemId(),
                    role,
                    trackLength(role),
                    () -> acquireTrackKey(role),
                    this
            );
        }

        @Override
        public synchronized void onReaderOpened(
                EncryptedChunkFile.Reader reader
        ) throws IOException {
            Objects.requireNonNull(reader, "reader");
            ensureOpen();
            activeReaders.add(reader);
        }

        @Override
        public synchronized void onReaderClosed(
                EncryptedChunkFile.Reader reader
        ) {
            activeReaders.remove(reader);
        }

        @Override
        public synchronized void onReaderOpenFinished() {
            if (inFlightReaderOpens <= 0) {
                throw new IllegalStateException(
                        "Offline reader-open lease is unbalanced."
                );
            }
            inFlightReaderOpens--;
            notifyAll();
        }

        @Override
        public void onBeforeRead() throws IOException {
            synchronized (this) {
                ensureOpen();
            }
            requireOperationActive(operationGuard);
            OfflineSecurityPolicy.Decision securityDecision;
            try {
                securityDecision = store.keyProtector.status();
            } catch (RuntimeException exception) {
                throw new IOException(
                        "Offline playback security recheck failed.",
                        exception
                );
            }
            if (securityDecision == null || !securityDecision.isAllowed()) {
                throw new IOException(
                        "Offline playback security requirements changed."
                );
            }
            synchronized (this) {
                ensureOpen();
            }
        }

        private byte[] acquireTrackKey(
                EncryptedChunkFile.TrackRole role
        ) throws IOException {
            onBeforeRead();
            synchronized (this) {
                ensureOpen();
                requirePermittedRole(role);
                inFlightReaderOpens++;
                try {
                    return deriveTrackKey(contentKey, role);
                } catch (IOException | RuntimeException exception) {
                    inFlightReaderOpens--;
                    notifyAll();
                    throw exception;
                }
            }
        }

        private EncryptedChunkFile.TrackRole parseRole(String uriText)
                throws IOException {
            Objects.requireNonNull(uriText, "uriText");
            EncryptedChunkFile.TrackRole role = null;
            for (EncryptedChunkFile.TrackRole candidate
                    : EncryptedChunkFile.TrackRole.values()) {
                if (expectedUri(candidate).equals(uriText)) {
                    role = candidate;
                    break;
                }
            }
            if (role == null) {
                throw new IOException("Offline media URI is not canonical.");
            }

            try {
                URI uri = new URI(uriText);
                if (!EncryptedMediaDataSource.URI_SCHEME.equals(uri.getScheme())
                        || uri.isOpaque()
                        || !record.getItemId().toString().equals(
                                uri.getRawAuthority()
                        )
                        || uri.getRawUserInfo() != null
                        || uri.getPort() != -1
                        || uri.getRawQuery() != null
                        || uri.getRawFragment() != null
                        || !rolePath(role).equals(uri.getRawPath())) {
                    throw new IOException("Offline media URI is invalid.");
                }
            } catch (URISyntaxException exception) {
                throw new IOException("Offline media URI is malformed.", exception);
            }
            requirePermittedRole(role);
            return role;
        }

        private String expectedUri(EncryptedChunkFile.TrackRole role) {
            return EncryptedMediaDataSource.URI_SCHEME
                    + "://" + record.getItemId() + rolePath(role);
        }

        private static String rolePath(EncryptedChunkFile.TrackRole role) {
            switch (role) {
                case PROGRESSIVE:
                    return "/progressive";
                case VIDEO:
                    return "/video";
                case AUDIO:
                    return "/audio";
                default:
                    throw new IllegalArgumentException(
                            "Unsupported offline track role."
                    );
            }
        }

        private Path trackPath(EncryptedChunkFile.TrackRole role) {
            return itemDirectory.resolve(
                    role == EncryptedChunkFile.TrackRole.AUDIO
                            ? AUDIO_FILE
                            : VIDEO_FILE
            );
        }

        private long trackLength(EncryptedChunkFile.TrackRole role) {
            return role == EncryptedChunkFile.TrackRole.AUDIO
                    ? record.getAudioPlaintextLength()
                    : record.getVideoPlaintextLength();
        }

        private void requirePermittedRole(
                EncryptedChunkFile.TrackRole role
        ) throws IOException {
            Objects.requireNonNull(role, "role");
            boolean permitted;
            if (record.getSourceType()
                    == OfflineMediaRecord.SourceType.PROGRESSIVE) {
                permitted = role == EncryptedChunkFile.TrackRole.PROGRESSIVE;
            } else {
                permitted = role == EncryptedChunkFile.TrackRole.VIDEO
                        || role == EncryptedChunkFile.TrackRole.AUDIO;
            }
            if (!permitted) {
                throw new IOException(
                        "Offline track role does not match this record."
                );
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Offline playback session is closed.");
            }
        }

        @Override
        public void close() {
            List<EncryptedChunkFile.Reader> readers;
            boolean interrupted = false;
            synchronized (this) {
                if (closeComplete) {
                    return;
                }
                if (closed) {
                    while (!closeComplete) {
                        try {
                            wait();
                        } catch (InterruptedException exception) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                closed = true;
                readers = new ArrayList<>(activeReaders);
                activeReaders.clear();
                OfflineCrypto.wipe(contentKey);
            }
            for (EncryptedChunkFile.Reader reader : readers) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Reader.close clears its cached plaintext and key before closing the
                            // channel, so revocation remains effective even if channel close reports.
                }
            }
            synchronized (this) {
                while (inFlightReaderOpens > 0) {
                    try {
                        wait();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            }
            try {
                store.releasePlayback(this);
            } finally {
                synchronized (this) {
                    closeComplete = true;
                    notifyAll();
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static final class LoadedItem implements Closeable {
        private final OfflineMediaRecord record;
        private byte[] contentKey;

        private LoadedItem(OfflineMediaRecord record, byte[] contentKey) {
            this.record = record;
            this.contentKey = contentKey;
        }

        private byte[] takeContentKey() throws IOException {
            if (contentKey == null) {
                throw new IOException("Offline content key was already transferred.");
            }
            byte[] transferred = contentKey;
            contentKey = null;
            return transferred;
        }

        @Override
        public void close() {
            OfflineCrypto.wipe(contentKey);
            contentKey = null;
        }
    }
}
