package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NearZoneRoutingTest {
    @Test public void speechNamesEightMinimapSectorsFactually() {
        assertEquals("右方有敌人", NearZoneRouting.speech(1));
        assertEquals("上方有敌人", NearZoneRouting.speech(3));
        assertEquals("左上有敌人", NearZoneRouting.speech(4));
        assertEquals("右下有敌人", NearZoneRouting.speech(8));
        assertEquals("附近有敌人", NearZoneRouting.speech(0));
        assertEquals("附近有敌人", NearZoneRouting.speech(9));
        for (int sector = 0; sector <= 8; sector++) {
            String phrase = NearZoneRouting.speech(sector);
            assertFalse(phrase.contains("危险"));
            assertFalse(phrase.contains("安全"));
        }
    }

    @Test public void standardNearCueCarriesSpeechToneAndHapticTogether() {
        assertEquals(CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH,
                NearZoneRouting.nearChannels(false, CueRequest.CHANNEL_TONE
                        | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC));
        assertEquals(CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH
                        | CueRequest.CHANNEL_HAPTIC,
                NearZoneRouting.nearChannels(true, 15));
        assertEquals(CueRequest.CHANNEL_TONE,
                NearZoneRouting.nearChannels(false, CueRequest.CHANNEL_TONE));
        assertEquals(0, NearZoneRouting.nearChannels(false, CueRequest.CHANNEL_VISUAL));
    }

    @Test public void stereoGainsKeepLegacyHardPanAndCentre() {
        float[] left = NearZoneRouting.stereoGains(-1f, 1f);
        assertEquals(1f, left[0], 1e-6f);
        assertEquals(0.12f, left[1], 1e-6f);
        float[] right = NearZoneRouting.stereoGains(1f, 0.5f);
        assertEquals(0.06f, right[0], 1e-6f);
        assertEquals(0.5f, right[1], 1e-6f);
        float[] centre = NearZoneRouting.stereoGains(0f, 1f);
        assertEquals(1f, centre[0], 1e-6f);
        assertEquals(1f, centre[1], 1e-6f);
        float[] diagonal = NearZoneRouting.stereoGains(-0.7071f, 1f);
        assertEquals(1f, diagonal[0], 1e-6f);
        assertTrue(diagonal[1] > 0.12f && diagonal[1] < 1f);
        float[] invalid = NearZoneRouting.stereoGains(Float.NaN, 1f);
        assertEquals(1f, invalid[0], 1e-6f);
        assertEquals(1f, invalid[1], 1e-6f);
    }

    @Test public void detailedHonorsToneSpeechAndHapticTogether() {
        assertEquals(CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH
                        | CueRequest.CHANNEL_HAPTIC,
                NearZoneRouting.nearChannels(true, 15, false));
        assertEquals(CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC,
                NearZoneRouting.nearChannels(true,
                        CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC, false));
        assertEquals(CueRequest.CHANNEL_TONE,
                NearZoneRouting.nearChannels(false, CueRequest.CHANNEL_TONE, false));
    }

    @Test public void channelsAndFarAppearRule() {
        assertEquals(CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH,
                NearZoneRouting.nearChannels(false));
        assertEquals(CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH
                | CueRequest.CHANNEL_HAPTIC, NearZoneRouting.nearChannels(true));
        assertTrue(NearZoneRouting.farAppearAudible(false, true, false));
        assertFalse(NearZoneRouting.farAppearAudible(true, true, false));
        assertTrue(NearZoneRouting.farAppearAudible(true, true, true));
        assertTrue(NearZoneRouting.farAppearAudible(true, false, false));
    }

    @Test public void nearOutputDefaultsMatchAccessibleBaseline() {
        assertEquals(180, CuePlayer.DEFAULT_TTS_RATE_PERCENT);
        assertEquals(100, CuePlayer.NEAR_HAPTIC_ON_MS);
        assertEquals(140, CuePlayer.NEAR_HAPTIC_GAP_MS);
    }

    @Test public void logNamesMatchTheParserVocabulary() {
        assertEquals("OCCUPIED", NearZoneRouting.stateName(3));
        assertEquals("UNAVAILABLE", NearZoneRouting.stateName(-1));
        assertEquals("NEAR_ENTER", NearZoneRouting.eventName(1));
        assertEquals("RADAR_RESUMED", NearZoneRouting.eventName(3));
        assertEquals("rearm_pending", NearZoneRouting.suppressionName(1));
        assertEquals("short_gap", NearZoneRouting.suppressionName(2));
        assertEquals("none", NearZoneRouting.suppressionName(0));
    }

    @Test public void relationSectionBoundsMatchNative() throws Exception {
        GameProfile.RelationData data = GameProfile.relationData(true, 0.2f, 0.25f, 7.5f,
                0.2f, 0.1f, 2, 3000, 2000, 10000, 500, false);
        assertEquals(0.2f, data.enterRadius(), 1e-6f);
        assertEquals(3000, data.ints[1]);
        assertFalse(data.calibrated);
        expectInvalid(false, 0.2f, 0.25f, 2, 2000);
        expectInvalid(true, 0.2f, 0.2f, 2, 2000);
        expectInvalid(true, 0.2f, 0.25f, 0, 2000);
        expectInvalid(true, 0.2f, 0.25f, 2, 100);
        expectInvalid(true, Float.NaN, 0.25f, 2, 2000);
    }

    @Test public void relationNeedsTheLocalPlayerClass() throws Exception {
        GameProfile.RelationData data = GameProfile.relationData(true, 0.2f, 0.25f, 7.5f,
                0.2f, 0.1f, 2, 3000, 2000, 10000, 500, false);
        GameProfile.requireRelationModel(null, new int[] {2});
        GameProfile.requireRelationModel(data, new int[] {2, 6});
        try {
            GameProfile.requireRelationModel(data, new int[] {2});
            throw new AssertionError("Expected enemy-only model to be rejected");
        } catch (org.json.JSONException expected) {
            // Expected.
        }
    }

    private static void expectInvalid(boolean body, float enter, float exit, int confirm,
                                      int shortGap) {
        try {
            GameProfile.relationData(body, enter, exit, 7.5f, 0.2f, 0.1f, confirm, 3000,
                    shortGap, 10000, 500, false);
            throw new AssertionError("Expected invalid minimap_relation to be rejected");
        } catch (org.json.JSONException expected) {
            // Expected.
        }
    }
}
