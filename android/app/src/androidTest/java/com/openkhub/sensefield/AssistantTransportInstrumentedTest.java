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
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Explicit live synthetic transport test. Runtime token and test CA are never source assets. */
@RunWith(AndroidJUnit4.class)
public class AssistantTransportInstrumentedTest {
    private Context context;
    private SharedPreferences preferences;
    private AssistantSettings settings;
    private AssistantGatewayClient client;

    @Before public void configuration() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File config = new File(context.getFilesDir(), "assistant-transport-test.json");
        assumeTrue("Live synthetic transport fixture was not configured", config.isFile());
        JSONObject values = new JSONObject(new String(Files.readAllBytes(config.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
        preferences = context.getSharedPreferences("assistant_transport_test", Context.MODE_PRIVATE);
        preferences.edit().clear().putString(AssistantSettings.ENDPOINT, values.getString("endpoint"))
                .putString(AssistantSettings.TOKEN, values.getString("token"))
                .putBoolean(AssistantSettings.VOICE, true).putBoolean(AssistantSettings.VISION, true)
                .putBoolean(AssistantSettings.AUDIO_CONSENT, true)
                .putBoolean(AssistantSettings.IMAGE_CONSENT, true).commit();
        settings = new AssistantSettings(preferences);
        assertTrue(settings.enabled());
    }

    @After public void cleanup() {
        if (client != null) client.close();
        if (preferences != null) preferences.edit().clear().commit();
    }

    private static class Callbacks implements AssistantGatewayClient.Listener {
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch finalTranscript = new CountDownLatch(1);
        final CountDownLatch reset = new CountDownLatch(1);
        final CountDownLatch visual = new CountDownLatch(1);
        final AtomicReference<JSONObject> audioResult = new AtomicReference<>();
        final AtomicReference<JSONObject> visualResult = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();
        @Override public void audioMessage(JSONObject message) {
            if ("ready".equals(message.optString("status"))) ready.countDown();
            if ("reset".equals(message.optString("reason"))) reset.countDown();
            if ("final".equals(message.optString("type"))) {
                audioResult.set(message); finalTranscript.countDown();
            }
        }
        @Override public void audioUnavailable(String reason) {
            failure.compareAndSet(null, reason); ready.countDown(); finalTranscript.countDown();
        }
        @Override public void visualResult(long id, JSONObject result) {
            visualResult.set(result); visual.countDown();
        }
        @Override public void visualFailure(long id, String code) {
            failure.set(code); visual.countDown();
        }
    }

    @Test public void realHttpsReadsSyntheticMenuAndPreservesFrameOwnership() throws Exception {
        Callbacks callbacks = new Callbacks();
        client = new AssistantGatewayClient(settings, callbacks);
        AssistantSession session = new AssistantSession("android-synthetic-visual");
        String turn = session.newTurn();
        Bitmap bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.rgb(16, 24, 32));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.WHITE); paint.setTextSize(32);
        canvas.drawText("SYNTHETIC HUD TEST", 40, 90, paint);
        ByteBuffer rgba = ByteBuffer.allocateDirect(640 * 360 * 4);
        int[] pixels = new int[640 * 360]; bitmap.getPixels(pixels, 0, 640, 0, 0, 640, 360);
        bitmap.recycle();
        for (int pixel : pixels) rgba.put((byte) (pixel >> 16)).put((byte) (pixel >> 8))
                .put((byte) pixel).put((byte) 255);
        rgba.rewind(); long now = SystemClock.elapsedRealtime();
        FrameSnapshot frame = FrameSnapshot.copy(rgba, 640, 360, 2560, 1280,
                42, now, session.generation(), 1, null);
        assertNotNull(frame); byte[] jpeg = frame.jpeg(); assertNotNull(jpeg);
        client.visual(1, session, turn, frame, jpeg, "请读出画面中清晰的大字", false, now);
        assertTrue("Visual callback timed out", callbacks.visual.await(12, TimeUnit.SECONDS));
        assertNull(callbacks.failure.get());
        JSONObject result = callbacks.visualResult.get(); assertNotNull(result);
        assertEquals(session.sessionId, result.getString("session_id"));
        assertEquals(session.generation(), result.getLong("generation"));
        assertEquals(turn, result.getString("turn_id"));
        assertEquals("42", result.getString("frame_id"));
        assertEquals("ui_text", result.getString("kind"));
        assertFalse(result.getBoolean("uncertain"));
        assertTrue(result.getString("answer").contains("SYNTHETIC HUD TEST"));
    }

    @Test public void realWssRecognizesSyntheticChineseAndAcknowledgesReset() throws Exception {
        File source = new File(context.getFilesDir(), "asr-test.pcm");
        assumeTrue("Synthetic PCM fixture was not configured", source.isFile());
        byte[] pcm = Files.readAllBytes(source.toPath());
        assertTrue(pcm.length > 0 && pcm.length <= 16000 * 2 * 15);
        Callbacks callbacks = new Callbacks(); client = new AssistantGatewayClient(settings, callbacks);
        client.connectAudio("android-synthetic-asr", 1);
        assertTrue("Audio readiness timed out", callbacks.ready.await(8, TimeUnit.SECONDS));
        assertNull(callbacks.failure.get());
        assertTrue(client.sendJson(AssistantGatewayClient.json("type", "speech_start",
                "turn_id", "synthetic-turn", "generation", 1)));
        for (int offset = 0; offset < pcm.length; offset += 19200) {
            int length = Math.min(19200, pcm.length - offset);
            SystemClock.sleep(length * 1000L / 32000);
            assertTrue(client.sendPcm(java.util.Arrays.copyOfRange(pcm, offset, offset + length)));
        }
        assertTrue(client.sendJson(AssistantGatewayClient.json("type", "speech_end",
                "turn_id", "synthetic-turn", "generation", 1)));
        assertTrue("Final transcript timed out", callbacks.finalTranscript.await(15, TimeUnit.SECONDS));
        assertNull(callbacks.failure.get());
        JSONObject result = callbacks.audioResult.get(); assertNotNull(result);
        assertEquals("android-synthetic-asr", result.getString("session_id"));
        assertEquals(1, result.getLong("generation"));
        assertEquals("synthetic-turn", result.getString("turn_id"));
        String text = result.getString("text");
        assertTrue(text.contains("当前比分") || text.contains("请读出"));
        assertTrue(result.getLong("finalization_ms") >= 0);
        client.resetAudio(2);
        assertTrue("Reset acknowledgement timed out", callbacks.reset.await(5, TimeUnit.SECONDS));
    }
}
