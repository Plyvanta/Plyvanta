package app.plyvanta.subscription;

import java.util.Objects;
import java.util.regex.Pattern;

/** A local subscription whose original timestamp is the video's publication cutoff. */
public final class ChannelSubscription {
    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    private static final int MAX_NAME_LENGTH = 1_000;

    private final String channelId;
    private final String name;
    private final long subscribedAtMs;
    private final boolean autoDownload;

    public ChannelSubscription(
            String channelId,
            String name,
            long subscribedAtMs,
            boolean autoDownload
    ) {
        if (channelId == null || !CHANNEL_ID.matcher(channelId).matches()) {
            throw new IllegalArgumentException("A canonical YouTube channel ID is required.");
        }
        if (subscribedAtMs < 0) {
            throw new IllegalArgumentException("The subscription timestamp must not be negative.");
        }
        this.channelId = channelId;
        String displayName = name == null ? "" : name.trim();
        this.name = displayName.isEmpty() ? channelId : displayName.substring(
                0,
                Math.min(MAX_NAME_LENGTH, displayName.length())
        );
        this.subscribedAtMs = subscribedAtMs;
        this.autoDownload = autoDownload;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getName() {
        return name;
    }

    public long getSubscribedAtMs() {
        return subscribedAtMs;
    }

    public boolean isAutoDownload() {
        return autoDownload;
    }

    public String getChannelUrl() {
        return "https://www.youtube.com/channel/" + channelId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChannelSubscription that)) {
            return false;
        }
        return subscribedAtMs == that.subscribedAtMs
                && autoDownload == that.autoDownload
                && channelId.equals(that.channelId)
                && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelId, name, subscribedAtMs, autoDownload);
    }
}
