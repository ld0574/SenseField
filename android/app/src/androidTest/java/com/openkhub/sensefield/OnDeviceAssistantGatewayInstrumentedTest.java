package com.openkhub.sensefield;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic PCM and pixels only; exercises local SenseVoice through the production HTTPS client. */
@RunWith(AndroidJUnit4.class)
public final class OnDeviceAssistantGatewayInstrumentedTest {
    private static final String CONFIG_NAME = "assistant-local-asr-vision-test.json";
    private static final String SESSION_ID = "android-local-asr-vision-synthetic";

    private Context target;
    private Context fixtures;
    private File configFile;
    private String endpoint;
    private String token;
    private SharedPreferences preferences;
    private AssistantController controller;
    private OnDeviceAsr asr;
    private TestHost host;

    @AfterClass public static void removeFixtureConfig() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Files.deleteIfExists(new File(target.getFilesDir(), CONFIG_NAME).toPath());
    }

    private static final class TestHost implements AssistantController.Host {
        final CountDownLatch questionSeen = new CountDownLatch(1);
        final List<AssistantReply> replies = Collections.synchronizedList(new ArrayList<>());
        final List<String> audits = Collections.synchronizedList(new ArrayList<>());
        volatile String status = "";

        @Override public void speak(AssistantReply reply) {
            replies.add(reply);
        }
        @Override public boolean speechReady() { return true; }
        @Override public void cancelSpeech() { }
        @Override public boolean speaking() { return false; }
        @Override public long lastAlertAtMs() { return -1; }
        @Override public String nearby() { return "合成附近信息"; }
        @Override public void mark() { }
        @Override public void status(String value) { status = value; }
        @Override public void audit(String metadata) {
            audits.add(metadata);
            if (metadata.contains("AssistantInteraction event=QUESTION proactive=false")) {
                questionSeen.countDown();
            }
        }
    }

    @Before public void setup() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fixtures = InstrumentationRegistry.getInstrumentation().getContext();
        configFile = new File(target.getFilesDir(), CONFIG_NAME);
        assumeTrue("Local HTTPS vision fixture is not configured", configFile.isFile());
        JSONObject config = new JSONObject(new String(Files.readAllBytes(configFile.toPath()),
                StandardCharsets.UTF_8));
        endpoint = config.getString("endpoint");
        token = config.getString("token");
        assumeTrue("Fixture endpoint must use emulator HTTPS loopback", endpoint.startsWith("https://10.0.2.2:"));
        assertTrue(AssistantSettings.validEndpoint(endpoint));
        assertTrue(token.length() >= 24 && token.length() <= 256);
        preferences = target.getSharedPreferences("assistant_local_asr_vision_test", Context.MODE_PRIVATE);
    }

    @After public void cleanup() {
        if (controller != null) controller.close();
        if (asr != null) asr.close();
        if (preferences != null) preferences.edit().clear().commit();
    }

    private void createController(boolean vision) {
        preferences.edit().clear()
                .putBoolean(AssistantSettings.VOICE, true)
                .putBoolean(AssistantSettings.VISION, vision)
                .putBoolean(AssistantSettings.PROACTIVE, false)
                .putBoolean(AssistantSettings.AUDIO_CONSENT, true)
                .putBoolean(AssistantSettings.IMAGE_CONSENT, vision)
                .putBoolean(AssistantSettings.CUSTOM_SERVICE, true).putString(AssistantSettings.ENDPOINT, endpoint)
                .putString(AssistantSettings.TOKEN, token)
                .commit();
        AssistantSettings settings = new AssistantSettings(preferences);
        assertTrue(settings.enabled());
        host = new TestHost();
        controller = new AssistantController(target, SESSION_ID, settings, host);
        // Deliberately do not call start(): it creates AudioRecord and the UI overlay.
    }

    @Test public void localQuestionUsesRealHttpsVisualRequestAndLateReplyIsDiscarded() throws Exception {
        createController(true);
        fixtureRequest("POST", "/__fixture__/reset");
        AssistantSession session = session(controller);
        String recognizedQuestion = runScoreFixtureThroughLocalAsr(session);
        assertTrue(host.questionSeen.await(3, TimeUnit.SECONDS));
        assertTrue("known synthetic score audio should be actionable",
                recognizedQuestion.contains("比分") && AssistantController.isRequest(recognizedQuestion));

        // The first reply is held by the local fixture. A newer question cancels its ownership
        // before a second genuine Android HTTPS request is issued.
        SystemClock.sleep(300);
        controller.offerFrame(syntheticRgba(), 640, 360, 640 * 4, 42,
                SystemClock.elapsedRealtime());
        JSONObject firstObserved = awaitEvidence(1, 1, 0, 0, 5000);
        JSONArray firstRequests = firstObserved.getJSONArray("visual_posts");
        JSONObject first = firstRequests.getJSONObject(0);
        assertEquals(recognizedQuestion, first.getString("question"));
        assertEquals(1, first.getInt("image_count"));
        assertTrue(first.getBoolean("authorized"));
        assertFalse(first.getBoolean("proactive"));

        String replacementQuestion = "请帮我选择一位英雄";
        controller.question(replacementQuestion, false);
        SystemClock.sleep(300);
        controller.offerFrame(syntheticRgba(), 640, 360, 640 * 4, 43,
                SystemClock.elapsedRealtime());
        JSONObject afterSelection = awaitEvidence(2, 2, 1, 1, 8000);
        awaitReplies(1, 8_000);
        AssistantReply selected = host.replies.get(0);
        assertTrue(selected.answer.contains("合成选人建议一") && selected.answer.contains("合成选人建议二"));

        String equipmentQuestion = "请推荐一件当前适合的装备";
        askControllerQuestion(equipmentQuestion, 44);
        JSONObject afterEquipment = awaitEvidence(3, 3, 2, 1, 8000);
        awaitReplies(2, 8_000);
        AssistantReply equipped = host.replies.get(1);
        assertTrue(equipped.answer.contains("合成出装建议一") && equipped.answer.contains("合成出装建议二"));

        String strategyQuestion = "请给我当前对战策略建议";
        askControllerQuestion(strategyQuestion, 45);
        JSONObject evidence = awaitEvidence(4, 4, 3, 1, 8000);
        awaitReplies(3, 8_000);
        AssistantReply strategized = host.replies.get(2);
        assertTrue(strategized.answer.contains("合成策略建议一") && strategized.answer.contains("合成策略建议二"));

        JSONArray requests = evidence.getJSONArray("visual_posts");
        assertEquals(4, requests.length());
        assertRequest(requests.getJSONObject(0), recognizedQuestion, 1);
        assertRequest(requests.getJSONObject(1), replacementQuestion, 2);
        assertRequest(requests.getJSONObject(2), equipmentQuestion, 2);
        assertRequest(requests.getJSONObject(3), strategyQuestion, 2);
        assertTrue("new questions must advance generation",
                requests.getJSONObject(1).getLong("generation") > first.getLong("generation")
                        && requests.getJSONObject(2).getLong("generation") > requests.getJSONObject(1).getLong("generation")
                        && requests.getJSONObject(3).getLong("generation") > requests.getJSONObject(2).getLong("generation"));
        assertNotEquals(requests.getJSONObject(0).getString("turn_id"), requests.getJSONObject(1).getString("turn_id"));
        assertNotEquals(requests.getJSONObject(1).getString("turn_id"), requests.getJSONObject(2).getString("turn_id"));
        assertNotEquals(requests.getJSONObject(2).getString("turn_id"), requests.getJSONObject(3).getString("turn_id"));
        assertEquals("No audio WebSocket or HTTP route may receive PCM", 0,
                evidence.getJSONArray("audio_requests").length());
        assertEquals("No cancelled score response may reach the host sink", 3, host.replies.size());
        assertEquals(requests.getJSONObject(1).getLong("generation"), selected.generation);
        assertEquals(requests.getJSONObject(1).getString("turn_id"), selected.turnId);
        assertEquals(requests.getJSONObject(2).getLong("generation"), equipped.generation);
        assertEquals(requests.getJSONObject(3).getLong("generation"), strategized.generation);
        assertReplyMatchesRequest(selected, requests.getJSONObject(1));
        assertReplyMatchesRequest(equipped, requests.getJSONObject(2));
        assertReplyMatchesRequest(strategized, requests.getJSONObject(3));
        assertEquals(1, afterSelection.getInt("provider_cancelled"));
        assertEquals(2, afterEquipment.getInt("provider_completed"));
    }

    @Test public void voiceOnlyLocalAsrDoesNotOpenAudioOrVisualNetworkRoutes() throws Exception {
        createController(false);
        fixtureRequest("POST", "/__fixture__/reset");
        AssistantSession session = session(controller);
        String text = runScoreFixtureThroughLocalAsr(session);
        assertTrue(text.contains("比分") && AssistantController.isRequest(text));
        assertTrue("voice-only question should remain local", awaitReplies(1, 3000));

        JSONObject evidence = fixtureRequest("GET", "/__fixture__/evidence");
        assertEquals(0, evidence.getJSONArray("visual_posts").length());
        assertEquals(0, evidence.getJSONArray("audio_requests").length());
        assertEquals("local", host.replies.get(0).kind);
    }

    private String runScoreFixtureThroughLocalAsr(AssistantSession session) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch result = new CountDownLatch(1);
        AtomicReference<String> recognized = new AtomicReference<>();
        AtomicReference<String> returnedTurn = new AtomicReference<>();
        AtomicReference<Long> returnedGeneration = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        asr = new OnDeviceAsr(target, new OnDeviceAsr.Listener() {
            @Override public void ready() { ready.countDown(); }
            @Override public void result(long generation, String turn, String text, long inferenceMs) {
                recognized.set(text);
                returnedTurn.set(turn);
                returnedGeneration.set(generation);
                controller.onLocalAsrResult(generation, turn, text, inferenceMs);
                result.countDown();
            }
            @Override public void unavailable(String reason) { failure.set("local_asr_unavailable"); ready.countDown(); result.countDown(); }
            @Override public void dropped(String reason) { failure.set(reason); }
        });
        asr.start();
        assertTrue("pinned local SenseVoice model did not initialize", ready.await(60, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertTrue(asr.ready());

        long generation = session.generation();
        String turn = session.newCaptureTurn();
        setAwaitingFinal(controller, true);
        assertTrue(asr.begin(generation, turn));
        byte[] bytes;
        try (InputStream source = fixtures.getAssets().open("asr-smoke-score.pcm")) {
            bytes = readAllBounded(source, 16_000 * 2 * 15);
        }
        assertTrue(bytes.length > 0 && (bytes.length & 1) == 0);
        short[] samples = new short[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) ((bytes[2 * i] & 255) | (bytes[2 * i + 1] << 8));
        }
        asr.accept(samples);
        assertTrue(asr.finish());
        assertTrue("synthetic PCM local ASR result timed out", result.await(30, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertEquals(turn, returnedTurn.get());
        assertEquals(Long.valueOf(generation), returnedGeneration.get());
        assertNotNull(recognized.get());
        return recognized.get().trim();
    }

    private JSONObject awaitEvidence(
            int visualCount, int startedCount, int completedCount, int cancelledCount, long timeoutMs)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        JSONObject current;
        do {
            current = fixtureRequest("GET", "/__fixture__/evidence");
            if (current.getJSONArray("visual_posts").length() >= visualCount
                    && current.getInt("provider_started") >= startedCount
                    && current.getInt("provider_completed") >= completedCount
                    && current.getInt("provider_cancelled") >= cancelledCount) return current;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("fixture did not observe expected visual request count",
                current.getJSONArray("visual_posts").length() >= visualCount);
        assertTrue("fixture did not start expected scripted vision calls",
                current.getInt("provider_started") >= startedCount);
        assertTrue("fixture did not complete expected scripted vision calls",
                current.getInt("provider_completed") >= completedCount);
        assertTrue("fixture did not observe expected scripted cancellation count",
                current.getInt("provider_cancelled") >= cancelledCount);
        return current;
    }

    private JSONObject fixtureRequest(String method, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        int status = connection.getResponseCode();
        InputStream body = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (body == null) {
            connection.disconnect();
            throw new AssertionError("Local fixture request failed with HTTP " + status);
        }
        String text;
        try (InputStream owned = body) {
            text = new String(readAllBounded(owned, 64 * 1024), StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
        if (status < 200 || status >= 300) throw new AssertionError("Local fixture request failed with HTTP " + status);
        return new JSONObject(text);
    }

    private boolean awaitReplies(int count, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (host.replies.size() >= count) return true;
            SystemClock.sleep(25);
        }
        return host.replies.size() >= count;
    }

    private void askControllerQuestion(String question, long frameId) throws Exception {
        controller.question(question, false);
        SystemClock.sleep(300);
        controller.offerFrame(syntheticRgba(), 640, 360, 640 * 4, frameId,
                SystemClock.elapsedRealtime());
    }

    private static void assertRequest(JSONObject request, String question, int imageCount) throws Exception {
        assertEquals("POST", request.getString("method"));
        assertEquals("/v1/visual", request.getString("path"));
        assertEquals(question, request.getString("question"));
        assertEquals(imageCount, request.getInt("image_count"));
        assertTrue(request.getLong("generation") > 0);
        assertFalse(request.getString("turn_id").isEmpty());
        assertTrue(request.getBoolean("authorized"));
        assertFalse(request.getBoolean("proactive"));
    }

    private static void assertReplyMatchesRequest(AssistantReply reply, JSONObject request) throws Exception {
        assertEquals("Dynamic match advice should use the short-freshness HUD category", "hud", reply.kind);
        assertEquals(request.getLong("generation"), reply.generation);
        assertEquals(request.getString("turn_id"), reply.turnId);
        assertEquals(request.getLong("frame_id"), reply.frameId);
    }

    private static byte[] readAllBounded(InputStream source, int maximumBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int count;
        while ((count = source.read(block)) != -1) {
            if (output.size() + count > maximumBytes) throw new AssertionError("Synthetic fixture input exceeded its limit");
            output.write(block, 0, count);
        }
        return output.toByteArray();
    }

    private static ByteBuffer syntheticRgba() {
        int width = 640, height = 360;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(16, 24, 32));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(30);
        canvas.drawText("SYNTHETIC MENU FIXTURE", 32, 84, paint);
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();
        ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
        for (int pixel : pixels) rgba.put((byte) (pixel >> 16))
                .put((byte) (pixel >> 8)).put((byte) pixel).put((byte) 255);
        rgba.rewind();
        return rgba;
    }

    private static AssistantSession session(AssistantController controller) throws Exception {
        java.lang.reflect.Field field = AssistantController.class.getDeclaredField("session");
        field.setAccessible(true);
        return (AssistantSession) field.get(controller);
    }

    private static void setAwaitingFinal(AssistantController controller, boolean value) throws Exception {
        java.lang.reflect.Field field = AssistantController.class.getDeclaredField("awaitingFinal");
        field.setAccessible(true);
        field.setBoolean(controller, value);
    }
}
