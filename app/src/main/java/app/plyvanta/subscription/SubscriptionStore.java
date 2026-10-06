package app.plyvanta.subscription;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** A process-wide, atomic local catalog of subscriptions and their post-subscription uploads. */
public final class SubscriptionStore {
    public static final String DIRECTORY_NAME = "subscriptions_v1";

    private static final String CATALOG_NAME = "catalog.json";
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_CATALOG_BYTES = 32L * 1024 * 1024;
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Comparator<SubscriptionVideo> NEWEST_FIRST =
            Comparator.comparingLong(SubscriptionVideo::getPublishedAtMs)
                    .reversed()
                    .thenComparing(SubscriptionVideo::getVideoId);
    private static volatile SubscriptionStore applicationInstance;

    private final Path root;
    private final Path catalog;
    private State state;

    /** All production readers and writers use one lock and one catalog snapshot. */
    public static SubscriptionStore forApplication(Context context) throws IOException {
        Objects.requireNonNull(context, "context");
        SubscriptionStore current = applicationInstance;
        if (current != null) {
            return current;
        }
        synchronized (SubscriptionStore.class) {
            current = applicationInstance;
            if (current == null) {
                Context application = context.getApplicationContext();
                Context owner = application == null ? context : application;
                current = new SubscriptionStore(
                        owner.getNoBackupFilesDir().toPath().resolve(DIRECTORY_NAME)
                );
                applicationInstance = current;
            }
            return current;
        }
    }

    /** Isolated filesystem constructor for JVM tests. */
    SubscriptionStore(Path requestedRoot) throws IOException {
        Path normalizedRoot = Objects.requireNonNull(requestedRoot, "root")
                .toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalizedRoot)) {
            throw new IOException("The subscription directory must be app-private.");
        }
        Files.createDirectories(normalizedRoot);
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The subscription directory is unavailable.");
        }
        root = normalizedRoot.toRealPath();
        catalog = root.resolve(CATALOG_NAME);
        state = read();
    }

    public synchronized List<ChannelSubscription> getSubscriptions() {
        List<ChannelSubscription> subscriptions = new ArrayList<>(state.subscriptions.values());
        subscriptions.sort(Comparator.comparing(
                ChannelSubscription::getName,
                String.CASE_INSENSITIVE_ORDER
        ).thenComparing(ChannelSubscription::getChannelId));
        return Collections.unmodifiableList(subscriptions);
    }

    public synchronized List<SubscriptionVideo> getFeed() {
        return sortedVideos(state.feed.values());
    }

    /** Repeated subscription requests preserve the first cutoff and the user's existing choices. */
    public synchronized ChannelSubscription subscribe(
            String channelId,
            String name,
            long subscribedAtMs,
            boolean autoDownload
    ) throws IOException {
        ChannelSubscription requested = new ChannelSubscription(
                channelId,
                name,
                subscribedAtMs,
                autoDownload
        );
        ChannelSubscription existing = state.subscriptions.get(channelId);
        if (existing != null) {
            return existing;
        }
        Long previousCutoff = state.lastCutoffs.get(channelId);
        if (previousCutoff != null && subscribedAtMs <= previousCutoff) {
            if (previousCutoff == Long.MAX_VALUE) {
                throw new IOException("The subscription timestamp cannot be advanced.");
            }
            requested = new ChannelSubscription(channelId, name, previousCutoff + 1, autoDownload);
        }
        State next = state.copy();
        next.subscriptions.put(channelId, requested);
        next.lastCutoffs.put(channelId, requested.getSubscribedAtMs());
        commit(next);
        return requested;
    }

    public synchronized void unsubscribe(String channelId) throws IOException {
        if (!state.subscriptions.containsKey(channelId)) {
            return;
        }
        State next = state.copy();
        next.subscriptions.remove(channelId);
        next.feed.values().removeIf(video -> video.getChannelId().equals(channelId));
        next.downloadedVideoIds.retainAll(next.feed.keySet());
        commit(next);
    }

    public synchronized void setAutoDownload(String channelId, boolean enabled) throws IOException {
        ChannelSubscription subscription = state.subscriptions.get(channelId);
        if (subscription == null || subscription.isAutoDownload() == enabled) {
            return;
        }
        State next = state.copy();
        next.subscriptions.put(channelId, new ChannelSubscription(
                subscription.getChannelId(),
                subscription.getName(),
                subscription.getSubscribedAtMs(),
                enabled
        ));
        commit(next);
    }

    public synchronized boolean isAutoDownloadEnabled(String channelId) {
        ChannelSubscription subscription = state.subscriptions.get(channelId);
        return subscription != null && subscription.isAutoDownload();
    }

    /**
     * Adds only uploads strictly newer than the original subscription and already published at
     * check time. An in-flight response from an unsubscribed/re-subscribed channel is discarded.
     * Existing items stay in the feed when they fall out of YouTube's recent-upload window.
     */
    public synchronized int mergeFeed(
            String channelId,
            long expectedSubscribedAtMs,
            List<SubscriptionVideo> videos,
            long checkedAtMs
    ) throws IOException {
        Objects.requireNonNull(videos, "videos");
        ChannelSubscription subscription = state.subscriptions.get(channelId);
        if (subscription == null
                || subscription.getSubscribedAtMs() != expectedSubscribedAtMs
                || checkedAtMs <= subscription.getSubscribedAtMs()) {
            return 0;
        }
        State next = state.copy();
        int added = 0;
        for (SubscriptionVideo video : videos) {
            if (video == null
                    || !video.getChannelId().equals(channelId)
                    || video.getPublishedAtMs() <= subscription.getSubscribedAtMs()
                    || video.getPublishedAtMs() > checkedAtMs
                    || next.feed.containsKey(video.getVideoId())) {
                continue;
            }
            next.feed.put(video.getVideoId(), video);
            added++;
        }
        if (added != 0) {
            commit(next);
        }
        return added;
    }

    /** Completion survives offline-copy deletion, so periodic refresh does not re-download it. */
    public synchronized void markDownloaded(String videoId) throws IOException {
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
            throw new IllegalArgumentException("A canonical YouTube video ID is required.");
        }
        if (!state.feed.containsKey(videoId) || state.downloadedVideoIds.contains(videoId)) {
            return;
        }
        State next = state.copy();
        next.downloadedVideoIds.add(videoId);
        commit(next);
    }

    /** Rechecks a queued request against unsubscribe, re-subscribe, toggles and completion. */
    public synchronized boolean isDownloadPending(String videoId, long expectedSubscribedAtMs) {
        SubscriptionVideo video = state.feed.get(videoId);
        if (video == null || state.downloadedVideoIds.contains(videoId)) {
            return false;
        }
        ChannelSubscription subscription = state.subscriptions.get(video.getChannelId());
        return subscription != null
                && subscription.isAutoDownload()
                && subscription.getSubscribedAtMs() == expectedSubscribedAtMs;
    }

    /** Records completion only for the incarnation and offline choice that queued this request. */
    public synchronized boolean markDownloaded(String videoId, long expectedSubscribedAtMs)
            throws IOException {
        if (!isDownloadPending(videoId, expectedSubscribedAtMs)) {
            return false;
        }
        State next = state.copy();
        next.downloadedVideoIds.add(videoId);
        commit(next);
        return true;
    }

    public synchronized List<SubscriptionVideo> getPendingDownloads() {
        List<SubscriptionVideo> pending = new ArrayList<>();
        for (SubscriptionVideo video : state.feed.values()) {
            if (isAutoDownloadEnabled(video.getChannelId())
                    && !state.downloadedVideoIds.contains(video.getVideoId())) {
                pending.add(video);
            }
        }
        pending.sort(NEWEST_FIRST);
        return Collections.unmodifiableList(pending);
    }

    private static List<SubscriptionVideo> sortedVideos(
            Iterable<SubscriptionVideo> videos
    ) {
        List<SubscriptionVideo> sorted = new ArrayList<>();
        for (SubscriptionVideo video : videos) {
            sorted.add(video);
        }
        sorted.sort(NEWEST_FIRST);
        return Collections.unmodifiableList(sorted);
    }

    private State read() throws IOException {
        State loaded = new State();
        if (!Files.exists(catalog, LinkOption.NOFOLLOW_LINKS)) {
            return loaded;
        }
        if (Files.isSymbolicLink(catalog) || Files.size(catalog) > MAX_CATALOG_BYTES) {
            throw new IOException("The subscription catalog is invalid.");
        }
        try {
            JSONObject document = new JSONObject(new String(
                    Files.readAllBytes(catalog),
                    StandardCharsets.UTF_8
            ));
            if (document.getInt("schemaVersion") != SCHEMA_VERSION) {
                throw new IOException("The subscription catalog version is unsupported.");
            }
            JSONArray subscriptions = document.getJSONArray("subscriptions");
            for (int index = 0; index < subscriptions.length(); index++) {
                JSONObject item = subscriptions.getJSONObject(index);
                ChannelSubscription subscription = new ChannelSubscription(
                        item.getString("channelId"),
                        item.getString("name"),
                        item.getLong("subscribedAtMs"),
                        item.getBoolean("autoDownload")
                );
                loaded.subscriptions.put(subscription.getChannelId(), subscription);
                loaded.lastCutoffs.put(subscription.getChannelId(), subscription.getSubscribedAtMs());
            }
            JSONArray cutoffs = document.getJSONArray("lastCutoffs");
            for (int index = 0; index < cutoffs.length(); index++) {
                JSONObject item = cutoffs.getJSONObject(index);
                String channelId = item.getString("channelId");
                long cutoff = item.getLong("subscribedAtMs");
                new ChannelSubscription(channelId, channelId, cutoff, false);
                loaded.lastCutoffs.merge(channelId, cutoff, Math::max);
            }
            JSONArray videos = document.getJSONArray("feed");
            for (int index = 0; index < videos.length(); index++) {
                JSONObject item = videos.getJSONObject(index);
                SubscriptionVideo video = new SubscriptionVideo(
                        item.getString("channelId"),
                        item.getString("videoId"),
                        item.getString("title"),
                        item.getLong("publishedAtMs")
                );
                ChannelSubscription subscription = loaded.subscriptions.get(video.getChannelId());
                if (subscription != null && video.getPublishedAtMs() > subscription.getSubscribedAtMs()) {
                    loaded.feed.putIfAbsent(video.getVideoId(), video);
                }
            }
            JSONArray downloaded = document.getJSONArray("downloadedVideoIds");
            for (int index = 0; index < downloaded.length(); index++) {
                String videoId = downloaded.getString(index);
                if (!VIDEO_ID.matcher(videoId).matches()) {
                    throw new IOException("The subscription download history is invalid.");
                }
                if (loaded.feed.containsKey(videoId)) {
                    loaded.downloadedVideoIds.add(videoId);
                }
            }
            return loaded;
        } catch (JSONException | IllegalArgumentException exception) {
            throw new IOException("The subscription catalog could not be read.", exception);
        }
    }

    /** Save before publishing the in-memory snapshot: failed writes leave all callers unchanged. */
    private void commit(State next) throws IOException {
        byte[] bytes;
        try {
            JSONObject document = new JSONObject();
            document.put("schemaVersion", SCHEMA_VERSION);
            JSONArray subscriptions = new JSONArray();
            for (ChannelSubscription subscription : next.subscriptions.values()) {
                subscriptions.put(new JSONObject()
                        .put("channelId", subscription.getChannelId())
                        .put("name", subscription.getName())
                        .put("subscribedAtMs", subscription.getSubscribedAtMs())
                        .put("autoDownload", subscription.isAutoDownload()));
            }
            document.put("subscriptions", subscriptions);
            JSONArray cutoffs = new JSONArray();
            for (Map.Entry<String, Long> entry : next.lastCutoffs.entrySet()) {
                cutoffs.put(new JSONObject()
                        .put("channelId", entry.getKey())
                        .put("subscribedAtMs", entry.getValue()));
            }
            document.put("lastCutoffs", cutoffs);
            JSONArray videos = new JSONArray();
            for (SubscriptionVideo video : next.feed.values()) {
                videos.put(new JSONObject()
                        .put("channelId", video.getChannelId())
                        .put("videoId", video.getVideoId())
                        .put("title", video.getTitle())
                        .put("publishedAtMs", video.getPublishedAtMs()));
            }
            document.put("feed", videos);
            document.put("downloadedVideoIds", new JSONArray(next.downloadedVideoIds));
            bytes = document.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException exception) {
            throw new IOException("The subscription catalog could not be saved.", exception);
        }
        if (bytes.length > MAX_CATALOG_BYTES) {
            throw new IOException("The subscription catalog is full.");
        }
        Path temporary = Files.createTempFile(root, ".catalog-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(bytes);
                output.getFD().sync();
            }
            try {
                Files.move(temporary, catalog,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, catalog, StandardCopyOption.REPLACE_EXISTING);
            }
            state = next;
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // Some Android providers cannot fsync directories; the synced catalog is complete.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final class State {
        private final Map<String, ChannelSubscription> subscriptions = new LinkedHashMap<>();
        private final Map<String, SubscriptionVideo> feed = new LinkedHashMap<>();
        private final Map<String, Long> lastCutoffs = new HashMap<>();
        private final Set<String> downloadedVideoIds = new HashSet<>();

        private State copy() {
            State copy = new State();
            copy.subscriptions.putAll(subscriptions);
            copy.feed.putAll(feed);
            copy.lastCutoffs.putAll(lastCutoffs);
            copy.downloadedVideoIds.addAll(downloadedVideoIds);
            return copy;
        }
    }
}
