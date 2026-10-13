package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

    @Test public void twoWordStyleNamesEightSectorsAndKeepsUnknownsAmbiguous() {
        assertEquals("右侧", NearZoneRouting.twoWordSpeech(1));
        assertEquals("右上", NearZoneRouting.twoWordSpeech(2));
        assertEquals("上方", NearZoneRouting.twoWordSpeech(3));
        assertEquals("左上", NearZoneRouting.twoWordSpeech(4));
        assertEquals("左侧", NearZoneRouting.twoWordSpeech(5));
        assertEquals("左下", NearZoneRouting.twoWordSpeech(6));
        assertEquals("下方", NearZoneRouting.twoWordSpeech(7));
        assertEquals("右下", NearZoneRouting.twoWordSpeech(8));
        assertEquals("附近", NearZoneRouting.twoWordSpeech(0));
        assertEquals("附近", NearZoneRouting.twoWordSpeech(9));
    }

    @Test public void nearRequestsRespectTheSavedSpeechPreferenceInQuietAndDenseCombat() {
        for (boolean dense : new boolean[] {false, true}) {
            NearZoneCombatPolicy.Decision combat = new NearZoneCombatPolicy.Decision(
                    dense ? 3 : 1, 40, 95, dense, false, false, "none");
            for (int sector = -1; sector <= 9; sector++) {
                CueRequest full = NearZoneCuePolicy.request("s", "full", 1,
                        relation(sector, 0, -1), combat, 100, 7, false);
                CueRequest shortCue = NearZoneCuePolicy.request("s", "short", 1,
                        relation(sector, 0, -1), combat, 100, 7, true);
                assertEquals("Disabled short style must remain full during dense=" + dense,
                        NearZoneRouting.speech(sector), full.speech);
                assertEquals(NearZoneRouting.twoWordSpeech(sector), shortCue.speech);
                assertEquals(2, shortCue.speech.codePointCount(0, shortCue.speech.length()));
            }
        }
    }

    @Test public void liveRequestKeepsNativePositionPriorityChannelsAndEpisode() {
        NearZoneCombatPolicy.Decision combat = new NearZoneCombatPolicy.Decision(
                3, 40, 95, true, false, false, "none");
        CueRequest request = NearZoneCuePolicy.request("s", "cue", 7,
                relation(4, -707, 125), combat, 5000, 7, false);
        assertEquals("左上有敌人", request.speech);
        assertEquals(1, request.hapticCode);
        assertEquals(-.707f, request.pan, 1e-6f);
        assertEquals(.125f, request.distance, 1e-6f);
        assertEquals(2f, request.urgency, 0f);
        assertEquals(3, request.freshEnemyCount);
        assertEquals(95, request.priority);
        assertEquals(7, request.requestedChannels);
        assertEquals("near:7:12", request.eventKey);
        assertEquals(6200, request.expiresAtMs);
        assertTrue(request.playbackAllowedAt(6200));
        assertFalse(request.playbackAllowedAt(6201));
    }

    @Test public void unknownAndDiagonalSectorsDoNotInventASideForHaptics() {
        int[] expected = {0, 2, 2, 3, 1, 1, 1, 4, 2, 0};
        for (int sector = 0; sector <= 9; sector++) {
            CueRequest request = NearZoneCuePolicy.request("s", "cue", 1,
                    relation(sector, 0, -1), null, 100, 4, true);
            assertEquals(expected[sector], request.hapticCode);
            assertEquals(NearZoneRouting.twoWordSpeech(sector), request.speech);
            assertTrue(Float.isNaN(request.distance));
        }
        assertEquals(0, NearZoneRouting.hapticDirection(-1));
    }

    @Test public void presentationAuditRecordsTheSavedPreferenceAndActualStyle() {
        CueRequest request = NearZoneCuePolicy.request("s", "cue", 1,
                relation(0, 0, -1), null, 100, 7, true);
        String audit = NearZoneCuePolicy.presentationAudit(request, 0, false, true);
        assertTrue(audit.contains("cueId=cue"));
        assertTrue(audit.contains("policy=saved_speech_style_v2"));
        assertTrue(audit.contains("dense=false nearTwoWordEnabled=true"));
        assertTrue(audit.contains("speechStyle=two_word speechChars=2"));
        assertTrue(audit.contains("hapticCode=0"));
        assertTrue(audit.contains("distanceMilli=unknown"));
        assertFalse(audit.contains("NaN"));

        NearZoneCombatPolicy.Decision combat = new NearZoneCombatPolicy.Decision(
                3, 40, 95, true, false, false, "none");
        CueRequest full = NearZoneCuePolicy.request("s", "full", 1,
                relation(4, -707, 125), combat, 100, 7, false);
        String denseAudit = NearZoneCuePolicy.presentationAudit(full, 4, true, false);
        assertTrue(denseAudit.contains("dense=true nearTwoWordEnabled=false"));
        assertTrue(denseAudit.contains("speechStyle=full speechChars=5"));
    }

    private static NativeFrameResult.Relation relation(int sector, int panMilli, int distanceMilli) {
        int[] packet = new int[NativeFrameResult.VERSION_2_HEADER_SIZE];
        packet[0] = NativeFrameResult.MAGIC;
        packet[1] = NativeFrameResult.VERSION_2;
        packet[2] = NativeFrameResult.VERSION_2_HEADER_SIZE;
        packet[3] = NativeFrameResult.VERSIONED_RECORD_STRIDE;
        packet[16] = NearZoneRouting.STATE_OCCUPIED;
        packet[17] = NearZoneRouting.EVENT_NEAR_ENTER;
        packet[18] = sector;
        packet[19] = panMilli;
        packet[20] = distanceMilli;
        packet[21] = 12;
        packet[23] = 1;
        return NativeFrameResult.parse(packet).relation;
    }

    @Test public void presentationDistanceBinsStayInsideTheCurrentNearZoneScale() {
        assertEquals(0f, NearZoneRouting.presentationDistanceLevel(0f), 1e-6f);
        assertEquals(0.5f, NearZoneRouting.presentationDistanceLevel(0.10f), 1e-6f);
        assertEquals(1f, NearZoneRouting.presentationDistanceLevel(0.20f), 1e-6f);
        assertEquals(1f, NearZoneRouting.presentationDistanceLevel(0.40f), 1e-6f);
        assertEquals(0f, NearZoneRouting.presentationDistanceLevel(Float.NaN), 1e-6f);
    }

    @Test public void spatialCacheMissIsReadOnlyAndPrewarmedStereoBinsAreReused() {
        CuePlayer.SpatialToneCache cache = new CuePlayer.SpatialToneCache();
        assertFalse(cache.isReady());
        assertNull(cache.get(-1f, 0.5f, 1f));
        assertEquals(0, cache.entryCountForTest());

        cache.prewarm();
        assertTrue(cache.isReady());
        assertEquals(108, cache.entryCountForTest());
        short[] left = cache.get(-1f, 0.5f, 1f);
        short[] right = cache.get(1f, 0.5f, 1f);
        assertEquals(left.length, right.length);
        assertSame(left, cache.get(-1f, 0.5f, 1f));
        for (int i = 0; i < left.length; i += 2) {
            assertEquals("swapped stereo samples at frame " + (i / 2), left[i], right[i + 1]);
            assertEquals("swapped stereo samples at frame " + (i / 2), left[i + 1], right[i]);
        }
        assertEquals(48_000 * 240 / 1000 * 2, left.length);
    }

    @Test public void experimentalPresentationOptionsDefaultOffAndHapticsScaleMonotonically() {
        PresentationAudioPolicy defaults = PresentationAudioPolicy.defaults();
        assertFalse(defaults.spatial);
        assertFalse(defaults.distanceHaptic);
        assertFalse(defaults.nearTwoWord);
        assertTrue(PresentationAudioPolicy.distanceHapticAmplitude(0f, 2f)
                > PresentationAudioPolicy.distanceHapticAmplitude(0.20f, 0f));
        assertTrue(PresentationAudioPolicy.distanceHapticDurationMs(0f, 2f)
                > PresentationAudioPolicy.distanceHapticDurationMs(0.20f, 0f));
        assertEquals(110, PresentationAudioPolicy.distanceHapticDurationMs(0f, 2f));
        assertEquals(65, PresentationAudioPolicy.distanceHapticDurationMs(0.20f, 0f));
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
        assertEquals(100, CuePlayer.DEFAULT_TTS_RATE_PERCENT);
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
