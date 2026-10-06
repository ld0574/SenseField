package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Local emulator UI checks with UI-only screenshots; no microphone, gameplay, or remote API. */
@RunWith(AndroidJUnit4.class)
public final class PatientLearningInstrumentedTest {
    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static Intent intent(Class<? extends Activity> type) { return new Intent(context(), type); }

    @Test public void groupHelpOpensOnTheFirstRealTouch() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<AlertSettingsActivity> scenario = ActivityScenario.launch(intent(AlertSettingsActivity.class))) {
            instrumentation.setInTouchMode(true);
            final int[] point = new int[2];
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertFalse(hasVisiblePanel(root));
                View help = findTag(root, "setting_help_group_button:channel_group");
                assertNotNull(help);
                help.clearFocus();
                help.getLocationOnScreen(point);
                point[0] += help.getWidth() / 2;
                point[1] += help.getHeight() / 2;
            });
            long down = SystemClock.uptimeMillis();
            MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN,
                    point[0], point[1], 0);
            MotionEvent release = MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP,
                    point[0], point[1], 0);
            try {
                instrumentation.sendPointerSync(press);
                instrumentation.sendPointerSync(release);
            } finally { press.recycle(); release.recycle(); }
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertTrue("One touch opens help without a focus-only first tap", hasVisiblePanel(root));
                assertGroupHelpContentsInOrder(findTag(root, "setting_help_panel_view"), "channel_group");
                SettingHelp.close(activity);
            });
        }
    }

    @Test public void firstStartReadsTheFullGuideAndLaterStartsGoDirectlyToAuthorization() {
        SharedPreferences prefs = GameProfile.settings(context());
        String completed = ReminderGuide.PREF_FULL_GUIDE_COMPLETED;
        String repeat = ReminderGuide.PREF_REPEAT_BEFORE_START;
        boolean hadCompleted = prefs.contains(completed), wasCompleted = prefs.getBoolean(completed, false);
        boolean hadRepeat = prefs.contains(repeat), wasRepeat = prefs.getBoolean(repeat, false);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Intent[] next = new Intent[1];
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() != null && (intent.getComponent().getClassName()
                        .equals(ReminderGuideActivity.class.getName()) || intent.getComponent().getClassName()
                        .equals(CapturePermissionsActivity.class.getName()))) {
                    next[0] = intent;
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        prefs.edit().putBoolean(completed, false).remove(repeat).commit();
        instrumentation.addMonitor(monitor);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent(MainActivity.class))) {
            scenario.onActivity(activity -> ((Button) find(activity.getWindow().getDecorView(), "开始辅助")).performClick());
            assertNotNull(next[0]);
            assertEquals(ReminderGuideActivity.class.getName(), next[0].getComponent().getClassName());
            assertTrue(next[0].getBooleanExtra(ReminderGuideActivity.EXTRA_FULL, false));
            assertTrue(next[0].getBooleanExtra(ReminderGuideActivity.EXTRA_AUTO_READ, false));
            prefs.edit().putBoolean(completed, true).commit();
            next[0] = null;
            scenario.onActivity(activity -> ((Button) find(activity.getWindow().getDecorView(), "开始辅助")).performClick());
            assertNotNull(next[0]);
            assertEquals(CapturePermissionsActivity.class.getName(), next[0].getComponent().getClassName());
            prefs.edit().putBoolean(repeat, true).commit();
            next[0] = null;
            scenario.onActivity(activity -> ((Button) find(activity.getWindow().getDecorView(), "开始辅助")).performClick());
            assertEquals(ReminderGuideActivity.class.getName(), next[0].getComponent().getClassName());
        } finally {
            instrumentation.removeMonitor(monitor);
            restoreBoolean(prefs, completed, hadCompleted, wasCompleted);
            restoreBoolean(prefs, repeat, hadRepeat, wasRepeat);
        }
    }

    @Test public void firstGuideHasExplicitReadingFallbackWhenApplicationAudioIsMuted() {
        SharedPreferences prefs = GameProfile.settings(context());
        String completed = ReminderGuide.PREF_FULL_GUIDE_COMPLETED;
        boolean hadCompleted = prefs.contains(completed), wasCompleted = prefs.getBoolean(completed, false);
        boolean hadVolume = prefs.contains("volume");
        int volume = prefs.getInt("volume", 45);
        prefs.edit().putBoolean(completed, false).putInt("volume", 0).commit();
        try (ActivityScenario<ReminderGuideActivity> scenario = ActivityScenario.launch(
                intent(ReminderGuideActivity.class).putExtra(ReminderGuideActivity.EXTRA_FULL, true)
                        .putExtra(ReminderGuideActivity.EXTRA_START, true)
                        .putExtra(ReminderGuideActivity.EXTRA_AUTO_READ, true))) {
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertNotNull(find(root, "完整提醒说明"));
                assertNotNull(find(root, "播放全文"));
                Button confirmation = (Button) find(root, "已阅读说明，继续开始");
                assertNotNull("A missing sound route must not trap the first-time user", confirmation);
                assertTrue(confirmation.isEnabled());
                assertFalse("Merely opening the fallback does not count as reading", prefs.getBoolean(completed, false));
                assertNull(player(activity));
            });
        } finally {
            restoreBoolean(prefs, completed, hadCompleted, wasCompleted);
            SharedPreferences.Editor edit = prefs.edit();
            if (hadVolume) edit.putInt("volume", volume); else edit.remove("volume");
            edit.commit();
        }
    }

    @Test public void fullGuideCompletionIsRememberedButFailureAndSingleSectionAreNot() throws Exception {
        SharedPreferences prefs = GameProfile.settings(context());
        String completed = ReminderGuide.PREF_FULL_GUIDE_COMPLETED;
        boolean hadCompleted = prefs.contains(completed), wasCompleted = prefs.getBoolean(completed, false);
        prefs.edit().putBoolean(completed, false).commit();
        try (ActivityScenario<ReminderGuideActivity> scenario = ActivityScenario.launch(
                intent(ReminderGuideActivity.class).putExtra(ReminderGuideActivity.EXTRA_FULL, true)
                        .putExtra(ReminderGuideActivity.EXTRA_START, true))) {
            scenario.onActivity(activity -> {
                assertFalse(((Button) find(activity.getWindow().getDecorView(), "请先听完完整说明")).isEnabled());
                try {
                    // Supply controlled playback completion; this test does not certify audible output.
                    Field field = ReminderGuideActivity.class.getDeclaredField("playback");
                    field.setAccessible(true);
                    Object playback = field.get(activity);
                    Field listenerField = ReminderGuidePlayback.class.getDeclaredField("listener");
                    listenerField.setAccessible(true);
                    ReminderGuidePlayback.Listener listener =
                            (ReminderGuidePlayback.Listener) listenerField.get(playback);
                    listener.onEnded(false);
                    assertFalse(prefs.getBoolean(completed, false));
                    Field selected = ReminderGuideActivity.class.getDeclaredField("selectedSectionTitle");
                    selected.setAccessible(true);
                    selected.set(activity, "方位语音");
                    listener.onEnded(true);
                    assertFalse("A section must not finish full onboarding", prefs.getBoolean(completed, false));
                    selected.set(activity, null);
                    listener.onEnded(true);
                    assertTrue(prefs.getBoolean(completed, false));
                    assertTrue(((Button) find(activity.getWindow().getDecorView(), "继续开始")).isEnabled());
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(((Button) find(activity.getWindow().getDecorView(), "继续开始")).isEnabled()));
        } finally { restoreBoolean(prefs, completed, hadCompleted, wasCompleted); }
    }

    private static void restoreBoolean(SharedPreferences prefs, String key, boolean present, boolean value) {
        SharedPreferences.Editor edit = prefs.edit();
        if (present) edit.putBoolean(key, value); else edit.remove(key);
        edit.commit();
    }

    @Test public void directoryAndCompleteExplanationOpenWithoutAutomaticNarration() throws Exception {
        try (ActivityScenario<ReminderGuideActivity> scenario = ActivityScenario.launch(intent(ReminderGuideActivity.class))) {
            scenario.onActivity(activity -> {
                assertNotNull(find(activity.getWindow().getDecorView(), "完整说明"));
                assertNull("The directory does not create a sound player", player(activity));
                View left = findTag(activity.getWindow().getDecorView(), "left_tone");
                assertNotNull(left);
                assertTrue(left.getContentDescription().toString().contains("左侧短音"));
                View direction = findTag(activity.getWindow().getDecorView(), "near_speech_4");
                assertNotNull(direction);
                assertEquals("左上方位语音", ((TextView) direction).getText().toString());
                assertEquals("Only one representative direction is listed", 1,
                        ((ViewGroup) direction.getParent()).getChildCount());
                assertNull(findTag(activity.getWindow().getDecorView(), "near_speech_1"));
                TextView tone = (TextView) findTag(activity.getWindow().getDecorView(), "near_tone");
                assertNotNull(tone);
                assertEquals("附近敌人短音", tone.getText().toString());
                ReminderSampleGrid grid = (ReminderSampleGrid) tone.getParent();
                int wide = UiKit.dp(activity, 320);
                measureGrid(grid, wide);
                if (activity.getResources().getConfiguration().fontScale <= 1.5f)
                    assertEquals("Full sound names can share a row", grid.getChildAt(0).getTop(),
                            grid.getChildAt(1).getTop());
                int wideHeight = grid.getMeasuredHeight();
                assertGridFits(grid);
                measureGrid(grid, UiKit.dp(activity, 180));
                assertTrue("Narrow windows reflow rather than overlap", grid.getMeasuredHeight() >= wideHeight);
                assertGridFits(grid);
                grid.requestLayout();
            });
            screenshot("directory");
        }
        try (ActivityScenario<ReminderGuideActivity> scenario = ActivityScenario.launch(
                intent(ReminderGuideActivity.class).putExtra(ReminderGuideActivity.EXTRA_FULL, true))) {
            scenario.onActivity(activity -> {
                assertNotNull(find(activity.getWindow().getDecorView(), "播放全文"));
                assertNotNull(find(activity.getWindow().getDecorView(), "返回试听列表"));
                assertNull("Reading the full explanation is silent until requested", player(activity));
                ScrollView explanation = firstScrollView(activity.getWindow().getDecorView());
                assertNotNull(explanation);
                explanation.scrollTo(0, explanation.getChildAt(0).getHeight());
                View controls = findTag(activity.getWindow().getDecorView(),
                        "reminder_guide_playback_controls");
                assertNotNull(controls);
                assertFalse("Playback controls stay outside the long scrolling explanation",
                        isDescendant(explanation, controls));
                assertTrue("Playback controls stay visible at the bottom of the explanation",
                        isVisibleInWindow(controls));
            });
            screenshot("full-guide");
        }
    }

    @Test public void completeGuideCanPlayOneSectionWithItsExamplesWithoutStartingAtTheBeginning() {
        try (ActivityScenario<ReminderGuideActivity> scenario = ActivityScenario.launch(
                intent(ReminderGuideActivity.class).putExtra(ReminderGuideActivity.EXTRA_FULL, true))) {
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                List<ReminderGuide.Step> full;
                try {
                    Field field = ReminderGuideActivity.class.getDeclaredField("fullSteps");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked") List<ReminderGuide.Step> value =
                            (List<ReminderGuide.Step>) field.get(activity);
                    full = value;
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                assertNotNull(full);
                List<ReminderGuideSections.Section> sections = ReminderGuideSections.build(full);
                ReminderGuideSections.Section chosen = null;
                for (ReminderGuideSections.Section section : sections) {
                    assertNotNull(find(root, section.title));
                    TextView text = find(root, section.text);
                    assertNotNull(text);
                    assertEquals(Integer.MAX_VALUE, text.getMaxLines());
                    Button play = (Button) findTag(root, section.id);
                    assertNotNull(play);
                    assertEquals("朗读这一段：" + section.title, play.getContentDescription());
                    assertTrue(play.getMinimumHeight() >= UiKit.dp(activity, 56));
                    if (chosen == null && section.startIndex > 0 && section.steps.size() > 1)
                        chosen = section;
                }
                assertNotNull("An enabled section includes its own sound example", chosen);
                ((Button) findTag(root, chosen.id)).performClick();
                try {
                    Field field = ReminderGuideActivity.class.getDeclaredField("steps");
                    field.setAccessible(true);
                    assertEquals("The selected paragraph and attached examples play independently",
                            chosen.steps, field.get(activity));
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                assertNotSame(full.get(0), chosen.steps.get(0));
                ((Button) find(root, "停止播放")).performClick();
                assertNull("Stopping a section releases its output player", player(activity));
            });
        }
    }

    @Test public void tuningUsesOneCombinedReminderAndVibrationTest() {
        try (ActivityScenario<GameTuningActivity> scenario = ActivityScenario.launch(intent(GameTuningActivity.class))) {
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertNotNull(find(root, "测试提醒与振动"));
                assertNull(find(root, "测试提醒"));
                assertNull(find(root, "测试震动"));
                Map<String, ?> before = GameProfile.settings(activity).getAll();
                ((Button) findTag(root, "setting_help_group_button:voice_group")).performClick();
                assertGroupHelpContentsInOrder(findTag(root, "setting_help_panel_view"), "voice_group");
                assertTrue(before.equals(GameProfile.settings(activity).getAll()));
                SettingHelp.close(activity);
            });
            screenshot("combined-test");
        }
    }

    @Test public void settingSpecificHelpPreservesPreferencesAndHasHeading() {
        SharedPreferences preferences = GameProfile.settings(context());
        Map<String, ?> before = preferences.getAll();
        try (ActivityScenario<SettingHelpActivity> scenario = ActivityScenario.launch(
                intent(SettingHelpActivity.class).putExtra(SettingHelp.EXTRA_KEY, "event_near"))) {
            scenario.onActivity(activity -> {
                TextView heading = find(activity.getWindow().getDecorView(), "附近敌人提醒（小地图近区）的说明");
                assertNotNull(heading);
                assertTrue(heading.isAccessibilityHeading());
                TextView back = find(activity.getWindow().getDecorView(), "返回");
                assertNotNull(back);
                TextView body = find(activity.getWindow().getDecorView(), SettingHelpContent.text("event_near"));
                assertNotNull(body);
                TextView readingReference = new TextView(activity);
                readingReference.setTextSize(18);
                TextView titleReference = new TextView(activity);
                titleReference.setTextSize(24);
                assertEquals("Reading obeys the full current system font scale", readingReference.getTextSize(), body.getTextSize(), 0.1f);
                assertEquals(titleReference.getTextSize(), heading.getTextSize(), 0.1f);
                assertTrue(back.getMinimumHeight() >= UiKit.dp(activity, 56));
                assertReadingCopy(activity.getWindow().getDecorView(), SettingHelpContent.text("event_near"));
                assertEquals(Integer.MAX_VALUE, body.getMaxLines());
            });
            screenshot("help");
        }
        assertTrue("Opening complete help does not change settings", before.equals(preferences.getAll()));
    }

    @Test public void widePageIsCenteredAndCanShrinkWithoutStaleWidth() {
        try (ActivityScenario<SettingHelpActivity> scenario = ActivityScenario.launch(
                intent(SettingHelpActivity.class).putExtra(SettingHelp.EXTRA_KEY, "channel_haptic"))) {
            scenario.onActivity(activity -> {
                ViewGroup root = activity.findViewById(android.R.id.content);
                ScrollView scroll = (ScrollView) root.getChildAt(0);
                LinearLayout page = (LinearLayout) scroll.getChildAt(0);
                float density = activity.getResources().getDisplayMetrics().density;
                assertEquals(Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                        ((FrameLayout.LayoutParams) page.getLayoutParams()).gravity);
                int wide = Math.round(1400 * density);
                int height = Math.round(600 * density);
                scroll.measure(View.MeasureSpec.makeMeasureSpec(wide, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                scroll.layout(0, 0, wide, height);
                assertEquals(Math.round(720 * density), page.getMeasuredWidth());
                int availableWide = wide - scroll.getPaddingLeft() - scroll.getPaddingRight();
                assertEquals(scroll.getPaddingLeft() + (availableWide - page.getMeasuredWidth()) / 2,
                        page.getLeft(), 1);
                int narrow = Math.round(320 * density);
                scroll.measure(View.MeasureSpec.makeMeasureSpec(narrow, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                scroll.layout(0, 0, narrow, height);
                assertEquals(narrow - scroll.getPaddingLeft() - scroll.getPaddingRight(), page.getMeasuredWidth());
            });
        }
    }

    @Test public void hapticSettingsDoNotStartPlaybackOnEntry() {
        try (ActivityScenario<HapticSettingsActivity> scenario = ActivityScenario.launch(intent(HapticSettingsActivity.class))) {
            scenario.onActivity(activity -> {
                assertNotNull(find(activity.getWindow().getDecorView(), "试听当前触觉设置"));
                try {
                    Field field = HapticSettingsActivity.class.getDeclaredField("previewPlayer");
                    field.setAccessible(true);
                    assertNull(field.get(activity));
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            screenshot("haptics");
        }
    }

    @Test public void groupSummaryAndItemOrderMatchTheSettingsControls() {
        try (ActivityScenario<AlertSettingsActivity> scenario = ActivityScenario.launch(intent(AlertSettingsActivity.class))) {
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                String key = "events_group";
                TextView heading = find(root, SettingHelpContent.title(key));
                assertNotNull(heading);
                assertTrue(heading.isAccessibilityHeading());

                TextView summary = (TextView) findTag(root,
                        "setting_help_group_summary:" + key);
                assertNotNull(summary);
                assertEquals(SettingHelpContent.summary(key), summary.getText().toString());
                assertEquals(Integer.MAX_VALUE, summary.getMaxLines());

                Button help = (Button) findTag(root, "setting_help_group_button:" + key);
                assertNotNull(help);
                assertTrue(help.isClickable());
                assertEquals("了解更多：" + SettingHelpContent.title(key),
                        help.getContentDescription().toString());
                assertTrue(help.getMinimumHeight() >= UiKit.dp(activity, 56));
                assertTrue(help.getHeight() >= UiKit.dp(activity, 56));

                List<String> expected = Arrays.asList("event_near", "event_near_haptic",
                        "event_far_appear", "event_new_target", "event_edge", "event_danger",
                        "event_player", "event_system");
                assertEquals(expected, SettingHelpContent.itemKeys(key));
                List<TextView> textViews = new ArrayList<>();
                collectTextViews(root, textViews);
                int previous = -1;
                for (String itemKey : expected) {
                    TextView setting = find(root, SettingHelpContent.title(itemKey));
                    assertNotNull("Missing setting named by group help: " + itemKey, setting);
                    int current = textViews.indexOf(setting);
                    assertTrue("Group items follow their visible settings order: " + itemKey,
                            current > previous);
                    previous = current;
                }

                assertNull("Individual controls use the group explanation", find(root,
                        "附近敌人提醒（小地图近区）的说明"));
            });
            screenshot("settings-groups");
        }
    }

    @Test public void groupHelpSwitchesInOnePanelAndBackOrCloseRestoresTheSettingsPage() {
        // Input-focus restoration belongs to keyboard navigation; touch uses immediate clicks.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        final ScrollView[] originalPageRef = new ScrollView[1];
        final Button[] channelTriggerRef = new Button[1];
        final boolean[] originalNearChecked = new boolean[1];
        try (ActivityScenario<AlertSettingsActivity> scenario = ActivityScenario.launch(intent(AlertSettingsActivity.class))) {
            scenario.onActivity(activity -> {
                FrameLayout content = activity.findViewById(android.R.id.content);
                ScrollView originalPage = firstScrollView(content);
                assertNotNull(originalPage);
                originalPageRef[0] = originalPage;
                CheckBox near = (CheckBox) find(content, "附近敌人提醒（小地图近区）");
                assertNotNull(near);
                originalNearChecked[0] = near.isChecked();
                Map<String, ?> before = GameProfile.settings(activity).getAll();

                Button events = (Button) findTag(content, "setting_help_group_button:events_group");
                Button channels = (Button) findTag(content, "setting_help_group_button:channel_group");
                assertNotNull(events);
                assertNotNull(channels);
                channelTriggerRef[0] = channels;
                events.performClick();

                View panel = findTag(content, "setting_help_panel_view");
                assertNotNull(panel);
                assertEquals(View.VISIBLE, panel.getVisibility());
                assertNotNull(findTag(content, "setting_help_panel"));
                assertGroupHelpContentsInOrder(panel, "events_group");
                assertNull("Group help is passive and has no playback action", find(panel, "播放"));
                assertNull("Group help is passive and has no narration action", find(panel, "朗读"));
                assertTrue("Opening group help does not change settings",
                        before.equals(GameProfile.settings(activity).getAll()));
                assertTrue(originalPage.getParent() != null);
                assertEquals(originalNearChecked[0], near.isChecked());

                channels.performClick();
                assertSame("Switching groups updates the active panel", panel,
                        findTag(content, "setting_help_panel_view"));
                assertGroupHelpContentsInOrder(panel, "channel_group");
                assertNull(find(panel, SettingHelpContent.text("events_group")));
                assertTrue("Switching groups does not change settings",
                        before.equals(GameProfile.settings(activity).getAll()));
                assertTrue(originalPage.getParent() != null);
                assertEquals(originalNearChecked[0], near.isChecked());
                assertFalse(activity.isFinishing());
            });

            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                FrameLayout content = activity.findViewById(android.R.id.content);
                ScrollView originalPage = firstScrollView(content);
                assertNotNull(originalPage);
                assertSame("Back restores the original settings ScrollView", originalPageRef[0],
                        originalPage);
                assertFalse("Back closes the panel instead of leaving settings",
                        hasVisiblePanel(content));
                assertTrue("The same settings page remains attached after Back", isDescendant(content,
                        originalPageRef[0]));
                assertNotNull(find(content, SettingHelpContent.title("events_group")));
                Button lastTrigger = channelTriggerRef[0];
                assertSame(lastTrigger, findTag(content, "setting_help_group_button:channel_group"));
                assertTrue("Back returns focus to the last help entry", lastTrigger.hasFocus());
                assertTrue("The last help entry remains visible", isVisibleInWindow(lastTrigger));
                CheckBox near = (CheckBox) find(content, "附近敌人提醒（小地图近区）");
                assertNotNull(near);
                assertEquals(originalNearChecked[0], near.isChecked());

                Map<String, ?> before = GameProfile.settings(activity).getAll();
                Button events = (Button) findTag(content, "setting_help_group_button:events_group");
                events.performClick();
                View panel = findTag(content, "setting_help_panel_view");
                assertNotNull(panel);
                Button close = (Button) findTag(panel, "setting_help_close_button");
                assertNotNull(close);
                assertTrue(close.isClickable());
                assertTrue(close.getMinimumHeight() >= UiKit.dp(activity, 56));
                close.performClick();
                assertTrue("Closing group help does not change settings",
                        before.equals(GameProfile.settings(activity).getAll()));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                FrameLayout content = activity.findViewById(android.R.id.content);
                assertFalse("Close returns to the existing settings page", hasVisiblePanel(content));
                assertSame(originalPageRef[0], firstScrollView(content));
                Button events = (Button) findTag(content, "setting_help_group_button:events_group");
                assertTrue("Close returns focus to the last help entry", events.hasFocus());
                assertTrue("Close leaves the last help entry visible", isVisibleInWindow(events));
                CheckBox near = (CheckBox) find(content, "附近敌人提醒（小地图近区）");
                assertNotNull(near);
                assertEquals(originalNearChecked[0], near.isChecked());
            });
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertTrue("The restored keyboard focus can reopen help", hasVisiblePanel(root));
                assertGroupHelpContentsInOrder(findTag(root, "setting_help_panel_view"), "events_group");
                SettingHelp.close(activity);
            });
            screenshot("settings-groups-restored");
        }
    }

    @Test public void groupHelpUsesBottomSheetOnNarrowAndRightPanelOnWideWindows() {
        final View[] panelRef = new View[1];
        final ScrollView[] originalPageRef = new ScrollView[1];
        try (ActivityScenario<AlertSettingsActivity> scenario = ActivityScenario.launch(intent(AlertSettingsActivity.class))) {
            scenario.onActivity(activity -> {
                FrameLayout content = activity.findViewById(android.R.id.content);
                ScrollView originalPage = firstScrollView(content);
                Button events = (Button) findTag(content, "setting_help_group_button:events_group");
                assertNotNull(originalPage);
                assertNotNull(events);
                originalPageRef[0] = originalPage;
                events.performClick();
                panelRef[0] = findTag(content, "setting_help_panel_view");
                assertNotNull(panelRef[0]);
            });

            // Capture the real narrow-window sheet before applying synthetic test bounds.
            screenshot("help-bottom");
            scenario.onActivity(activity -> {
                FrameLayout content = activity.findViewById(android.R.id.content);
                layoutAt(activity, content, 390, 844);
                View panel = panelRef[0];
                ScrollView originalPage = originalPageRef[0];
                assertSame(panel, findTag(content, "setting_help_panel_view"));
                assertTrue("Narrow panel occupies the lower part of the page",
                        panel.getTop() > 0 && panel.getBottom() <= content.getHeight());
                assertTrue("The settings page remains attached behind the sheet",
                        isDescendant(content, originalPage));
                assertEquals(View.VISIBLE, originalPage.getVisibility());

                layoutAt(activity, content, 1400, 900);
                Button channels = (Button) findTag(content, "setting_help_group_button:channel_group");
                assertNotNull(channels);
                channels.performClick(); // Re-evaluate the active panel against the new content width.
                layoutAt(activity, content, 1400, 900); // Apply LayoutParams changed by show().
                assertSame(panel, findTag(content, "setting_help_panel_view"));
                float density = activity.getResources().getDisplayMetrics().density;
                int densityWidth = Math.round(panel.getMeasuredWidth() / density);
                FrameLayout host = (FrameLayout) findTag(content, "setting_help_panel");
                assertNotNull(host);
                ViewGroup.LayoutParams panelParams = panel.getLayoutParams();
                String geometry = "panelWidthDp=" + densityWidth + ", panelWidthPx=" + panel.getWidth()
                        + ", panelMeasuredWidthPx=" + panel.getMeasuredWidth() + ", paramsWidthPx="
                        + panelParams.width + ", host=" + host.getWidth() + "x" + host.getHeight()
                        + ", density=" + density;
                assertEquals("Panel width settles after remeasurement: " + geometry,
                        panel.getMeasuredWidth(), panel.getWidth());
                assertTrue("Wide help stays within a readable side-panel width: " + geometry,
                        densityWidth >= 360 && densityWidth <= 420);
                assertEquals("Wide panel aligns to its content frame: " + geometry,
                        host.getWidth(), panel.getRight(), UiKit.dp(activity, 2));
                assertTrue("The original settings pane retains a usable width",
                        originalPage.getMeasuredWidth() >= UiKit.dp(activity, 360));
                assertTrue("The settings pane stays beside the help panel",
                        originalPage.getRight() <= panel.getLeft());
                screenshotView(content, "help-wide");
            });
        }
    }

    @Test public void longStandaloneHelpRemainsScrollableWithAFullSizeReturnTarget() {
        try (ActivityScenario<SettingHelpActivity> scenario = ActivityScenario.launch(
                intent(SettingHelpActivity.class).putExtra(SettingHelp.EXTRA_KEY, "voice_group"))) {
            scenario.onActivity(activity -> {
                FrameLayout root = activity.findViewById(android.R.id.content);
                ScrollView scroll = firstScrollView(root);
                assertNotNull(scroll);
                TextView body = find(root, SettingHelpContent.text("voice_group"));
                assertNotNull(body);
                assertEquals(Integer.MAX_VALUE, body.getMaxLines());
                Button back = (Button) find(root, "返回");
                assertNotNull(back);
                assertTrue(back.getMinimumHeight() >= UiKit.dp(activity, 56));
                assertTrue("Long help copy remains available by scrolling",
                        scroll.canScrollVertically(1) || scroll.getChildAt(0).getHeight() > scroll.getHeight());
            });
        }
    }

    @Test public void groupPanelKeepsLongHelpScrollableAtTheSystemFontScale() {
        try (ActivityScenario<AlertSettingsActivity> scenario = ActivityScenario.launch(intent(AlertSettingsActivity.class))) {
            scenario.onActivity(activity -> {
                FrameLayout content = activity.findViewById(android.R.id.content);
                Button events = (Button) findTag(content, "setting_help_group_button:events_group");
                assertNotNull(events);
                events.performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                FrameLayout content = activity.findViewById(android.R.id.content);
                View panel = findTag(content, "setting_help_panel_view");
                assertNotNull(panel);
                ScrollView explanation = firstScrollView(panel);
                assertNotNull(explanation);
                TextView body = find(panel, SettingHelpContent.text("events_group"));
                assertNotNull(body);
                assertEquals(Integer.MAX_VALUE, body.getMaxLines());
                assertGroupHelpContentsInOrder(panel, "events_group");
                assertTrue("The long group explanation and item details remain scrollable",
                        explanation.canScrollVertically(1));
                Button close = (Button) findTag(panel, "setting_help_close_button");
                assertNotNull(close);
                assertTrue(close.getMinimumHeight() >= UiKit.dp(activity, 56));
                assertTrue("The close target is at least 56dp wide",
                        close.getWidth() >= UiKit.dp(activity, 56));
                assertTrue("The close target is at least 56dp tall",
                        close.getHeight() >= UiKit.dp(activity, 56));

                TextView header = find(panel, SettingHelpContent.title("events_group"));
                assertNotNull(header);
                assertTrue(header.isAccessibilityHeading());
                int[] headerBefore = new int[2];
                int[] closeBefore = new int[2];
                header.getLocationOnScreen(headerBefore);
                close.getLocationOnScreen(closeBefore);

                List<String> itemKeys = SettingHelpContent.itemKeys("events_group");
                String lastItemKey = itemKeys.get(itemKeys.size() - 1);
                TextView lastItemBody = lastParagraph(explanation, SettingHelpContent.text(lastItemKey));
                assertNotNull(lastItemBody);
                explanation.scrollTo(0, explanation.getChildAt(0).getMeasuredHeight());
                assertTrue("Scrolling reaches the end of the long group explanation",
                        explanation.canScrollVertically(-1) && !explanation.canScrollVertically(1));
                assertTrue("The last setting detail is visible at the end of the panel",
                        isVisibleInWindow(lastItemBody));
                assertTrue("The close target remains visible while the explanation scrolls",
                        isVisibleInWindow(close));
                int[] headerAfter = new int[2];
                int[] closeAfter = new int[2];
                header.getLocationOnScreen(headerAfter);
                close.getLocationOnScreen(closeAfter);
                assertArrayEquals("The fixed panel heading does not scroll with its body",
                        headerBefore, headerAfter);
                assertArrayEquals("The close target stays in the fixed panel header",
                        closeBefore, closeAfter);
            });
        }
    }

    private static void assertGroupHelpContentsInOrder(View panel, String key) {
        TextView header = find(panel, SettingHelpContent.title(key));
        assertNotNull("Group help keeps its title in the fixed header", header);

        ScrollView explanation = firstScrollView(panel);
        assertNotNull("Group help keeps its copy in a scrollable body", explanation);
        List<String> actual = new ArrayList<>();
        collectLogicalCopy(explanation, actual);

        List<String> expected = new ArrayList<>();
        expected.add(SettingHelpContent.text(key));
        for (String itemKey : SettingHelpContent.itemKeys(key)) {
            expected.add(SettingHelpContent.title(itemKey));
            expected.add(SettingHelpContent.text(itemKey));
        }
        assertEquals("The complete group introduction and ordered item details stay intact: " + key,
                expected, actual);
        assertNull("Group help does not add a second full-explanation navigation link",
                find(panel, "查看完整说明"));
    }

    private static void assertReadingCopy(View root, String copy) {
        View block = findTag(root, "ui_reading:" + copy);
        assertNotNull("Original explanation is still available", block);
        List<TextView> paragraphs = new ArrayList<>();
        collectTextViews(block, paragraphs);
        StringBuilder rendered = new StringBuilder();
        for (TextView paragraph : paragraphs) rendered.append(paragraph.getText());
        assertEquals("Paragraph layout never removes original words", copy.replaceAll("\\s", ""),
                rendered.toString().replaceAll("\\s", ""));
    }

    private static TextView lastParagraph(View root, String copy) {
        ViewGroup block = (ViewGroup) findTag(root, "ui_reading:" + copy);
        assertNotNull(block);
        assertReadingCopy(block, copy);
        return (TextView) block.getChildAt(block.getChildCount() - 1);
    }

    private static void collectLogicalCopy(View view, List<String> output) {
        Object tag = view.getTag();
        if (tag instanceof String && ((String) tag).startsWith("ui_reading:")) {
            String copy = ((String) tag).substring("ui_reading:".length());
            assertReadingCopy(view, copy);
            output.add(copy);
        } else if (view instanceof TextView) output.add(((TextView) view).getText().toString());
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectLogicalCopy(group.getChildAt(i), output);
        }
    }

    private static void layoutAt(Activity activity, View view, int widthDp, int heightDp) {
        int width = UiKit.dp(activity, widthDp);
        int height = UiKit.dp(activity, heightDp);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        // The first layout lets SettingHelpPanel update pane margins and panel LayoutParams;
        // a second forced pass measures those updated bounds before geometry assertions.
        for (int pass = 0; pass < 3; pass++) {
            forceLayoutTree(view);
            view.measure(widthSpec, heightSpec);
            view.layout(0, 0, width, height);
        }
    }

    private static void forceLayoutTree(View view) {
        view.forceLayout();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                forceLayoutTree(group.getChildAt(index));
        }
    }

    private static ScrollView firstScrollView(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
                ScrollView found = firstScrollView(((ViewGroup) view).getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasVisiblePanel(View root) {
        View panel = findTag(root, "setting_help_panel_view");
        return panel != null && panel.getVisibility() == View.VISIBLE && panel.getParent() != null;
    }

    private static boolean isVisibleInWindow(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getWidth() <= 0
                || view.getHeight() <= 0) return false;
        android.graphics.Rect visible = new android.graphics.Rect();
        return view.getGlobalVisibleRect(visible) && visible.width() >= view.getWidth()
                && visible.height() >= view.getHeight();
    }

    private static boolean isDescendant(View root, View candidate) {
        if (root == candidate) return true;
        if (!(root instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) root;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (isDescendant(group.getChildAt(index), candidate)) return true;
        }
        return false;
    }

    private static void collectTextViews(View view, List<TextView> output) {
        if (view instanceof TextView) output.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                collectTextViews(group.getChildAt(index), output);
        }
    }

    private static void screenshot(String name) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        // A resumed activity can be idle before its first frame reaches SurfaceFlinger.
        // This is a UI capture delay, not an app latency measurement.
        android.os.SystemClock.sleep(300);
        Bitmap picture = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(picture);
        File directory = context().getExternalFilesDir("patient-feedback-preview");
        assertNotNull(directory);
        int scale = Math.round(context().getResources().getConfiguration().fontScale * 100);
        try (FileOutputStream file = new FileOutputStream(new File(directory, name + "-font" + scale + ".png"))) {
            assertTrue(picture.compress(Bitmap.CompressFormat.PNG, 100, file));
        } catch (IOException error) { throw new AssertionError(error); }
        finally { picture.recycle(); }
    }

    private static void screenshotView(View view, String name) {
        assertTrue("Synthetic layout must have positive bounds", view.getWidth() > 0 && view.getHeight() > 0);
        Bitmap picture = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(picture));
        File directory = context().getExternalFilesDir("patient-feedback-preview");
        assertNotNull(directory);
        int scale = Math.round(context().getResources().getConfiguration().fontScale * 100);
        try (FileOutputStream file = new FileOutputStream(new File(directory, name + "-font" + scale + ".png"))) {
            assertTrue(picture.compress(Bitmap.CompressFormat.PNG, 100, file));
        } catch (IOException error) { throw new AssertionError(error); }
        finally { picture.recycle(); }
    }

    private static Object player(ReminderGuideActivity activity) {
        try {
            Field field = ReminderGuideActivity.class.getDeclaredField("player");
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void measureGrid(ReminderSampleGrid grid, int width) {
        grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(0, 0, width, grid.getMeasuredHeight());
    }

    private static void assertGridFits(ReminderSampleGrid grid) {
        for (int index = 0; index < grid.getChildCount(); index++) {
            TextView button = (TextView) grid.getChildAt(index);
            assertTrue(button.isClickable());
            assertTrue(button.getWidth() >= UiKit.dp(button.getContext(), 56));
            assertTrue(button.getHeight() >= UiKit.dp(button.getContext(), 56));
            assertTrue(button.getLeft() >= 0);
            assertTrue(button.getRight() <= grid.getWidth());
            assertTrue(button.getBottom() <= grid.getHeight());
            assertNotNull(button.getLayout());
            for (int line = 0; line < button.getLineCount(); line++)
                assertEquals("No label is ellipsized", 0, button.getLayout().getEllipsisCount(line));
            for (int previous = 0; previous < index; previous++) {
                View other = grid.getChildAt(previous);
                assertFalse("Touch targets must not overlap", android.graphics.Rect.intersects(
                        new android.graphics.Rect(button.getLeft(), button.getTop(), button.getRight(), button.getBottom()),
                        new android.graphics.Rect(other.getLeft(), other.getTop(), other.getRight(), other.getBottom())));
            }
        }
    }

    private static View findTag(View view, String tag) {
        if (tag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
                View found = findTag(((ViewGroup) view).getChildAt(index), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView find(View view, String text) {
        if (view instanceof TextView && (text.contentEquals(((TextView) view).getText())
                || text.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))) return (TextView) view;
        if (("ui_reading:" + text).equals(view.getTag()) && view instanceof ViewGroup) {
            assertReadingCopy(view, text);
            return (TextView) ((ViewGroup) view).getChildAt(0);
        }
        if (view instanceof ViewGroup) {
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
                TextView found = find(((ViewGroup) view).getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
