package app.plyvanta.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import app.plyvanta.offline.OfflineDownloadEligibility;
import kotlin.jvm.functions.Function0;
import kotlin.reflect.KClass;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Timeout;

/** Package-private routing engine without Android dependencies, also exercised by JVM tests. */
final class NetworkRouter {
    private final Object lock = new Object();
    private final Set<RoutedCall> activeCalls = new HashSet<>();
    private final EnumMap<AppNetwork.Profile, Call.Factory> factories =
            new EnumMap<>(AppNetwork.Profile.class);
    private EnumMap<AppNetwork.Profile, OkHttpClient> clients =
            buildClients(false, AppNetwork.DEFAULT_TOR_PORT);
    private boolean initialized;
    private boolean torEnabled;
    private int torPort = AppNetwork.DEFAULT_TOR_PORT;
    private long generation;

    NetworkRouter() {
        for (AppNetwork.Profile profile : AppNetwork.Profile.values()) {
            factories.put(profile, request -> newCall(profile, request, false));
        }
    }

    Call.Factory calls(AppNetwork.Profile profile) {
        return factories.get(Objects.requireNonNull(profile, "profile"));
    }

    boolean isTorEnabled() {
        synchronized (lock) {
            return torEnabled;
        }
    }

    int torPort() {
        synchronized (lock) {
            return torPort;
        }
    }

    void configure(boolean enabled, int port, Runnable persist) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SOCKS port must be between 1 and 65535.");
        }
        synchronized (lock) {
            // New calls cannot race the persistence step and select the previous route.
            persist.run();
            if (initialized && enabled == torEnabled && port == torPort) {
                return;
            }
            EnumMap<AppNetwork.Profile, OkHttpClient> replacement =
                    buildClients(enabled, port);
            EnumMap<AppNetwork.Profile, OkHttpClient> retired = clients;
            generation++;
            initialized = true;
            torEnabled = enabled;
            torPort = port;
            clients = replacement;
            // Dispatcher.cancelAll alone misses execute() calls whose bodies are still open.
            for (RoutedCall call : new ArrayList<>(activeCalls)) {
                call.delegate.cancel();
            }
            activeCalls.clear();
            for (OkHttpClient client : retired.values()) {
                client.dispatcher().cancelAll();
                client.connectionPool().evictAll();
            }
        }
    }

    Call newTorCheckCall(Request request) throws IOException {
        synchronized (lock) {
            if (!initialized || !torEnabled) {
                throw new IOException("Enable Tor before checking the connection.");
            }
            return newCall(AppNetwork.Profile.EXTRACTOR, request, true);
        }
    }

    private RoutedCall newCall(AppNetwork.Profile profile, Request request, boolean requireTor) {
        synchronized (lock) {
            OkHttpClient client = clients.get(profile);
            return new RoutedCall(client.newCall(request), client.dispatcher(), generation,
                    requireTor);
        }
    }

    static EnumMap<AppNetwork.Profile, OkHttpClient> buildClients(
            boolean tor, int port
    ) {
        ConnectionPool pool = new ConnectionPool();
        Dispatcher dispatcher = new Dispatcher();
        OkHttpClient.Builder shared = new OkHttpClient.Builder()
                .connectionPool(pool)
                .dispatcher(dispatcher)
                .followRedirects(true);
        if (tor) {
            shared.proxy(new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress("127.0.0.1", port)));
            // OkHttp passes unresolved destinations to SOCKS. Reject any future DNS path.
            shared.dns(host -> {
                throw new UnknownHostException("Local DNS is disabled while Tor is enabled.");
            });
        }
        OkHttpClient base = shared.build();
        EnumMap<AppNetwork.Profile, OkHttpClient> result =
                new EnumMap<>(AppNetwork.Profile.class);
        for (AppNetwork.Profile profile : AppNetwork.Profile.values()) {
            OkHttpClient.Builder builder = base.newBuilder();
            switch (profile) {
                case EXTRACTOR:
                    builder.connectTimeout(tor ? 30 : 12, TimeUnit.SECONDS)
                            .readTimeout(tor ? 60 : 20, TimeUnit.SECONDS)
                            .writeTimeout(tor ? 60 : 20, TimeUnit.SECONDS)
                            .callTimeout(tor ? 90 : 30, TimeUnit.SECONDS)
                            .followSslRedirects(true);
                    break;
                case PLAYBACK:
                    builder.connectTimeout(tor ? 30 : 8, TimeUnit.SECONDS)
                            .readTimeout(tor ? 60 : 8, TimeUnit.SECONDS)
                            .writeTimeout(tor ? 60 : 10, TimeUnit.SECONDS)
                            .callTimeout(0, TimeUnit.SECONDS)
                            .followSslRedirects(true);
                    break;
                case OFFLINE:
                    builder.connectTimeout(tor ? 30 : 15, TimeUnit.SECONDS)
                            .readTimeout(tor ? 60 : 30, TimeUnit.SECONDS)
                            .writeTimeout(tor ? 60 : 30, TimeUnit.SECONDS)
                            .callTimeout(0, TimeUnit.SECONDS)
                            .followSslRedirects(false)
                            .addNetworkInterceptor(chain -> {
                                if (!OfflineDownloadEligibility.isTrustedMediaUrl(
                                        chain.request().url().toString())) {
                                    throw new IOException(
                                            "Media redirect left the trusted HTTPS host boundary.");
                                }
                                return chain.proceed(chain.request());
                            });
                    break;
                case SPONSOR:
                    builder.connectTimeout(tor ? 30 : 5, TimeUnit.SECONDS)
                            .readTimeout(tor ? 60 : 8, TimeUnit.SECONDS)
                            .writeTimeout(tor ? 60 : 5, TimeUnit.SECONDS)
                            .callTimeout(tor ? 90 : 12, TimeUnit.SECONDS);
                    break;
                case UPDATE:
                    builder.connectTimeout(tor ? 30 : 10, TimeUnit.SECONDS)
                            .readTimeout(tor ? 60 : 20, TimeUnit.SECONDS)
                            .writeTimeout(tor ? 60 : 10, TimeUnit.SECONDS)
                            .callTimeout(tor ? 90 : 30, TimeUnit.SECONDS);
                    break;
                default:
                    throw new AssertionError(profile);
            }
            result.put(profile, builder.build());
        }
        return result;
    }

    private final class RoutedCall implements Call {
        private final Call delegate;
        private final Dispatcher dispatcher;
        private final long createdGeneration;
        private final boolean requireTor;
        private boolean executed;

        private RoutedCall(Call delegate, Dispatcher dispatcher, long createdGeneration,
                boolean requireTor) {
            this.delegate = delegate;
            this.dispatcher = dispatcher;
            this.createdGeneration = createdGeneration;
            this.requireTor = requireTor;
        }

        private void begin() throws IOException {
            synchronized (lock) {
                if (executed) {
                    throw new IllegalStateException("Already executed");
                }
                executed = true;
                checkCurrent();
                activeCalls.add(this);
            }
        }

        private void checkCurrent() throws IOException {
            synchronized (lock) {
                if (!initialized) {
                    throw new IOException("App networking is not initialized.");
                }
                if (createdGeneration != generation || (requireTor && !torEnabled)) {
                    throw new IOException("The network route changed. Start a new request.");
                }
                if (delegate.isCanceled()) {
                    throw new IOException("Canceled");
                }
            }
        }

        private void finished() {
            synchronized (lock) {
                activeCalls.remove(this);
            }
        }

        @Override
        public Request request() {
            return delegate.request();
        }

        @Override
        public Response execute() throws IOException {
            begin();
            try {
                return trackResponse(delegate.execute());
            } catch (IOException | RuntimeException failure) {
                finished();
                throw failure;
            }
        }

        @Override
        public void enqueue(Callback callback) {
            Objects.requireNonNull(callback, "callback");
            try {
                begin();
            } catch (IOException failure) {
                dispatcher.executorService().execute(() -> callback.onFailure(this, failure));
                return;
            }
            try {
                delegate.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException failure) {
                        finished();
                        callback.onFailure(RoutedCall.this, failure);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        final Response tracked;
                        try {
                            tracked = trackResponse(response);
                        } catch (IOException failure) {
                            finished();
                            callback.onFailure(RoutedCall.this, failure);
                            return;
                        }
                        try {
                            callback.onResponse(RoutedCall.this, tracked);
                        } catch (IOException | RuntimeException failure) {
                            tracked.close();
                            throw failure;
                        }
                    }
                });
            } catch (RuntimeException failure) {
                finished();
                throw failure;
            }
        }

        private Response trackResponse(Response response) throws IOException {
            try {
                checkCurrent();
            } catch (IOException stale) {
                response.close();
                throw stale;
            }
            ResponseBody body = response.body();
            if (body == null) {
                finished();
                return response;
            }
            ResponseBody trackedBody = new ResponseBody() {
                private final BufferedSource source = Okio.buffer(new ForwardingSource(
                        body.source()) {
                    @Override
                    public long read(Buffer sink, long byteCount) throws IOException {
                        try {
                            checkCurrent();
                            long count = super.read(sink, byteCount);
                            checkCurrent();
                            if (count == -1L) {
                                finished();
                            }
                            return count;
                        } catch (IOException | RuntimeException failure) {
                            finished();
                            throw failure;
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            finished();
                        }
                    }
                });

                @Override
                public MediaType contentType() {
                    return body.contentType();
                }

                @Override
                public long contentLength() {
                    return body.contentLength();
                }

                @Override
                public BufferedSource source() {
                    return source;
                }
            };
            return response.newBuilder().body(trackedBody).build();
        }

        @Override
        public void cancel() {
            delegate.cancel();
            finished();
        }

        @Override
        public boolean isExecuted() {
            synchronized (lock) {
                return executed;
            }
        }

        @Override
        public boolean isCanceled() {
            synchronized (lock) {
                return delegate.isCanceled() || createdGeneration != generation;
            }
        }

        @Override
        public Timeout timeout() {
            return delegate.timeout();
        }

        @Override
        public void addEventListener(EventListener listener) {
            delegate.addEventListener(listener);
        }

        @Override
        public <T> T tag(KClass<T> type) {
            return delegate.tag(type);
        }

        @Override
        public <T> T tag(Class<? extends T> type) {
            return delegate.tag(type);
        }

        @Override
        public <T> T tag(KClass<T> type, Function0<? extends T> compute) {
            return delegate.tag(type, compute);
        }

        @Override
        public <T> T tag(Class<T> type, Function0<? extends T> compute) {
            return delegate.tag(type, compute);
        }

        @Override
        public Call clone() {
            return new RoutedCall(delegate.clone(), dispatcher, createdGeneration, requireTor);
        }
    }
}
