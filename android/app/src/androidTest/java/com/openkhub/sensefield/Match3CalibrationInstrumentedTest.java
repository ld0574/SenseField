package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Screenshot correction is local and must not silently change the live board candidate. */
@RunWith(AndroidJUnit4.class)
public final class Match3CalibrationInstrumentedTest {
    private Map<String, ?> previous;
    private File image;
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static SharedPreferences prefs() { return GameProfile.settings(context()); }
    private static boolean calibrationKey(String key) {
        return key.startsWith("match3_manual_") || key.equals("match3_calibrated")
                || key.equals("match3_l") || key.equals("match3_t") || key.equals("match3_r")
                || key.equals("match3_b") || key.equals("match3_rows") || key.equals("match3_cols");
    }
    private static Map<String, Object> calibrationValues() {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet())
            if (calibrationKey(entry.getKey())) result.put(entry.getKey(), entry.getValue());
        return result;
    }
    @Before public void prepare() throws Exception {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true);
        previous = new HashMap<>(prefs().getAll());
        SharedPreferences.Editor edit = prefs().edit().putBoolean("app_update_auto_check", false);
        for (String key : previous.keySet()) if (calibrationKey(key)) edit.remove(key);
        edit.commit();
        Bitmap bitmap = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff00ccee);
        for (int y = 280; y < 640; y++) for (int x = 24; x < 384; x++) {
            int xx = (x - 24) % 40, yy = (y - 280) % 40;
            bitmap.setPixel(x, y, xx < 3 || yy < 3 || xx >= 37 || yy >= 37 ? 0xff203050 : 0xffdd9944);
        }
        image = new File(context().getCacheDir(), "calibration-test.png");
        try (FileOutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
    }
    @After public void restore() {
        SharedPreferences.Editor edit = prefs().edit();
        for (String key : prefs().getAll().keySet())
            if (calibrationKey(key) || key.equals("app_update_auto_check")) edit.remove(key);
        for (Map.Entry<String, ?> entry : previous.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (!calibrationKey(key) && !key.equals("app_update_auto_check")) continue;
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof String) edit.putString(key, (String) value);
        }
        edit.commit();
        if (image != null) image.delete();
    }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void load(Match3ToolsActivity activity) {
        try {
            Method method = Match3ToolsActivity.class.getDeclaredMethod("loadScreenshot", Uri.class, boolean.class);
            method.setAccessible(true); method.invoke(activity, Uri.fromFile(image), false);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void tap(ImageView preview, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try { assertTrue("The screenshot must consume the pointer sequence", preview.dispatchTouchEvent(event)); }
        finally { event.recycle(); }
    }
    private static void imageTap(ImageView preview, float x, float y) {
        float[] point = {x, y}; preview.getImageMatrix().mapPoints(point);
        point[0] += preview.getPaddingLeft(); point[1] += preview.getPaddingTop();
        tap(preview, MotionEvent.ACTION_DOWN, point[0], point[1]);
        tap(preview, MotionEvent.ACTION_UP, point[0], point[1]);
    }
    private static ImageView layoutPreview(Match3ToolsActivity activity) {
        ImageView preview = (ImageView) field(activity, "preview");
        preview.setPadding(18, 24, 18, 24);
        preview.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        preview.layout(0, 0, 300, 400);
        assertEquals(((Bitmap) field(activity, "screenshot")).getWidth(), preview.getDrawable().getIntrinsicWidth());
        return preview;
    }
    private static void button(Match3ToolsActivity activity, String tag) {
        View action = activity.findViewById(android.R.id.content).findViewWithTag(tag);
        assertNotNull(action); assertTrue(action.isEnabled()); action.performClick();
    }
    private static void assertEdges(BoardGeometry g) {
        assertNotNull(g); assertEquals(25, g.left); assertEquals(281, g.top);
        assertEquals(385, g.right); assertEquals(641, g.bottom);
    }
    private static void settle() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(100, 3000);
    }
    private static ScrollView scroll(Match3ToolsActivity activity) {
        ScrollView scroll = (ScrollView) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS); scroll.clearFocus();
        scroll.setFocusableInTouchMode(true); scroll.requestFocus();
        return scroll;
    }
    private static void entirelyVisible(View view) {
        android.graphics.Rect visible = new android.graphics.Rect();
        assertTrue(view.getGlobalVisibleRect(visible));
        assertEquals(view.getHeight(), visible.height()); assertEquals(view.getWidth(), visible.width());
    }
    private static View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = find(((ViewGroup) root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    @Test public void automaticScreenshotPositioningDoesNotSaveALiveCalibration() {
        try (ActivityScenario<Match3ToolsActivity> screen = ActivityScenario.launch(
                new Intent(context(), Match3ToolsActivity.class))) {
            screen.onActivity(activity -> {
                Map<String, Object> before = calibrationValues();
                load(activity);
                assertNotNull("The synthetic screenshot has an independently detected grid", field(activity, "screenshotGeometry"));
                assertEquals("Loading or reading a screenshot must leave live settings alone", before, calibrationValues());
            });
        }
        Bitmap flat = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888); flat.eraseColor(0xff203050);
        try (FileOutputStream out = new FileOutputStream(image)) { flat.compress(Bitmap.CompressFormat.PNG, 100, out); }
        catch (Exception e) { throw new AssertionError(e); }
        finally { flat.recycle(); }
        try (ActivityScenario<Match3ToolsActivity> screen = ActivityScenario.launch(new Intent(context(), Match3ToolsActivity.class))) {
            screen.onActivity(activity -> {
                load(activity); assertNull(field(activity, "screenshotGeometry"));
                button(activity, "match3_save_calibration");
                find(activity.findViewById(android.R.id.content), "采样截图并播报可消除位置").performClick();
                assertTrue("An unconfirmed screenshot cannot save a default board", calibrationValues().isEmpty());
                assertNull(field(activity, "screenshotGeometry"));
            });
            screen.recreate(); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> { assertNull(field(activity, "screenshotGeometry")); assertTrue(calibrationValues().isEmpty()); });
        }
    }
    @Test public void previewTapConsumesDownAndReadsTheCellThroughItsActualImageMatrix() {
        try (ActivityScenario<Match3ToolsActivity> screen = ActivityScenario.launch(
                new Intent(context(), Match3ToolsActivity.class))) {
            screen.onActivity(this::load);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                ImageView preview = layoutPreview(activity);
                BoardGeometry g = (BoardGeometry) field(activity, "screenshotGeometry");
                float[] point = {g.centerX(7), g.centerY(1)};
                preview.getImageMatrix().mapPoints(point); point[0] += preview.getPaddingLeft(); point[1] += preview.getPaddingTop();
                tap(preview, MotionEvent.ACTION_DOWN, point[0], point[1]);
                tap(preview, MotionEvent.ACTION_UP, point[0], point[1]);
                assertTrue(((TextView) field(activity, "output")).getText().toString().startsWith("第 2 行，第 8 列："));
                assertTrue(calibrationValues().isEmpty());
            });
        }
    }
    @Test public void twoReverseCornersRemainADraftAcrossGridChangesAndRecreationUntilExplicitSave() {
        try (ActivityScenario<Match3ToolsActivity> screen = ActivityScenario.launch(new Intent(context(), Match3ToolsActivity.class))) {
            screen.onActivity(this::load); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                find(activity.findViewById(android.R.id.content), "手动棋盘校准").performClick();
                ImageView preview = layoutPreview(activity);
                button(activity, "match3_select_board");
                imageTap(preview, 385, 641);
                button(activity, "match3_save_calibration");
                assertTrue("Incomplete selection cannot be saved", calibrationValues().isEmpty());
                imageTap(preview, 25, 281);
                assertEdges((BoardGeometry) field(activity, "screenshotGeometry"));
                ((Spinner) field(activity, "colsSpin")).setSelection(2);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                BoardGeometry g = (BoardGeometry) field(activity, "screenshotGeometry");
                assertEdges(g); assertEquals(8, g.cols); assertEquals(9, g.rows);
                assertTrue(calibrationValues().isEmpty());
            });
            screen.recreate(); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                assertEdges((BoardGeometry) field(activity, "screenshotGeometry"));
                assertTrue(calibrationValues().isEmpty());
                button(activity, "match3_save_calibration");
                BoardGeometry saved = Match3Calibration.manual(prefs()); assertEdges(saved);
                assertEquals(8, saved.cols); assertEquals(9, saved.rows);
                assertEquals(75, Match3Calibration.candidateForFrame(prefs(), 1296, 2880).left);
                assertNull("Changed aspect ratio must not stretch the saved screenshot", Match3Calibration.candidateForFrame(prefs(), 432, 850));
            });
        }
    }
    @Test public void dragSelectionUsesTheActualFitCenterTransformAndBlankMarginsCannotBeTapped() {
        try (ActivityScenario<Match3ToolsActivity> screen = ActivityScenario.launch(new Intent(context(), Match3ToolsActivity.class))) {
            screen.onActivity(this::load); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                ImageView preview = layoutPreview(activity);
                long now = SystemClock.uptimeMillis();
                MotionEvent margin = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1, 1, 0);
                try { assertFalse(preview.dispatchTouchEvent(margin)); } finally { margin.recycle(); }
                button(activity, "match3_select_board");
                float[] points = {25, 281, 385, 641}; preview.getImageMatrix().mapPoints(points);
                float x = points[0] + preview.getPaddingLeft(), y = points[1] + preview.getPaddingTop();
                float endX = points[2] + preview.getPaddingLeft(), endY = points[3] + preview.getPaddingTop();
                tap(preview, MotionEvent.ACTION_DOWN, x, y);
                tap(preview, MotionEvent.ACTION_MOVE, endX, endY);
                tap(preview, MotionEvent.ACTION_UP, endX, endY);
                assertEdges((BoardGeometry) field(activity, "screenshotGeometry"));
                assertFalse(((Match3BoardPreview) preview).isSelecting());
                assertTrue(calibrationValues().isEmpty());
                ((EditText) field(activity, "leftIn")).setText("10");
                BoardGeometry edited = (BoardGeometry) field(activity, "screenshotGeometry");
                assertEquals(43, edited.left); assertEquals(281, edited.top);
                assertEquals(385, edited.right); assertEquals(641, edited.bottom);
            });
        }
    }
    @Test public void invalidNumbersTinyBoxesAndCancellationNeverSaveDefaultBounds() {
        try (ActivityScenario<Match3ToolsActivity> screen = ActivityScenario.launch(new Intent(context(), Match3ToolsActivity.class))) {
            screen.onActivity(this::load); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                ImageView preview = layoutPreview(activity);
                button(activity, "match3_select_board"); imageTap(preview, 25, 281); imageTap(preview, 26, 282);
                assertTrue(((Match3BoardPreview) preview).isSelecting());
                button(activity, "match3_select_board"); assertFalse(((Match3BoardPreview) preview).isSelecting());
                for (String value : new String[]{"", "-1", "101", "12.5", "99"}) {
                    ((EditText) field(activity, "leftIn")).setText(value);
                    button(activity, "match3_save_calibration");
                    assertTrue("Invalid input " + value + " must not be silently clamped or replaced", calibrationValues().isEmpty());
                }
            });
        }
    }
    @Test public void restoringAutomaticDetectionClearsBothCandidatesAndKeepsOtherSettings() {
        String highlight = "match3_hint_highlight_enabled";
        boolean hadHighlight = prefs().contains(highlight), oldHighlight = prefs().getBoolean(highlight, true);
        prefs().edit().putBoolean(highlight, false).commit();
        try (ActivityScenario<Match3ToolsActivity> screen = ActivityScenario.launch(new Intent(context(), Match3ToolsActivity.class))) {
            screen.onActivity(this::load); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                button(activity, "match3_save_calibration"); assertNotNull(Match3Calibration.manual(prefs()));
                // A later live automatic observation may update the legacy cache, not the explicit pixel rectangle.
                prefs().edit().putInt("match3_l", 40).putInt("match3_rows", 6).commit();
                assertEquals(24, Match3Calibration.candidateForFrame(prefs(), 432, 960).left);
                button(activity, "match3_reset_calibration");
                assertTrue(calibrationValues().isEmpty());
                assertNull(Match3Calibration.candidateForFrame(prefs(), 432, 960));
                assertFalse(prefs().getBoolean(highlight, true));
                assertNotNull("The screenshot may still be tried with an automatic draft", field(activity, "screenshotGeometry"));
            });
        } finally {
            SharedPreferences.Editor edit = prefs().edit();
            if (hadHighlight) edit.putBoolean(highlight, oldHighlight); else edit.remove(highlight);
            edit.commit();
        }
    }
    @Test public void savedRectangleStillRequiresCurrentGridEvidenceAndLegacyCandidatesRemainReadable() {
        BoardGeometry geometry = new BoardGeometry(432, 960, 24, 280, 384, 640, 9, 9);
        Match3Calibration.saveManual(prefs(), geometry);
        Bitmap flat = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888); flat.eraseColor(0xff203050);
        try {
            BoardGeometry candidate = Match3Calibration.candidateForFrame(prefs(), 432, 960);
            assertNull("Stored geometry cannot authorize a flat panel", Match3Sampler.autoDetectGeometry(flat,
                    new int[]{candidate.left, candidate.top, candidate.right, candidate.bottom}));
        } finally { flat.recycle(); }
        Match3Calibration.clear(prefs());
        prefs().edit().putBoolean("match3_calibrated", true).putInt("match3_l", 5).putInt("match3_t", 29)
                .putInt("match3_r", 89).putInt("match3_b", 67).putInt("match3_rows", 9).putInt("match3_cols", 9).commit();
        assertNotNull(Match3Calibration.candidateForFrame(prefs(), 432, 960));
        prefs().edit().putInt("match3_r", 2).commit();
        assertNull(Match3Calibration.candidateForFrame(prefs(), 432, 960));
    }
    @Test public void screenshotCalibrationControlsAndGridFitTheActualWindow() throws Exception {
        try (ActivityScenario<Match3ToolsActivity> screen = ActivityScenario.launch(new Intent(context(), Match3ToolsActivity.class))) {
            screen.onActivity(activity -> {
                load(activity);
                View root = activity.findViewById(android.R.id.content);
                find(root, "手动棋盘校准").performClick();
                find(root, "说明书与截图点读").performClick();
                assertNotNull(find(root, "选取区域并试读（点两个对角格）"));
                assertNull(find(root, "框选识别（点我后在预览上点左上角和右下角）"));
                for (String tag : new String[]{"match3_select_board", "match3_save_calibration", "match3_reset_calibration"}) {
                    View action = root.findViewWithTag(tag);
                    assertNotNull(action); assertTrue(action.isEnabled());
                }
            });
            settle();
            screen.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                UiQualityInstrumentedTest.assertLayout(root, "match3-calibration");
                scroll(activity);
                View save = root.findViewWithTag("match3_save_calibration");
                save.requestRectangleOnScreen(new android.graphics.Rect(0, 0, save.getWidth(), save.getHeight()), true);
            });
            settle();
            screen.onActivity(activity -> entirelyVisible(activity.findViewById(android.R.id.content).findViewWithTag("match3_save_calibration")));
            UiQualityInstrumentedTest.screenshot("match3-calibration-controls");
            screen.onActivity(activity -> {
                ImageView preview = (ImageView) field(activity, "preview");
                scroll(activity).scrollTo(0, preview.getTop());
                assertTrue(preview.getContentDescription().toString().contains("9 行 9 列"));
                assertTrue(calibrationValues().isEmpty());
            });
            settle();
            screen.onActivity(activity -> entirelyVisible((ImageView) field(activity, "preview")));
            UiQualityInstrumentedTest.screenshot("match3-calibration-grid");
        }
    }
}
