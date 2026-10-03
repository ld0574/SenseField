package com.openkhub.sensefield;

import android.util.Base64;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
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
    interface Listener {
        void audioMessage(JSONObject message);
        void audioUnavailable(String reason);
        void visualResult(long requestId, JSONObject result);
        void visualFailure(long requestId, String code);
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
        JSONObject body = json("session_id", session.sessionId, "generation", frame.generation,
                "turn_id", turn, "frame_id", String.valueOf(frame.frameId), "question", question,
                "image_base64", Base64.encodeToString(jpeg, Base64.NO_WRAP),
                "frame_age_ms", Math.max(0, now - frame.capturedAtMs), "proactive", proactive);
        Call call = http.newCall(new Request.Builder().url(settings.endpoint + "/v1/visual")
                .header("Authorization", "Bearer " + settings.token)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"))).build());
        synchronized (this) {
            if (closed) { listener.visualFailure(requestId, "closed"); return; }
            if (visual != null) { listener.visualFailure(requestId, "busy"); return; }
            visual = call;
        }
        call.enqueue(new Callback() {
            private void release() { synchronized (AssistantGatewayClient.this) { if (visual == call) visual = null; } }
            @Override public void onFailure(Call ignored, IOException error) {
                release(); listener.visualFailure(requestId, call.isCanceled() ? "cancelled" : "network_unavailable");
            }
            @Override public void onResponse(Call ignored, Response response) {
                String failure = null; JSONObject result = null;
                try (Response owned = response) {
                    if (owned.body() == null || owned.body().contentLength() > 32_768) failure = "invalid_response";
                    else {
                        String text = readBounded(owned.body().source(), 32_768);
                        {
                            JSONObject parsed = new JSONObject(text);
                            if (owned.isSuccessful()) result = parsed;
                            else {
                                JSONObject error = parsed.optJSONObject("error");
                                failure = owned.code() == 429 ? "rate_limited"
                                        : error == null ? parsed.optString("code", "service_unavailable")
                                        : error.optString("code", "service_unavailable");
                            }
                        }
                    }
                } catch (Exception ignoredError) { failure = "invalid_response"; }
                release();
                if (result != null) listener.visualResult(requestId, result);
                else listener.visualFailure(requestId, failure == null ? "invalid_response" : failure);
            }
        });
    }
    synchronized void cancelVisual() { if (visual != null) { visual.cancel(); visual = null; } }
    static String readBounded(BufferedSource source, int maximumBytes) throws IOException {
        source.request((long) maximumBytes + 1);
        if (source.getBuffer().size() > maximumBytes) throw new IOException("Assistant response too large");
        return source.readUtf8();
    }
    @Override public synchronized void close() {
        closed = true; open = false;
        if (socket != null) { socket.cancel(); socket = null; }
        if (visual != null) { visual.cancel(); visual = null; }
        http.dispatcher().cancelAll(); audioHttp.dispatcher().cancelAll();
        http.connectionPool().evictAll(); audioHttp.connectionPool().evictAll();
        http.dispatcher().executorService().shutdown(); audioHttp.dispatcher().executorService().shutdown();
    }
    static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) object.put(String.valueOf(pairs[i]), pairs[i + 1]); }
        catch (Exception impossible) { throw new IllegalArgumentException("Invalid assistant metadata", impossible); }
        return object;
    }
}
