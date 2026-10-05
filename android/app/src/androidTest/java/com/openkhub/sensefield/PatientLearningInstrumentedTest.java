package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
        assertEquals(before, preferences.getAll());
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

    @Test public void explanationIsBesideItsNamedToggleWithoutChangingTheToggle() {
        try (ActivityScenario<AlertSettingsActivity> scenario = ActivityScenario.launch(intent(AlertSettingsActivity.class))) {
            scenario.onActivity(activity -> {
                TextView toggle = find(activity.getWindow().getDecorView(), "附近敌人提醒（小地图近区）");
                assertNotNull(toggle);
                ViewGroup row = (ViewGroup) toggle.getParent();
                View explanation = null;
                for (int index = 0; index < row.getChildCount(); index++) {
                    View candidate = row.getChildAt(index);
                    if ("附近敌人提醒（小地图近区）的说明".contentEquals(
                            candidate.getContentDescription() == null ? "" : candidate.getContentDescription()))
                        explanation = candidate;
                }
                assertNotNull(explanation);
                assertTrue(explanation.isClickable());
                assertTrue(explanation.getMinimumHeight() >= UiKit.dp(activity, 72));
            });
            screenshot("settings");
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
