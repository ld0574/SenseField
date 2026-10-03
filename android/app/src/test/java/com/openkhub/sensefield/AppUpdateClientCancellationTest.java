package com.openkhub.sensefield;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AppUpdateClientCancellationTest {
    @Test
    public void cancelAfterHeadersInterruptsResponseBodyRead() throws Exception {
        BlockingBody body = new BlockingBody();
        FakeCall call = new FakeCall(body);
        AppUpdateClient.ActiveCall activeCall = new AppUpdateClient.ActiveCall();
        Response response = AppUpdateClient.executeAndRetainCall(activeCall, call);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> result = reader.submit(() -> response.body().byteStream().read());
            assertTrue("response body read did not start", body.awaitReadStart());

            activeCall.cancel();

            assertEquals(Integer.valueOf(-1), result.get(2, TimeUnit.SECONDS));
            assertTrue("OkHttp call was not canceled", call.isCanceled());
        } finally {
            activeCall.clear(call);
            response.close();
            reader.shutdownNow();
        }
    }

    private static final class FakeCall implements Call {
        private final Request request = new Request.Builder()
                .url("https://updates.example.test/app.apk").build();
        private final BlockingBody body;
        private volatile boolean executed;
        private volatile boolean canceled;

        FakeCall(BlockingBody body) {
            this.body = body;
        }

        @Override public Request request() {
            return request;
        }

        @Override public Response execute() {
            executed = true;
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body).build();
        }

        @Override public void enqueue(Callback responseCallback) {
            throw new UnsupportedOperationException();
        }

        @Override public void cancel() {
            canceled = true;
            body.cancel();
        }

        @Override public boolean isExecuted() {
            return executed;
        }

        @Override public boolean isCanceled() {
            return canceled;
        }

        @Override public Timeout timeout() {
            return Timeout.NONE;
        }

        @Override public Call clone() {
            return new FakeCall(body);
        }

    }

    private static final class BlockingBody extends ResponseBody {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final BufferedSource source = Okio.buffer(new Source() {
            @Override public long read(Buffer sink, long byteCount) throws IOException {
                readStarted.countDown();
                try {
                    if (!cancelled.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting for fake transport cancellation.");
                    }
                    return -1;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", interrupted);
                }
            }

            @Override public Timeout timeout() {
                return Timeout.NONE;
            }

            @Override public void close() {
                cancelled.countDown();
            }
        });

        @Override public MediaType contentType() {
            return null;
        }

        @Override public long contentLength() {
            return -1;
        }

        @Override public BufferedSource source() {
            return source;
        }

        boolean awaitReadStart() throws InterruptedException {
            return readStarted.await(2, TimeUnit.SECONDS);
        }

        void cancel() {
            cancelled.countDown();
        }
    }
}
