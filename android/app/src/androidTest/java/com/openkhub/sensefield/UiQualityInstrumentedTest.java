package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real laid-out Android screens. Tests never request microphone, gameplay or remote models. */
@RunWith(AndroidJUnit4.class)
public final class UiQualityInstrumentedTest {
    private final Map<String, Boolean> saved = new HashMap<>();
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static SharedPreferences prefs() { return GameProfile.settings(context()); }
    private static Intent intent(Class<? extends Activity> type) { return new Intent(context(), type); }

    @Before public void localOnly() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true);
        for (String key : new String[] {"app_update_auto_check", AssistantSettings.ENABLED,
                AssistantSettings.VOICE, AssistantSettings.VISION, AssistantSettings.PROACTIVE}) {
            saved.put(key, prefs().contains(key) ? prefs().getBoolean(key, false) : null);
            prefs().edit().putBoolean(key, false).commit();
        }
    }
    @After public void restorePreferences() {
        SharedPreferences.Editor edit = prefs().edit();
        for (Map.Entry<String, Boolean> item : saved.entrySet()) {
            if (item.getValue() == null) edit.remove(item.getKey());
            else edit.putBoolean(item.getKey(), item.getValue());
        }
        edit.commit();
    }

    @Test public void capturePrimaryComposition() throws Exception {
        capture(intent(GameSelectionActivity.class), "game-selection");
        capture(intent(MainActivity.class), "main");
        capture(intent(AppSettingsActivity.class), "settings");
    }

    @Test public void everyPageHasUnclippedTextAndFitsTheActualWindow() throws Exception {
        capturePrimaryComposition();
        capture(intent(CapturePermissionsActivity.class), "permissions");
        capture(intent(GameTuningActivity.class), "tuning");
        capture(intent(AlertSettingsActivity.class), "alerts");
        capture(intent(HapticSettingsActivity.class), "haptics");
        capture(intent(ReminderGuideActivity.class), "samples");
        capture(intent(ReminderGuideActivity.class).putExtra(ReminderGuideActivity.EXTRA_FULL, true), "full-guide");
        capture(intent(DiagnosticsActivity.class), "feedback");
        capture(intent(AssistantSettingsActivity.class), "assistant-off");
        prefs().edit().putBoolean(AssistantSettings.ENABLED, true).commit();
        capture(intent(AssistantSettingsActivity.class), "assistant-options");
        capture(intent(Match3AssistActivity.class), "match3");
        capture(intent(JudgmentSelfTestActivity.class), "match3-self-test");
        capture(intent(SettingHelpActivity.class).putExtra(SettingHelp.EXTRA_KEY, "voice_group"), "help-page");
        try (ActivityScenario<AlertSettingsActivity> scenario = ActivityScenario.launch(intent(AlertSettingsActivity.class))) {
            scenario.onActivity(activity -> tagged(activity.findViewById(android.R.id.content),
                    "setting_help_group_button:events_group").performClick());
            idle();
            scenario.onActivity(activity -> assertLayout(activity.findViewById(android.R.id.content), "context-help"));
            screenshot("context-help");
            scenario.onActivity(activity -> {
                View panel = tagged(activity.findViewById(android.R.id.content), SettingHelpPanel.PANEL_VIEW_TAG);
                ScrollView scroll = first(panel, ScrollView.class);
                assertNotNull(scroll);
                scrollToBottom(scroll);
            });
            idle();
            screenshot("context-help-bottom");
        }
    }

    @Test public void pageAndHelpPositionsSurviveRecreationAndPauseResume() throws Exception {
        try (ActivityScenario<DiagnosticsActivity> scenario = ActivityScenario.launch(intent(DiagnosticsActivity.class))) {
            final int[] position = new int[1];
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                EditText note = first(root, EditText.class);
                note.setText("第二局 2:30：需要核对方向"); note.setSelection(5);
                ViewGroup details = (ViewGroup) tagged(root, "ui_details:记录范围与隐私");
                details.getChildAt(0).performClick();
                first(root, ScrollView.class).scrollTo(0, UiKit.dp(activity, 360));
            });
            idle();
            scenario.onActivity(activity -> position[0] = first(activity.findViewById(android.R.id.content), ScrollView.class).getScrollY());
            scenario.recreate(); idle();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                EditText note = first(root, EditText.class);
                assertEquals("第二局 2:30：需要核对方向", note.getText().toString());
                assertEquals(5, note.getSelectionStart());
                ViewGroup details = (ViewGroup) tagged(root, "ui_details:记录范围与隐私");
                assertEquals(View.VISIBLE, details.getChildAt(1).getVisibility());
                assertEquals(position[0], first(root, ScrollView.class).getScrollY());
            });
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); idle();
            scenario.onActivity(activity -> assertEquals("第二局 2:30：需要核对方向",
                    first(activity.findViewById(android.R.id.content), EditText.class).getText().toString()));
        }
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(intent(Match3AssistActivity.class))) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                first(root, EditText.class).setText("12.5");
                first(root, Spinner.class).setSelection(1);
            });
            idle(); scenario.recreate(); idle();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertEquals("12.5", first(root, EditText.class).getText().toString());
                assertEquals(1, first(root, Spinner.class).getSelectedItemPosition());
            });
        }
        try (ActivityScenario<AlertSettingsActivity> scenario = ActivityScenario.launch(intent(AlertSettingsActivity.class))) {
            scenario.onActivity(activity -> tagged(activity.findViewById(android.R.id.content),
                    "setting_help_group_button:events_group").performClick());
            idle();
            scenario.onActivity(activity -> first(tagged(activity.findViewById(android.R.id.content),
                    SettingHelpPanel.PANEL_VIEW_TAG), ScrollView.class).scrollTo(0, UiKit.dp(activity, 180)));
            idle(); scenario.recreate(); idle();
            scenario.onActivity(activity -> {
                View panel = tagged(activity.findViewById(android.R.id.content), SettingHelpPanel.PANEL_VIEW_TAG);
                assertNotNull(panel); assertEquals(View.VISIBLE, panel.getVisibility());
                assertNotNull(find(panel, SettingHelpContent.title("events_group")));
                assertEquals(UiKit.dp(activity, 180), first(panel, ScrollView.class).getScrollY());
                SettingHelp.close(activity);
            });
        }
    }

    @Test public void systemAuthorizationPageReturnsToTheSameReadingPosition() throws Exception {
        try (ActivityScenario<CapturePermissionsActivity> scenario = ActivityScenario.launch(
                intent(CapturePermissionsActivity.class))) {
            final int[] position = new int[1];
            final boolean[] visual = new boolean[1];
            idle();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                TextView action = find(root, "授权置顶显示");
                action.requestRectangleOnScreen(new Rect(0, 0, action.getWidth(), action.getHeight()), true);
                visual[0] = first(root, CheckBox.class).isChecked();
            });
            idle();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                position[0] = first(root, ScrollView.class).getScrollY();
                find(root, "授权置顶显示").performClick();
            });
            awaitActivePackage("com.android.settings");
            idle();
            pressBack();
            awaitActivePackage(context().getPackageName());
            idle();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertEquals("Returning from real system settings preserves the reading position",
                        position[0], first(root, ScrollView.class).getScrollY());
                assertEquals("Visiting settings never changes a reminder preference",
                        visual[0], first(root, CheckBox.class).isChecked());
            });
            screenshot("permissions-return");
        }
    }

    @Test public void guideRestoresUnfinishedSentenceWithoutAutoplayOrAcceptingOldCallbacks() throws Exception {
        try (ActivityScenario<ReminderGuideActivity> scenario = ActivityScenario.launch(
                intent(ReminderGuideActivity.class).putExtra(ReminderGuideActivity.EXTRA_FULL, true))) {
            final List<ReminderGuidePlayback.Completion> callbacks = new ArrayList<>();
            final ReminderGuidePlayback[] old = new ReminderGuidePlayback[1];
            scenario.onActivity(activity -> {
                @SuppressWarnings("unchecked") List<ReminderGuide.Step> full = (List<ReminderGuide.Step>) value(activity, "fullSteps");
                old[0] = new ReminderGuidePlayback(new ReminderGuidePlayback.Output() {
                    public void play(ReminderGuide.Step step, ReminderGuidePlayback.Completion callback) { callbacks.add(callback); }
                    public void stop() { }
                }, new ReminderGuidePlayback.Listener() {
                    public void onStep(ReminderGuide.Step step) { }
                    public void onEnded(boolean success) { fail("Canceled playback must not finish a new round"); }
                });
                set(activity, "playback", old[0]);
                old[0].startAt(ReminderGuideSections.sentenceSteps(full), 2);
            });
            scenario.recreate(); idle();
            callbacks.get(0).finish(true);
            assertEquals(2, old[0].currentIndex());
            scenario.onActivity(activity -> {
                assertEquals(2, value(activity, "resumeIndex"));
                assertNull("Returning never starts narration automatically", value(activity, "player"));
                assertNotNull(find(activity.findViewById(android.R.id.content), "继续播放"));
                find(activity.findViewById(android.R.id.content), "继续播放").performClick();
                assertEquals("Continuation starts at the saved sentence", 2, value(activity, "startIndex"));
                assertNull("The old round cannot remain paused after continuation", value(activity, "resumeSectionId"));
                find(activity.findViewById(android.R.id.content), "停止播放").performClick();
                assertEquals(-1, value(activity, "resumeIndex"));
            });
        }
    }

    @Test public void dialogReflowKeepsOriginalCallbacksAndDismissal() throws Exception {
        try (ActivityScenario<AppSettingsActivity> scenario = ActivityScenario.launch(intent(AppSettingsActivity.class))) {
            final int[] actions = new int[2];
            final AlertDialog[] dialog = new AlertDialog[1];
            scenario.onActivity(activity -> {
                dialog[0] = new AlertDialog.Builder(activity).setTitle("确认此操作")
                        .setMessage("重要文字应完整显示，操作可随系统字号重新排列。")
                        .setPositiveButton("确认并继续操作", (d, which) -> actions[0]++)
                        .setNegativeButton("取消并返回设置", (d, which) -> actions[1]++).create();
                dialog[0].show(); UiKit.styleDialog(dialog[0], UiKit.ButtonStyle.FILLED);
            });
            idle();
            scenario.onActivity(activity -> {
                assertLayout(dialog[0].getWindow().getDecorView(), "dialog");
                assertDialogActionsVisible(dialog[0].getWindow().getDecorView());
            });
            screenshot("dialog-reflow");
            scenario.onActivity(activity -> dialog[0].getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            idle(); assertEquals(0, actions[0]); assertEquals(1, actions[1]); assertFalse(dialog[0].isShowing());
            scenario.onActivity(activity -> { dialog[0].show(); UiKit.styleDialog(dialog[0], UiKit.ButtonStyle.FILLED); });
            idle();
            scenario.onActivity(activity -> dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            idle(); assertEquals(1, actions[0]); assertEquals(1, actions[1]); assertFalse(dialog[0].isShowing());
        }
    }

    @Test public void realUpdateOfferAndUploadConsentRemainReadableAndCancelable() throws Exception {
        try (ActivityScenario<GameSelectionActivity> scenario = ActivityScenario.launch(intent(GameSelectionActivity.class))) {
            final AlertDialog[] prompt = new AlertDialog[1];
            scenario.onActivity(activity -> {
                Object controller = value(activity, "updater");
                okhttp3.HttpUrl metadata = okhttp3.HttpUrl.get(BuildConfig.APP_UPDATE_METADATA_URL);
                AppUpdateRelease offer = new AppUpdateRelease(context().getPackageName(), "0.4.4",
                        BuildConfig.VERSION_CODE + 1, okhttp3.HttpUrl.get(
                        "https://gitee.com/leda/SenseField/releases/download/0.4.4/sensefieldv0.4.4.apk"),
                        50 * 1024 * 1024, "0000000000000000000000000000000000000000000000000000000000000000",
                        "改进设置阅读与操作。此测试只展示说明，不下载或安装。", metadata);
                set(controller, "offered", offer);
                invoke(controller, "maybePrompt");
                prompt[0] = (AlertDialog) value(controller, "prompt");
                assertNotNull(prompt[0]);
            });
            idle();
            scenario.onActivity(activity -> {
                assertLayout(prompt[0].getWindow().getDecorView(), "update-offer");
                assertDialogActionsVisible(prompt[0].getWindow().getDecorView());
            });
            screenshot("update-offer");
            scenario.onActivity(activity -> prompt[0].getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            idle(); assertFalse(prompt[0].isShowing());
        }
        prefs().edit().putBoolean(AssistantSettings.ENABLED, true).commit();
        try (ActivityScenario<AssistantSettingsActivity> scenario = ActivityScenario.launch(intent(AssistantSettingsActivity.class))) {
            final View[] dialog = new View[1];
            scenario.onActivity(activity -> ((CheckBox) find(activity.findViewById(android.R.id.content), "画面理解")).setChecked(true));
            idle();
            scenario.onActivity(activity -> {
                for (View window : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    if (find(window, "同意并开启") != null) { dialog[0] = window; break; }
                }
                assertNotNull(dialog[0]); assertLayout(dialog[0], "vision-consent");
                assertDialogActionsVisible(dialog[0]);
            });
            screenshot("vision-consent");
            scenario.onActivity(activity -> find(dialog[0], "取消").performClick());
            idle();
            assertFalse("Cancel leaves upload disabled", prefs().getBoolean(AssistantSettings.VISION, false));
        }
    }

    @Test public void selectedGuideSectionSurvivesRotationWithoutRestartingTheWholeGuide() throws Exception {
        try (ActivityScenario<ReminderGuideActivity> scenario = ActivityScenario.launch(
                intent(ReminderGuideActivity.class).putExtra(ReminderGuideActivity.EXTRA_FULL, true))) {
            final String[] sectionId = new String[1];
            scenario.onActivity(activity -> {
                @SuppressWarnings("unchecked") List<ReminderGuide.Step> full = (List<ReminderGuide.Step>) value(activity, "fullSteps");
                for (ReminderGuideSections.Section section : ReminderGuideSections.build(full)) {
                    if (section.startIndex == 0 || ReminderGuideSections.sentenceSteps(section.steps).size() < 2) continue;
                    sectionId[0] = section.id;
                    set(activity, "steps", section.steps);
                    set(activity, "selectedSectionId", section.id);
                    set(activity, "selectedSectionTitle", section.title);
                    ReminderGuidePlayback fake = new ReminderGuidePlayback(new ReminderGuidePlayback.Output() {
                        public void play(ReminderGuide.Step step, ReminderGuidePlayback.Completion completion) { }
                        public void stop() { }
                    }, new ReminderGuidePlayback.Listener() {
                        public void onStep(ReminderGuide.Step step) { }
                        public void onEnded(boolean success) { }
                    });
                    set(activity, "playback", fake);
                    fake.startAt(ReminderGuideSections.sentenceSteps(section.steps), 1);
                    break;
                }
                assertNotNull(sectionId[0]);
            });
            for (int orientation : new int[] {android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}) {
                scenario.onActivity(activity -> activity.setRequestedOrientation(orientation));
                int expected = orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        ? android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        : android.content.res.Configuration.ORIENTATION_PORTRAIT;
                final boolean[] rotated = new boolean[1];
                for (int attempt = 0; attempt < 50 && !rotated[0]; attempt++) {
                    idle();
                    scenario.onActivity(activity -> rotated[0] =
                            activity.getResources().getConfiguration().orientation == expected);
                    if (!rotated[0]) android.os.SystemClock.sleep(40);
                }
                assertTrue("The system must actually rotate the window", rotated[0]);
                scenario.onActivity(activity -> {
                    assertEquals(sectionId[0], value(activity, "selectedSectionId"));
                    assertEquals(1, value(activity, "resumeIndex"));
                    assertNull(value(activity, "player"));
                    assertNotNull(find(activity.findViewById(android.R.id.content), "继续播放"));
                    assertLayout(activity.findViewById(android.R.id.content), "guide-rotation");
                });
                screenshot(expected == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        ? "guide-rotated-landscape" : "guide-rotated-portrait");
            }
        }
    }

    @Test public void keyboardLeavesFeedbackEditableAndBackRestoresThePage() throws Exception {
        try (ActivityScenario<DiagnosticsActivity> scenario = ActivityScenario.launch(intent(DiagnosticsActivity.class))) {
            awaitCommittedWindow();
            scenario.onActivity(activity -> {
                EditText note = first(activity.findViewById(android.R.id.content), EditText.class);
                note.requestFocus(); note.setText("键盘输入继续保留");
                activity.getSystemService(InputMethodManager.class).showSoftInput(note, InputMethodManager.SHOW_IMPLICIT);
            });
            final boolean[] imeVisible = new boolean[1];
            for (int attempt = 0; attempt < 50 && !imeVisible[0]; attempt++) {
                scenario.onActivity(activity -> {
                    android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                    imeVisible[0] = insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
                });
                if (!imeVisible[0]) android.os.SystemClock.sleep(40);
            }
            assertTrue("This check requires the actual on-screen keyboard to be visible", imeVisible[0]);
            idle();
            screenshot("feedback-keyboard");
            scenario.onActivity(activity -> {
                EditText note = first(activity.findViewById(android.R.id.content), EditText.class);
                Rect visible = new Rect();
                assertTrue("Input can be reached while typing", note.getGlobalVisibleRect(visible));
                assertEquals("The keyboard must not cover any part of the input", note.getHeight(), visible.height());
                assertLayout(activity.findViewById(android.R.id.content), "feedback-keyboard");
            });
            pressBack();
            idle();
            scenario.onActivity(activity -> {
                android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                assertFalse("Back must actually dismiss the keyboard",
                        insets != null && insets.isVisible(android.view.WindowInsets.Type.ime()));
            });
            scenario.onActivity(activity -> assertEquals("键盘输入继续保留",
                    first(activity.findViewById(android.R.id.content), EditText.class).getText().toString()));
        }
    }

    @Test public void typographyContrastAndNavigationSemanticsAreConsistent() {
        for (int foreground : new int[] {UiKit.INK, UiKit.MUTED, UiKit.PRIMARY, UiKit.DANGER})
            for (int background : new int[] {UiKit.PAGE, UiKit.SURFACE, UiKit.PRIMARY_SOFT})
                assertTrue("Text contrast is at least 4.5:1", ColorUtils.calculateContrast(foreground, background) >= 4.5);
        for (int background : new int[] {UiKit.PAGE, UiKit.SURFACE})
            assertTrue("Necessary control edge has 3:1 contrast", ColorUtils.calculateContrast(UiKit.OUTLINE, background) >= 3);
        assertTrue(ColorUtils.calculateContrast(android.graphics.Color.WHITE, UiKit.PRIMARY) >= 4.5);
        try (ActivityScenario<AppSettingsActivity> scenario = ActivityScenario.launch(intent(AppSettingsActivity.class))) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                TextView heading = find(root, "设置");
                TextView reference = new TextView(activity); reference.setTextSize(24);
                assertEquals(reference.getTextSize(), heading.getTextSize(), .1f);
                assertTrue(heading.isAccessibilityHeading());
                View navigation = tagged(root, "ui_nav:配置与调参");
                assertNotNull(navigation); assertTrue(navigation.isClickable());
                android.view.accessibility.AccessibilityNodeInfo node = navigation.createAccessibilityNodeInfo();
                assertEquals(Button.class.getName(), node.getClassName());
                assertNotNull(node.getContentDescription()); node.recycle();
            });
        }
    }

    private static void capture(Intent intent, String name) throws Exception {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            idle();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertTrue(root.getWidth() > 0 && root.getHeight() > 0);
                assertLayout(root, name);
                if (activity instanceof MainActivity) {
                    View start = find(root, "开始辅助"), stop = find(root, "停止");
                    assertNotNull(start); assertNotNull(stop);
                    Rect a = bounds(start), b = bounds(stop);
                    int separation = Math.max(b.left - a.right, b.top - a.bottom);
                    assertTrue("Start and stop are separated by 20dp", separation >= UiKit.dp(activity, 20));
                    if (activity.getResources().getConfiguration().fontScale <= 1f
                            && activity.getResources().getConfiguration().screenWidthDp >= 360
                            && activity.getResources().getConfiguration().screenHeightDp >= 720) {
                        Rect screen = bounds(root);
                        assertTrue("Main action is visible on the first screen", a.bottom <= screen.bottom);
                    }
                }
                if ("full-guide".equals(name)) {
                    ScrollView text = first(root, ScrollView.class);
                    assertTrue("The fixed playback bar leaves room to read", text.getHeight() >= Math.min(
                            UiKit.dp(activity, 120), root.getHeight() / 3));
                }
            });
            screenshot(name);
            scenario.onActivity(activity -> {
                ScrollView scroll = first(activity.findViewById(android.R.id.content), ScrollView.class);
                if (scroll != null) {
                    int maximum = scrollToBottom(scroll);
                    assertEquals("Bottom captures must actually reach the end of the page", maximum, scroll.getScrollY());
                }
            });
            idle();
            scenario.onActivity(activity -> {
                ScrollView scroll = first(activity.findViewById(android.R.id.content), ScrollView.class);
                if (scroll != null) assertEquals("The bottom position must remain stable through the next layout",
                        Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight()
                                + scroll.getPaddingTop() + scroll.getPaddingBottom()), scroll.getScrollY());
            });
            screenshot(name + "-bottom");
        }
    }

    private static int scrollToBottom(ScrollView scroll) {
        // Snapshot navigation is independent of keyboard focus. A focused input/button
        // may otherwise pull the page back to itself on the next layout pass.
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scroll.clearFocus();
        scroll.setFocusableInTouchMode(true);
        scroll.requestFocus();
        int maximum = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight()
                + scroll.getPaddingTop() + scroll.getPaddingBottom());
        scroll.scrollTo(0, maximum);
        return maximum;
    }

    private static void assertLayout(View root, String page) {
        List<String> errors = new ArrayList<>(); inspect(root, errors);
        assertTrue(page + " layout problems: " + errors, errors.isEmpty());
    }
    private static void inspect(View view, List<String> errors) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0 || !view.isShown()) return;
        if (view instanceof TextView && !(view instanceof EditText)) {
            TextView text = (TextView) view; Layout layout = text.getLayout();
            if (layout != null && text.getText().length() > 0) {
                String label = text.getText().toString();
                for (int i = 0; i < layout.getLineCount(); i++)
                    if (layout.getEllipsisCount(i) != 0) errors.add(label + " is ellipsized");
                int height = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
                if (layout.getHeight() > height + 2) errors.add(label + " vertically clips: " + layout.getHeight() + "/" + height);
                int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                for (int i = 0; i < layout.getLineCount(); i++)
                    if (layout.getLineRight(i) - layout.getLineLeft(i) > width + 2)
                        errors.add(label + " horizontally clips");
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0 && !(group instanceof ScrollView))
                    if (child.getLeft() < -2 || child.getRight() > group.getWidth() + 2)
                        errors.add(child.getClass().getSimpleName() + " exceeds parent width");
                inspect(child, errors);
            }
        }
    }

    private static void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        try { InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(100, 3000); }
        catch (java.util.concurrent.TimeoutException ignored) { }
    }
    private static void screenshot(String name) throws Exception {
        Bitmap screenshot = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            awaitCommittedWindow();
            screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            if (containsContent(screenshot)) break;
            screenshot.recycle(); screenshot = null;
            android.os.SystemClock.sleep(100);
        }
        assertNotNull("Capture must include rendered app content, not a blank launch frame: " + name, screenshot);
        String phase = InstrumentationRegistry.getArguments().getString("preview_phase", "after")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        File dir = new File(context().getExternalFilesDir("ui-0.4.3"), phase);
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream file = new FileOutputStream(new File(dir, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, file));
        } finally { screenshot.recycle(); }
    }
    private static void awaitActivePackage(String expected) {
        String lastPackage = "none";
        for (int attempt = 0; attempt < 100; attempt++) {
            android.view.accessibility.AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            boolean matches = root != null && expected.equals(String.valueOf(root.getPackageName()));
            if (root != null) lastPackage = String.valueOf(root.getPackageName());
            if (root != null) root.recycle();
            if (matches) return;
            android.os.SystemClock.sleep(40);
        }
        fail("Expected the actual foreground window of " + expected + "; observed " + lastPackage);
    }
    private static void assertDialogActionsVisible(View root) {
        if (root.getVisibility() != View.VISIBLE) return;
        if (root instanceof Button) {
            Rect visible = new Rect();
            assertTrue("Dialog action must be on screen: " + ((Button) root).getText(),
                    root.getGlobalVisibleRect(visible));
            assertEquals("The complete action height must stay visible", root.getHeight(), visible.height());
            assertEquals("The complete action width must stay visible", root.getWidth(), visible.width());
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) assertDialogActionsVisible(group.getChildAt(i));
        }
    }
    private static void pressBack() {
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        long down = android.os.SystemClock.uptimeMillis();
        assertTrue(automation.injectInputEvent(new android.view.KeyEvent(down, down,
                android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK, 0,
                0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                android.view.InputDevice.SOURCE_KEYBOARD), true));
        assertTrue(automation.injectInputEvent(new android.view.KeyEvent(down, android.os.SystemClock.uptimeMillis(),
                android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK, 0,
                0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                android.view.InputDevice.SOURCE_KEYBOARD), true));
    }
    private static void awaitCommittedWindow() throws Exception {
        final View[] window = new View[1];
        for (int attempt = 0; attempt < 25 && window[0] == null; attempt++) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (View candidate : android.view.inspector.WindowInspector.getGlobalWindowViews())
                    if (candidate.isShown() && candidate.hasWindowFocus()
                            && context().getPackageName().equals(candidate.getContext().getPackageName()))
                        window[0] = candidate;
            });
            if (window[0] == null) android.os.SystemClock.sleep(20);
        }
        assertNotNull("App window must have focus before capture", window[0]);
        java.util.concurrent.CountDownLatch frame = new java.util.concurrent.CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (window[0].isHardwareAccelerated())
                window[0].getViewTreeObserver().registerFrameCommitCallback(frame::countDown);
            else frame.countDown();
            window[0].postInvalidateOnAnimation();
        });
        assertTrue("The app must commit its drawn frame", frame.await(2, java.util.concurrent.TimeUnit.SECONDS));
    }
    private static boolean containsContent(Bitmap bitmap) {
        int dark = 0;
        for (int y = bitmap.getHeight() / 12; y < bitmap.getHeight() * 11 / 12; y += 12)
            for (int x = bitmap.getWidth() / 12; x < bitmap.getWidth() * 11 / 12; x += 12) {
                int color = bitmap.getPixel(x, y);
                if (android.graphics.Color.red(color) + android.graphics.Color.green(color)
                        + android.graphics.Color.blue(color) < 480) dark++;
            }
        return dark > 30;
    }
    private static Rect bounds(View view) {
        int[] p = new int[2]; view.getLocationOnScreen(p);
        return new Rect(p[0], p[1], p[0] + view.getWidth(), p[1] + view.getHeight());
    }
    private static TextView find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                TextView found = find(((ViewGroup) view).getChildAt(i), text); if (found != null) return found;
            }
        }
        return null;
    }
    private static View tagged(View view, String tag) {
        if (tag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = tagged(((ViewGroup) view).getChildAt(i), tag); if (found != null) return found;
            }
        }
        return null;
    }
    private static <T extends View> T first(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                T found = first(((ViewGroup) view).getChildAt(i), type); if (found != null) return found;
            }
        }
        return null;
    }
    private static Object value(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void set(Object object, String name, Object value) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void invoke(Object object, String name) {
        try { java.lang.reflect.Method method = object.getClass().getDeclaredMethod(name);
            method.setAccessible(true); method.invoke(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
