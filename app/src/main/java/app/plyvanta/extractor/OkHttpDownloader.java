package app.plyvanta.extractor;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import app.plyvanta.network.AppNetwork;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * Small, stateless downloader adapter used by NewPipe Extractor.
 */
public final class OkHttpDownloader extends Downloader {
    public static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) "
                    + "Gecko/20100101 Firefox/140.0";

    private final Call.Factory client;

    public OkHttpDownloader() {
        this(AppNetwork.calls(AppNetwork.Profile.EXTRACTOR));
    }

    public OkHttpDownloader(Call.Factory client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(request.url());
        boolean hasUserAgent = false;

        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase("User-Agent")) {
                hasUserAgent = true;
            }
            for (String value : entry.getValue()) {
                builder.addHeader(entry.getKey(), value);
            }
        }
        if (!hasUserAgent) {
            builder.header("User-Agent", DESKTOP_USER_AGENT);
        }

        byte[] data = request.dataToSend();
        String method = request.httpMethod();
        if ("GET".equalsIgnoreCase(method)) {
            builder.get();
        } else if ("HEAD".equalsIgnoreCase(method)) {
            builder.head();
        } else {
            MediaType mediaType = null;
            String contentType = builder.build().header("Content-Type");
            if (contentType != null) {
                mediaType = MediaType.parse(contentType);
            }
            RequestBody body = data == null
                    ? RequestBody.create(new byte[0], mediaType)
                    : RequestBody.create(data, mediaType);
            builder.method(method, body);
        }

        Call call = client.newCall(builder.build());
        CompletableFuture<Response> responseFuture = new CompletableFuture<>();
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call failedCall, IOException exception) {
                responseFuture.completeExceptionally(exception);
            }

            @Override
            public void onResponse(Call completedCall, okhttp3.Response response) {
                Response mappedResponse;
                try (okhttp3.Response closeableResponse = response) {
                    ResponseBody responseBody = closeableResponse.body();
                    String body = responseBody == null ? "" : responseBody.string();
                    mappedResponse = new Response(
                            closeableResponse.code(),
                            closeableResponse.message(),
                            closeableResponse.headers().toMultimap(),
                            body,
                            closeableResponse.request().url().toString()
                    );
                } catch (Throwable exception) {
                    responseFuture.completeExceptionally(exception);
                    return;
                }
                responseFuture.complete(mappedResponse);
            }
        });

        try {
            return responseFuture.get();
        } catch (InterruptedException exception) {
            call.cancel();
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted =
                    new InterruptedIOException("Interrupted while waiting for HTTP response");
            interrupted.initCause(exception);
            throw interrupted;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IOException("HTTP request failed", cause);
        }
    }
}
