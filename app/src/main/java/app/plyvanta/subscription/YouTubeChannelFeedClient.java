package app.plyvanta.subscription;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import app.plyvanta.extractor.OkHttpDownloader;
import app.plyvanta.network.AppNetwork;
import app.plyvanta.util.YouTubeUrlParser;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Reads YouTube's public upload feed without a login or API key. Run on a worker thread.
 * YouTube exposes only its latest 15 public uploads; refreshes cannot backfill longer gaps.
 */
public final class YouTubeChannelFeedClient {
    static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_ENTRIES = 100;
    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
    private static final String YOUTUBE_NAMESPACE = "http://www.youtube.com/xml/schemas/2015";
    private static final String FEED_URL = "https://www.youtube.com/feeds/videos.xml?channel_id=";

    private final Call.Factory calls;
    private final Object callLock = new Object();
    private final Set<Call> activeCalls = new HashSet<>();
    private boolean cancelled;

    public YouTubeChannelFeedClient() {
        this(AppNetwork.calls(AppNetwork.Profile.EXTRACTOR));
    }

    YouTubeChannelFeedClient(Call.Factory calls) {
        this.calls = Objects.requireNonNull(calls, "calls");
    }

    /** Returns validated videos in newest-publication-first order. */
    public List<SubscriptionVideo> fetch(String channelId) throws IOException {
        if (!YouTubeChannelUrls.isValidChannelId(channelId)) {
            throw new IllegalArgumentException("Invalid YouTube channel ID.");
        }
        throwIfCancelled();
        Request request = new Request.Builder()
                .url(FEED_URL + channelId)
                .header("Accept", "application/atom+xml")
                .header("User-Agent", OkHttpDownloader.DESKTOP_USER_AGENT)
                .build();
        Call call = calls.newCall(request);
        synchronized (callLock) {
            throwIfCancelled();
            activeCalls.add(call);
        }
        try (Response response = call.execute()) {
            throwIfCancelled();
            if (response.code() != 200) {
                throw new IOException("Channel feed returned HTTP " + response.code() + ".");
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Channel feed returned no response.");
            }
            String xml = readBoundedBody(body);
            throwIfCancelled();
            List<SubscriptionVideo> videos = parseFeed(channelId, xml);
            throwIfCancelled();
            return videos;
        } catch (IOException failure) {
            throwIfCancelled();
            throw failure;
        } finally {
            synchronized (callLock) {
                activeCalls.remove(call);
            }
            if (Thread.currentThread().isInterrupted()) {
                call.cancel();
            }
        }
    }

    /** Cancels pending work permanently; create a new client for the next refresh. */
    public void cancel() {
        synchronized (callLock) {
            cancelled = true;
            for (Call call : activeCalls) {
                call.cancel();
            }
        }
    }

    private void throwIfCancelled() throws InterruptedIOException {
        synchronized (callLock) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Channel feed refresh was cancelled.");
            }
        }
    }

    private String readBoundedBody(ResponseBody body) throws IOException {
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw new IOException("Channel feed response is too large.");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = body.byteStream()) {
            byte[] buffer = new byte[4_096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                throwIfCancelled();
                if (bytes.size() + count > MAX_RESPONSE_BYTES) {
                    throw new IOException("Channel feed response is too large.");
                }
                bytes.write(buffer, 0, count);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException invalidEncoding) {
            throw new IOException("Channel feed was not valid UTF-8.", invalidEncoding);
        }
    }

    static List<SubscriptionVideo> parseFeed(String channelId, String xml) throws IOException {
        if (!YouTubeChannelUrls.isValidChannelId(channelId)) {
            throw new IllegalArgumentException("Invalid YouTube channel ID.");
        }
        if (xml == null || xml.isBlank()
                || xml.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new IOException("Channel feed is empty or too large.");
        }
        // Android XML factories do not implement every desktop security feature. Reject all
        // DTD declarations before parsing the decoded Reader, independently of those features.
        // XML's DOCTYPE/ENTITY keywords are case-sensitive and cannot contain character escapes.
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) {
            throw new IOException("Channel feeds must not contain DTDs or custom entities.");
        }
        if (xml.charAt(0) == '\uFEFF') {
            xml = xml.substring(1);
        }
        final Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            setOptionalFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setOptionalFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setOptionalFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setOptionalFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External XML entities are not allowed.");
            });
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            document = builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException invalidXml) {
            throw new IOException("YouTube returned an invalid channel feed.", invalidXml);
        }
        Element feed = document.getDocumentElement();
        if (!matches(feed, ATOM_NAMESPACE, "feed")) {
            throw new IOException("Channel response is not an Atom feed.");
        }
        if (!channelId.equals(requiredText(feed, YOUTUBE_NAMESPACE, "channelId"))) {
            throw new IOException("Channel feed belongs to a different channel.");
        }

        ArrayList<SubscriptionVideo> videos = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Node child = feed.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && "entry".equals(child.getLocalName())
                    && !matches((Element) child, ATOM_NAMESPACE, "entry")) {
                throw new IOException("Channel feed contains an invalid entry namespace.");
            }
            if (!(child instanceof Element)
                    || !matches((Element) child, ATOM_NAMESPACE, "entry")) {
                continue;
            }
            if (videos.size() >= MAX_ENTRIES) {
                throw new IOException("Channel feed contains too many videos.");
            }
            Element entry = (Element) child;
            String videoId = requiredText(entry, YOUTUBE_NAMESPACE, "videoId");
            if (!YouTubeUrlParser.isValidVideoId(videoId) || !ids.add(videoId)) {
                throw new IOException("Channel feed contains an invalid or duplicate video ID.");
            }
            if (!channelId.equals(requiredText(entry, YOUTUBE_NAMESPACE, "channelId"))) {
                throw new IOException("Channel feed contains a video from another channel.");
            }
            String title = requiredText(entry, ATOM_NAMESPACE, "title");
            String published = requiredText(entry, ATOM_NAMESPACE, "published");
            final long publishedAtMs;
            try {
                // The upload's published instant defines eligibility. The unrelated updated
                // instant and human-readable relative dates must never change that boundary.
                publishedAtMs = OffsetDateTime.parse(published, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        .toInstant().toEpochMilli();
                if (publishedAtMs < 0) {
                    throw new DateTimeParseException("Publication predates Unix time", published, 0);
                }
            } catch (DateTimeParseException | ArithmeticException invalidDate) {
                throw new IOException("Channel feed contains an invalid publication time.", invalidDate);
            }
            videos.add(new SubscriptionVideo(channelId, videoId, title, publishedAtMs));
        }
        videos.sort(Comparator.comparingLong(SubscriptionVideo::getPublishedAtMs).reversed()
                .thenComparing(SubscriptionVideo::getVideoId));
        return Collections.unmodifiableList(videos);
    }

    private static void setOptionalFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | AbstractMethodError unsupportedOnAndroid) {
            // The pre-parse DTD ban and rejecting EntityResolver remain mandatory protections.
        }
    }

    private static boolean matches(Element element, String namespace, String localName) {
        return element != null && namespace.equals(element.getNamespaceURI())
                && localName.equals(element.getLocalName());
    }

    private static String requiredText(Element parent, String namespace, String localName)
            throws IOException {
        Element found = null;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && matches((Element) child, namespace, localName)) {
                if (found != null) {
                    throw new IOException("Channel feed contains duplicate " + localName + " fields.");
                }
                found = (Element) child;
            }
        }
        if (found == null) {
            throw new IOException("Channel feed is missing " + localName + ".");
        }
        for (Node child = found.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                throw new IOException("Channel feed contains an invalid " + localName + " field.");
            }
        }
        return found.getTextContent().trim();
    }
}
