package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Everyday screens and actual system-consent cancellation on the emulator, with no game/network. */
@RunWith(AndroidJUnit4.class)
public final class Match3UiInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static SharedPreferences prefs() { return GameProfile.settings(context()); }
    private static View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = find(((ViewGroup) root).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private static Intent intent(Class<?> activity) { return new Intent(context(), activity); }
    private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }

    @Test public void runScreenHasOneStartAndNoRequiredCalibrationOrSecondLauncher() throws Exception {
        try (ActivityScenario<Match3AssistActivity> screen = ActivityScenario.launch(intent(Match3AssistActivity.class))) {
            screen.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertNotNull(find(root, "开始辅助")); assertNotNull(find(root, "停止"));
                assertFalse(find(root, "停止").isEnabled());
                assertNotNull(root.findViewWithTag("ui_nav:设置"));
                assertNull(find(root, "启动开心消消乐")); assertNull(find(root, "棋盘标定"));
                assertNull(find(root, "棋子学习库")); assertNull(find(root, "选择游戏截图"));
                if (activity.getResources().getConfiguration().fontScale <= 1f
                        && activity.getResources().getConfiguration().screenWidthDp >= 360)
                    assertTrue(find(root, "开始辅助").getGlobalVisibleRect(new android.graphics.Rect()));
            });
        }
        UiQualityInstrumentedTest.capture(intent(Match3AssistActivity.class), "match3-run-light");
    }

    @Test public void settingsKeepHighlightPreferenceAndExposeToolsDirectlyAcrossRecreation() throws Exception {
        boolean present = prefs().contains("match3_hint_highlight_enabled");
        boolean previous = prefs().getBoolean("match3_hint_highlight_enabled", true);
        try (ActivityScenario<Match3SettingsActivity> screen = ActivityScenario.launch(intent(Match3SettingsActivity.class))) {
            screen.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertNotNull(root.findViewWithTag("ui_nav:语音引擎与语速"));
                assertNull(find(root, "沿用当前手机引擎，支持 MultiTTS"));
                assertNull(root.findViewWithTag("ui_details:高级工具"));
                assertSame(root.findViewWithTag("match3_exchange_settings"),
                        root.findViewWithTag("ui_details:行列编号说明").getParent());
                assertEquals(View.VISIBLE, root.findViewWithTag("ui_nav:截图校准与棋子学习").getVisibility());
                assertEquals(View.VISIBLE, root.findViewWithTag("ui_nav:游戏内触屏点读").getVisibility());
                assertNull(find(root, "行数")); assertNull(find(root, "自定义名称"));
                ((CheckBox) find(root, "交换位置高亮")).setChecked(!previous);
                assertEquals(!previous, prefs().getBoolean("match3_hint_highlight_enabled", previous));
            });
            screen.recreate(); idle();
            screen.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertEquals(!previous, ((CheckBox) find(root, "交换位置高亮")).isChecked());
                assertEquals(View.VISIBLE, root.findViewWithTag("ui_nav:截图校准与棋子学习").getVisibility());
            });
        } finally {
            SharedPreferences.Editor edit = prefs().edit();
            if (present) edit.putBoolean("match3_hint_highlight_enabled", previous); else edit.remove("match3_hint_highlight_enabled");
            edit.commit();
        }
        UiQualityInstrumentedTest.capture(intent(Match3SettingsActivity.class), "match3-settings-light");
    }

    @Test public void speechPageReusesSelectedEngineAndRateWithoutHonorTuningControls() throws Exception {
        String engine = prefs().getString(CuePlayer.PREF_TTS_ENGINE, "");
        int rate = prefs().getInt(CuePlayer.PREF_TTS_RATE, CuePlayer.DEFAULT_TTS_RATE_PERCENT);
        Intent speech = intent(GameTuningActivity.class).putExtra(GameTuningActivity.EXTRA_DYNAMIC_SPEECH_ONLY, true);
        try (ActivityScenario<GameTuningActivity> screen = ActivityScenario.launch(speech)) {
            screen.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertNotNull(find(root, "动态文本语音引擎"));
                assertNotNull(find(root, "试听交换提示"));
                assertNull(find(root, "提醒音效")); assertNull(find(root, "测试提醒与振动"));
                assertNull(find(root, "提醒新出现的敌方头像"));
                assertEquals(engine, prefs().getString(CuePlayer.PREF_TTS_ENGINE, ""));
                assertEquals(rate, prefs().getInt(CuePlayer.PREF_TTS_RATE, CuePlayer.DEFAULT_TTS_RATE_PERCENT));
            });
        }
        UiQualityInstrumentedTest.capture(speech, "match3-speech-settings");
    }

    @Test public void advancedToolsCollapseAndRestoreManualCalibrationAndLearning() throws Exception {
        try (ActivityScenario<Match3ToolsActivity> screen = ActivityScenario.launch(intent(Match3ToolsActivity.class))) {
            screen.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                for (String title : new String[]{"手动棋盘校准", "棋子学习库", "实验判定（可选）"}) {
                    View group = root.findViewWithTag("ui_details:" + title);
                    assertEquals(View.GONE, group.findViewWithTag("ui_details_body").getVisibility());
                }
                find(root, "棋子学习库").performClick();
                assertNotNull(find(root, "从当前截图裁剪该格，保存为模板"));
            });
            screen.recreate(); idle();
            screen.onActivity(activity -> assertEquals(View.VISIBLE, activity.findViewById(android.R.id.content)
                    .findViewWithTag("ui_details:棋子学习库").findViewWithTag("ui_details_body").getVisibility()));
        }
        UiQualityInstrumentedTest.capture(intent(Match3ToolsActivity.class), "match3-advanced-tools");
    }

    private static boolean cancelConsent(AccessibilityNodeInfo root) {
        if (root == null) return false;
        String text = String.valueOf(root.getText());
        if (text.equalsIgnoreCase("Cancel") || text.equals("取消"))
            return root.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        for (int i = 0; i < root.getChildCount(); i++) if (cancelConsent(root.getChild(i))) return true;
        return false;
    }
    @Test public void actualConsentCancellationRestoresStartAndDoesNotRunTheService() throws Exception {
        boolean present = prefs().contains("match3_overlay_permission_explained");
        boolean old = prefs().getBoolean("match3_overlay_permission_explained", false);
        prefs().edit().putBoolean("match3_overlay_permission_explained", true).commit();
        try (ActivityScenario<Match3AssistActivity> screen = ActivityScenario.launch(intent(Match3AssistActivity.class))) {
            assertFalse(Match3LiveService.isRunning());
            screen.onActivity(activity -> find(activity.findViewById(android.R.id.content), "开始辅助").performClick());
            boolean cancelled = false;
            long until = SystemClock.elapsedRealtime() + 8000;
            while (!cancelled && SystemClock.elapsedRealtime() < until) {
                cancelled = cancelConsent(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
                SystemClock.sleep(100);
            }
            assertTrue("The actual Android recording consent must be cancelled", cancelled);
            // System dialog dismissal and delivery of Activity.onActivityResult are asynchronous.
            // Wait for the user-visible ready state, not just this process's main-looper idle.
            boolean[] ready = {false};
            until = SystemClock.elapsedRealtime() + 3000;
            while (!ready[0] && SystemClock.elapsedRealtime() < until) {
                screen.onActivity(activity -> ready[0] = find(activity.findViewById(android.R.id.content),
                        "开始辅助").isEnabled());
                SystemClock.sleep(100);
            }
            assertTrue("Cancellation returns to a retryable start within 3 seconds", ready[0]);
            screen.onActivity(activity -> {
                assertFalse(Match3LiveService.isRunning());
                assertTrue(find(activity.findViewById(android.R.id.content), "开始辅助").isEnabled());
            });
            screen.recreate(); idle();
            screen.onActivity(activity -> assertTrue(find(activity.findViewById(android.R.id.content), "开始辅助").isEnabled()));
        } finally {
            SharedPreferences.Editor edit = prefs().edit();
            if (present) edit.putBoolean("match3_overlay_permission_explained", old); else edit.remove("match3_overlay_permission_explained");
            edit.commit();
        }
    }
}
