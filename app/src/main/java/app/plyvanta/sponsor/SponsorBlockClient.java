package app.plyvanta.sponsor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import app.plyvanta.network.AppNetwork;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Keyless, read-only client for SponsorBlock's privacy-preserving segment endpoint.
 */
public final class SponsorBlockClient {
    public static final List<String> DEFAULT_CATEGORIES = Collections.unmodifiableList(
            Arrays.asList("sponsor", "selfpromo")
    );

    static final int HASH_PREFIX_LENGTH = 4;
    static final int MAX_RESPONSE_BYTES = 512 * 1024;
    static final int MAX_VIDEO_BUCKETS = 4_096;
    static final int MAX_SEGMENTS_PER_VIDEO = 2_048;

    private static final String DEFAULT_API_BASE_URL = "https://sponsor.ajay.app/";
    private static final String USER_AGENT = "Plyvanta/1.0.0 (Android; read-only)";
    private static final String ACTION_TYPE_SKIP = "skip";
    private static final long DEFAULT_CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final int DEFAULT_MAX_CACHE_ENTRIES = 128;
    private static final int MAX_CATEGORIES = 16;
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern CATEGORY_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private final Call.Factory httpClient;
    private final HttpUrl apiBaseUrl;
    private final LongSupplier clock;
    private final long cacheTtlMillis;
    private final int maxCacheEntries;
    private final Object cacheLock = new Object();
    private final LinkedHashMap<CacheKey, CacheEntry> cache =
            new LinkedHashMap<>(16, 0.75f, true);

    public SponsorBlockClient() {
        this(
                AppNetwork.calls(AppNetwork.Profile.SPONSOR),
                HttpUrl.get(DEFAULT_API_BASE_URL),
                SponsorBlockClient::monotonicTimeMillis,
                DEFAULT_CACHE_TTL_MILLIS,
                DEFAULT_MAX_CACHE_ENTRIES
        );
    }

    /**
     * Creates a client using a custom HTTP stack and API root.
     *
     * <p>The URL is treated as a root and {@code api/skipSegments/:hashPrefix} is appended.
     */
    public SponsorBlockClient(Call.Factory httpClient, HttpUrl apiBaseUrl) {
        this(
                httpClient,
                apiBaseUrl,
                SponsorBlockClient::monotonicTimeMillis,
                DEFAULT_CACHE_TTL_MILLIS,
                DEFAULT_MAX_CACHE_ENTRIES
        );
    }

    SponsorBlockClient(
            Call.Factory httpClient,
            HttpUrl apiBaseUrl,
            LongSupplier clock,
            long cacheTtlMillis,
            int maxCacheEntries
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.apiBaseUrl = Objects.requireNonNull(apiBaseUrl, "apiBaseUrl");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (cacheTtlMillis <= 0L) {
            throw new IllegalArgumentException("cacheTtlMillis must be positive");
        }
        if (maxCacheEntries <= 0) {
            throw new IllegalArgumentException("maxCacheEntries must be positive");
        }
        this.cacheTtlMillis = cacheTtlMillis;
        this.maxCacheEntries = maxCacheEntries;
    }

    public List<SponsorSegment> fetchSegments(String videoId) throws IOException {
        return fetchSegments(videoId, DEFAULT_CATEGORIES);
    }

    /**
     * Fetches validated skip segments on the calling thread.
     *
     * <p>A 404 is a normal empty result. Other HTTP errors and malformed or oversized successful
     * responses are surfaced as {@link IOException}s so the caller can play without SponsorBlock.
     */
    public List<SponsorSegment> fetchSegments(
            String videoId,
            Collection<String> categories
    ) throws IOException {
        PreparedRequest prepared = prepareRequest(videoId, categories);
        List<SponsorSegment> cached = getCached(prepared.cacheKey);
        if (cached != null) {
            return cached;
        }

        try (Response response = httpClient.newCall(prepared.request).execute()) {
            List<SponsorSegment> result = readResponse(
                    response,
                    prepared.videoId,
                    prepared.categories
            );
            putCached(prepared.cacheKey, result);
            return result;
        }
    }

    public CompletableFuture<List<SponsorSegment>> getSegments(String videoId) {
        return getSegments(videoId, DEFAULT_CATEGORIES);
    }

    /**
     * Fetches segments asynchronously using OkHttp's dispatcher.
     */
    public CompletableFuture<List<SponsorSegment>> getSegments(
            String videoId,
            Collection<String> categories
    ) {
        PreparedRequest prepared = prepareRequest(videoId, categories);
        List<SponsorSegment> cached = getCached(prepared.cacheKey);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<List<SponsorSegment>> future = new CompletableFuture<>();
        Call call = httpClient.newCall(prepared.request);
        future.whenComplete((ignoredResult, ignoredFailure) -> {
            if (future.isCancelled()) {
                call.cancel();
            }
        });
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call failedCall, IOException failure) {
                future.completeExceptionally(failure);
            }

            @Override
            public void onResponse(Call completedCall, Response response) {
                try (response) {
                    List<SponsorSegment> result = readResponse(
                            response,
                            prepared.videoId,
                            prepared.categories
                    );
                    putCached(prepared.cacheKey, result);
                    future.complete(result);
                } catch (IOException | RuntimeException failure) {
                    future.completeExceptionally(failure);
                }
            }
        });
        return future;
    }

    public void clearCache() {
        synchronized (cacheLock) {
            cache.clear();
        }
    }

    Request buildRequest(String videoId, Collection<String> categories) {
        return prepareRequest(videoId, categories).request;
    }

    private PreparedRequest prepareRequest(String videoId, Collection<String> categories) {
        String validatedVideoId = validateVideoId(videoId);
        List<String> normalizedCategories = normalizeCategories(categories);

        HttpUrl.Builder url = apiBaseUrl.newBuilder()
                .addPathSegment("api")
                .addPathSegment("skipSegments")
                .addPathSegment(hashPrefix(validatedVideoId));
        for (String category : normalizedCategories) {
            // Repeated parameters avoid placing a JSON category array in logs or intermediaries.
            url.addQueryParameter("category", category);
        }
        url.addQueryParameter("actionType", ACTION_TYPE_SKIP);
        url.addQueryParameter("service", "YouTube");

        Request request = new Request.Builder()
                .url(url.build())
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build();
        return new PreparedRequest(
                validatedVideoId,
                normalizedCategories,
                new CacheKey(validatedVideoId, normalizedCategories),
                request
        );
    }

    private List<SponsorSegment> readResponse(
            Response response,
            String videoId,
            List<String> requestedCategories
    ) throws IOException {
        int statusCode = response.code();
        if (statusCode == 404) {
            return Collections.emptyList();
        }
        if (statusCode != 200) {
            throw new IOException("SponsorBlock request failed with HTTP " + statusCode);
        }

        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("SponsorBlock returned HTTP 200 without a response body");
        }
        return parseResponseBody(readBoundedBody(body), videoId, requestedCategories);
    }

    static List<SponsorSegment> parseResponseBody(
            String responseBody,
            String videoId,
            Collection<String> requestedCategories
    ) throws IOException {
        Objects.requireNonNull(responseBody, "responseBody");
        String validatedVideoId = validateVideoId(videoId);
        List<String> normalizedCategories = normalizeCategories(requestedCategories);

        final JSONArray videoBuckets;
        try {
            videoBuckets = new JSONArray(responseBody);
        } catch (JSONException malformedJson) {
            throw new IOException("SponsorBlock returned malformed JSON", malformedJson);
        }
        if (videoBuckets.length() > MAX_VIDEO_BUCKETS) {
            throw new IOException("SponsorBlock response contains too many video buckets");
        }

        List<SponsorSegment> parsed = new ArrayList<>();
        int matchingSegmentCount = 0;
        for (int bucketIndex = 0; bucketIndex < videoBuckets.length(); bucketIndex++) {
            JSONObject bucket = videoBuckets.optJSONObject(bucketIndex);
            if (bucket == null || !hasExactString(bucket, "videoID", validatedVideoId)) {
                continue;
            }

            JSONArray segments = bucket.optJSONArray("segments");
            if (segments == null) {
                continue;
            }
            if (segments.length() > MAX_SEGMENTS_PER_VIDEO - matchingSegmentCount) {
                throw new IOException("SponsorBlock response contains too many segments");
            }
            matchingSegmentCount += segments.length();

            for (int segmentIndex = 0; segmentIndex < segments.length(); segmentIndex++) {
                SponsorSegment segment = parseSegment(
                        segments.optJSONObject(segmentIndex),
                        normalizedCategories
                );
                if (segment != null) {
                    parsed.add(segment);
                }
            }
        }

        parsed.sort(
                Comparator.comparingDouble(SponsorSegment::getStartSeconds)
                        .thenComparingDouble(SponsorSegment::getEndSeconds)
                        .thenComparing(SponsorSegment::getUuid)
        );
        return immutableMergedSegments(parsed);
    }

    static String hashPrefix(String videoId) {
        String validatedVideoId = validateVideoId(videoId);
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(validatedVideoId.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }

        char[] prefix = new char[HASH_PREFIX_LENGTH];
        final char[] hexadecimal = "0123456789abcdef".toCharArray();
        for (int index = 0; index < HASH_PREFIX_LENGTH / 2; index++) {
            int value = digest[index] & 0xff;
            prefix[index * 2] = hexadecimal[value >>> 4];
            prefix[index * 2 + 1] = hexadecimal[value & 0x0f];
        }
        return new String(prefix);
    }

    private static SponsorSegment parseSegment(
            JSONObject object,
            Collection<String> requestedCategories
    ) {
        if (object == null
                || !hasExactString(object, "actionType", ACTION_TYPE_SKIP)) {
            return null;
        }

        Object rawCategory = object.opt("category");
        Object rawUuid = object.opt("UUID");
        JSONArray interval = object.optJSONArray("segment");
        if (!(rawCategory instanceof String)
                || !(rawUuid instanceof String)
                || interval == null
                || interval.length() != 2) {
            return null;
        }

        String category = ((String) rawCategory).trim().toLowerCase(Locale.ROOT);
        String uuid = ((String) rawUuid).trim();
        if (!requestedCategories.contains(category) || uuid.isEmpty()) {
            return null;
        }

        Object rawStart = interval.opt(0);
        Object rawEnd = interval.opt(1);
        if (!(rawStart instanceof Number) || !(rawEnd instanceof Number)) {
            return null;
        }

        try {
            return new SponsorSegment(
                    uuid,
                    ((Number) rawStart).doubleValue(),
                    ((Number) rawEnd).doubleValue(),
                    category
            );
        } catch (IllegalArgumentException invalidSegment) {
            return null;
        }
    }

    private static List<SponsorSegment> immutableMergedSegments(List<SponsorSegment> sorted) {
        if (sorted.isEmpty()) {
            return Collections.emptyList();
        }

        List<SponsorSegment> merged = new ArrayList<>();
        SponsorSegment current = sorted.get(0);
        for (int index = 1; index < sorted.size(); index++) {
            SponsorSegment next = sorted.get(index);
            if (next.getStartSeconds() <= current.getEndSeconds()) {
                current = current.mergeWith(next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return Collections.unmodifiableList(merged);
    }

    private static String readBoundedBody(ResponseBody body) throws IOException {
        long declaredLength = body.contentLength();
        if (declaredLength > MAX_RESPONSE_BYTES) {
            throw new IOException("SponsorBlock response exceeds the size limit");
        }

        int initialCapacity = declaredLength >= 0L
                ? (int) Math.min(declaredLength, MAX_RESPONSE_BYTES)
                : 8_192;
        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity)) {
            byte[] buffer = new byte[8_192];
            int totalBytes = 0;
            while (true) {
                int permittedRead = Math.min(
                        buffer.length,
                        MAX_RESPONSE_BYTES - totalBytes + 1
                );
                int bytesRead = input.read(buffer, 0, permittedRead);
                if (bytesRead == -1) {
                    break;
                }
                totalBytes += bytesRead;
                if (totalBytes > MAX_RESPONSE_BYTES) {
                    throw new IOException("SponsorBlock response exceeds the size limit");
                }
                output.write(buffer, 0, bytesRead);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static boolean hasExactString(JSONObject object, String name, String expected) {
        Object value = object.opt(name);
        return value instanceof String && expected.equals(value);
    }

    private static String validateVideoId(String videoId) {
        Objects.requireNonNull(videoId, "videoId");
        if (!VIDEO_ID_PATTERN.matcher(videoId).matches()) {
            throw new IllegalArgumentException("videoId must be an 11-character YouTube ID");
        }
        return videoId;
    }

    private static List<String> normalizeCategories(Collection<String> categories) {
        Objects.requireNonNull(categories, "categories");
        TreeSet<String> normalized = new TreeSet<>();
        for (String category : categories) {
            Objects.requireNonNull(category, "category");
            String value = category.trim().toLowerCase(Locale.ROOT);
            if (!CATEGORY_PATTERN.matcher(value).matches()) {
                throw new IllegalArgumentException("Invalid SponsorBlock category: " + category);
            }
            normalized.add(value);
            if (normalized.size() > MAX_CATEGORIES) {
                throw new IllegalArgumentException("Too many SponsorBlock categories");
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one SponsorBlock category is required");
        }
        return Collections.unmodifiableList(new ArrayList<>(normalized));
    }

    private List<SponsorSegment> getCached(CacheKey key) {
        long now = clock.getAsLong();
        synchronized (cacheLock) {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.expiresAtMillis <= now) {
                cache.remove(key);
                return null;
            }
            return entry.segments;
        }
    }

    private void putCached(CacheKey key, List<SponsorSegment> segments) {
        List<SponsorSegment> immutableSegments = segments.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(segments));
        long now = clock.getAsLong();
        long expiresAt = now > Long.MAX_VALUE - cacheTtlMillis
                ? Long.MAX_VALUE
                : now + cacheTtlMillis;

        synchronized (cacheLock) {
            cache.put(key, new CacheEntry(immutableSegments, expiresAt));
            while (cache.size() > maxCacheEntries) {
                Iterator<Map.Entry<CacheKey, CacheEntry>> entries = cache.entrySet().iterator();
                if (entries.hasNext()) {
                    entries.next();
                    entries.remove();
                }
            }
        }
    }

    private static long monotonicTimeMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private static final class PreparedRequest {
        private final String videoId;
        private final List<String> categories;
        private final CacheKey cacheKey;
        private final Request request;

        private PreparedRequest(
                String videoId,
                List<String> categories,
                CacheKey cacheKey,
                Request request
        ) {
            this.videoId = videoId;
            this.categories = categories;
            this.cacheKey = cacheKey;
            this.request = request;
        }
    }

    private static final class CacheKey {
        private final String videoId;
        private final List<String> categories;

        private CacheKey(String videoId, List<String> categories) {
            this.videoId = videoId;
            this.categories = categories;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheKey)) {
                return false;
            }
            CacheKey that = (CacheKey) other;
            return videoId.equals(that.videoId) && categories.equals(that.categories);
        }

        @Override
        public int hashCode() {
            return Objects.hash(videoId, categories);
        }
    }

    private static final class CacheEntry {
        private final List<SponsorSegment> segments;
        private final long expiresAtMillis;

        private CacheEntry(List<SponsorSegment> segments, long expiresAtMillis) {
            this.segments = segments;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
