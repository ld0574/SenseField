package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class BundledSpeechCatalogTest {
    @Test public void allDirectionsAndLiveFixedMessagesHaveRecordings() {
        assertEquals(24, BundledSpeechCatalog.fixed().size());
        for (int direction = 0; direction <= 8; direction++) {
            assertTrue(BundledSpeechCatalog.isFixed(NearZoneRouting.speech(direction)));
            assertTrue(BundledSpeechCatalog.isFixed(NearZoneRouting.twoWordSpeech(direction)));
        }
        assertTrue(BundledSpeechCatalog.isFixed(CueRouting.unlocatedMinimapEnemySpeech()));
        assertTrue(BundledSpeechCatalog.isFixed("截屏恢复失败，请重新授权"));
        assertFalse(BundledSpeechCatalog.isFixed("第3行第4列向右"));
    }

    @Test public void guideInventoryCoversEveryEnabledOutputAndWordingCombination() {
        Set<String> recorded = new HashSet<>(BundledSpeechCatalog.guide());
        for (int near = 0; near < 8; near++) for (int appearance = 0; appearance < 8; appearance++)
            for (int player = 0; player < 8; player++) for (int danger = 0; danger < 8; danger++)
                for (int mode = 0; mode < 8; mode++) {
                    ReminderGuide.Outputs outputs = new ReminderGuide.Outputs(near, appearance,
                            player, danger, (mode & 1) != 0, (mode & 2) != 0);
                    for (ReminderGuide.Step step : ReminderGuideSections.sentenceSteps(
                            ReminderGuideCatalog.fullGuide(outputs, (mode & 4) != 0))) {
                        if (!step.sample) assertTrue("Missing recording: " + step.text, recorded.contains(step.text));
                    }
                }
    }

    @Test public void stableTextHashesAndBoundedRatesPreventMismatchedRecordings() {
        assertEquals("fef304a69096b2c3e87df752dcf6b13980ac4f2d58482d3d901884b09657ba85",
                BundledSpeechCatalog.id("左上"));
        assertNotEquals(BundledSpeechCatalog.id("左上"), BundledSpeechCatalog.id("右上"));
        assertEquals(80, BundledSpeechCatalog.rate(Integer.MIN_VALUE));
        assertEquals(240, BundledSpeechCatalog.rate(Integer.MAX_VALUE));
        assertEquals(180, BundledSpeechCatalog.rate(176));
        assertEquals("game_male", BundledSpeechCatalog.DEFAULT_VOICE);
        assertEquals("game_male", BundledSpeechCatalog.voice(null));
        assertEquals("game_male", BundledSpeechCatalog.voice("unknown"));
        assertEquals("game_female", BundledSpeechCatalog.voice("xiaoxiao"));
        assertEquals("game_female", BundledSpeechCatalog.voice("game_female"));
        assertEquals("game_male", BundledSpeechCatalog.voice("yunxi"));
        assertEquals("game_male", BundledSpeechCatalog.voice("game_male"));
    }
}
