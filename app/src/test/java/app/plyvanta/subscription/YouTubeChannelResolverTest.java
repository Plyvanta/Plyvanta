package app.plyvanta.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.schabi.newpipe.extractor.channel.ChannelInfo;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class YouTubeChannelResolverTest {
    private static final String CHANNEL_ID = "UCabcdefghijklmnopqrstuv";

    @Test
    public void resolvesHandleToCanonicalIdentityAndIgnoresUntrustedMetadataUrls() throws Exception {
        AtomicReference<String> requested = new AtomicReference<>();
        YouTubeChannelResolver resolver = new YouTubeChannelResolver(url -> {
            requested.set(url);
            return new ChannelInfo(0, CHANNEL_ID, "https://attacker.test/", url, " My creator ");
        });
        YouTubeChannelResolver.ResolvedChannel result = resolver.resolve("@MyCreator");
        assertEquals("https://www.youtube.com/@MyCreator", requested.get());
        assertEquals(CHANNEL_ID, result.getChannelId());
        assertEquals("My creator", result.getName());
        assertEquals("https://www.youtube.com/channel/" + CHANNEL_ID, result.getChannelUrl());
    }

    @Test
    public void rejectsUntrustedInputsBeforeCallingExtractor() {
        AtomicBoolean requested = new AtomicBoolean();
        YouTubeChannelResolver resolver = new YouTubeChannelResolver(url -> {
            requested.set(true);
            return info(CHANNEL_ID, "Creator");
        });
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("https://attacker.test/@creator"));
        assertFalse(requested.get());
    }

    @Test
    public void rejectsInvalidMetadataIdsAndUnexpectedCanonicalChannel() {
        YouTubeChannelResolver invalid = new YouTubeChannelResolver(url -> info("UCshort", "Creator"));
        assertThrows(IOException.class, () -> invalid.resolve("@creator"));
        YouTubeChannelResolver different = new YouTubeChannelResolver(url -> info(CHANNEL_ID, "Creator"));
        assertThrows(IOException.class, () -> different.resolve("UC0123456789_ABCDEFGHIJ-"));
    }

    @Test
    public void fallsBackForMissingDisplayNameAndBoundsNames() throws Exception {
        assertEquals(CHANNEL_ID,
                new YouTubeChannelResolver(url -> info(CHANNEL_ID, null)).resolve("@creator").getName());
        assertEquals(1_000,
                new YouTubeChannelResolver(url -> info(CHANNEL_ID, "x".repeat(1_001)))
                        .resolve("@creator").getName().length());
    }

    @Test
    public void refusesInterruptedLookupWithoutClearingInterrupt() {
        AtomicBoolean requested = new AtomicBoolean();
        YouTubeChannelResolver resolver = new YouTubeChannelResolver(url -> {
            requested.set(true);
            return info(CHANNEL_ID, "Creator");
        });
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> resolver.resolve("@creator"));
            assertFalse(requested.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void restoresInterruptFlagWhenExtractorThrowsInterruptedException() {
        YouTubeChannelResolver resolver = new YouTubeChannelResolver(url -> {
            throw new InterruptedException("Stopped during lookup");
        });
        try {
            assertThrows(InterruptedException.class, () -> resolver.resolve("@creator"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static ChannelInfo info(String id, String name) {
        return new ChannelInfo(0, id, "https://www.youtube.com/channel/" + id, "@creator", name);
    }
}
