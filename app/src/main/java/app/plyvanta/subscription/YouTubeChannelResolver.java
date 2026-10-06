package app.plyvanta.subscription;

import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;

/** Resolves channel links and handles to stable IDs through the app's NewPipe downloader. */
public final class YouTubeChannelResolver {
    interface ChannelSource {
        ChannelInfo load(String channelUrl) throws Exception;
    }

    private final ChannelSource source;

    public YouTubeChannelResolver() {
        this(url -> ChannelInfo.getInfo(ServiceList.YouTube, url));
    }

    YouTubeChannelResolver(ChannelSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Run on a worker thread. The global NewPipe downloader respects AppNetwork's Tor route. */
    public ResolvedChannel resolve(String input) throws Exception {
        String url = YouTubeChannelUrls.canonicalize(input);
        if (url == null) {
            throw new IllegalArgumentException("Enter a YouTube channel link or @handle.");
        }
        throwIfInterrupted();
        final ChannelInfo info;
        try {
            info = source.load(url);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        throwIfInterrupted();
        if (info == null || !isValidChannelId(info.getId())) {
            throw new IOException("YouTube returned an invalid channel ID.");
        }
        if (url.contains("/channel/") && !url.equals(YouTubeChannelUrls.channelUrl(info.getId()))) {
            throw new IOException("YouTube returned a different channel.");
        }
        return new ResolvedChannel(info.getId(), info.getName());
    }

    public static boolean isValidChannelId(String channelId) {
        return YouTubeChannelUrls.isValidChannelId(channelId);
    }

    private static void throwIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Channel lookup was interrupted.");
        }
    }

    public static final class ResolvedChannel {
        private final String channelId;
        private final String name;

        private ResolvedChannel(String channelId, String name) {
            this.channelId = channelId;
            String displayName = name == null ? "" : name.trim();
            this.name = displayName.isEmpty() ? channelId
                    : displayName.substring(0, Math.min(1_000, displayName.length()));
        }

        public String getChannelId() {
            return channelId;
        }

        public String getName() {
            return name;
        }

        public String getChannelUrl() {
            return YouTubeChannelUrls.channelUrl(channelId);
        }
    }
}
