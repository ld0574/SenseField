package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Both Android motion settings, rapid reopening, and lifecycle cancellation are exercised. */
@RunWith(AndroidJUnit4.class)
public final class UiMotionInstrumentedTest {
    @Test public void reducedAndEnabledMotionEndAtTheSameGeometryAndCannotHideAReopenedPanel() throws Exception {
        String previous = shell("settings get global animator_duration_scale").trim();
        try {
            for (boolean enabled : new boolean[] {false, true}) {
                shell("settings put global animator_duration_scale " + (enabled ? "1" : "0"));
                long deadline = SystemClock.elapsedRealtime() + 2000;
                while (ValueAnimator.areAnimatorsEnabled() != enabled && SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(20);
                assertEquals(enabled, ValueAnimator.areAnimatorsEnabled());
                Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
                try (ActivityScenario<AlertSettingsActivity> scenario = ActivityScenario.launch(new Intent(context, AlertSettingsActivity.class))) {
                    scenario.onActivity(activity -> {
                        View root = activity.findViewById(android.R.id.content);
                        tagged(root, "setting_help_group_button:events_group").performClick();
                    });
                    SystemClock.sleep(UiMotion.PANEL_DURATION_MS + 100);
                    scenario.onActivity(activity -> {
                        View root = activity.findViewById(android.R.id.content);
                        View panel = tagged(root, SettingHelpPanel.PANEL_VIEW_TAG);
                        assertEquals(1f, panel.getAlpha(), .001f);
                        assertEquals(0f, panel.getTranslationY(), .001f);
                        SettingHelp.close(activity);
                        tagged(root, "setting_help_group_button:channel_group").performClick();
                    });
                    SystemClock.sleep(UiMotion.PANEL_DURATION_MS + 100);
                    scenario.onActivity(activity -> {
                        View panel = tagged(activity.findViewById(android.R.id.content), SettingHelpPanel.PANEL_VIEW_TAG);
                        assertEquals("A canceled close must not hide a new group", View.VISIBLE, panel.getVisibility());
                        assertEquals(1f, panel.getAlpha(), .001f);
                    });
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
                    SystemClock.sleep(UiMotion.PANEL_DURATION_MS + 100);
                    scenario.onActivity(activity -> {
                        View panel = tagged(activity.findViewById(android.R.id.content), SettingHelpPanel.PANEL_VIEW_TAG);
                        assertEquals(View.VISIBLE, panel.getVisibility());
                        assertEquals(0f, panel.getTranslationX(), .001f);
                        assertEquals(0f, panel.getTranslationY(), .001f);
                        SettingHelp.close(activity);
                    });
                    SystemClock.sleep(UiMotion.PANEL_DURATION_MS + 100);
                    scenario.onActivity(activity -> assertFalse(tagged(activity.findViewById(android.R.id.content),
                            SettingHelpPanel.PANEL_VIEW_TAG).isShown()));
                }
            }
        } finally {
            if ("null".equals(previous)) shell("settings delete global animator_duration_scale");
            else shell("settings put global animator_duration_scale " + previous);
        }
    }
    private static String shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
                FileInputStream input = new FileInputStream(fd.getFileDescriptor())) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static View tagged(View root, String tag) {
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = tagged(group.getChildAt(i), tag); if (found != null) return found;
            }
        }
        return null;
    }
}
