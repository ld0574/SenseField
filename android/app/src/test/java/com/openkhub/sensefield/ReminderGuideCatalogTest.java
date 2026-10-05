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

    @Test public void twoWordSettingChangesExamplesAndFullGuideWithoutChangingLiveTtl() {
        ReminderGuideCatalog.Item item = ReminderGuideCatalog.find(catalog(2, true, false), "near_speech_4");
        assertEquals("左上", item.samples.get(0).request("speech", 0).speech);
        assertTrue(item.explanation.contains("“左上”"));
        ReminderGuideCatalog.Item regular = ReminderGuideCatalog.find(catalog(2, false, false), "near_speech_4");
        assertEquals("左上有敌人", regular.samples.get(0).request("speech", 0).speech);
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

    private static ReminderGuide.Outputs outputs(int near) {
        return new ReminderGuide.Outputs(near, 0, 0, 0, false, false);
    }
    private static List<ReminderGuideCatalog.Item> catalog(int near, boolean twoWord, boolean distance) {
        return ReminderGuideCatalog.build(outputs(near), twoWord, distance);
    }
}
