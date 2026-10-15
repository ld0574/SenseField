package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public final class ReminderGuideCatalogTest {
    @Test public void individualReplayContainsOnlyTheSelectedSoundAndKeepsItsStereoSide() {
        List<ReminderGuideCatalog.Item> items = catalog(CueRequest.CHANNEL_TONE, false, false);
        ReminderGuideCatalog.Item left = ReminderGuideCatalog.find(items, "left_tone");
        assertNotNull(left);
        assertEquals(1, left.samples.size());
        assertTrue(left.samples.get(0).sample);
        CueRequest request = left.samples.get(0).request("left", 100);
        assertEquals(CueRequest.CHANNEL_TONE, request.requestedChannels);
        assertEquals(-1f, request.pan, 0f);
        assertNull(request.speech);
        assertNull(ReminderGuideCatalog.find(items, "near_haptic"));
        assertNull(ReminderGuideCatalog.find(items, "near_speech_4"));
    }

    @Test public void speechCatalogHasOneDirectionalExampleAndKeepsUnknownDirectionAsVoice() {
        List<ReminderGuideCatalog.Item> items = catalog(CueRequest.CHANNEL_SPEECH, false, false);
        ReminderGuideCatalog.Item directional = ReminderGuideCatalog.find(items, "near_speech_4");
        assertNotNull(directional);
        assertEquals("左上方位语音", directional.title);
        assertEquals("左上有敌人", directional.samples.get(0).text);
        assertEquals(ReminderGuideCatalog.Section.DIRECTION, ReminderGuideCatalog.section(directional));

        long listedDirections = items.stream()
                .filter(item -> item.id.matches("near_speech_[1-8]"))
                .count();
        assertEquals(1, listedDirections);

        ReminderGuideCatalog.Item unknown = ReminderGuideCatalog.find(items, "near_speech_unknown");
        assertNotNull(unknown);
        assertEquals("方向不明确时的语音", unknown.title);
        assertEquals(NearZoneRouting.speech(0), unknown.samples.get(0).text);
        assertEquals(ReminderGuideCatalog.Section.VOICE, ReminderGuideCatalog.section(unknown));
        assertNull(ReminderGuideCatalog.find(catalog(CueRequest.CHANNEL_TONE, false, false),
                "near_speech_unknown"));
    }

    @Test public void removedDirectionalIdsResolveOnlyToTheRepresentativeWhenEnabled() {
        List<ReminderGuideCatalog.Item> items = catalog(CueRequest.CHANNEL_SPEECH, false, false);
        ReminderGuideCatalog.Item representative = ReminderGuideCatalog.find(items, "near_speech_4");
        for (String oldId : new String[]{"near_speech_1", "near_speech_2", "near_speech_3",
                "near_speech_5", "near_speech_6", "near_speech_7", "near_speech_8"}) {
            assertSame(representative, ReminderGuideCatalog.find(items, oldId));
        }
        assertNull(ReminderGuideCatalog.find(items, "near_speech_9"));
        assertNull(ReminderGuideCatalog.find(catalog(0, false, false), "near_speech_2"));
    }

    @Test public void compactLabelsUseTheDescriptiveItemTitles() {
        int channels = CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC;
        ReminderGuide.Outputs all = new ReminderGuide.Outputs(
                channels, channels, channels, channels, true, true);
        for (ReminderGuideCatalog.Item item : ReminderGuideCatalog.build(all, false, true)) {
            assertEquals(item.title, ReminderGuideCatalog.compactLabel(item));
        }
        assertEquals("附近敌人短音", ReminderGuideCatalog.compactLabel(
                ReminderGuideCatalog.find(catalog(CueRequest.CHANNEL_TONE, false, false), "near_tone")));
        assertEquals("新敌方头像语音", ReminderGuideCatalog.compactLabel(
                ReminderGuideCatalog.find(ReminderGuideCatalog.build(all, false, true), "portrait_speech")));
    }

    @Test public void twoWordSettingChangesExamplesAndFullGuideWithoutChangingLiveTtl() {
        ReminderGuideCatalog.Item item = ReminderGuideCatalog.find(catalog(2, true, false), "near_speech_4");
        assertEquals("左上", item.samples.get(0).request("speech", 0).speech);
        assertTrue(item.explanation.contains("“左上”"));
        ReminderGuideCatalog.Item regular = ReminderGuideCatalog.find(catalog(2, false, false), "near_speech_4");
        assertEquals("左上有敌人", regular.samples.get(0).request("speech", 0).speech);
        ReminderGuideCatalog.Item unknown = ReminderGuideCatalog.find(catalog(2, true, false), "near_speech_unknown");
        assertEquals(NearZoneRouting.twoWordSpeech(0), unknown.samples.get(0).request("unknown", 0).speech);
        List<ReminderGuide.Step> full = ReminderGuideCatalog.fullGuide(outputs(2), true);
        assertTrue(full.stream().filter(step -> step.sample).allMatch(step -> !"左上有敌人".equals(step.text)));
        assertEquals(1200, NearZoneRouting.NEAR_TTL_MS);
    }

    @Test public void enabledExperimentalHapticsHaveIndependentRangeExamples() {
        List<ReminderGuideCatalog.Item> items = catalog(4, false, true);
        CueRequest close = ReminderGuideCatalog.find(items, "near_haptic_close").samples.get(0).request("close", 0);
        CueRequest far = ReminderGuideCatalog.find(items, "near_haptic_far").samples.get(0).request("far", 0);
        assertEquals(CueRequest.Category.NEAR_ZONE, close.category);
        assertTrue(close.distance < far.distance);
        assertEquals(close.urgency, far.urgency, 0f);
        assertNull(ReminderGuideCatalog.find(catalog(4, false, false), "near_haptic_far"));
    }

    @Test public void catalogHasStableUniqueIdsAndOnlyEnabledOutputs() {
        List<ReminderGuideCatalog.Item> items = catalog(7, false, false);
        HashSet<String> ids = new HashSet<>();
        for (ReminderGuideCatalog.Item item : items) {
            assertTrue(ids.add(item.id));
            assertFalse(item.samples.isEmpty());
            assertTrue(item.samples.stream().allMatch(step -> step.sample));
        }
        assertNull(ReminderGuideCatalog.find(items, "portrait_tone"));
        assertNull(ReminderGuideCatalog.find(items, "danger_speech"));
        assertTrue(catalog(0, false, false).isEmpty());
    }

    @Test public void directionSamplesUseTheSameLiveCodesAndSelectedWaveforms() {
        List<ReminderGuideCatalog.Item> items = ReminderGuideCatalog.build(outputs(4),
                false, false, HapticPolicy.MODE_DIRECTIONAL);
        String[] ids = {"left", "right", "up", "down"};
        int[] sectors = {5, 1, 3, 7};
        for (int index = 0; index < ids.length; index++) {
            CueRequest sample = ReminderGuideCatalog.find(items, "near_haptic_" + ids[index])
                    .samples.get(0).request("sample", 100);
            assertEquals(NearZoneRouting.hapticDirection(sectors[index]), sample.hapticCode);
            assertEquals(CueRequest.Category.NEAR_ZONE, sample.category);
            assertEquals(CueRequest.CHANNEL_HAPTIC, sample.requestedChannels);
            assertNull(sample.speech);
            HapticPolicy.Pattern pattern = HapticPolicy.create(sample, false, false,
                    HapticPolicy.MODE_DIRECTIONAL, HapticPolicy.STRENGTH_SYSTEM);
            assertEquals(index == 2 ? 2 : 4, pattern.timingsMs.length);
        }
        assertNull(ReminderGuideCatalog.find(catalog(4, false, false), "near_haptic_left"));
        assertTrue(ReminderGuideCatalog.build(outputs(0), false, false,
                HapticPolicy.MODE_DIRECTIONAL).isEmpty());
    }

    @Test public void directionModeFullGuideDoesNotPlayTheOldNoDirectionClaim() {
        List<ReminderGuide.Step> full = ReminderGuideCatalog.fullGuide(outputs(4), false,
                HapticPolicy.MODE_DIRECTIONAL);
        assertTrue(full.stream().noneMatch(step -> step.text != null
                && step.text.contains("震动不区分左右方向")));
        assertTrue(ReminderGuideCatalog.fullGuide(outputs(4), false).stream()
                .anyMatch(step -> step.text != null && step.text.contains("震动不区分左右方向")));
    }

    private static ReminderGuide.Outputs outputs(int near) {
        return new ReminderGuide.Outputs(near, 0, 0, 0, false, false);
    }
    private static List<ReminderGuideCatalog.Item> catalog(int near, boolean twoWord, boolean distance) {
        return ReminderGuideCatalog.build(outputs(near), twoWord, distance);
    }
}
