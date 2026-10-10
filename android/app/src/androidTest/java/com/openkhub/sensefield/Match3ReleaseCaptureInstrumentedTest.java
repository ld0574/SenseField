package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** A real MediaProjection on the signed minified APK; the displayed game is explicitly synthetic. */
@RunWith(AndroidJUnit4.class)
public final class Match3ReleaseCaptureInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    private static void click(View root, String text) {
        if (root instanceof Button && text.contentEquals(((Button) root).getText())) { root.performClick(); return; }
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            click(((ViewGroup) root).getChildAt(i), text);
    }

    private static boolean consent(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String text = String.valueOf(node.getText());
        if (text.equalsIgnoreCase("Start now") || text.equalsIgnoreCase("Start recording")
                || text.equals("立即开始") || text.equals("开始录制"))
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        for (int i = 0; i < node.getChildCount(); i++) if (consent(node.getChild(i))) return true;
        return false;
    }

    private static JSONObject state() throws Exception {
        DiagnosticRecorder recorder = DiagnosticRecorder.current;
        return recorder == null ? new JSONObject() : new JSONObject(recorder.stateForDiagnostics());
    }

    private static final class Board extends View {
        final AtomicInteger touches = new AtomicInteger();
        private final Paint paint = new Paint();
        private final Match3TestSprites sprites;
        private int tick;
        boolean changed, popup, cascade;
        int rows = 7, cols = 7;
        Board(Context context) {
            super(context);
            sprites=new Match3TestSprites(context);
            setOnTouchListener((v, event) -> { touches.incrementAndGet(); return true; });
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xff32c8f0);
            float cell = Math.min(getWidth(), getHeight()) * .7f / Math.max(rows, cols);
            float left = (getWidth() - cell * cols) / 2, top = getHeight() * .3f;
            paint.setColor(0xff1e2a58); canvas.drawRect(left, top, left + cols * cell, top + rows * cell, paint);
            int[] colors = {0xffefb630, 0xffeb5520, 0xff44d832, 0xff55a9ed, 0xffb15edb, 0xffc78030};
            for (int row = 0; row < rows; row++) for (int col = 0; col < cols; col++) {
                int sourceRow = changed ? rows - 1 - row : row, sourceCol = changed ? cols - 1 - col : col;
                int index = (sourceRow + sourceCol * 2) % colors.length;
                if (sourceRow == 0 && sourceCol < 3) index = sourceCol == 1 ? 1 : 0;
                if (sourceRow == 1 && sourceCol == 1) index = 0;
                if (cascade && row == 2 && col >= 1 && col <= 3) index = 0;
                sprites.draw(canvas,left+col*cell,top+row*cell,cell,"YRGBPO".charAt(index));
            }
            if (popup) {
                paint.setColor(0xfff8f8f8);
                canvas.drawRect(getWidth() * .15f, getHeight() * .3f, getWidth() * .85f, getHeight() * .7f, paint);
            }
            // Produces new frames, never an animated hint or an altered chessboard.
            paint.setColor((tick++ & 1) == 0 ? 0xffeeeeee : 0xffdddddd);
            canvas.drawRect(0, 0, 10, 10, paint); postInvalidateDelayed(150);
        }
    }

    private static JSONObject awaitState(java.util.function.Predicate<JSONObject> condition, long budgetMs) throws Exception {
        long until = SystemClock.elapsedRealtime() + budgetMs; JSONObject latest;
        do {
            latest = state(); if (condition.test(latest)) return latest;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < until);
        fail("Expected capture state before deadline; actual=" + latest);
        return latest;
    }

    @Test public void actualCaptureClearsOldHintsAcrossBoardsPopupsCascadesAndNewGeometry() throws Exception {
        assertTrue(android.provider.Settings.canDrawOverlays(context()));
        android.content.SharedPreferences prefs = GameProfile.settings(context());
        boolean hadHighlight = prefs.contains("match3_hint_highlight_enabled");
        boolean oldHighlight = prefs.getBoolean("match3_hint_highlight_enabled", true);
        prefs.edit().putBoolean("match3_hint_highlight_enabled", true).commit();
        Board[] board = new Board[1];
        org.json.JSONArray checkpoints = new org.json.JSONArray();
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(Match3AssistActivity.class)) {
            scenario.onActivity(a -> click(a.getWindow().getDecorView(), "开始辅助"));
            long until = SystemClock.elapsedRealtime() + 10000;
            while (!Match3LiveService.isRunning() && SystemClock.elapsedRealtime() < until) {
                consent(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
                SystemClock.sleep(100);
            }
            assertTrue(Match3LiveService.isRunning());
            DiagnosticRecorder.current.audit("Match3SyntheticValidation source=audit actual_game=false acoustic_evidence=false");
            scenario.onActivity(a -> { board[0] = new Board(a); a.setContentView(board[0]); });
            JSONObject initial = awaitState(s -> s.optBoolean("highlight_visible") && s.optBoolean("board_valid"), 12000);
            checkpoints.put(initial); long oldRevision = initial.getLong("board_revision");
            scenario.onActivity(a -> { board[0].changed = true; board[0].invalidate(); });
            JSONObject cleared = awaitState(s -> s.optLong("processed_frames") > initial.optLong("processed_frames")
                    && !s.optBoolean("highlight_visible"), 4000);
            checkpoints.put(cleared);
            JSONObject changed = awaitState(s -> s.optLong("board_revision") > oldRevision && s.optBoolean("highlight_visible"), 12000);
            checkpoints.put(changed);
            assertFalse("A changed board cannot keep the previous pair", initial.getJSONObject("hint").getInt("from_row")
                    == changed.getJSONObject("hint").getInt("from_row") && initial.getJSONObject("hint").getInt("from_col")
                    == changed.getJSONObject("hint").getInt("from_col"));
            scenario.onActivity(a -> { board[0].popup = true; board[0].invalidate(); });
            JSONObject popup = awaitState(s -> "popup".equals(s.optString("state")) && !s.optBoolean("highlight_visible"), 4000);
            checkpoints.put(popup); assertFalse(popup.getBoolean("board_valid"));
            scenario.onActivity(a -> { board[0].popup = false; board[0].invalidate(); });
            checkpoints.put(awaitState(s -> s.optBoolean("highlight_visible") && s.optBoolean("board_valid"), 12000));
            scenario.onActivity(a -> { board[0].cascade = true; board[0].invalidate(); });
            JSONObject resolving = awaitState(s -> !s.optBoolean("highlight_visible"), 4000);
            JSONObject stillResolving = awaitState(s -> s.optLong("processed_frames") >= resolving.optLong("processed_frames") + 5, 8000);
            assertFalse("No exchange is suggested before elimination resolves", stillResolving.getBoolean("board_valid"));
            assertFalse(stillResolving.getBoolean("highlight_visible")); checkpoints.put(stillResolving);
            scenario.onActivity(a -> { board[0].cascade = false; board[0].rows = 8; board[0].cols = 6; board[0].invalidate(); });
            JSONObject resized = awaitState(s -> s.optInt("rows") == 8 && s.optInt("cols") == 6
                    && s.optBoolean("board_valid") && s.optBoolean("highlight_visible"), 12000);
            assertFalse(resized.getBoolean("highlight_suppressed")); checkpoints.put(resized);
            File directory = context().getExternalFilesDir("match3-release-capture");
            assertNotNull(directory); directory.mkdirs();
            try (FileOutputStream output = new FileOutputStream(new File(directory, "audit-transitions.json"))) {
                output.write(new JSONObject().put("source", "synthetic_actual_media_projection")
                        .put("acoustic_evidence", false).put("checkpoints", checkpoints).toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            context().stopService(new Intent(context(), Match3LiveService.class));
            android.content.SharedPreferences.Editor editor = prefs.edit();
            if (hadHighlight) editor.putBoolean("match3_hint_highlight_enabled", oldHighlight); else editor.remove("match3_hint_highlight_enabled");
            editor.commit();
        }
    }

    @Test public void signedReleaseRetainsAHintThroughTenActualCaptureSamples() throws Exception {
        assertTrue("The pre-authorized overlay permission is required", android.provider.Settings.canDrawOverlays(context()));
        android.content.SharedPreferences prefs = GameProfile.settings(context());
        boolean hadHighlight = prefs.contains("match3_hint_highlight_enabled");
        boolean oldHighlight = prefs.getBoolean("match3_hint_highlight_enabled", true);
        prefs.edit().putBoolean("match3_hint_highlight_enabled", true).commit();
        Board[] board = new Board[1];
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(Match3AssistActivity.class)) {
            scenario.onActivity(a -> click(a.getWindow().getDecorView(), "开始辅助"));
            long until = SystemClock.elapsedRealtime() + 10000;
            while (!Match3LiveService.isRunning() && SystemClock.elapsedRealtime() < until) {
                consent(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
                SystemClock.sleep(100);
            }
            assertTrue("The actual system projection starts", Match3LiveService.isRunning());
            DiagnosticRecorder.current.audit("Match3SyntheticValidation source=instrumentation actual_game=false acoustic_evidence=false");
            scenario.onActivity(a -> { board[0] = new Board(a); a.setContentView(board[0]); });
            until = SystemClock.elapsedRealtime() + 12000;
            JSONObject first = state();
            while (!first.optBoolean("highlight_visible") && SystemClock.elapsedRealtime() < until) {
                SystemClock.sleep(100); first = state();
            }
            assertTrue("The pair is actually attached", first.optBoolean("highlight_visible"));
            assertEquals(7, first.getInt("rows")); assertEquals(7, first.getInt("cols"));
            long frame = first.getLong("processed_frames"), revision = first.getLong("board_revision");
            until = SystemClock.elapsedRealtime() + 12000;
            JSONObject later = state();
            while (later.optLong("processed_frames") < frame + 10 && SystemClock.elapsedRealtime() < until) {
                SystemClock.sleep(100); later = state();
            }
            assertTrue("Ten new samples arrived", later.getLong("processed_frames") >= frame + 10);
            assertTrue("The highlight continuously remains attached", later.getBoolean("highlight_visible"));
            assertFalse("No visual fallback is allowed in this check", later.getBoolean("highlight_suppressed"));
            assertTrue("Current-frame removal is exercised", later.getLong("overlay_capture_filtered_frames") > 0);
            assertEquals("The overlay cannot manufacture a board change", revision, later.getLong("board_revision"));
            assertTrue(later.getBoolean("board_valid"));

            JSONObject geometry = later.getJSONObject("geometry"), hint = later.getJSONObject("hint");
            float cellWidth = (geometry.getInt("right") - geometry.getInt("left")) / 7f;
            float cellHeight = (geometry.getInt("bottom") - geometry.getInt("top")) / 7f;
            float x = geometry.getInt("left") + (hint.getInt("from_col") - .5f) * cellWidth;
            float y = geometry.getInt("top") + (hint.getInt("from_row") - .5f) * cellHeight;
            float toX = geometry.getInt("left") + (hint.getInt("to_col") - .5f) * cellWidth;
            float toY = geometry.getInt("top") + (hint.getInt("to_row") - .5f) * cellHeight;
            android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            long at = SystemClock.uptimeMillis();
            automation.injectInputEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, y, 0), true);
            automation.injectInputEvent(MotionEvent.obtain(at, at + 100, MotionEvent.ACTION_MOVE, toX, toY, 0), true);
            automation.injectInputEvent(MotionEvent.obtain(at, at + 180, MotionEvent.ACTION_UP, toX, toY, 0), true);
            assertTrue("The swap gesture reaches the underlying game View", board[0].touches.get() >= 3);

            File directory = context().getExternalFilesDir("match3-release-capture");
            assertNotNull(directory); directory.mkdirs();
            Bitmap screenshot = automation.takeScreenshot();
            long screenshotUntil = SystemClock.elapsedRealtime() + 2000;
            while (screenshot == null && SystemClock.elapsedRealtime() < screenshotUntil) {
                SystemClock.sleep(100); screenshot = automation.takeScreenshot();
            }
            assertNotNull("The actual compositor must supply a screenshot; generated fallback images are not evidence", screenshot);
            try (FileOutputStream output = new FileOutputStream(new File(directory, "actual-screen.png"))) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
            } finally { screenshot.recycle(); }
            later.put("game_pixels", "synthetic_fixture"); later.put("acoustic_validation", false);
            try (FileOutputStream output = new FileOutputStream(new File(directory, "capture.json"))) {
                output.write(later.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            context().stopService(new Intent(context(), Match3LiveService.class));
            android.content.SharedPreferences.Editor editor = prefs.edit();
            if (hadHighlight) editor.putBoolean("match3_hint_highlight_enabled", oldHighlight);
            else editor.remove("match3_hint_highlight_enabled");
            editor.commit();
        }
    }
}
