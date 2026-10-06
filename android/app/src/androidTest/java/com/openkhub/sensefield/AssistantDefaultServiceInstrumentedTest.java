package com.openkhub.sensefield;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AssistantDefaultServiceInstrumentedTest {
    @Test public void defaultServiceIgnoresLegacyConnectionAndPersistsRandomInstallation() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("public-assistant-settings-fixture", Context.MODE_PRIVATE);
        prefs.edit().clear().putString(AssistantSettings.ENDPOINT, "https://old.example")
                .putString(AssistantSettings.TOKEN, "old-private-test-token-must-not-be-used").commit();
        AssistantSettings first = new AssistantSettings(prefs), second = new AssistantSettings(prefs);
        assertEquals(BuildConfig.ASSISTANT_DEFAULT_ENDPOINT, first.endpoint);
        assertEquals("", first.token); assertTrue(first.configured());
        assertFalse(first.enabled()); assertFalse(first.voice); assertFalse(first.vision);
        assertEquals(first.installationId, second.installationId);
        assertTrue(first.installationId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
        prefs.edit().putBoolean(AssistantSettings.CUSTOM_SERVICE, true).commit();
        assertEquals("https://old.example", new AssistantSettings(prefs).endpoint);
        prefs.edit().clear().commit();
    }
    @Test public void settingsRequireNoAddressOrCodeAndShowResourcePreparation() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        SharedPreferences prefs = GameProfile.settings(context);
        boolean present = prefs.contains(AssistantSettings.VOICE), previous = prefs.getBoolean(AssistantSettings.VOICE, false);
        prefs.edit().putBoolean(AssistantSettings.VOICE, false).commit();
        AssistantSettingsActivity activity = (AssistantSettingsActivity) instrumentation.startActivitySync(
                new Intent(context, AssistantSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                assertFalse(hasEditable(root));
                assertNull(findText(root, "体验连接码"));
                assertNull(findText(root, "保存服务连接"));
                assertNotNull(findText(root, "无需填写地址或连接码"));
                TextView retry = findText(root, "准备语音资源／重试");
                assertNotNull(retry); retry.performClick();
                assertNotNull(findText(root, "请先开启连续语音"));
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            SharedPreferences.Editor edit = prefs.edit();
            if (present) edit.putBoolean(AssistantSettings.VOICE, previous); else edit.remove(AssistantSettings.VOICE);
            edit.commit();
        }
    }
    private static boolean hasEditable(View view) {
        if (view instanceof EditText) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasEditable(group.getChildAt(i))) return true;
        }
        return false;
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text); if (found != null) return found;
            }
        }
        return null;
    }
}
