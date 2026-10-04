package app.plyvanta.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Request;
import okhttp3.Response;

@UnstableApi
@RunWith(AndroidJUnit4.class)
public final class AppNetworkInstrumentedTest {
    private static final String UNRESOLVABLE_HOST = "plyvanta-tor-routing.invalid";
    private static final String PROXY_BODY = "through-test-socks";
    private static final long TIMEOUT_SECONDS = 10L;

    private Context context;
    private NetworkPreferenceSnapshot originalPreferences;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalPreferences = new NetworkPreferenceSnapshot(context);
    }

    @After
    public void tearDown() {
        if (originalPreferences != null) {
            originalPreferences.close();
        }
    }

    @Test
    public void everyProfilePassesUnresolvedHostnameToSocksWithoutLocalDns() throws Exception {
        try (SocksFixture proxy = new SocksFixture(false, true)) {
            AppNetwork.setTorEnabled(true, proxy.port());
            AtomicInteger localDnsRequests = new AtomicInteger();
            for (AppNetwork.Profile profile : AppNetwork.Profile.values()) {
                Request request = new Request.Builder()
                        .url("http://" + UNRESOLVABLE_HOST + ":12345/" + profile.name())
                        .build();
                Call call = AppNetwork.calls(profile).newCall(request);
                call.addEventListener(new EventListener() {
                    @Override
                    public void dnsStart(Call ignored, String domainName) {
                        localDnsRequests.incrementAndGet();
                    }
                });
                if (profile == AppNetwork.Profile.OFFLINE) {
                    // The download allowlist rejects this fixture URL after SOCKS connects.
                    // Its CONNECT address still proves the download client used the proxy.
                    expectIoFailure(call);
                } else {
                    try (Response response = call.execute()) {
                        assertEquals(profile.name(), 200, response.code());
                        assertNotNull(response.body());
                        assertEquals(profile.name(), PROXY_BODY, response.body().string());
                    }
                }
                SocksDestination destination = proxy.nextDestination();
                assertEquals(profile.name() + " must ask SOCKS to resolve the hostname",
                        3, destination.addressType);
                assertEquals(profile.name(), UNRESOLVABLE_HOST, destination.host);
                assertEquals(profile.name(), 12345, destination.port);
                proxy.assertHealthy();
            }
            assertEquals("Tor mode must not invoke local OkHttp DNS",
                    0, localDnsRequests.get());
        }
    }

    @Test
    public void unreachableProxyNeverFallsBackToDirectConnection() throws Exception {
        try (DirectConnectionSentinel direct = new DirectConnectionSentinel()) {
            int stoppedProxyPort = unusedLoopbackPort();
            AppNetwork.setTorEnabled(true, stoppedProxyPort);
            Request request = new Request.Builder()
                    .url("http://127.0.0.1:" + direct.port() + "/must-not-be-reached")
                    .build();
            for (AppNetwork.Profile profile : AppNetwork.Profile.values()) {
                expectIoFailure(AppNetwork.calls(profile).newCall(request));
            }
            direct.assertNoConnections();
        }
    }

    @Test
    public void callCreatedBeforeTorIsEnabledCannotExecuteUsingOldDirectRoute() throws Exception {
        try (DirectConnectionSentinel direct = new DirectConnectionSentinel();
                SocksFixture proxy = new SocksFixture(false)) {
            AppNetwork.setTorEnabled(false, AppNetwork.DEFAULT_TOR_PORT);
            Request request = new Request.Builder()
                    .url("http://127.0.0.1:" + direct.port() + "/stale-direct-call")
                    .build();
            List<Call> staleCalls = new ArrayList<>();
            for (AppNetwork.Profile profile : AppNetwork.Profile.values()) {
                staleCalls.add(AppNetwork.calls(profile).newCall(request));
            }
            AppNetwork.setTorEnabled(true, proxy.port());
            for (Call call : staleCalls) {
                expectIoFailure(call);
            }
            direct.assertNoConnections();
            assertEquals("A stale Call must not be transparently retried via either route",
                    0, proxy.destinationCount());
        }
    }

    @Test
    public void changingTorPortCancelsResponseBodyThatAlreadyReturnedFromExecute() throws Exception {
        try (SocksFixture proxy = new SocksFixture(true)) {
            AppNetwork.setTorEnabled(true, proxy.port());
            Request request = new Request.Builder()
                    .url("http://" + UNRESOLVABLE_HOST + "/unfinished-body")
                    .build();
            try (Response response = AppNetwork.calls(AppNetwork.Profile.EXTRACTOR)
                    .newCall(request).execute()) {
                assertNotNull(response.body());
                assertEquals('x', response.body().byteStream().read());
                assertStreamReadCancelled(
                        () -> response.body().byteStream().read(),
                        () -> AppNetwork.setTorEnabled(true, unusedLoopbackPort())
                );
            }
            proxy.assertHealthy();
        }
    }

    @Test
    public void changingTorModeCancelsBlockedMedia3BodyRead() throws Exception {
        try (SocksFixture proxy = new SocksFixture(true)) {
            AppNetwork.setTorEnabled(true, proxy.port());
            OkHttpDataSource source = new OkHttpDataSource.Factory(
                    AppNetwork.calls(AppNetwork.Profile.PLAYBACK)
            ).createDataSource();
            try {
                source.open(new DataSpec(Uri.parse(
                        "http://" + UNRESOLVABLE_HOST + "/unfinished-media"
                )));
                byte[] firstByte = new byte[1];
                assertEquals(1, source.read(firstByte, 0, 1));
                assertEquals('x', firstByte[0]);
                assertStreamReadCancelled(
                        () -> source.read(new byte[1], 0, 1),
                        () -> AppNetwork.setTorEnabled(false, AppNetwork.DEFAULT_TOR_PORT)
                );
            } finally {
                source.close();
            }
            proxy.assertHealthy();
        }
    }

    @Test
    public void torModeAndPortAreCommittedToNetworkPreferences() throws Exception {
        int port = unusedLoopbackPort();
        SharedPreferences preferences = context.getSharedPreferences(
                NetworkPreferenceSnapshot.PREFERENCES_NAME, Context.MODE_PRIVATE
        );
        AppNetwork.setTorEnabled(true, port);
        assertTrue(AppNetwork.isTorEnabled());
        assertEquals(port, AppNetwork.torPort());
        assertTrue(preferences.getBoolean("tor_enabled", false));
        assertEquals(port, preferences.getInt("tor_socks_port", -1));

        AppNetwork.setTorEnabled(false, port);
        assertFalse(AppNetwork.isTorEnabled());
        assertFalse(preferences.getBoolean("tor_enabled", true));
        assertEquals("The chosen Orbot port must remain available when Tor is disabled",
                port, preferences.getInt("tor_socks_port", -1));
    }

    private static void expectIoFailure(Call call) throws Exception {
        try (Response ignored = call.execute()) {
            fail("Networking succeeded while the selected route was unavailable or revoked");
        } catch (IOException expected) {
            // This route must fail closed; the listening direct endpoint is checked separately.
        }
    }

    private static int unusedLoopbackPort() throws IOException {
        try (ServerSocket server = loopbackServer()) {
            return server.getLocalPort();
        }
    }

    private static ServerSocket loopbackServer() throws IOException {
        return new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    }

    private interface IoRead {
        int read() throws IOException;
    }

    private interface ChangeRoute {
        void change() throws IOException;
    }

    private static void assertStreamReadCancelled(IoRead read, ChangeRoute change)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<IOException> result = executor.submit(() -> {
                started.countDown();
                try {
                    int unexpected = read.read();
                    throw new AssertionError("Revoked response returned " + unexpected);
                } catch (IOException expected) {
                    return expected;
                }
            });
            assertTrue("The body reader did not start",
                    started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFalse("Fixture must leave the read blocked until the route changes",
                    result.isDone());
            change.change();
            assertNotNull("Changing route must interrupt an already-open response body",
                    result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class SocksDestination {
        final int addressType;
        final String host;
        final int port;

        SocksDestination(int addressType, String host, int port) {
            this.addressType = addressType;
            this.host = host;
            this.port = port;
        }
    }

    /** Minimal SOCKS5 endpoint that records the CONNECT address and serves local HTTP. */
    private static final class SocksFixture implements AutoCloseable {
        private final ServerSocket server = loopbackServer();
        private final boolean streaming;
        private final boolean allowCloseBeforeHttp;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final BlockingQueue<SocksDestination> destinations = new LinkedBlockingQueue<>();
        private final List<Socket> acceptedSockets = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger destinationCount = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch releaseBodies = new CountDownLatch(1);
        private volatile boolean closed;

        SocksFixture(boolean streaming) throws IOException {
            this(streaming, false);
        }

        SocksFixture(boolean streaming, boolean allowCloseBeforeHttp) throws IOException {
            this.streaming = streaming;
            this.allowCloseBeforeHttp = allowCloseBeforeHttp;
            workers.execute(() -> {
                while (!closed) {
                    try {
                        Socket socket = server.accept();
                        acceptedSockets.add(socket);
                        workers.execute(() -> serve(socket));
                    } catch (SocketException stopped) {
                        if (!closed) {
                            failure.compareAndSet(null, stopped);
                        }
                    } catch (IOException error) {
                        failure.compareAndSet(null, error);
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        int destinationCount() {
            return destinationCount.get();
        }

        SocksDestination nextDestination() throws Exception {
            SocksDestination destination = destinations.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("SOCKS never received a hostname; local DNS may have been used",
                    destination);
            return destination;
        }

        void assertHealthy() {
            Throwable error = failure.get();
            if (error != null) {
                throw new AssertionError("SOCKS fixture failed", error);
            }
        }

        private void serve(Socket socket) {
            try (Socket connection = socket) {
                connection.setSoTimeout((int) TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                InputStream input = connection.getInputStream();
                OutputStream output = connection.getOutputStream();
                require(input, 5, "SOCKS greeting version");
                int methodCount = readByte(input);
                boolean offersNoAuthentication = false;
                for (int index = 0; index < methodCount; index++) {
                    offersNoAuthentication |= readByte(input) == 0;
                }
                if (!offersNoAuthentication) {
                    throw new IOException("Client did not offer unauthenticated local SOCKS");
                }
                output.write(new byte[]{5, 0});
                output.flush();

                require(input, 5, "SOCKS CONNECT version");
                require(input, 1, "SOCKS CONNECT command");
                require(input, 0, "SOCKS reserved field");
                int addressType = readByte(input);
                String host;
                if (addressType == 3) {
                    host = new String(readBytes(input, readByte(input)), StandardCharsets.US_ASCII);
                } else if (addressType == 1) {
                    host = InetAddress.getByAddress(readBytes(input, 4)).getHostAddress();
                } else if (addressType == 4) {
                    host = InetAddress.getByAddress(readBytes(input, 16)).getHostAddress();
                } else {
                    throw new IOException("Unknown SOCKS address type " + addressType);
                }
                int destinationPort = (readByte(input) << 8) | readByte(input);
                destinationCount.incrementAndGet();
                destinations.add(new SocksDestination(addressType, host, destinationPort));
                output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                output.flush();
                readHttpHeaders(input);

                byte[] body = PROXY_BODY.getBytes(StandardCharsets.US_ASCII);
                int contentLength = streaming ? 1_048_576 : body.length;
                output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + contentLength
                        + "\r\nContent-Type: application/octet-stream\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(streaming ? new byte[]{'x'} : body);
                output.flush();
                if (streaming) {
                    releaseBodies.await();
                }
            } catch (EOFException closedByClient) {
                if (!closed && !allowCloseBeforeHttp) {
                    failure.compareAndSet(null, closedByClient);
                }
            } catch (Exception error) {
                if (!closed) {
                    failure.compareAndSet(null, error);
                }
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            releaseBodies.countDown();
            server.close();
            synchronized (acceptedSockets) {
                for (Socket socket : acceptedSockets) {
                    socket.close();
                }
            }
            workers.shutdownNow();
        }
    }

    /** Counts connections before TLS or HTTP can obscure an accidental direct fallback. */
    private static final class DirectConnectionSentinel implements AutoCloseable {
        private final ServerSocket server = loopbackServer();
        private final AtomicInteger connections = new AtomicInteger();
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private volatile boolean closed;

        DirectConnectionSentinel() throws IOException {
            server.setSoTimeout(200);
            worker.execute(() -> {
                while (!closed) {
                    try (Socket ignored = server.accept()) {
                        connections.incrementAndGet();
                    } catch (SocketTimeoutException timeout) {
                        // Keep checking until the test completes.
                    } catch (IOException stopped) {
                        if (!closed) {
                            throw new AssertionError("Direct sentinel failed", stopped);
                        }
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        void assertNoConnections() throws InterruptedException {
            // Allow the accept worker to account for a connection that just arrived.
            Thread.sleep(300L);
            assertEquals("A request bypassed the selected Tor route", 0, connections.get());
        }

        @Override
        public void close() throws IOException {
            closed = true;
            server.close();
            worker.shutdownNow();
        }
    }

    private static void require(InputStream input, int expected, String label) throws IOException {
        int actual = readByte(input);
        if (actual != expected) {
            throw new IOException(label + " was " + actual + ", expected " + expected);
        }
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Client closed a SOCKS handshake early");
        }
        return value;
    }

    private static byte[] readBytes(InputStream input, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(buffer, offset, length - offset);
            if (count < 0) {
                throw new EOFException("Client closed a SOCKS address early");
            }
            offset += count;
        }
        return buffer;
    }

    private static void readHttpHeaders(InputStream input) throws IOException {
        int terminator = 0;
        byte[] end = new byte[]{'\r', '\n', '\r', '\n'};
        for (int count = 0; count < 65_536; count++) {
            int value = readByte(input);
            if (value == end[terminator]) {
                terminator++;
                if (terminator == end.length) {
                    return;
                }
            } else {
                terminator = value == '\r' ? 1 : 0;
            }
        }
        throw new IOException("HTTP request exceeded fixture header limit");
    }
}
