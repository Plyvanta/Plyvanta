package app.plyvanta.subscription;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Trusted YouTube channel references, normalized before passing them to the extractor. */
public final class YouTubeChannelUrls {
    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    private static final Pattern CHANNEL_NAME = Pattern.compile("[\\p{L}\\p{M}\\p{N}._-]{1,100}");
    private static final String PREFIX = "https://www.youtube.com";

    private YouTubeChannelUrls() {
    }

    public static boolean isValidChannelId(String value) {
        return value != null && CHANNEL_ID.matcher(value).matches();
    }

    public static String channelUrl(String channelId) {
        if (!isValidChannelId(channelId)) {
            throw new IllegalArgumentException("Invalid YouTube channel ID.");
        }
        return PREFIX + "/channel/" + channelId;
    }

    /** Returns a trusted HTTPS channel URL, or null when the input is unsupported. */
    public static String canonicalize(String input) {
        if (input == null) {
            return null;
        }
        String candidate = input.trim();
        if (candidate.isEmpty() || candidate.length() > 2_048) {
            return null;
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (Character.isWhitespace(candidate.charAt(i))
                    || Character.isISOControl(candidate.charAt(i))) {
                return null;
            }
        }
        if (isValidChannelId(candidate)) {
            return channelUrl(candidate);
        }
        if (candidate.startsWith("@")) {
            candidate = PREFIX + "/" + candidate;
        } else if (candidate.startsWith("//")) {
            candidate = "https:" + candidate;
        } else if (!candidate.contains("://")) {
            candidate = "https://" + candidate;
        }

        final URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException invalid) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))
                || uri.getRawUserInfo() != null || uri.getHost() == null) {
            return null;
        }
        int port = uri.getPort();
        if (port != -1 && port != (scheme.equalsIgnoreCase("https") ? 443 : 80)) {
            return null;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("youtube.com") && !host.equals("www.youtube.com")
                && !host.equals("m.youtube.com") && !host.equals("music.youtube.com")) {
            return null;
        }

        String path = uri.getPath();
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        // Encoded route delimiters must not change the identity seen by the extractor.
        String rawPath = uri.getRawPath().toLowerCase(Locale.ROOT);
        if (rawPath.contains("%2f") || rawPath.contains("%5c") || rawPath.contains("%25")) {
            return null;
        }
        String[] parts = path.substring(1).split("/", -1);
        int used;
        if (parts.length > 0 && parts[0].startsWith("@")
                && validName(parts[0].substring(1))) {
            used = 1;
        } else if (parts.length >= 2 && parts[0].equals("channel")
                && isValidChannelId(parts[1])) {
            used = 2;
        } else if (parts.length >= 2
                && (parts[0].equals("user") || parts[0].equals("c"))
                && validName(parts[1])) {
            used = 2;
        } else {
            return null;
        }
        if (parts.length > used && !parts[used].isEmpty()
                && !isChannelTab(parts[used])) {
            return null;
        }
        int maximum = used + (parts.length > used && !parts[used].isEmpty() ? 2 : 1);
        if (parts.length > maximum
                || (parts.length == used + 2 && !parts[used + 1].isEmpty())) {
            return null;
        }
        String canonicalPath = "/" + parts[0] + (used == 2 ? "/" + parts[1] : "");
        try {
            return new URI("https", "www.youtube.com", canonicalPath, null).toASCIIString();
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    private static boolean validName(String value) {
        return CHANNEL_NAME.matcher(value).matches() && !value.equals(".") && !value.equals("..");
    }

    private static boolean isChannelTab(String value) {
        return value.equals("videos") || value.equals("shorts") || value.equals("streams")
                || value.equals("featured") || value.equals("playlists")
                || value.equals("community") || value.equals("about");
    }
}
