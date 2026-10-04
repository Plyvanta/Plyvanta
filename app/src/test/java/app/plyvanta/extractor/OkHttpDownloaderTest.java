package app.plyvanta.extractor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;

public final class OkHttpDownloaderTest {
    private static final long WAIT_SECONDS = 3L;

    @Test
    public void executeMapsCompletedResponseAndDefaultRequestHeaders() throws Exception {
        AtomicReference<List<String>> requestHeaders = new AtomicReference<>();
        try (TestServer server = new TestServer(socket -> {
            requestHeaders.set(readRequestHeaders(socket));
            byte[] body = "mapped response".getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 201 Fixture\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "X-Fixture: first\r\n"
                    + "X-Fixture: second\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(head.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        })) {
            String url = server.url("/mapped?value=1");

            Response response = new OkHttpDownloader(new OkHttpClient()).execute(
                    Request.newBuilder().get(url).build()
            );

            server.awaitFinished();
            assertEquals(201, response.responseCode());
            assertEquals("Fixture", response.responseMessage());
            assertEquals("mapped response", response.responseBody());
            assertEquals(url, response.latestUrl());
            assertTrue(hasHeaderValues(response, "X-Fixture", List.of("first", "second")));

            List<String> headers = requestHeaders.get();
            assertNotNull(headers);
            assertEquals("GET /mapped?value=1 HTTP/1.1", headers.get(0));
            assertTrue(headers.stream().anyMatch(line -> line
                    .toLowerCase(Locale.ROOT)
                    .startsWith("user-agent: " + OkHttpDownloader.DESKTOP_USER_AGENT
                            .toLowerCase(Locale.ROOT))));
        }
    }

    @Test
    public void interruptedExecuteCancelsCallAndReturnsPromptly() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch clientDisconnected = new CountDownLatch(1);
        try (TestServer server = new TestServer(socket -> {
            readRequestHeaders(socket);
            requestReceived.countDown();
            try {
                while (socket.getInputStream().read() != -1) {
                    // Wait for cancellation to close the connection.
                }
            } catch (IOException ignored) {
                // A reset is also a successful cancellation signal.
            } finally {
                clientDisconnected.countDown();
            }
        })) {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicBoolean interruptRestored = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                try {
                    new OkHttpDownloader(new OkHttpClient()).execute(
                            Request.newBuilder().get(server.url("/blocked")).build()
                    );
                    thrown.set(new AssertionError("Blocked request unexpectedly completed"));
                } catch (Throwable exception) {
                    thrown.set(exception);
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                }
            }, "okhttp-downloader-interrupt-test");
            worker.setDaemon(true);
            worker.start();

            assertTrue(requestReceived.await(WAIT_SECONDS, TimeUnit.SECONDS));
            long startedNanos = System.nanoTime();
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedNanos
            );

            assertFalse("Interrupted execute did not return", worker.isAlive());
            assertTrue(thrown.get() instanceof InterruptedIOException);
            assertTrue("Interrupted status was not restored", interruptRestored.get());
            assertTrue("Interrupted execute took " + elapsedMillis + " ms",
                    elapsedMillis < TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertTrue("Cancel did not close the socket",
                    clientDisconnected.await(WAIT_SECONDS, TimeUnit.SECONDS));
            server.awaitFinished();
        }
    }

    @Test
    public void transportFailureIsExposedAsIOException() throws Exception {
        try (TestServer server = new TestServer(socket -> {
            readRequestHeaders(socket);
            // Close without sending a status line.
        })) {
            IOException exception = assertThrows(
                    IOException.class,
                    () -> new OkHttpDownloader(new OkHttpClient()).execute(
                            Request.newBuilder().get(server.url("/closed")).build()
                    )
            );

            assertFalse(exception instanceof InterruptedIOException);
            server.awaitFinished();
        }
    }

    private static boolean hasHeaderValues(
            Response response,
            String expectedName,
            List<String> expectedValues
    ) {
        return response.responseHeaders().entrySet().stream().anyMatch(entry ->
                entry.getKey().equalsIgnoreCase(expectedName)
                        && entry.getValue().equals(expectedValues)
        );
    }

    private static List<String> readRequestHeaders(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(),
                StandardCharsets.US_ASCII
        ));
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    private interface ConnectionHandler {
        void handle(Socket socket) throws Exception;
    }

    private static final class TestServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread serverThread;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean closing = new AtomicBoolean();
        private volatile Socket acceptedSocket;

        private TestServer(ConnectionHandler handler) throws IOException {
            serverSocket = new ServerSocket(
                    0,
                    1,
                    InetAddress.getByName("127.0.0.1")
            );
            serverThread = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    acceptedSocket = socket;
                    handler.handle(socket);
                } catch (Throwable exception) {
                    if (!closing.get()) {
                        failure.set(exception);
                    }
                } finally {
                    try {
                        serverSocket.close();
                    } catch (IOException ignored) {
                        // The test cleanup may already have closed it.
                    }
                    finished.countDown();
                }
            }, "okhttp-downloader-test-server");
            serverThread.setDaemon(true);
            serverThread.start();
        }

        private String url(String path) {
            return "http://127.0.0.1:" + serverSocket.getLocalPort() + path;
        }

        private void awaitFinished() throws Exception {
            assertTrue("Test server did not finish",
                    finished.await(WAIT_SECONDS, TimeUnit.SECONDS));
            Throwable exception = failure.get();
            if (exception instanceof Exception) {
                throw (Exception) exception;
            }
            if (exception instanceof Error) {
                throw (Error) exception;
            }
        }

        @Override
        public void close() throws Exception {
            closing.set(true);
            Socket socket = acceptedSocket;
            if (socket != null) {
                socket.close();
            }
            serverSocket.close();
            serverThread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
    }
}
