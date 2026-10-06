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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class OfflineMediaStoreTest {
    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static final long CREATED_AT = 1_700_000_000_000L;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void committedDownloadProbeWorksAfterRestartWithoutPrivateKeyUnwrap()
            throws Exception {
        Path root = newRoot("commit-probe-locked-key");
        FakeContentKeyProtector protector = new FakeContentKeyProtector();
        OfflineMediaStore store = new OfflineMediaStore(root, protector);
        UUID itemId = UUID.randomUUID();
        commitProgressive(store, itemId, patternedBytes(4_123), "Background item");
        protector.forbidUnwrap();

        assertTrue(store.hasCommittedDownload(itemId));
        OfflineMediaStore restarted = new OfflineMediaStore(root, protector);
        assertTrue(restarted.hasCommittedDownload(itemId));
        assertEquals(0, protector.unwrapCount());
        assertThrows(ContentKeyProtector.AuthenticationRequiredException.class,
                () -> restarted.open(itemId));
        assertEquals(1, protector.unwrapCount());
    }

    @Test
    public void committedDownloadProbeDoesNotRecognizeMissingOrInProgressItems()
            throws Exception {
        Path root = newRoot("commit-probe-publication");
        FakeContentKeyProtector protector = new FakeContentKeyProtector();
        OfflineMediaStore store = new OfflineMediaStore(root, protector);
        UUID stableItemId = UUID.fromString("5350cf65-3bb6-4f9b-a38a-73e7c8e232fc");
        assertFalse(store.hasCommittedDownload(stableItemId));
        byte[] video = patternedBytes(2_217);
        byte[] audio = patternedBytes(913);
        try (OfflineMediaStore.DownloadSession session = store.begin(stableItemId)) {
            assertFalse(store.hasCommittedDownload(stableItemId));
            session.writeTrack(EncryptedChunkFile.TrackRole.VIDEO,
                    new ByteArrayInputStream(video), video.length);
            session.writeTrack(EncryptedChunkFile.TrackRole.AUDIO,
                    new ByteArrayInputStream(audio), audio.length);
            assertFalse(store.hasCommittedDownload(stableItemId));
            session.commit(mergedRecord(stableItemId, video.length, audio.length));
        }
        assertTrue(store.hasCommittedDownload(stableItemId));
        assertFalse(store.hasCommittedDownload(UUID.randomUUID()));
        UUID cancelledId = UUID.randomUUID();
        try (OfflineMediaStore.DownloadSession session = store.begin(cancelledId)) {
            session.writeTrack(EncryptedChunkFile.TrackRole.PROGRESSIVE,
                    new ByteArrayInputStream(video), video.length);
            assertFalse(store.hasCommittedDownload(cancelledId));
        }
        assertFalse(store.hasCommittedDownload(cancelledId));
        assertEquals(0, protector.unwrapCount());
    }

    @Test
    public void committedDownloadProbeRejectsMalformedFilesAndIncompleteLayouts()
            throws Exception {
        Path root = newRoot("commit-probe-malformed");
        OfflineMediaStore store = new OfflineMediaStore(root, new FakeContentKeyProtector());
        UUID itemId = UUID.randomUUID();
        commitProgressive(store, itemId, patternedBytes(1_251), "Probe bounds");
        Path directory = root.resolve(itemId.toString());
        Path key = directory.resolve("key.pvk");
        byte[] originalEnvelope = Files.readAllBytes(key);
        Files.write(key, new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> store.hasCommittedDownload(itemId));
        Files.write(key, new byte[265]);
        assertThrows(IOException.class, () -> store.hasCommittedDownload(itemId));
        Files.write(key, originalEnvelope);
        // A v2 RSA envelope is larger than the legacy AES envelope and must fit the bound.
        Files.write(key, ContentKeyProtector.Envelope.createRsa(new byte[256]).toByteArray());
        assertTrue(store.hasCommittedDownload(itemId));
        Files.write(key, originalEnvelope);

        Path record = directory.resolve("record.pvm");
        byte[] originalRecord = Files.readAllBytes(record);
        Files.write(record, new byte[0]);
        assertThrows(IOException.class, () -> store.hasCommittedDownload(itemId));
        Files.write(record, new byte[16 * 1024 + 1]);
        assertThrows(IOException.class, () -> store.hasCommittedDownload(itemId));
        Files.write(record, originalRecord);
        Path video = directory.resolve("video.pvc");
        byte[] originalVideo = Files.readAllBytes(video);
        Files.write(video, new byte[0]);
        assertThrows(IOException.class, () -> store.hasCommittedDownload(itemId));
        Files.write(video, originalVideo);
        Files.write(directory.resolve("unexpected"), new byte[] {1});
        assertThrows(IOException.class, () -> store.hasCommittedDownload(itemId));
        Files.delete(directory.resolve("unexpected"));
        Files.delete(key);
        assertThrows(IOException.class, () -> store.hasCommittedDownload(itemId));
    }

    @Test
    public void committedDownloadProbeRejectsDirectoryAndFileSymlinks()
            throws Exception {
        Path root = newRoot("commit-probe-links");
        OfflineMediaStore store = new OfflineMediaStore(root, new FakeContentKeyProtector());
        Path outside = temporaryFolder.newFolder("commit-probe-outside").toPath();
        Path marker = outside.resolve("protected");
        Files.write(marker, new byte[] {9, 8, 7});
        UUID linkedId = UUID.randomUUID();
        Files.createSymbolicLink(root.resolve(linkedId.toString()), outside);
        assertThrows(IOException.class, () -> store.hasCommittedDownload(linkedId));

        UUID itemId = UUID.randomUUID();
        commitProgressive(store, itemId, patternedBytes(901), "No external files");
        Path key = root.resolve(itemId.toString()).resolve("key.pvk");
        Files.delete(key);
        Files.createSymbolicLink(key, marker);
        assertThrows(IOException.class, () -> store.hasCommittedDownload(itemId));
        assertArrayEquals(new byte[] {9, 8, 7}, Files.readAllBytes(marker));
        Files.delete(key);
        Files.createDirectory(key);
        assertThrows(IOException.class, () -> store.hasCommittedDownload(itemId));
        assertThrows(IllegalArgumentException.class, () -> store.hasCommittedDownload(
                UUID.fromString("00000000-0000-0000-0000-000000000001")));
    }

    @Test
    public void commitIsAtomicEncryptedAtRestAndFailureOrCancelLeavesNoItem()
            throws Exception {
        Path root = newRoot("atomic");
        FakeContentKeyProtector protector = new FakeContentKeyProtector();
        OfflineMediaStore store = new OfflineMediaStore(root, protector);
        UUID itemId = UUID.randomUUID();
        byte[] plaintext = patternedBytes(417_319);
        byte[] signature = Arrays.copyOfRange(plaintext, 919, 1_047);
        String title = "A title that must never appear on disk";

        try (OfflineMediaStore.DownloadSession session = store.begin(itemId)) {
            assertEquals(0, store.list().getRecords().size());
            assertTrue(hasPartialDirectory(root));
            session.writeTrack(
                    EncryptedChunkFile.TrackRole.PROGRESSIVE,
                    new ByteArrayInputStream(plaintext),
                    plaintext.length
            );
            session.commit(progressiveRecord(
                    itemId,
                    title,
                    plaintext.length
            ));
        }

        Path itemDirectory = root.resolve(itemId.toString());
        assertTrue(Files.isDirectory(itemDirectory));
        assertFalse(hasPartialDirectory(root));
        assertEquals(
                List.of("key.pvk", "record.pvm", "video.pvc"),
                childNames(itemDirectory)
        );

        byte[] persisted = concatenateFiles(itemDirectory);
        assertFalse(contains(
                persisted,
                title.getBytes(StandardCharsets.UTF_8)
        ));
        assertFalse(contains(
                persisted,
                VIDEO_ID.getBytes(StandardCharsets.US_ASCII)
        ));
        assertFalse(contains(persisted, signature));

        UUID cancelledId = UUID.randomUUID();
        try (OfflineMediaStore.DownloadSession cancelled =
                     store.begin(cancelledId)) {
            cancelled.writeTrack(
                    EncryptedChunkFile.TrackRole.PROGRESSIVE,
                    new ByteArrayInputStream(plaintext),
                    plaintext.length
            );
        }
        assertFalse(Files.exists(
                root.resolve(cancelledId.toString()),
                LinkOption.NOFOLLOW_LINKS
        ));
        assertFalse(hasPartialDirectory(root));

        UUID mismatchedId = UUID.randomUUID();
        OfflineMediaStore.DownloadSession mismatched =
                store.begin(mismatchedId);
        mismatched.writeTrack(
                EncryptedChunkFile.TrackRole.PROGRESSIVE,
                new ByteArrayInputStream(plaintext),
                plaintext.length
        );
        assertThrows(
                IOException.class,
                () -> mismatched.commit(progressiveRecord(
                        mismatchedId,
                        "Wrong authenticated length",
                        plaintext.length - 1L
                ))
        );
        assertFalse(Files.exists(
                root.resolve(mismatchedId.toString()),
                LinkOption.NOFOLLOW_LINKS
        ));
        assertFalse(hasPartialDirectory(root));
    }

    @Test
    public void listOpenAndReadAuthenticateBothMergedTracks() throws Exception {
        Path root = newRoot("merged");
        OfflineMediaStore store = new OfflineMediaStore(
                root,
                new FakeContentKeyProtector()
        );
        UUID itemId = UUID.randomUUID();
        byte[] video = patternedBytes(
                EncryptedChunkFile.CHUNK_SIZE_BYTES + 7_193
        );
        byte[] audio = patternedBytes(93_117);
        OfflineMediaRecord record = mergedRecord(
                itemId,
                video.length,
                audio.length
        );

        try (OfflineMediaStore.DownloadSession session = store.begin(itemId)) {
            session.writeTrack(
                    EncryptedChunkFile.TrackRole.VIDEO,
                    new ByteArrayInputStream(video),
                    video.length
            );
            session.writeTrack(
                    EncryptedChunkFile.TrackRole.AUDIO,
                    new ByteArrayInputStream(audio),
                    audio.length
            );
            session.commit(record);
        }

        OfflineMediaStore.ListResult listed = store.list();
        assertEquals(0, listed.getCorruptCount());
        assertEquals(List.of(record), listed.getRecords());

        OfflineMediaStore.PlaybackSession playback = store.open(itemId);
        assertEquals(record, playback.getRecord());
        String videoUri = "plyvanta-vault://" + itemId + "/video";
        String audioUri = "plyvanta-vault://" + itemId + "/audio";
        assertEquals(
                videoUri,
                playback.uriFor(EncryptedChunkFile.TrackRole.VIDEO)
        );
        assertEquals(
                audioUri,
                playback.uriFor(EncryptedChunkFile.TrackRole.AUDIO)
        );
        assertNotNull(playback.resolve(videoUri));
        assertNotNull(playback.resolve(audioUri));
        assertArrayEquals(
                video,
                readAll(playback.openTrack(EncryptedChunkFile.TrackRole.VIDEO))
        );
        assertArrayEquals(
                audio,
                readAll(playback.openTrack(EncryptedChunkFile.TrackRole.AUDIO))
        );
        assertThrows(
                IOException.class,
                () -> playback.resolve(
                        "plyvanta-vault://" + itemId + "/progressive"
                )
        );
        playback.close();
        assertThrows(
                IOException.class,
                () -> playback.openTrack(EncryptedChunkFile.TrackRole.VIDEO)
        );
    }

    @Test
    public void tamperedFinalItemIsCountedButNeverAutoDeleted() throws Exception {
        Path root = newRoot("tamper");
        OfflineMediaStore store = new OfflineMediaStore(
                root,
                new FakeContentKeyProtector()
        );
        UUID itemId = UUID.randomUUID();
        byte[] plaintext = patternedBytes(18_321);
        commitProgressive(store, itemId, plaintext, "Tamper target");

        Path encryptedVideo = root.resolve(itemId.toString()).resolve("video.pvc");
        flipByte(encryptedVideo, 27L);

        OfflineMediaStore.ListResult result = store.list();
        assertEquals(0, result.getRecords().size());
        assertEquals(1, result.getCorruptCount());
        assertTrue(Files.isDirectory(root.resolve(itemId.toString())));
        assertThrows(IOException.class, () -> store.open(itemId));
    }

    @Test
    public void commitGuardAndSecurityRecheckFailClosedBeforeRename()
            throws Exception {
        Path root = newRoot("commit-guard");
        FakeContentKeyProtector protector = new FakeContentKeyProtector();
        OfflineMediaStore store = new OfflineMediaStore(root, protector);
        byte[] plaintext = patternedBytes(13_007);

        UUID cancelledId = UUID.randomUUID();
        OfflineMediaStore.DownloadSession cancelled = store.begin(cancelledId);
        cancelled.writeTrack(
                EncryptedChunkFile.TrackRole.PROGRESSIVE,
                new ByteArrayInputStream(plaintext),
                plaintext.length
        );
        assertThrows(
                IOException.class,
                () -> cancelled.commit(
                        progressiveRecord(
                                cancelledId,
                                "Guarded cancellation",
                                plaintext.length
                        ),
                        () -> false
                )
        );
        assertFalse(Files.exists(
                root.resolve(cancelledId.toString()),
                LinkOption.NOFOLLOW_LINKS
        ));
        assertFalse(hasPartialDirectory(root));

        UUID renameRaceId = UUID.randomUUID();
        OfflineMediaStore.DownloadSession renameRace =
                store.begin(renameRaceId);
        renameRace.writeTrack(
                EncryptedChunkFile.TrackRole.PROGRESSIVE,
                new ByteArrayInputStream(plaintext),
                plaintext.length
        );
        AtomicInteger guardChecks = new AtomicInteger();
        assertThrows(
                IOException.class,
                () -> renameRace.commit(
                        progressiveRecord(
                                renameRaceId,
                                "Rename-boundary cancellation",
                                plaintext.length
                        ),
                        () -> guardChecks.incrementAndGet() == 1
                )
        );
        assertEquals(2, guardChecks.get());
        assertFalse(Files.exists(
                root.resolve(renameRaceId.toString()),
                LinkOption.NOFOLLOW_LINKS
        ));
        assertFalse(hasPartialDirectory(root));

        UUID policyId = UUID.randomUUID();
        OfflineMediaStore.DownloadSession policyChanged = store.begin(policyId);
        policyChanged.writeTrack(
                EncryptedChunkFile.TrackRole.PROGRESSIVE,
                new ByteArrayInputStream(plaintext),
                plaintext.length
        );
        protector.setAllowed(false);
        assertThrows(
                IOException.class,
                () -> policyChanged.commit(progressiveRecord(
                        policyId,
                        "Policy changed",
                        plaintext.length
                ))
        );
        assertFalse(Files.exists(
                root.resolve(policyId.toString()),
                LinkOption.NOFOLLOW_LINKS
        ));
        assertFalse(hasPartialDirectory(root));
    }

    @Test
    public void foregroundGuardsWipeUnwrappedKeysAndRevokeAllReaders()
            throws Exception {
        Path root = newRoot("foreground-guard");
        FakeContentKeyProtector protector = new FakeContentKeyProtector();
        OfflineMediaStore store = new OfflineMediaStore(root, protector);
        UUID itemId = UUID.randomUUID();
        commitProgressive(
                store,
                itemId,
                patternedBytes(31_337),
                "Foreground guarded"
        );

        assertThrows(
                InterruptedIOException.class,
                () -> store.list(() -> false)
        );
        assertThrows(
                InterruptedIOException.class,
                () -> store.open(itemId, () -> false)
        );

        AtomicBoolean active = new AtomicBoolean(true);
        protector.afterNextUnwrap(() -> active.set(false));
        assertThrows(
                InterruptedIOException.class,
                () -> store.open(itemId, active::get)
        );
        assertTrue(protector.lastUnwrappedKeyWasWiped());

        OfflineMediaStore.PlaybackSession first = store.open(itemId);
        OfflineMediaStore.PlaybackSession second = store.open(itemId);
        EncryptedChunkFile.Reader firstReader = first.openTrack(
                EncryptedChunkFile.TrackRole.PROGRESSIVE
        );
        EncryptedChunkFile.Reader secondReader = second.openTrack(
                EncryptedChunkFile.TrackRole.PROGRESSIVE
        );

        store.revokeAllPlayback();
        assertThrows(
                IOException.class,
                () -> firstReader.read(0L, new byte[1], 0, 1)
        );
        assertThrows(
                IOException.class,
                () -> secondReader.read(0L, new byte[1], 0, 1)
        );
        assertTrue(store.delete(itemId));
    }

    @Test
    public void concurrentSessionCloseAndStoreRevocationDoNotDeadlock()
            throws Exception {
        Path root = newRoot("concurrent-revoke");
        OfflineMediaStore store = new OfflineMediaStore(
                root,
                new FakeContentKeyProtector()
        );
        UUID itemId = UUID.randomUUID();
        commitProgressive(
                store,
                itemId,
                patternedBytes(4_321),
                "Concurrent revoke"
        );
        OfflineMediaStore.PlaybackSession session = store.open(itemId);

        CountDownLatch sessionMonitorHeld = new CountDownLatch(1);
        CountDownLatch releaseCloser = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread closer = daemonThread("offline-session-closer", () -> {
            synchronized (session) {
                sessionMonitorHeld.countDown();
                try {
                    if (!releaseCloser.await(2L, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "Timed out waiting to close the session."
                        );
                    }
                    session.close();
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                }
            }
        });
        Thread revoker = daemonThread("offline-store-revoker", () -> {
            try {
                if (!sessionMonitorHeld.await(2L, TimeUnit.SECONDS)) {
                    throw new AssertionError(
                            "Timed out waiting for the session monitor."
                    );
                }
                store.revokeAllPlayback();
            } catch (Throwable exception) {
                failure.compareAndSet(null, exception);
            }
        });

        closer.start();
        assertTrue(sessionMonitorHeld.await(2L, TimeUnit.SECONDS));
        revoker.start();
        waitUntilBlocked(revoker);
        releaseCloser.countDown();
        closer.join(2_000L);
        revoker.join(2_000L);

        assertFalse("Session closer deadlocked.", closer.isAlive());
        assertFalse("Store revoker deadlocked.", revoker.isAlive());
        if (failure.get() != null) {
            throw new AssertionError("Concurrent close failed.", failure.get());
        }
        assertTrue(store.delete(itemId));
    }

    @Test
    public void revocationWaitsForInFlightReaderOpenLease()
            throws Exception {
        Path root = newRoot("reader-open-lease");
        OfflineMediaStore store = new OfflineMediaStore(
                root,
                new FakeContentKeyProtector()
        );
        UUID itemId = UUID.randomUUID();
        commitProgressive(
                store,
                itemId,
                patternedBytes(5_123),
                "Reader lease"
        );
        OfflineMediaStore.PlaybackSession session = store.open(itemId);
        EncryptedMediaDataSource.Resource resource = session.resolve(
                "plyvanta-vault://" + itemId + "/progressive"
        );
        byte[] derivedKey = resource.acquireKeyForReader();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread revoker = daemonThread("offline-lease-revoker", () -> {
            try {
                store.revokeAllPlayback();
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        revoker.start();
        waitUntilWaiting(revoker);
        assertTrue("Revocation returned while a key lease was live.", revoker.isAlive());

        OfflineCrypto.wipe(derivedKey);
        resource.finishReaderOpen();
        revoker.join(2_000L);

        assertFalse("Revocation did not finish after lease release.", revoker.isAlive());
        if (failure.get() != null) {
            throw new AssertionError("Reader lease revocation failed.", failure.get());
        }
        assertTrue(store.delete(itemId));
    }

    @Test
    public void playbackRechecksForegroundAndSecurityBeforeReads()
            throws Exception {
        Path root = newRoot("read-recheck");
        FakeContentKeyProtector protector = new FakeContentKeyProtector();
        OfflineMediaStore store = new OfflineMediaStore(root, protector);
        UUID itemId = UUID.randomUUID();
        commitProgressive(
                store,
                itemId,
                patternedBytes(6_789),
                "Read recheck"
        );

        AtomicBoolean foreground = new AtomicBoolean(true);
        OfflineMediaStore.PlaybackSession session = store.open(
                itemId,
                foreground::get
        );
        session.onBeforeRead();

        foreground.set(false);
        assertThrows(InterruptedIOException.class, session::onBeforeRead);
        foreground.set(true);
        protector.setAllowed(false);
        assertThrows(IOException.class, session::onBeforeRead);

        session.close();
        protector.setAllowed(true);
        assertTrue(store.delete(itemId));
    }

    @Test
    public void wrongWrappingKeyCannotListOrOpenAnotherStoresItem()
            throws Exception {
        Path root = newRoot("wrong-key");
        FakeContentKeyProtector originalProtector =
                new FakeContentKeyProtector();
        OfflineMediaStore original = new OfflineMediaStore(
                root,
                originalProtector
        );
        UUID itemId = UUID.randomUUID();
        commitProgressive(
                original,
                itemId,
                patternedBytes(9_181),
                "Key-bound item"
        );

        OfflineMediaStore wrongStore = new OfflineMediaStore(
                root,
                new FakeContentKeyProtector()
        );
        OfflineMediaStore.ListResult wrongList = wrongStore.list();
        assertEquals(0, wrongList.getRecords().size());
        assertEquals(1, wrongList.getCorruptCount());
        assertThrows(
                ContentKeyProtector.InvalidEnvelopeException.class,
                () -> wrongStore.open(itemId)
        );

        OfflineMediaStore.ListResult originalList = original.list();
        assertEquals(1, originalList.getRecords().size());
        assertEquals(0, originalList.getCorruptCount());
    }

    @Test
    public void resolverAndFilesystemOperationsRejectTraversalAndSymlinks()
            throws Exception {
        Path root = newRoot("paths");
        OfflineMediaStore store = new OfflineMediaStore(
                root,
                new FakeContentKeyProtector()
        );
        UUID itemId = UUID.randomUUID();
        commitProgressive(
                store,
                itemId,
                patternedBytes(5_117),
                "URI-bound item"
        );

        try (OfflineMediaStore.PlaybackSession playback = store.open(itemId)) {
            String valid = "plyvanta-vault://" + itemId + "/progressive";
            assertNotNull(playback.resolve(valid));
            assertThrows(
                    IOException.class,
                    () -> playback.resolve(
                            "PLYVANTA-VAULT://" + itemId + "/progressive"
                    )
            );
            assertThrows(
                    IOException.class,
                    () -> playback.resolve(valid + "?export=1")
            );
            assertThrows(
                    IOException.class,
                    () -> playback.resolve(valid + "#fragment")
            );
            assertThrows(
                    IOException.class,
                    () -> playback.resolve(
                            "plyvanta-vault://" + itemId + "/../video"
                    )
            );
            assertThrows(
                    IOException.class,
                    () -> playback.resolve(
                            "plyvanta-vault://" + itemId + "/%2e%2e/video"
                    )
            );
            assertThrows(
                    IOException.class,
                    () -> playback.resolve(
                            "plyvanta-vault://user@" + itemId + "/progressive"
                    )
            );
            assertThrows(
                    IOException.class,
                    () -> playback.resolve(
                            "plyvanta-vault://"
                                    + UUID.randomUUID() + "/progressive"
                    )
            );
        }

        Path outside = temporaryFolder.newFolder("outside").toPath();
        Path outsideMarker = outside.resolve("must-survive");
        Files.write(outsideMarker, new byte[] {1, 2, 3});

        OfflineMediaStore.DownloadSession replacedStaging =
                store.begin(UUID.randomUUID());
        Path staging = partialDirectory(root);
        Files.delete(staging.resolve("key.pvk"));
        Files.delete(staging);
        Files.createSymbolicLink(staging, outside);
        assertThrows(
                IOException.class,
                () -> replacedStaging.writeTrack(
                        EncryptedChunkFile.TrackRole.PROGRESSIVE,
                        new ByteArrayInputStream(patternedBytes(1_009)),
                        1_009L
                )
        );
        replacedStaging.close();
        assertTrue(Files.exists(outsideMarker));

        UUID linkedId = UUID.randomUUID();
        Path linkedDirectory = root.resolve(linkedId.toString());
        Files.createSymbolicLink(linkedDirectory, outside);

        OfflineMediaStore.ListResult withLink = store.list();
        assertEquals(1, withLink.getRecords().size());
        assertEquals(1, withLink.getCorruptCount());
        assertTrue(store.delete(linkedId));
        assertTrue(Files.exists(outsideMarker));

        Path abandoned = root.resolve(".partial-malicious");
        Files.createDirectory(abandoned);
        Files.createSymbolicLink(abandoned.resolve("outside"), outside);
        assertEquals(1, store.cleanupPartials());
        assertTrue(Files.exists(outsideMarker));

        Path rootLink = temporaryFolder.getRoot().toPath().resolve("root-link");
        Files.createSymbolicLink(rootLink, outside);
        assertThrows(
                IOException.class,
                () -> new OfflineMediaStore(
                        rootLink,
                        new FakeContentKeyProtector()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> store.delete(UUID.fromString(
                        "00000000-0000-0000-0000-000000000001"
                ))
        );
    }

    @Test
    public void deleteCleanupAndResetRemoveOnlyVaultChildren()
            throws Exception {
        Path root = newRoot("delete-reset");
        FakeContentKeyProtector protector = new FakeContentKeyProtector();
        OfflineMediaStore store = new OfflineMediaStore(root, protector);
        UUID deletedId = UUID.randomUUID();
        commitProgressive(
                store,
                deletedId,
                patternedBytes(7_777),
                "Delete me"
        );

        OfflineMediaStore.PlaybackSession activePlayback =
                store.open(deletedId);
        EncryptedChunkFile.Reader activeReader = activePlayback.openTrack(
                EncryptedChunkFile.TrackRole.PROGRESSIVE
        );
        assertThrows(IOException.class, () -> store.delete(deletedId));
        assertThrows(IOException.class, store::reset);
        activePlayback.close();
        assertThrows(
                IOException.class,
                () -> activeReader.read(0L, new byte[1], 0, 1)
        );
        assertTrue(store.delete(deletedId));
        assertFalse(store.delete(deletedId));

        Path abandoned = root.resolve(".partial-old-download");
        Files.createDirectories(abandoned.resolve("nested"));
        Files.write(abandoned.resolve("nested").resolve("ciphertext"), new byte[] {9});
        assertEquals(1, store.cleanupPartials());
        assertFalse(Files.exists(abandoned, LinkOption.NOFOLLOW_LINKS));

        UUID resetId = UUID.randomUUID();
        commitProgressive(
                store,
                resetId,
                patternedBytes(8_123),
                "Reset me"
        );
        store.reset();
        assertTrue(protector.wasDeleted());
        assertEquals(List.of(".nomedia"), childNames(root));
        assertThrows(
                ContentKeyProtector.KeyUnavailableException.class,
                () -> store.begin(UUID.randomUUID())
        );
    }

    private Path newRoot(String name) throws IOException {
        return temporaryFolder.newFolder(name).toPath();
    }

    private static void commitProgressive(
            OfflineMediaStore store,
            UUID itemId,
            byte[] plaintext,
            String title
    ) throws Exception {
        try (OfflineMediaStore.DownloadSession session = store.begin(itemId)) {
            session.writeTrack(
                    EncryptedChunkFile.TrackRole.PROGRESSIVE,
                    new ByteArrayInputStream(plaintext),
                    plaintext.length
            );
            session.commit(progressiveRecord(
                    itemId,
                    title,
                    plaintext.length
            ));
        }
    }

    private static OfflineMediaRecord progressiveRecord(
            UUID itemId,
            String title,
            long length
    ) {
        return new OfflineMediaRecord(
                itemId,
                VIDEO_ID,
                title,
                "Uploader",
                301L,
                OfflineMediaRecord.SourceType.PROGRESSIVE,
                "video/mp4",
                null,
                720,
                length,
                0L,
                CREATED_AT
        );
    }

    private static OfflineMediaRecord mergedRecord(
            UUID itemId,
            long videoLength,
            long audioLength
    ) {
        return new OfflineMediaRecord(
                itemId,
                VIDEO_ID,
                "Merged encrypted item",
                "Uploader",
                611L,
                OfflineMediaRecord.SourceType.MERGED,
                "video/mp4",
                "audio/mp4",
                1_080,
                videoLength,
                audioLength,
                CREATED_AT
        );
    }

    private static byte[] readAll(EncryptedChunkFile.Reader reader)
            throws IOException {
        try (EncryptedChunkFile.Reader owned = reader) {
            if (owned.length() > Integer.MAX_VALUE) {
                throw new IOException("Test track is too large.");
            }
            byte[] result = new byte[(int) owned.length()];
            int offset = 0;
            while (offset < result.length) {
                int count = owned.read(
                        offset,
                        result,
                        offset,
                        result.length - offset
                );
                if (count <= 0) {
                    throw new IOException("Authenticated reader ended early.");
                }
                offset += count;
            }
            return result;
        }
    }

    private static byte[] patternedBytes(int length) {
        byte[] result = new byte[length];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (
                    (index * 131 + (index >>> 3) * 17 + 29) & 0xff
            );
        }
        return result;
    }

    private static Thread daemonThread(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void waitUntilBlocked(Thread thread)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (thread.isAlive()
                && thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
    }

    private static void waitUntilWaiting(Thread thread)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (thread.isAlive()
                && thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertTrue(
                thread.getState() == Thread.State.WAITING
                        || thread.getState() == Thread.State.TIMED_WAITING
        );
    }

    private static void flipByte(Path path, long offset) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.seek(offset);
            int original = file.read();
            if (original < 0) {
                throw new IOException("Test offset is past end of file.");
            }
            file.seek(offset);
            file.write(original ^ 0x40);
        }
    }

    private static boolean hasPartialDirectory(Path root) throws IOException {
        for (String name : childNames(root)) {
            if (name.startsWith(".partial-")) {
                return true;
            }
        }
        return false;
    }

    private static Path partialDirectory(Path root) throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.list(root)) {
            return entries.filter(
                    path -> path.getFileName().toString().startsWith(".partial-")
            ).findFirst().orElseThrow(
                    () -> new IOException("Expected a staging directory.")
            );
        }
    }

    private static List<String> childNames(Path directory) throws IOException {
        List<String> names = new ArrayList<>();
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            entries.forEach(path -> names.add(path.getFileName().toString()));
        }
        names.sort(String::compareTo);
        return names;
    }

    private static byte[] concatenateFiles(Path directory) throws IOException {
        List<byte[]> values = new ArrayList<>();
        int length = 0;
        for (String name : childNames(directory)) {
            byte[] value = Files.readAllBytes(directory.resolve(name));
            values.add(value);
            length = Math.addExact(length, value.length);
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || needle.length > haystack.length) {
            return false;
        }
        outer:
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static final class FakeContentKeyProtector
            implements ContentKeyProtector {
        private static final int TAG_BITS = 128;
        private static final byte[] AAD_PREFIX =
                "test-item-key-v1\0".getBytes(StandardCharsets.US_ASCII);

        private final SecureRandom random = new SecureRandom();
        private final byte[] wrappingKey =
                new byte[EncryptedChunkFile.KEY_SIZE_BYTES];
        private Runnable afterNextUnwrap;
        private byte[] lastUnwrappedKey;
        private boolean deleted;
        private boolean allowed = true;
        private boolean unwrapForbidden;
        private int unwrapCalls;

        private FakeContentKeyProtector() {
            random.nextBytes(wrappingKey);
        }

        @Override
        public synchronized Envelope wrap(byte[] contentKey, String itemId)
                throws KeyProtectionException {
            requireAvailable();
            if (contentKey == null
                    || contentKey.length != EncryptedChunkFile.KEY_SIZE_BYTES) {
                throw new InvalidEnvelopeException(
                        "Fake protector received an invalid content key."
                );
            }
            byte[] iv = new byte[12];
            byte[] ciphertext = null;
            random.nextBytes(iv);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(wrappingKey, "AES"),
                        new GCMParameterSpec(TAG_BITS, iv)
                );
                cipher.updateAAD(itemAad(itemId));
                ciphertext = cipher.doFinal(contentKey);
                return Envelope.create(iv, ciphertext);
            } catch (InvalidEnvelopeException exception) {
                throw exception;
            } catch (GeneralSecurityException exception) {
                throw new KeyUnavailableException(
                        "Fake protector could not wrap a key.",
                        exception
                );
            } finally {
                Arrays.fill(iv, (byte) 0);
                OfflineCrypto.wipe(ciphertext);
            }
        }

        @Override
        public synchronized byte[] unwrap(Envelope envelope, String itemId)
                throws KeyProtectionException {
            unwrapCalls++;
            if (unwrapForbidden) {
                throw new AuthenticationRequiredException(
                        "The private test wrapping key is locked.", null
                );
            }
            requireAvailable();
            byte[] iv = envelope.getInitializationVector();
            byte[] ciphertext = envelope.getCiphertext();
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        new SecretKeySpec(wrappingKey, "AES"),
                        new GCMParameterSpec(TAG_BITS, iv)
                );
                cipher.updateAAD(itemAad(itemId));
                byte[] result = cipher.doFinal(ciphertext);
                if (result.length != EncryptedChunkFile.KEY_SIZE_BYTES) {
                    OfflineCrypto.wipe(result);
                    throw new InvalidEnvelopeException(
                            "Fake protector unwrapped an invalid key."
                    );
                }
                lastUnwrappedKey = result;
                Runnable callback = afterNextUnwrap;
                afterNextUnwrap = null;
                if (callback != null) {
                    callback.run();
                }
                return result;
            } catch (InvalidEnvelopeException exception) {
                throw exception;
            } catch (GeneralSecurityException exception) {
                throw new InvalidEnvelopeException(
                        "Envelope belongs to another fake wrapping key.",
                        exception
                );
            } finally {
                OfflineCrypto.wipe(iv);
                OfflineCrypto.wipe(ciphertext);
            }
        }

        @Override
        public synchronized OfflineSecurityPolicy.Decision status() {
            return OfflineSecurityPolicy.Decision.forReason(
                    allowed
                            ? OfflineSecurityPolicy.Reason.ALLOWED
                            : OfflineSecurityPolicy.Reason.ROOT_INDICATORS_DETECTED
            );
        }

        @Override
        public synchronized void deleteKey() {
            OfflineCrypto.wipe(wrappingKey);
            deleted = true;
        }

        private synchronized boolean wasDeleted() {
            return deleted;
        }

        private synchronized void forbidUnwrap() {
            unwrapForbidden = true;
        }

        private synchronized int unwrapCount() {
            return unwrapCalls;
        }

        private synchronized void setAllowed(boolean allowed) {
            this.allowed = allowed;
        }

        private synchronized void afterNextUnwrap(Runnable callback) {
            afterNextUnwrap = callback;
        }

        private synchronized boolean lastUnwrappedKeyWasWiped() {
            if (lastUnwrappedKey == null) {
                return false;
            }
            for (byte value : lastUnwrappedKey) {
                if (value != 0) {
                    return false;
                }
            }
            return true;
        }

        private void requireAvailable() throws KeyUnavailableException {
            if (deleted) {
                throw new KeyUnavailableException(
                        "Fake wrapping key was deleted."
                );
            }
        }

        private static byte[] itemAad(String itemId) {
            byte[] item = itemId.getBytes(StandardCharsets.US_ASCII);
            byte[] aad = new byte[AAD_PREFIX.length + item.length];
            System.arraycopy(AAD_PREFIX, 0, aad, 0, AAD_PREFIX.length);
            System.arraycopy(item, 0, aad, AAD_PREFIX.length, item.length);
            return aad;
        }
    }
}
