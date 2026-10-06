package com.openkhub.sensefield;

import android.content.Context;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic PCM only: never opens a microphone, screen capture, or network connection. */
@RunWith(AndroidJUnit4.class)
public class OnDeviceAsrInstrumentedTest {
    @Test public void pinnedLocalRuntimeRecognizesGameQuestionsWithoutAudioTransport() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File cache = new java.io.File(target.getNoBackupFilesDir(), "sensevoice-int8");
        AsrModelStore.Spec pinned = AsrModelStore.Spec.read(target);
        assertTrue("Seed pinned model cache before offline smoke", AsrModelStore.verified(
                new java.io.File(cache, "model.int8.onnx"), pinned.modelBytes, pinned.modelHash));
        assertTrue(AsrModelStore.verified(new java.io.File(cache, "tokens.txt"), pinned.tokensBytes, pinned.tokensHash));
        Context fixtures = InstrumentationRegistry.getInstrumentation().getContext();
        CountDownLatch initialized = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<CountDownLatch> completed = new AtomicReference<>();
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicReference<String> resultTurn = new AtomicReference<>();
        AtomicReference<Long> elapsed = new AtomicReference<>();
        try (OnDeviceAsr asr = new OnDeviceAsr(target, new OnDeviceAsr.Listener() {
            @Override public void ready() { initialized.countDown(); }
            @Override public void result(long generation, String turn, String text, long inferenceMs) {
                if (generation != 7) error.set("generation_mismatch");
                answer.set(text); resultTurn.set(turn); elapsed.set(inferenceMs);
                CountDownLatch waiter = completed.get(); if (waiter != null) waiter.countDown();
            }
            @Override public void unavailable(String reason) {
                error.set("local_runtime_unavailable"); initialized.countDown();
                CountDownLatch waiter = completed.get(); if (waiter != null) waiter.countDown();
            }
            @Override public void dropped(String reason) { error.set(reason); }
        })) {
            asr.start(); assertTrue("model initialization timeout", initialized.await(60, TimeUnit.SECONDS));
            assertNull(error.get()); assertTrue(asr.ready());
            for (String scenario : new String[]{"score", "build", "draft"}) {
                byte[] bytes;
                try (InputStream source = fixtures.getAssets().open("asr-smoke-" + scenario + ".pcm")) {
                    bytes = source.readAllBytes();
                }
                short[] pcm = new short[bytes.length / 2];
                for (int i = 0; i < pcm.length; i++) pcm[i] = (short)((bytes[2*i] & 255) | bytes[2*i+1] << 8);
                CountDownLatch waiter = new CountDownLatch(1); completed.set(waiter);
                answer.set(null); resultTurn.set(null); elapsed.set(null);
                assertTrue(asr.begin(7, "synthetic-" + scenario)); asr.accept(pcm); assertTrue(asr.finish());
                assertTrue("final timeout", waiter.await(20, TimeUnit.SECONDS));
                assertNull(error.get()); assertEquals("synthetic-" + scenario, resultTurn.get());
                String text = answer.get();
                boolean recognized = text != null && (scenario.equals("score") ? text.contains("比分")
                        : scenario.equals("build") ? text.contains("装备") : text.contains("英雄"));
                Bundle metrics = new Bundle(); metrics.putString("scenario", scenario);
                metrics.putLong("local_inference_ms", elapsed.get() == null ? -1 : elapsed.get());
                metrics.putBoolean("expected_keywords", recognized);
                metrics.putInt("text_chars", text == null ? 0 : text.length());
                InstrumentationRegistry.getInstrumentation().sendStatus(2, metrics);
                assertTrue("expected synthetic keywords", recognized);
                assertTrue("recognized question is actionable", AssistantController.isRequest(text));
            }
        }
    }
}
