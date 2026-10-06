package com.openkhub.sensefield;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import okio.BufferedSource;

/** Bounded TLS transport. Never contains a model provider key. */
final class AssistantGatewayClient implements AutoCloseable {
    private static final ExecutorService TRANSPORT_CLEANUP = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SenseFieldTransportClose");
        thread.setDaemon(true);
        return thread;
    });
    interface Listener {
        void audioMessage(JSONObject message);
        void audioUnavailable(String reason);
        void visualResult(long requestId, JSONObject result);
        void visualFailure(long requestId, String code);
        default void visualDiagnostic(long requestId, int httpStatus, String source,
                String providerCode, int upstreamHttpStatus, long retryAfterSeconds) { }
    }
    static final class ContextImage {
        final String frameId;
        final long frameAgeMs;
        final byte[] jpeg;
        ContextImage(String frameId, long frameAgeMs, byte[] jpeg) {
            this.frameId = frameId; this.frameAgeMs = frameAgeMs; this.jpeg = jpeg;
        }
    }
    private static final class EncodedContextImage {
        final ContextImage context; final String base64;
        EncodedContextImage(ContextImage context, String base64) {
            this.context = context; this.base64 = base64;
        }
    }
    private final AssistantSettings settings;
    private final Listener listener;
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();
    private final OkHttpClient audioHttp = new OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build();
    private WebSocket socket;
    private Call visual;
    private boolean open;
    private boolean closed;
    private String audioSession;
    private long audioGeneration;

    AssistantGatewayClient(AssistantSettings settings, Listener listener) {
        this.settings = settings; this.listener = listener;
    }
    synchronized void connectAudio(String sessionId, long generation) {
        if (closed || socket != null) return;
        audioSession = sessionId; audioGeneration = generation;
        Request request = new Request.Builder().url(settings.endpoint.replaceFirst("^https:", "wss:") + "/v1/audio")
                .header("Authorization", "Bearer " + settings.token).build();
        socket = audioHttp.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                synchronized (AssistantGatewayClient.this) {
                    if (closed || socket != ws) { ws.cancel(); return; }
                    open = true;
                    sendJson(json("type", "start", "session_id", audioSession,
                            "generation", audioGeneration, "sample_rate", 16000));
                }
            }
            @Override public void onMessage(WebSocket ws, String text) {
                synchronized (AssistantGatewayClient.this) {
                    if (closed || socket != ws) return;
                }
                if (text.length() > 16_384) { ws.cancel(); failed(ws, "invalid_audio_response"); return; }
                try { listener.audioMessage(new JSONObject(text)); }
                catch (Exception ignored) { ws.cancel(); failed(ws, "invalid_audio_response"); }
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                failed(ws, "audio_disconnected");
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) { failed(ws, "audio_disconnected"); }
        });
    }
    private void failed(WebSocket ws, String reason) {
        synchronized (this) { if (socket != ws) return; socket = null; open = false; }
        if (!closed) listener.audioUnavailable(reason);
    }
    synchronized boolean ready() { return open && socket != null && !closed; }
    synchronized boolean hasAudioConnection() { return socket != null && !closed; }
    synchronized boolean sendJson(JSONObject message) { return ready() && socket.send(message.toString()); }
    synchronized boolean sendPcm(byte[] bytes) {
        if (!ready()) return false;
        if (socket.queueSize() + bytes.length > 64_000) {
            socket.cancel(); socket = null; open = false;
            listener.audioUnavailable("audio_backlog"); return false;
        }
        return socket.send(ByteString.of(bytes));
    }
    synchronized void resetAudio(long generation) {
        audioGeneration = generation;
        sendJson(json("type", "reset", "generation", generation, "turn_id", "reset-g" + generation));
    }
    synchronized void stopAudio() {
        open = false;
        if (socket != null) { WebSocket old = socket; socket = null; old.cancel(); }
    }
    void visual(long requestId, AssistantSession session, String turn, FrameSnapshot frame,
                byte[] jpeg, String question, boolean proactive, long now) {
        visual(requestId, session, turn, frame, jpeg, Collections.emptyList(), question, proactive, now);
    }

    void visual(long requestId, AssistantSession session, String turn, FrameSnapshot frame,
                byte[] jpeg, List<ContextImage> contextFrames, String question, boolean proactive, long now) {
        String primaryBase64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
        Set<String> includedIds = new HashSet<>();
        ArrayList<ContextImage> orderedContext = new ArrayList<>();
        if (contextFrames != null) orderedContext.addAll(contextFrames);
        orderedContext.sort((left, right) -> Long.compare(right.frameAgeMs, left.frameAgeMs));
        ArrayList<EncodedContextImage> encodedImages = new ArrayList<>(FrameHistory.CAPACITY);
        String primaryFrameId = String.valueOf(frame.frameId);
        long initialPrimaryAgeMs = Math.max(0, now - frame.capturedAtMs);
        for (ContextImage context : orderedContext) {
            if (encodedImages.size() >= FrameHistory.CAPACITY) break;
            if (!validContextImage(context, primaryFrameId, initialPrimaryAgeMs)
                    || !includedIds.add(context.frameId)) continue;
            encodedImages.add(new EncodedContextImage(context,
                    Base64.encodeToString(context.jpeg, Base64.NO_WRAP)));
        }

        // Measure ages after Base64 work, close to request serialization, so a frame
        // that crosses the six-second boundary while encoding is omitted.
        long payloadAt = Math.max(now, android.os.SystemClock.elapsedRealtime());
        long primaryAgeMs = Math.max(0, payloadAt - frame.capturedAtMs);
        JSONArray encodedContext = new JSONArray();
        for (EncodedContextImage encoded : encodedImages) {
            ContextImage context = encoded.context;
            long contextAgeMs = context.frameAgeMs + Math.max(0, payloadAt - now);
            if (!validContextImage(context, primaryFrameId, primaryAgeMs, contextAgeMs)) continue;
            encodedContext.put(json("frame_id", context.frameId,
                    "frame_age_ms", contextAgeMs,
                    "image_base64", encoded.base64));
        }
        JSONObject body = json("session_id", session.sessionId, "generation", frame.generation,
                "turn_id", turn, "frame_id", primaryFrameId, "question", question,
                "image_base64", primaryBase64,
                "frame_age_ms", primaryAgeMs, "proactive", proactive);
        if (encodedContext.length() > 0) {
            try { body.put("context_frames", encodedContext); }
            catch (org.json.JSONException invalidContext) {
                listener.visualFailure(requestId, "invalid_request");
                return;
            }
        }
        Request.Builder request = new Request.Builder().url(settings.endpoint + "/v1/visual")
                .header("X-SenseField-Installation", settings.installationId);
        if (!settings.token.isEmpty()) request.header("Authorization", "Bearer " + settings.token);
        Call call = http.newCall(request.post(RequestBody.create(body.toString(),
                MediaType.get("application/json; charset=utf-8"))).build());
        synchronized (this) {
            if (closed) { listener.visualFailure(requestId, "closed"); return; }
            // A newer question can arrive during JPEG/Base64 work, before cancelVisual sees
            // this call. Recheck ownership when installing the call to avoid reviving it.
            if (!session.owns(frame.generation, turn)) {
                listener.visualFailure(requestId, "cancelled"); return;
            }
            if (visual != null) { listener.visualFailure(requestId, "busy"); return; }
            visual = call;
        }
        call.enqueue(new Callback() {
            private void release() { synchronized (AssistantGatewayClient.this) { if (visual == call) visual = null; } }
            @Override public void onFailure(Call ignored, IOException error) {
                final boolean explicitlyCancelled;
                synchronized (AssistantGatewayClient.this) {
                    // OkHttp also marks calls cancelled when callTimeout expires.
                    // Only our detached/replaced request means the user cancelled it.
                    explicitlyCancelled = closed || visual != call;
                    if (visual == call) visual = null;
                }
                listener.visualFailure(requestId, visualFailureCode(explicitlyCancelled, error));
            }
            @Override public void onResponse(Call ignored, Response response) {
                String failure = null; JSONObject result = null;
                String source = "unknown", providerCode = "none";
                int status = response.code(), upstreamStatus = -1;
                long retryAfter = -1;
                try (Response owned = response) {
                    if (owned.body() == null || owned.body().contentLength() > 32_768) failure = "invalid_response";
                    else {
                        String text = readBounded(owned.body().source(), 32_768);
                        {
                            JSONObject parsed = new JSONObject(text);
                            if (owned.isSuccessful()) result = parsed;
                            else {
                                JSONObject error = parsed.optJSONObject("error");
                                if (error != null) {
                                    String candidateSource = error.optString("source", "unknown");
                                    if ("provider".equals(candidateSource) || "cooldown".equals(candidateSource) || "public_quota".equals(candidateSource))
                                        source = candidateSource;
                                    String candidateCode = error.optString("provider_code", "");
                                    if (candidateCode.matches("[0-9]{1,6}")) providerCode = candidateCode;
                                    int candidateStatus = error.optInt("upstream_http_status", -1);
                                    if (candidateStatus >= 100 && candidateStatus <= 599) upstreamStatus = candidateStatus;
                                    retryAfter = Math.min(86_400, Math.max(-1, error.optLong("retry_after_seconds", -1)));
                                }
                                failure = error != null && "public_quota_exceeded".equals(error.optString("code"))
                                        ? "public_quota_exceeded" : owned.code() == 429 ? ("1305".equals(providerCode) ? "provider_busy" : "rate_limited")
                                        : error == null ? parsed.optString("code", "service_unavailable")
                                        : error.optString("code", "service_unavailable");
                            }
                        }
                    }
                } catch (Exception ignoredError) { failure = "invalid_response"; }
                release();
                if (!response.isSuccessful()) listener.visualDiagnostic(requestId, status,
                        source, providerCode, upstreamStatus, retryAfter);
                if (result != null) listener.visualResult(requestId, result);
                else listener.visualFailure(requestId, failure == null ? "invalid_response" : failure);
            }
        });
    }
    static boolean validContextImage(ContextImage image, String primaryFrameId, long primaryAgeMs) {
        return validContextImage(image, primaryFrameId, primaryAgeMs,
                image == null ? -1 : image.frameAgeMs);
    }
    static boolean validContextImage(ContextImage image, String primaryFrameId, long primaryAgeMs,
            long currentFrameAgeMs) {
        if (image == null || image.frameId == null || image.frameId.isEmpty()
                || image.frameId.length() > 128 || !image.frameId.matches("[A-Za-z0-9._:-]+")
                || image.frameId.equals(primaryFrameId) || currentFrameAgeMs <= primaryAgeMs
                || currentFrameAgeMs > FrameHistory.MAX_AGE_MS || image.jpeg == null
                || image.jpeg.length == 0 || image.jpeg.length > 512 * 1024) return false;
        return true;
    }
    synchronized void cancelVisual() { if (visual != null) { visual.cancel(); visual = null; } }
    static String visualFailureCode(boolean explicitlyCancelled, IOException error) {
        if (explicitlyCancelled) return "cancelled";
        return error instanceof java.io.InterruptedIOException ? "timeout" : "network_unavailable";
    }
    static String readBounded(BufferedSource source, int maximumBytes) throws IOException {
        source.request((long) maximumBytes + 1);
        if (source.getBuffer().size() > maximumBytes) throw new IOException("Assistant response too large");
        return source.readUtf8();
    }
    @Override public void close() {
        final WebSocket oldSocket;
        final Call oldVisual;
        synchronized (this) {
            if (closed) return;
            closed = true; open = false;
            oldSocket = socket; socket = null;
            oldVisual = visual; visual = null;
        }
        // TLS socket.close can write close_notify. Gate callbacks immediately,
        // but release network resources away from the caller/UI thread.
        TRANSPORT_CLEANUP.execute(() -> {
            if (oldSocket != null) oldSocket.cancel();
            if (oldVisual != null) oldVisual.cancel();
            closeTransport(http);
            closeTransport(audioHttp);
        });
    }
    private static void closeTransport(OkHttpClient client) {
        try {
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
        } finally {
            client.dispatcher().executorService().shutdown();
        }
    }
    static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) object.put(String.valueOf(pairs[i]), pairs[i + 1]); }
        catch (Exception impossible) { throw new IllegalArgumentException("Invalid assistant metadata", impossible); }
        return object;
    }
}
