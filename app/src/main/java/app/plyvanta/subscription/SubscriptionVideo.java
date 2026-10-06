package app.plyvanta.subscription;

import java.util.Objects;
import java.util.regex.Pattern;

/** Public video metadata; resolved media URLs and offline storage locations are never stored here. */
public final class SubscriptionVideo {
    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final int MAX_TITLE_LENGTH = 1_000;

    private final String channelId;
    private final String videoId;
    private final String title;
    private final long publishedAtMs;

    public SubscriptionVideo(
            String channelId,
            String videoId,
            String title,
            long publishedAtMs
    ) {
        if (channelId == null || !CHANNEL_ID.matcher(channelId).matches()) {
            throw new IllegalArgumentException("A canonical YouTube channel ID is required.");
        }
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
            throw new IllegalArgumentException("A canonical YouTube video ID is required.");
        }
        if (publishedAtMs < 0) {
            throw new IllegalArgumentException("The publication timestamp must not be negative.");
        }
        this.channelId = channelId;
        this.videoId = videoId;
        String displayTitle = title == null ? "" : title.trim();
        this.title = displayTitle.isEmpty() ? videoId : displayTitle.substring(
                0,
                Math.min(MAX_TITLE_LENGTH, displayTitle.length())
        );
        this.publishedAtMs = publishedAtMs;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getTitle() {
        return title;
    }

    public long getPublishedAtMs() {
        return publishedAtMs;
    }

    public String getVideoUrl() {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SubscriptionVideo that)) {
            return false;
        }
        return publishedAtMs == that.publishedAtMs
                && channelId.equals(that.channelId)
                && videoId.equals(that.videoId)
                && title.equals(that.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelId, videoId, title, publishedAtMs);
    }
}
