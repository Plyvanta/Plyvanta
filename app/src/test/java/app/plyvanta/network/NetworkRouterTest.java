package app.plyvanta.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public final class NetworkRouterTest {
    private static final long WAIT_SECONDS = 3L;

    @Test
    public void requestsCannotRunBeforeSavedPolicyIsRestored() throws Exception {
        NetworkRouter router = new NetworkRouter();
        try (ServerSocket direct = listener()) {
            direct.setSoTimeout(200);
            Call call = router.calls(AppNetwork.Profile.EXTRACTOR).newCall(
                    new Request.Builder().url(url(direct, "/uninitialized")).build());
            IOException failure = assertThrows(IOException.class, call::execute);
            assertTrue(failure.getMessage().contains("not initialized"));
            assertThrows(SocketTimeoutException.class, direct::accept);
        }
    }

    @Test
    public void socksRouteSendsUnresolvedDestinationWithoutLocalDns() throws Exception {
        AtomicReference<String> destination = new AtomicReference<>();
        AtomicReference<Integer> destinationPort = new AtomicReference<>();
        try (TestServer socks = new TestServer(socket -> {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            assertEquals(5, input.read());
            int methods = input.read();
            readExactly(input, methods);
            output.write(new byte[] {5, 0});
            output.flush();
            assertEquals(5, input.read());
            assertEquals(1, input.read());
            assertEquals(0, input.read());
            assertEquals("SOCKS must receive a hostname, not a locally resolved IP", 3,
                    input.read());
            destination.set(new String(readExactly(input, input.read()),
                    StandardCharsets.US_ASCII));
            destinationPort.set((input.read() << 8) | input.read());
            output.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
            output.flush();
            readHeaders(socket);
            writeResponse(socket, "Tor fixture");
        })) {
            NetworkRouter router = new NetworkRouter();
            router.configure(true, socks.port(), () -> {
            });
            try (Response response = router.calls(AppNetwork.Profile.EXTRACTOR).newCall(
                    new Request.Builder()
                            .url("http://plyvanta-cannot-resolve.invalid:8123/video")
                            .build()).execute()) {
                assertEquals("Tor fixture", response.body().string());
            }
            socks.awaitFinished();
            assertEquals("plyvanta-cannot-resolve.invalid", destination.get());
            assertEquals(Integer.valueOf(8123), destinationPort.get());
        }
    }

    @Test
    public void unavailableProxyNeverFallsBackToDirectTarget() throws Exception {
        int closedPort;
        try (ServerSocket reservation = listener()) {
            closedPort = reservation.getLocalPort();
        }
        try (ServerSocket direct = listener()) {
            direct.setSoTimeout(200);
            NetworkRouter router = new NetworkRouter();
            router.configure(true, closedPort, () -> {
            });
            assertThrows(IOException.class, () -> router.calls(AppNetwork.Profile.EXTRACTOR)
                    .newCall(new Request.Builder().url(url(direct, "/must-not-leak")).build())
                    .execute());
            assertThrows(SocketTimeoutException.class, direct::accept);
            assertTrue(router.isTorEnabled());
        }
    }

    @Test
    public void retainedCallsAndClonesCannotUsePreviousRoute() throws Exception {
        NetworkRouter router = new NetworkRouter();
        router.configure(false, AppNetwork.DEFAULT_TOR_PORT, () -> {
        });
        Request request = new Request.Builder().url("http://127.0.0.1:1/stale").build();
        Call retained = router.calls(AppNetwork.Profile.EXTRACTOR).newCall(request);
        Call asynchronous = router.calls(AppNetwork.Profile.EXTRACTOR).newCall(request);
        router.configure(true, AppNetwork.DEFAULT_TOR_PORT, () -> {
        });
        assertThrows(IOException.class, retained::execute);
        assertThrows(IOException.class, retained.clone()::execute);
        assertTrue(retained.isCanceled());

        CountDownLatch callback = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        asynchronous.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException exception) {
                failure.set(exception);
                callback.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) {
                response.close();
                failure.set(new AssertionError("Stale call unexpectedly ran"));
                callback.countDown();
            }
        });
        assertTrue(callback.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(failure.get() instanceof IOException);
    }

    @Test
    public void modeChangeCancelsBodiesAfterExecuteHasReturned() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        try (TestServer direct = new TestServer(socket -> {
            readHeaders(socket);
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Length: 100\r\nConnection: close\r\n\r\na")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            try {
                while (socket.getInputStream().read() != -1) {
                    // A response remains open after execute() leaves OkHttp's dispatcher.
                }
            } catch (IOException reset) {
                // Reset or EOF both prove that the previous route was closed.
            } finally {
                disconnected.countDown();
            }
        })) {
            NetworkRouter router = new NetworkRouter();
            router.configure(false, AppNetwork.DEFAULT_TOR_PORT, () -> {
            });
            Call call = router.calls(AppNetwork.Profile.PLAYBACK).newCall(
                    new Request.Builder().url(direct.url("/stream")).build());
            try (Response response = call.execute()) {
                InputStream body = response.body().byteStream();
                assertEquals('a', body.read());
                long started = System.nanoTime();
                router.configure(true, AppNetwork.DEFAULT_TOR_PORT, () -> {
                });
                assertThrows(IOException.class, body::read);
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000L);
                assertTrue(disconnected.await(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            direct.awaitFinished();
        }
    }

    @Test
    public void failedPreferenceWriteRetainsPreviousModeAndUsableCalls() throws Exception {
        try (TestServer direct = new TestServer(socket -> {
            readHeaders(socket);
            writeResponse(socket, "unchanged route");
        })) {
            NetworkRouter router = new NetworkRouter();
            router.configure(false, AppNetwork.DEFAULT_TOR_PORT, () -> {
            });
            Call beforeSave = router.calls(AppNetwork.Profile.EXTRACTOR).newCall(
                    new Request.Builder().url(direct.url("/save-failure")).build());
            assertThrows(IllegalStateException.class, () -> router.configure(true, 9150, () -> {
                throw new IllegalStateException("Fixture cannot commit settings");
            }));
            assertFalse(router.isTorEnabled());
            assertEquals(AppNetwork.DEFAULT_TOR_PORT, router.torPort());
            try (Response response = beforeSave.execute()) {
                assertEquals("unchanged route", response.body().string());
            }
            direct.awaitFinished();
        }
    }

    @Test
    public void torCheckCannotStartWhenTorIsDisabled() {
        NetworkRouter router = new NetworkRouter();
        router.configure(false, AppNetwork.DEFAULT_TOR_PORT, () -> {
        });
        assertThrows(IOException.class, () -> router.newTorCheckCall(
                new Request.Builder().url("https://check.torproject.org/api/ip").build()));
    }

    @Test
    public void invalidPortsAreRejectedBeforeSavingOrChangingMode() {
        NetworkRouter router = new NetworkRouter();
        router.configure(true, AppNetwork.DEFAULT_TOR_PORT, () -> {
        });
        AtomicBoolean persisted = new AtomicBoolean();
        assertThrows(IllegalArgumentException.class,
                () -> router.configure(false, 0, () -> persisted.set(true)));
        assertThrows(IllegalArgumentException.class,
                () -> router.configure(false, 65536, () -> persisted.set(true)));
        assertFalse(persisted.get());
        assertTrue(router.isTorEnabled());
    }

    private static byte[] readExactly(InputStream input, int count) throws IOException {
        if (count < 0) {
            throw new IOException("Unexpected end of fixture request");
        }
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = input.read(bytes, offset, count - offset);
            if (read == -1) {
                throw new IOException("Unexpected end of fixture request");
            }
            offset += read;
        }
        return bytes;
    }

    private static void readHeaders(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                StandardCharsets.US_ASCII));
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // Consume the fixture request without resolving or contacting its target.
        }
    }

    private static void writeResponse(Socket socket, String response) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    private static ServerSocket listener() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    }

    private static String url(ServerSocket socket, String path) {
        return "http://127.0.0.1:" + socket.getLocalPort() + path;
    }

    private interface ConnectionHandler {
        void handle(Socket socket) throws Exception;
    }

    private static final class TestServer implements AutoCloseable {
        private final ServerSocket server = listener();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean closing = new AtomicBoolean();
        private volatile Socket accepted;

        private TestServer(ConnectionHandler handler) throws IOException {
            Thread worker = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    accepted = socket;
                    socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                    handler.handle(socket);
                } catch (Throwable exception) {
                    if (!closing.get()) {
                        failure.set(exception);
                    }
                } finally {
                    finished.countDown();
                }
            }, "network-router-test-server");
            worker.setDaemon(true);
            worker.start();
        }

        private int port() {
            return server.getLocalPort();
        }

        private String url(String path) {
            return NetworkRouterTest.url(server, path);
        }

        private void awaitFinished() throws InterruptedException {
            assertTrue("Fixture server did not finish", finished.await(WAIT_SECONDS,
                    TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw new AssertionError("Fixture server failed", failure.get());
            }
        }

        @Override
        public void close() throws IOException {
            closing.set(true);
            server.close();
            Socket socket = accepted;
            if (socket != null) {
                socket.close();
            }
        }
    }
}
