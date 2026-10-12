package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Local Android UI/vibrator checks; no microphone, capture, game or remote API. */
@RunWith(AndroidJUnit4.class)
public final class NearZonePresentationInstrumentedTest {
    private Map<String, ?> saved;
    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }
    private static SharedPreferences prefs() { return GameProfile.settings(context()); }

    @Before public void localOnly() {
        saved = prefs().getAll();
        CueSettings.applyPreset(prefs(), CueSettings.PRESET_STANDARD);
        prefs().edit().putBoolean(AssistantSettings.ENABLED, false)
                .putBoolean(AssistantSettings.VOICE, false)
                .putBoolean(AssistantSettings.VISION, false)
                .putBoolean(AssistantSettings.PROACTIVE, false)
                .putBoolean(PresentationAudioPolicy.PREF_DISTANCE_HAPTIC, false)
                .putString(HapticPolicy.PREF_STRENGTH, HapticPolicy.STRENGTH_SYSTEM)
                .putString(HapticPolicy.PREF_MODE, HapticPolicy.MODE_DIRECTIONAL).commit();
    }

    @After @SuppressWarnings("unchecked") public void restorePreferences() {
        SharedPreferences.Editor edit = prefs().edit().clear();
        for (Map.Entry<String, ?> item : saved.entrySet()) {
            Object value = item.getValue();
            if (value instanceof Boolean) edit.putBoolean(item.getKey(), (Boolean) value);
            else if (value instanceof Integer) edit.putInt(item.getKey(), (Integer) value);
            else if (value instanceof Long) edit.putLong(item.getKey(), (Long) value);
            else if (value instanceof Float) edit.putFloat(item.getKey(), (Float) value);
            else if (value instanceof String) edit.putString(item.getKey(), (String) value);
            else if (value instanceof Set) edit.putStringSet(item.getKey(), (Set<String>) value);
        }
        edit.commit();
    }

    @Test public void liveDirectionRequestsReachTheAndroidVibratorWithFinalParameters() {
        CuePlayer player = CuePlayer.tonePreview(context());
        List<String> audit = new ArrayList<>();
        player.setAudioAuditListener(audit::add);
        try {
            int[] sectors = {5, 1, 3, 7, 0};
            String[] timings = {"[0,35,45,80]", "[0,80,45,35]", "[0,35]",
                    "[0,35,45,35]", "[0,100,140,100]"};
            for (int index = 0; index < sectors.length; index++) {
                long now = SystemClock.elapsedRealtime();
                CueRequest request = NearZoneCuePolicy.request("local", "direction-" + index, 1,
                        relation(sectors[index]), null, now, CueRequest.CHANNEL_HAPTIC, false);
                assertTrue("Android vibrator accepted the request", player.vibrate(request));
                String last = audit.get(audit.size() - 1);
                assertTrue(last, last.contains("timingsMs=" + timings[index]));
                assertTrue(last, last.contains("cueId=direction-" + index));
                assertTrue(last, last.contains("usage=ACCESSIBILITY"));
                assertTrue(last, last.contains("hapticCode=" + request.hapticCode));
                player.cancelHaptics();
            }
        } finally { player.close(); }
    }

    @Test public void pageSavesTheOptInAndPreviewDirectionWithoutPlaybackOnEntry() {
        prefs().edit().putString(HapticPolicy.PREF_MODE, HapticPolicy.MODE_ORIGINAL).commit();
        try (ActivityScenario<HapticSettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context(), HapticSettingsActivity.class))) {
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertNull(field(activity, "previewPlayer"));
                RadioButton mode = (RadioButton) findTag(root, HapticPolicy.MODE_DIRECTIONAL);
                assertNotNull(mode);
                mode.performClick();
                assertTrue(mode.isChecked());
                assertEquals(HapticPolicy.MODE_DIRECTIONAL,
                        prefs().getString(HapticPolicy.PREF_MODE, ""));
                RadioButton right = (RadioButton) findTag(root, "haptic-preview-sector:1");
                assertNotNull(right);
                right.performClick();
                assertTrue(right.isChecked());
                assertFalse(((RadioButton) findTag(root, "haptic-preview-sector:5")).isChecked());
                assertEquals(1, field(activity, "previewSector"));
                assertNull(field(activity, "previewPlayer"));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(((RadioButton) findTag(activity.getWindow().getDecorView(),
                        HapticPolicy.MODE_DIRECTIONAL)).isChecked());
                assertNull(field(activity, "previewPlayer"));
            });
        }
    }

    @Test public void neutralAndDirectionPagesFitTheActualWindow() throws Exception {
        prefs().edit().putString(HapticPolicy.PREF_MODE, HapticPolicy.MODE_ORIGINAL).commit();
        UiQualityInstrumentedTest.capture(new Intent(context(), HapticSettingsActivity.class),
                "compliance-haptics-original");
        prefs().edit().putString(HapticPolicy.PREF_MODE, HapticPolicy.MODE_DIRECTIONAL).commit();
        UiQualityInstrumentedTest.capture(new Intent(context(), HapticSettingsActivity.class),
                "compliance-haptics-directional");
        UiQualityInstrumentedTest.capture(new Intent(context(), ReminderGuideActivity.class),
                "compliance-directional-samples");
    }

    private static Object field(Object instance, String name) {
        try {
            Field value = instance.getClass().getDeclaredField(name);
            value.setAccessible(true);
            return value.get(instance);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static View findTag(View root, String tag) {
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findTag(group.getChildAt(index), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static NativeFrameResult.Relation relation(int sector) {
        int[] packet = new int[NativeFrameResult.VERSION_2_HEADER_SIZE];
        packet[0] = NativeFrameResult.MAGIC;
        packet[1] = NativeFrameResult.VERSION_2;
        packet[2] = NativeFrameResult.VERSION_2_HEADER_SIZE;
        packet[3] = NativeFrameResult.VERSIONED_RECORD_STRIDE;
        packet[16] = NearZoneRouting.STATE_OCCUPIED;
        packet[17] = NearZoneRouting.EVENT_NEAR_ENTER;
        packet[18] = sector;
        packet[20] = 80;
        packet[21] = 1;
        packet[23] = 1;
        return NativeFrameResult.parse(packet).relation;
    }
}
