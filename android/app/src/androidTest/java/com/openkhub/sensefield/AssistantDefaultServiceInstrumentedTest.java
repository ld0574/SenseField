package com.openkhub.sensefield;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Map;
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
        Map<String, ?> previous = prefs.getAll();
        prefs.edit().putBoolean(AssistantSettings.ENABLED, false)
                .putBoolean(AssistantSettings.VOICE, false).putBoolean(AssistantSettings.VISION, false).commit();
        AssistantSettingsActivity activity = (AssistantSettingsActivity) instrumentation.startActivitySync(
                new Intent(context, AssistantSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                assertFalse(hasEditable(root));
                assertNull(findText(root, "体验连接码"));
                assertNull(findText(root, "保存服务连接"));
                assertTrue(findText(root, "AI助手默认关闭").isShown());
                CheckBox enabled = (CheckBox) findText(root, "启用实验 AI 助手");
                assertFalse(enabled.isChecked());
                TextView retry = findText(root, "准备语音资源／重试");
                assertNotNull(retry); assertFalse(retry.isShown());
                enabled.performClick();
                assertTrue(new AssistantSettings(prefs).optedIn);
                assertTrue(retry.isShown());
                assertNotNull(findText(root, "无需填写地址或连接码"));
                retry.performClick();
                assertNotNull(findText(root, "请先开启连续语音"));
                enabled.performClick();
                assertFalse(new AssistantSettings(prefs).enabled());
                assertFalse(retry.isShown());
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            restore(prefs, previous, AssistantSettings.ENABLED, AssistantSettings.VOICE, AssistantSettings.VISION);
        }
    }
    @Test public void legacyModesStayOffUntilExplicitOptInAndKeepTheChoiceAcrossReloads() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("assistant-opt-in-fixture", Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean(AssistantSettings.VOICE, true)
                .putBoolean(AssistantSettings.VISION, true).putBoolean(AssistantSettings.PROACTIVE, true)
                .putBoolean(AssistantSettings.AUDIO_CONSENT, true)
                .putBoolean(AssistantSettings.IMAGE_CONSENT, true).commit();
        try {
            AssistantSettings legacy = new AssistantSettings(prefs);
            assertFalse(legacy.enabled()); assertFalse(legacy.voice);
            assertFalse(legacy.vision); assertFalse(legacy.proactive);
            assertFalse(AssistantSettings.voiceEnabled(prefs));
            prefs.edit().putBoolean(AssistantSettings.ENABLED, true).commit();
            AssistantSettings enabled = new AssistantSettings(prefs);
            assertTrue(enabled.enabled()); assertTrue(enabled.voice);
            assertTrue(enabled.vision); assertTrue(enabled.proactive);
            assertTrue(new AssistantSettings(prefs).enabled());
            prefs.edit().putBoolean(AssistantSettings.ENABLED, false).commit();
            assertFalse(new AssistantSettings(prefs).enabled());
            assertFalse(AssistantSettings.voiceEnabled(prefs));
        } finally { prefs.edit().clear().commit(); }
    }
    @Test public void masterOptInStillRequiresEachModeAndItsConsent() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("assistant-consent-fixture", Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean(AssistantSettings.ENABLED, true).commit();
        try {
            assertFalse(new AssistantSettings(prefs).enabled());
            prefs.edit().putBoolean(AssistantSettings.VOICE, true)
                    .putBoolean(AssistantSettings.VISION, true).putBoolean(AssistantSettings.PROACTIVE, true).commit();
            assertFalse(new AssistantSettings(prefs).enabled());
            prefs.edit().putBoolean(AssistantSettings.AUDIO_CONSENT, true).commit();
            AssistantSettings voiceOnly = new AssistantSettings(prefs);
            assertTrue(voiceOnly.voice); assertFalse(voiceOnly.vision); assertFalse(voiceOnly.proactive);
            prefs.edit().putBoolean(AssistantSettings.VOICE, false)
                    .putBoolean(AssistantSettings.IMAGE_CONSENT, true).commit();
            AssistantSettings visionOnly = new AssistantSettings(prefs);
            assertFalse(visionOnly.voice); assertTrue(visionOnly.vision); assertTrue(visionOnly.proactive);
        } finally { prefs.edit().clear().commit(); }
    }
    @Test public void openingLegacySettingsDoesNotAutomaticallyPrepareSpeechResources() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        SharedPreferences prefs = GameProfile.settings(context);
        Map<String, ?> previous = prefs.getAll();
        prefs.edit().remove(AssistantSettings.ENABLED).putBoolean(AssistantSettings.VOICE, true)
                .putBoolean(AssistantSettings.AUDIO_CONSENT, true).commit();
        AssistantSettingsActivity activity = (AssistantSettingsActivity) instrumentation.startActivitySync(
                new Intent(context, AssistantSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                assertFalse(((CheckBox) findText(root, "启用实验 AI 助手")).isChecked());
                assertFalse(findText(root, "准备语音资源／重试").isShown());
                assertNotNull(findText(root, "首次开启连续语音后准备资源"));
                assertFalse(new AssistantSettings(prefs).enabled());
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            restore(prefs, previous, AssistantSettings.ENABLED, AssistantSettings.VOICE, AssistantSettings.AUDIO_CONSENT);
        }
    }
    private static void restore(SharedPreferences prefs, Map<String, ?> previous, String... keys) {
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : keys) {
            if (previous.containsKey(key)) edit.putBoolean(key, (Boolean) previous.get(key));
            else edit.remove(key);
        }
        edit.commit();
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
