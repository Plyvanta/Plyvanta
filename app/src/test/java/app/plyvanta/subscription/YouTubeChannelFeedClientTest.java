package app.plyvanta.subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

public final class YouTubeChannelFeedClientTest {
    private static final String CHANNEL_ID = "UCabcdefghijklmnopqrstuv";
    private static final String VIDEO_ID = "abcdefghijk";
    private static final String SECOND_ID = "0123456789_";
    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String YOUTUBE = "http://www.youtube.com/xml/schemas/2015";
    private static final MediaType XML = MediaType.get("application/atom+xml; charset=utf-8");

    @Test
    public void usesExactPublishedInstantAndSortsNewestFirstIgnoringUpdated() throws Exception {
        String old = entry(VIDEO_ID, "Old", "2026-10-05T09:00:00.123Z")
                .replace("</entry>", "<updated>2026-10-06T00:00:00Z</updated></entry>");
        String newest = entry(SECOND_ID, "New", "2026-10-05T12:00:00+02:00");
        List<SubscriptionVideo> result = parse(feed(old + newest));
        assertEquals(2, result.size());
        assertEquals(SECOND_ID, result.get(0).getVideoId());
        assertEquals(Instant.parse("2026-10-05T10:00:00Z").toEpochMilli(), result.get(0).getPublishedAtMs());
        assertEquals(Instant.parse("2026-10-05T09:00:00.123Z").toEpochMilli(), result.get(1).getPublishedAtMs());
        assertEquals(CHANNEL_ID, result.get(0).getChannelId());
    }

    @Test
    public void decodesBuiltInAndNumericEntitiesAndSupportsNamespacePrefixes() throws Exception {
        String xml = "<a:feed xmlns:a=\"" + ATOM + "\" xmlns:y=\"" + YOUTUBE + "\">"
                + "<y:channelId>" + CHANNEL_ID + "</y:channelId><a:entry>"
                + "<y:channelId>" + CHANNEL_ID + "</y:channelId><y:videoId>" + VIDEO_ID + "</y:videoId>"
                + "<a:title>Cats &amp; dogs &#x1F600; &lt;3</a:title>"
                + "<a:published>2026-10-05T10:00:00Z</a:published></a:entry></a:feed>";
        assertEquals("Cats & dogs 😀 <3", parse(xml).get(0).getTitle());
    }

    @Test
    public void acceptsEmptyChannelFeed() throws Exception {
        assertTrue(parse(feed("")).isEmpty());
    }

    @Test
    public void rejectsDtdsExternalEntitiesAndExpansion() {
        for (String declaration : new String[] {
                "<!DOCTYPE feed SYSTEM \"https://attacker.test/feed.dtd\">",
                "<!DOCTYPE feed [<!ENTITY payload SYSTEM \"file:///etc/passwd\">]>",
                "<!DOCTYPE feed [<!ENTITY a \"123\"><!ENTITY b \"&a;&a;&a;\">]>"
        }) {
            assertThrows(IOException.class, () -> parse(declaration + feed(entry(VIDEO_ID, "&payload;", "2026-10-05T10:00:00Z"))));
        }
        assertThrows(IOException.class,
                () -> parse(feed(entry(VIDEO_ID, "&unregistered;", "2026-10-05T10:00:00Z"))));
    }

    @Test
    public void rejectsUnexpectedRootsNamespacesAndChannelIdentity() {
        String xml = feed(entry(VIDEO_ID, "Title", "2026-10-05T10:00:00Z"));
        for (String invalid : new String[] {
                "<html><body>Not a feed</body></html>", xml.replace(ATOM, "https://attacker.test/atom"),
                xml.replace(YOUTUBE, "https://attacker.test/youtube"),
                xml.replace("<entry>", "<entry xmlns=\"https://attacker.test/atom\">"),
                xml.replace(CHANNEL_ID, "UC0123456789_ABCDEFGHIJ-"),
                feed(entry(VIDEO_ID, "Title", "2026-10-05T10:00:00Z")
                        .replace(CHANNEL_ID, "UC0123456789_ABCDEFGHIJ-")),
                "<feed xmlns=\"" + ATOM + "\" />"
        }) {
            assertThrows(IOException.class, () -> parse(invalid));
        }
    }

    @Test
    public void rejectsInvalidMissingDuplicateAndNestedMetadata() {
        String entry = entry(VIDEO_ID, "Title", "2026-10-05T10:00:00Z");
        for (String invalid : new String[] {
                entry.replace(VIDEO_ID, "short"),
                entry.replace("<published>", "<updated>").replace("</published>", "</updated>"),
                entry.replace("</entry>", "<published>2026-10-06T00:00:00Z</published></entry>"),
                entry.replace("<title>Title</title>", "<title><nested>Title</nested></title>"),
                entry.replace("<yt:videoId>" + VIDEO_ID + "</yt:videoId>", ""),
                entry + entry
        }) {
            assertThrows(IOException.class, () -> parse(feed(invalid)));
        }
        assertThrows(IOException.class, () -> parse("<feed"));
    }

    @Test
    public void rejectsInvalidRelativeOrMissingTimezones() {
        for (String published : new String[] {
                "2 hours ago", "2026-10-05T10:00:00", "2026-02-30T10:00:00Z",
                "2026-10-05", "1969-12-31T23:59:59Z", ""
        }) {
            assertThrows(IOException.class,
                    () -> parse(feed(entry(VIDEO_ID, "Title", published))));
        }
    }

    @Test
    public void fetchesOnlyCanonicalHttpsFeedUrlThroughInjectedCallFactory() throws Exception {
        AtomicReference<Request> requested = new AtomicReference<>();
        OkHttpClient stack = new OkHttpClient.Builder().addInterceptor(chain -> {
            requested.set(chain.request());
            return response(chain.request(), 200, ResponseBody.create(feed(entry(VIDEO_ID, "Title", "2026-10-05T10:00:00Z")), XML));
        }).build();
        assertEquals(1, new YouTubeChannelFeedClient(stack).fetch(CHANNEL_ID).size());
        assertEquals("https://www.youtube.com/feeds/videos.xml?channel_id=" + CHANNEL_ID,
                requested.get().url().toString());
        assertEquals("application/atom+xml", requested.get().header("Accept"));
    }

    @Test
    public void rejectsHttpErrorsAndOversizedBodiesIncludingUnknownLengths() {
        YouTubeChannelFeedClient notFound = client(404, ResponseBody.create("Not found", XML));
        assertThrows(IOException.class, () -> notFound.fetch(CHANNEL_ID));
        String oversized = "x".repeat(YouTubeChannelFeedClient.MAX_RESPONSE_BYTES + 1);
        assertThrows(IOException.class, () -> client(200, ResponseBody.create(oversized, XML)).fetch(CHANNEL_ID));
        ResponseBody unknownLength = new ResponseBody() {
            private final Buffer source = new Buffer().writeUtf8(oversized);

            @Override
            public MediaType contentType() {
                return XML;
            }

            @Override
            public long contentLength() {
                return -1;
            }

            @Override
            public BufferedSource source() {
                return source;
            }
        };
        assertThrows(IOException.class, () -> client(200, unknownLength).fetch(CHANNEL_ID));
    }

    @Test
    public void rejectsInvalidUtf8InsteadOfSilentlyChangingMetadata() {
        assertThrows(IOException.class,
                () -> client(200, ResponseBody.create(new byte[] {(byte) 0xC3, 0x28}, XML)).fetch(CHANNEL_ID));
    }

    @Test
    public void rejectsInvalidIdsAndCancellationBeforeNetworkRequests() {
        AtomicInteger requests = new AtomicInteger();
        YouTubeChannelFeedClient client = new YouTubeChannelFeedClient(new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    requests.incrementAndGet();
                    return response(chain.request(), 200, ResponseBody.create(feed(""), XML));
                }).build());
        assertThrows(IllegalArgumentException.class, () -> client.fetch("UCshort&redirect=attacker"));
        client.cancel();
        assertThrows(InterruptedIOException.class, () -> client.fetch(CHANNEL_ID));
        assertEquals(0, requests.get());
    }

    private static List<SubscriptionVideo> parse(String xml) throws IOException {
        return YouTubeChannelFeedClient.parseFeed(CHANNEL_ID, xml);
    }

    private static String feed(String entries) {
        return "<feed xmlns=\"" + ATOM + "\" xmlns:yt=\"" + YOUTUBE + "\">"
                + "<yt:channelId>" + CHANNEL_ID + "</yt:channelId>" + entries + "</feed>";
    }

    private static String entry(String videoId, String title, String published) {
        return "<entry><yt:videoId>" + videoId + "</yt:videoId><yt:channelId>" + CHANNEL_ID
                + "</yt:channelId><title>" + title + "</title><published>" + published + "</published></entry>";
    }

    private static YouTubeChannelFeedClient client(int code, ResponseBody body) {
        return new YouTubeChannelFeedClient(new OkHttpClient.Builder()
                .addInterceptor(chain -> response(chain.request(), code, body)).build());
    }

    private static Response response(Request request, int code, ResponseBody body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message(code == 200 ? "OK" : "Not found").body(body).build();
    }
}
