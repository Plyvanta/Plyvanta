package app.plyvanta.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class YouTubeChannelUrlsTest {
    private static final String CHANNEL_ID = "UCabcdefghijklmnopqrstuv";
    private static final String CHANNEL_URL = "https://www.youtube.com/channel/" + CHANNEL_ID;

    @Test
    public void canonicalizesChannelIdsAndTrustedHosts() {
        for (String value : new String[] {
                CHANNEL_ID, CHANNEL_URL, "youtube.com/channel/" + CHANNEL_ID,
                "http://m.youtube.com/channel/" + CHANNEL_ID + "/videos?si=share",
                "https://music.youtube.com:443/channel/" + CHANNEL_ID + "/shorts/",
                "//WWW.YOUTUBE.COM/channel/" + CHANNEL_ID + "#about"
        }) {
            assertEquals(value, CHANNEL_URL, YouTubeChannelUrls.canonicalize(value));
        }
    }

    @Test
    public void acceptsHandlesAndLegacyChannelLinks() {
        assertEquals("https://www.youtube.com/@a.channel-1",
                YouTubeChannelUrls.canonicalize(" @a.channel-1 "));
        assertEquals("https://www.youtube.com/@SomeCreator",
                YouTubeChannelUrls.canonicalize("https://youtube.com/@SomeCreator/streams"));
        assertEquals("https://www.youtube.com/c/LegacyCreator",
                YouTubeChannelUrls.canonicalize("https://m.youtube.com/c/LegacyCreator/videos/"));
        assertEquals("https://www.youtube.com/user/OldCreator",
                YouTubeChannelUrls.canonicalize("www.youtube.com/user/OldCreator?feature=shared"));
        assertEquals("https://www.youtube.com/@%E6%97%A5%E6%9C%AC%E8%AA%9E",
                YouTubeChannelUrls.canonicalize("@日本語"));
    }

    @Test
    public void rejectsLookalikeHostsCredentialsUnusualPortsAndSchemes() {
        for (String value : new String[] {
                "https://youtube.com.attacker.test/@creator", "https://evil-youtube.com/@creator",
                "https://subdomain.youtube.com/@creator", "https://youtube.com@attacker.test/@creator",
                "https://attacker.test@youtube.com/@creator", "ftp://youtube.com/@creator",
                "https://youtube.com:8443/@creator", "http://youtube.com:443/@creator",
                "https://youtube.com./@creator", "https://youtu.be/abcdefghijk",
                "attacker.test/youtube.com/@creator", "javascript:youtube.com/@creator",
                "https://youtube.com\\@attacker.test/@creator", "https://youtube.com/@creator\nnext"
        }) {
            assertNull(value, YouTubeChannelUrls.canonicalize(value));
        }
    }

    @Test
    public void rejectsVideoPagesAmbiguousRoutesAndEncodedDelimiters() {
        for (String value : new String[] {
                "https://youtube.com/watch?v=abcdefghijk", "https://youtube.com/playlist?list=PL1234567890",
                "https://youtube.com/redirect?q=https://youtube.com/@creator", "https://youtube.com/channel/UCshort",
                "https://youtube.com/@", "@..", "https://youtube.com/@creator/other",
                "https://youtube.com/@creator/videos/another", "https://youtube.com/@creator//",
                "https://youtube.com/@creator%2fwatch", "https://youtube.com/@creator%5cwatch",
                "https://youtube.com/@creator%252fwatch", "https://youtube.com/@creator%0A",
                "https://youtube.com/c/..", "https://youtube.com/user/"
        }) {
            assertNull(value, YouTubeChannelUrls.canonicalize(value));
        }
        assertNull(YouTubeChannelUrls.canonicalize(null));
        assertNull(YouTubeChannelUrls.canonicalize(""));
    }

    @Test
    public void validatesExactCanonicalChannelIdSyntax() {
        assertTrue(YouTubeChannelUrls.isValidChannelId(CHANNEL_ID));
        assertTrue(YouTubeChannelUrls.isValidChannelId("UC0123456789_ABCDEFGHIJ-"));
        assertFalse(YouTubeChannelUrls.isValidChannelId("UCshort"));
        assertFalse(YouTubeChannelUrls.isValidChannelId("ucabcdefghijklmnopqrstuv"));
        assertFalse(YouTubeChannelUrls.isValidChannelId(CHANNEL_ID + "x"));
        assertFalse(YouTubeChannelUrls.isValidChannelId(null));
    }
}
