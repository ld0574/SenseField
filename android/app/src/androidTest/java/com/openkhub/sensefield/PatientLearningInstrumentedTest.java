package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.KeyEvent;
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
                assertEquals("左上", ((TextView) direction).getText().toString());
                ReminderSampleGrid grid = (ReminderSampleGrid) direction.getParent();
                int wide = UiKit.dp(activity, 320);
                measureGrid(grid, wide);
                assertEquals("Short options share a row", grid.getChildAt(0).getTop(),
                        grid.getChildAt(1).getTop());
                int wideHeight = grid.getMeasuredHeight();
                assertGridFits(grid);
                measureGrid(grid, UiKit.dp(activity, 180));
                assertTrue("Narrow windows reflow rather than overlap", grid.getMeasuredHeight() > wideHeight);
                assertGridFits(grid);
                grid.requestLayout();
            });
            screenshot("directory");
        }
        try (ActivityScenario<ReminderGuideActivity> scenario = ActivityScenario.launch(
                intent(ReminderGuideActivity.class).putExtra(ReminderGuideActivity.EXTRA_FULL, true))) {
            scenario.onActivity(activity -> {
                assertNotNull(find(activity.getWindow().getDecorView(), "朗读完整说明"));
                assertNotNull(find(activity.getWindow().getDecorView(), "返回试听列表"));
                assertNull("Reading the full explanation is silent until requested", player(activity));
            });
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
                Configuration reading = new Configuration(activity.getResources().getConfiguration());
                reading.fontScale = Math.min(reading.fontScale, 1.5f);
                TextView readingReference = new TextView(activity.createConfigurationContext(reading));
                readingReference.setTextSize(22);
                TextView controlReference = new TextView(activity);
                controlReference.setTextSize(22);
                assertEquals(readingReference.getTextSize(), body.getTextSize(), 0.1f);
                assertEquals(controlReference.getTextSize(), heading.getTextSize(), 0.1f);
                assertEquals(controlReference.getTextSize(), back.getTextSize(), 0.1f);
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
                assertNotNull(find(panel, SettingHelpContent.text("events_group")));
                assertNull("Group help is passive and has no playback action", find(panel, "播放"));
                assertNull("Group help is passive and has no narration action", find(panel, "朗读"));
                assertTrue("Opening group help does not change settings",
                        before.equals(GameProfile.settings(activity).getAll()));
                assertTrue(originalPage.getParent() != null);
                assertEquals(originalNearChecked[0], near.isChecked());

                channels.performClick();
                assertSame("Switching groups updates the active panel", panel,
                        findTag(content, "setting_help_panel_view"));
                assertNotNull(find(panel, SettingHelpContent.text("channel_group")));
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
                assertTrue(back.getMinimumHeight() >= UiKit.dp(activity, 72));
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
                assertTrue("The long group explanation and item details remain scrollable",
                        explanation.canScrollVertically(1));
                Button close = (Button) findTag(panel, "setting_help_close_button");
                assertNotNull(close);
                assertTrue(close.getMinimumHeight() >= UiKit.dp(activity, 56));
            });
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
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
                TextView found = find(((ViewGroup) view).getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
