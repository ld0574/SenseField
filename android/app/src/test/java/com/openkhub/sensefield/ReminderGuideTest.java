package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class ReminderGuideTest {
    @Test public void standardExplainsAndDemonstratesTheActualNearOutputs() {
        List<ReminderGuide.Step> steps = build(7, 0, 0, 0, false, true);
        String text = explanation(steps);
        assertTrue(text.contains("连续两声短音"));
        assertTrue(text.contains("以你的位置为中心"));
        assertTrue(text.contains("方向按地图，不随镜头转动"));
        assertTrue(text.contains("震动不区分左右方向"));
        assertFalse(text.contains("暂时无法定位"));
        assertEquals(3, samples(steps, CueRequest.CHANNEL_TONE));
        assertEquals(1, samples(steps, CueRequest.CHANNEL_SPEECH));
        assertEquals(1, samples(steps, CueRequest.CHANNEL_HAPTIC));
        assertFalse(text.contains("小地图出现了新敌方头像"));
    }

    @Test public void compactDoesNotTeachDisabledSpeechOrVibration() {
        List<ReminderGuide.Step> steps = build(CueRequest.CHANNEL_TONE, 0, 0, 0, false, false);
        assertEquals(3, samples(steps, CueRequest.CHANNEL_TONE));
        assertEquals(0, samples(steps, CueRequest.CHANNEL_SPEECH));
        assertEquals(0, samples(steps, CueRequest.CHANNEL_HAPTIC));
        assertTrue(explanation(steps).contains("当前关闭了方位语音"));
        assertFalse(explanation(steps).contains("手机连续震动两次"));
        assertFalse(explanation(steps).contains("听到“左上有敌人”"));
    }

    @Test public void speechOnlyDoesNotTeachOrPlayShortSounds() {
        List<ReminderGuide.Step> steps = build(CueRequest.CHANNEL_SPEECH, 0, 0, 0, false, false);
        assertEquals(0, samples(steps, CueRequest.CHANNEL_TONE));
        assertEquals(0, samples(steps, CueRequest.CHANNEL_HAPTIC));
        assertEquals(1, samples(steps, CueRequest.CHANNEL_SPEECH));
        assertFalse(explanation(steps).contains("偏左的短音"));
    }

    @Test public void stereoExamplesUseLiveNearToneAndOppositeChannelGains() {
        List<ReminderGuide.Step> steps = build(CueRequest.CHANNEL_TONE, 0, 0, 0, false, false);
        ReminderGuide.Step left = steps.stream().filter(step -> step.sample && step.pan == -1f)
                .findFirst().get();
        ReminderGuide.Step right = steps.stream().filter(step -> step.sample && step.pan == 1f)
                .findFirst().get();
        float[] leftGains = NearZoneRouting.stereoGains(left.request("left", 0).pan, 1f);
        float[] rightGains = NearZoneRouting.stereoGains(right.request("right", 0).pan, 1f);
        assertTrue(leftGains[0] > leftGains[1]);
        assertTrue(rightGains[1] > rightGains[0]);
        assertEquals(NearZoneRouting.TONE_NEAR, left.tone);
        assertEquals(NearZoneRouting.TONE_NEAR, right.tone);
        assertTrue(explanation(steps).contains("单声道"));
        assertFalse(explanation(steps).contains("短音表示敌人在上方"));
    }

    @Test public void baselineFallbackTeachesNewPortraitWithoutInventingProximity() {
        List<ReminderGuide.Step> steps = build(0, CueRequest.CHANNEL_TONE, 0, 0, false, true);
        assertTrue(explanation(steps).contains("它没有距离含义"));
        assertTrue(explanation(steps).contains("当前设置没有附近敌人"));
        assertFalse(explanation(steps).contains("以你的位置为中心"));
        ReminderGuide.Step sample = steps.stream().filter(step -> step.sample).findFirst().get();
        assertEquals(2, sample.tone);
        assertEquals(CueRequest.Category.SYSTEM, sample.request("sample", 10).category);
    }

    @Test public void optionalMessagesAreOnlyTaughtWhenTheirOutputsAreActive() {
        String none = explanation(build(0, 0, 0, 0, false, false));
        assertFalse(none.contains("你已阵亡"));
        assertFalse(none.contains("危险信号"));
        assertFalse(none.contains("主画面"));
        assertFalse(none.contains("截屏授权已结束"));
        String enabled = explanation(build(0, CueRequest.CHANNEL_SPEECH,
                CueRequest.CHANNEL_SPEECH, CueRequest.CHANNEL_SPEECH, true, true));
        assertTrue(enabled.contains("你已阵亡"));
        assertTrue(enabled.contains("你已复活"));
        assertTrue(enabled.contains(CueRouting.unlocatedMinimapEnemySpeech()));
        assertTrue(enabled.contains("危险信号"));
        assertTrue(enabled.contains("截屏恢复失败，请重新授权"));
    }

    @Test public void samplesUseLiveWordingAndTheLiveNearHapticCategory() {
        for (ReminderGuide.Step step : build(7, 0, 0, 0, false, true)) {
            CueRequest request = step.request("example", 100);
            assertEquals("reminder-guide", request.sessionId);
            if (!step.sample) continue;
            assertEquals(CueRequest.Category.NEAR_ZONE, request.category);
            assertEquals(ReminderGuide.SAMPLE_KIND, request.kind);
            if (step.channel == CueRequest.CHANNEL_SPEECH)
                assertEquals(NearZoneRouting.speech(4), request.speech);
            assertEquals(100, request.createdAtMs);
            assertEquals(2100, request.expiresAtMs);
        }
    }

    @Test public void longerNarrationDoesNotExtendTheLiveSpeechWatchdog() {
        ReminderGuide.Step narration = build(7, 0, 0, 0, false, true).get(0);
        assertEquals(30_000, CuePlayer.speechTimeoutMs(narration.request("intro", 0)));
        CueRequest near = new CueRequest("live", "live:1", "near:1", "NEAR_ENTER",
                CueRequest.Category.NEAR_ZONE, 80, 0, 1200,
                CueRequest.CHANNEL_SPEECH, 7, 0, 0, "左上有敌人");
        assertEquals(4000, CuePlayer.speechTimeoutMs(near));
        CueRequest mislabeled = new CueRequest("live", "live:2", "near:2",
                ReminderGuide.NARRATION_KIND, CueRequest.Category.NEAR_ZONE,
                80, 0, 1200, CueRequest.CHANNEL_SPEECH, 7, 0, 0, "左上有敌人");
        assertEquals(4000, CuePlayer.speechTimeoutMs(mislabeled));
        assertEquals(1200, NearZoneRouting.NEAR_TTL_MS);
    }

    private static List<ReminderGuide.Step> build(int near, int appearance, int player,
            int danger, boolean peripheral, boolean system) {
        return ReminderGuide.build(new ReminderGuide.Outputs(near, appearance, player,
                danger, peripheral, system));
    }

    private static String explanation(List<ReminderGuide.Step> steps) {
        StringBuilder result = new StringBuilder();
        for (ReminderGuide.Step step : steps) if (!step.sample) result.append(step.text);
        return result.toString();
    }

    private static long samples(List<ReminderGuide.Step> steps, int channel) {
        return steps.stream().filter(step -> step.sample && step.channel == channel).count();
    }
}
