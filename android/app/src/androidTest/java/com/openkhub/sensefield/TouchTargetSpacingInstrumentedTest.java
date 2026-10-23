package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Spacing between neighbouring touch targets, measured on the real laid-out screens.
 * Low-vision users press with a magnified, imprecise view: two actions must never touch.
 */
@RunWith(AndroidJUnit4.class)
public final class TouchTargetSpacingInstrumentedTest {
    /** Minimum clear space between neighbouring buttons. */
    private static final int MIN_GAP_DP = 12;
    /** Minimum size of a button, beyond the platform's 48dp. */
    private static final int MIN_TARGET_DP = 56;

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test public void runScreenStartAndStopAreClearlySeparated() {
        check(MainActivity.class, "main", activity -> {
            Button start = (Button) find(activity, "开始辅助");
            Button stop = (Button) find(activity, "停止");
            assertNotNull(start);
            assertNotNull(stop);
            int gap = Math.max(horizontalGap(start, stop), verticalGap(start, stop));
            assertTrue("开始/停止 must be at least 20dp apart, was " + px(activity, gap) + "dp",
                    gap >= UiKit.dp(activity, 20));
        });
    }

    @Test public void gameSelectionAndUpdateCard() {
        check(GameSelectionActivity.class, "game-selection", activity -> { });
    }

    @Test public void settingsHub() { check(AppSettingsActivity.class, "settings", activity -> { }); }

    @Test public void tuningTestAndHapticButtonsNoLongerTouch() {
        check(GameTuningActivity.class, "tuning", activity -> {
            Button test = (Button) find(activity, "测试提醒与振动");
            Button haptic = (Button) find(activity, "震感与节奏");
            assertNotNull(test);
            assertNotNull(haptic);
            int gap = verticalGap(test, haptic);
            assertTrue("测试提醒与振动/震感与节奏 gap was " + px(activity, gap) + "dp",
                    gap >= UiKit.dp(activity, MIN_GAP_DP));
        });
    }

    @Test public void permissions() { check(CapturePermissionsActivity.class, "permissions", activity -> { }); }

    @Test public void alertPreferences() { check(AlertSettingsActivity.class, "alerts", activity -> { }); }

    @Test public void haptics() { check(HapticSettingsActivity.class, "haptics", activity -> { }); }

    @Test public void cueSoundChoicesDoNotTouch() {
        check(CueSoundSettingsActivity.class, "cue-sounds", activity -> { });
    }

    @Test public void diagnosticsSeparatesDeleteFromExport() {
        check(DiagnosticsActivity.class, "diagnostics", activity -> {
            Button save = (Button) find(activity, "保存诊断包到文件");
            Button delete = (Button) find(activity, "删除本地测试记录");
            assertNotNull(save);
            assertNotNull(delete);
            assertTrue("The destructive action is set apart from export",
                    verticalGap(save, delete) >= UiKit.dp(activity, 24));
        });
    }

    @Test public void assistantSettingsWithOptionsShown() {
        SharedPreferences prefs = GameProfile.settings(context());
        boolean had = prefs.contains(AssistantSettings.ENABLED);
        boolean was = prefs.getBoolean(AssistantSettings.ENABLED, false);
        prefs.edit().putBoolean(AssistantSettings.ENABLED, true).commit();
        try {
            check(AssistantSettingsActivity.class, "assistant", activity -> { });
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (had) edit.putBoolean(AssistantSettings.ENABLED, was);
            else edit.remove(AssistantSettings.ENABLED);
            edit.commit();
        }
    }

    @Test public void reminderDirectory() { check(ReminderGuideActivity.class, "directory", activity -> { }); }

    @Test public void match3HasLabelledBoxedFields() {
        check(Match3AssistActivity.class, "match3", activity -> {
            List<View> fields = new ArrayList<>();
            collect(activity.findViewById(android.R.id.content), fields,
                    view -> view instanceof EditText || view instanceof Spinner);
            assertFalse(fields.isEmpty());
            for (View field : fields) {
                assertNotNull("Every field has a box", field.getBackground());
                assertTrue("Every field is a large target",
                        field.getHeight() >= UiKit.dp(activity, MIN_TARGET_DP));
                assertNotNull("Every field has a persistent label: " + field,
                        labelFor(activity.findViewById(android.R.id.content), field));
            }
        });
    }

    @Test public void judgmentSelfTest() { check(JudgmentSelfTestActivity.class, "judgment", activity -> { }); }

    private interface Extra { void run(Activity activity); }

    private static <T extends Activity> void check(Class<T> type, String name, Extra extra) {
        try (ActivityScenario<T> scenario = ActivityScenario.launch(new Intent(context(), type))) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                List<View> targets = new ArrayList<>();
                collect(root, targets, TouchTargetSpacingInstrumentedTest::isButtonTarget);
                assertFalse(name + " has buttons", targets.isEmpty());
                List<String> problems = new ArrayList<>();
                int minGap = UiKit.dp(activity, MIN_GAP_DP);
                int minSize = UiKit.dp(activity, MIN_TARGET_DP);
                for (View target : targets) {
                    if (target.getHeight() < minSize || target.getWidth() < minSize) {
                        problems.add(label(target) + " is " + px(activity, target.getWidth()) + "×"
                                + px(activity, target.getHeight()) + "dp");
                    }
                }
                for (int i = 0; i < targets.size(); i++) {
                    for (int j = i + 1; j < targets.size(); j++) {
                        View a = targets.get(i), b = targets.get(j);
                        Rect ra = bounds(a), rb = bounds(b);
                        boolean sameColumn = ra.left < rb.right && rb.left < ra.right;
                        boolean sameRow = ra.top < rb.bottom && rb.top < ra.bottom;
                        if (sameRow && sameColumn) {
                            problems.add(label(a) + " overlaps " + label(b));
                        } else if (sameColumn) {
                            int gap = Math.max(rb.top - ra.bottom, ra.top - rb.bottom);
                            if (gap < minGap) problems.add(label(a) + " / " + label(b)
                                    + " vertical gap " + px(activity, gap) + "dp");
                        } else if (sameRow) {
                            int gap = Math.max(rb.left - ra.right, ra.left - rb.right);
                            if (gap < minGap) problems.add(label(a) + " / " + label(b)
                                    + " horizontal gap " + px(activity, gap) + "dp");
                        }
                    }
                }
                assertTrue(name + ": " + problems, problems.isEmpty());
                extra.run(activity);
            });
            screenshotWholePage(scenario, name);
        }
    }

    /** Buttons and button-like cards. Checkboxes and radio rows are list items, not buttons. */
    private static boolean isButtonTarget(View view) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0 || !view.isShown()) {
            return false;
        }
        if (view instanceof CompoundButton) return false;
        if (view instanceof Button) return true;
        return view.isClickable() && view instanceof LinearLayout && view.getBackground() != null;
    }

    private interface Filter { boolean accept(View view); }

    private static void collect(View view, List<View> out, Filter filter) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        if (filter.accept(view)) { out.add(view); return; }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out, filter);
        }
    }

    private static TextView labelFor(View root, View field) {
        if (root instanceof TextView && ((TextView) root).getLabelFor() == field.getId()) {
            return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = labelFor(group.getChildAt(i), field);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Rect bounds(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    private static int verticalGap(View a, View b) {
        Rect ra = bounds(a), rb = bounds(b);
        return Math.max(rb.top - ra.bottom, ra.top - rb.bottom);
    }

    private static int horizontalGap(View a, View b) {
        Rect ra = bounds(a), rb = bounds(b);
        return Math.max(rb.left - ra.right, ra.left - rb.right);
    }

    private static String label(View view) {
        if (view instanceof TextView) return "「" + ((TextView) view).getText() + "」";
        CharSequence description = view.getContentDescription();
        return description != null ? "「" + description + "」" : view.getClass().getSimpleName();
    }

    private static int px(Activity activity, int pixels) {
        return Math.round(pixels / activity.getResources().getDisplayMetrics().density);
    }

    private static View find(Activity activity, String text) {
        List<View> found = new ArrayList<>();
        collect(activity.findViewById(android.R.id.content), found,
                view -> view instanceof TextView && text.contentEquals(((TextView) view).getText()));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Draws the whole scrolling page, not only the first screen, for visual review. */
    private static <T extends Activity> void screenshotWholePage(ActivityScenario<T> scenario,
                                                                 String name) {
        scenario.onActivity(activity -> {
            View content = activity.findViewById(android.R.id.content);
            ScrollView scroll = firstScroll(content);
            View page = scroll != null ? scroll.getChildAt(0) : content;
            int height = Math.max(1, page.getHeight());
            int width = Math.max(1, page.getWidth());
            Bitmap picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(picture);
            canvas.drawColor(UiKit.PAGE);
            page.draw(canvas);
            File directory = context().getExternalFilesDir("touch-spacing-preview");
            assertNotNull(directory);
            int scale = Math.round(context().getResources().getConfiguration().fontScale * 100);
            try (FileOutputStream file = new FileOutputStream(
                    new File(directory, name + "-font" + scale + ".png"))) {
                assertTrue(picture.compress(Bitmap.CompressFormat.PNG, 100, file));
            } catch (IOException error) {
                throw new AssertionError(error);
            } finally {
                picture.recycle();
            }
        });
    }

    private static ScrollView firstScroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = firstScroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
