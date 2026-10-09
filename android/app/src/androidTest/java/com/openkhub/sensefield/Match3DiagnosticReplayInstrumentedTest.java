package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** Real projection of an explicitly supplied diagnostic still; no acoustic or accuracy claim. */
@RunWith(AndroidJUnit4.class)
public final class Match3DiagnosticReplayInstrumentedTest {
    private static Bitmap load() throws Exception {
        InputStream input;
        try { input = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("screen-47-2258502432.jpg"); }
        catch (java.io.IOException absent) { Assume.assumeNoException(absent); return null; }
        try (input) { return BitmapFactory.decodeStream(input); }
    }

    private interface Condition { boolean ready() throws Exception; }
    private static void await(String message, long timeout, Condition check) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!check.ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        assertTrue(message, check.ready());
    }

    static void clickStart(View view) {
        if (view instanceof Button && "开始辅助".contentEquals(((Button) view).getText())) {
            view.performClick(); return;
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            clickStart(((ViewGroup) view).getChildAt(i));
    }

    static void consent(AccessibilityNodeInfo node) {
        if (node == null) return;
        String text = String.valueOf(node.getText());
        if (text.equalsIgnoreCase("Start now") || text.equalsIgnoreCase("Start recording")
                || text.equals("立即开始") || text.equals("开始录制")) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return;
        }
        for (int i = 0; i < node.getChildCount(); i++) consent(node.getChild(i));
    }

    static void dismissFullscreenTutorial(AccessibilityNodeInfo node) {
        if (node == null) return;
        String text = String.valueOf(node.getText());
        if (text.equalsIgnoreCase("GOT IT") || text.equals("知道了")) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return;
        }
        for (int i = 0; i < node.getChildCount(); i++) dismissFullscreenTutorial(node.getChild(i));
    }

    private static JSONObject state() throws Exception {
        return DiagnosticRecorder.current == null ? new JSONObject()
                : new JSONObject(DiagnosticRecorder.current.stateForDiagnostics());
    }

    static final class Replay extends View {
        final Bitmap frame;
        final Paint paint = new Paint();
        int tick;
        Replay(Context context, Bitmap frame) { super(context); this.frame = frame; }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawBitmap(frame, null, new Rect(0, 0, getWidth(), getHeight()), paint);
            // A non-board heartbeat makes the actual projection emit new frames.
            paint.setColor(0xff000000 | (100 + tick++ % 100));
            canvas.drawRect(0, 0, 8, 8, paint);
            postInvalidateDelayed(200);
        }
    }

    @Test public void actualProjectionOfTheDiagnosticBoardProducesAndRetainsARecommendation() throws Exception {
        String hardware = android.os.Build.HARDWARE;
        assertTrue("isolated emulator only", hardware.contains("ranchu") || hardware.contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap frame = load();
        android.content.SharedPreferences prefs = GameProfile.settings(context);
        String[] keys = {"match3_overlay_permission_explained", "match3_hint_highlight_enabled",
                "cue_channel_speech", "cue_category_system"};
        Map<String, ?> previous = prefs.getAll();
        android.content.SharedPreferences.Editor edit = prefs.edit();
        for (String key : keys) edit.putBoolean(key, true);
        edit.commit();
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(Match3AssistActivity.class)) {
            scenario.onActivity(a -> clickStart(a.getWindow().getDecorView()));
            await("projection authorization", 10000, () -> {
                consent(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
                return Match3LiveService.isRunning();
            });
            scenario.onActivity(a -> {
                a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                a.setContentView(new Replay(a, frame));
            });
            await("real capture reaches a recommended exchange", 15000, () -> {
                dismissFullscreenTutorial(InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation().getRootInActiveWindow());
                return "ready".equals(state().optString("recommendation_gate"))
                        && state().optJSONObject("hint") != null && state().optBoolean("highlight_visible");
            });
            JSONObject first = state();
            assertEquals(9, first.getInt("rows")); assertEquals(9, first.getInt("cols"));
            assertEquals(0, first.getInt("visible_runs"));
            assertTrue("obstacles are explicitly excluded", first.getInt("excluded_cells") > 0);
            assertTrue("actual window is attached", first.getBoolean("highlight_visible"));
            JSONObject hint = first.getJSONObject("hint");
            // Independently read from the screenshot: swapping yellow/purple at
            // (1,1)/(1,2) makes column 2 yellow in rows 1..3. Neither is an obstacle.
            assertEquals(1, hint.getInt("from_row")); assertEquals(1, hint.getInt("from_col"));
            assertEquals(1, hint.getInt("to_row")); assertEquals(2, hint.getInt("to_col"));
            File events = new File(DiagnosticRecorder.current.directory, "events.jsonl");
            await("recommendation submitted to the voice dispatcher", 5000, () -> {
                if (!events.isFile()) return false;
                String logs = new String(Files.readAllBytes(events.toPath()), StandardCharsets.UTF_8);
                if (!logs.contains("Match3Speech:")) return false;
                for (String line : logs.split("\n")) {
                    if (line.isEmpty()) continue;
                    JSONObject event = new JSONObject(line), data = event.optJSONObject("data");
                    if ("CueDispatch".equals(event.optString("type")) && data != null
                            && data.optString("event_key").startsWith("m3live:hint:")
                            && data.optInt("accepted_channels") == 2) return true;
                }
                return false;
            });
            long revision = first.getLong("board_revision");
            SystemClock.sleep(2400);
            assertEquals(revision, state().getLong("board_revision"));
            assertNotNull(state().optJSONObject("hint"));
            Log.i("Match3Level43Replay", "actual_projection=" + state());
            File directory = context.getExternalFilesDir("match3-release-capture");
            assertNotNull(directory); directory.mkdirs();
            JSONObject evidence = new JSONObject().put("source", "explicit_diagnostic_still_actual_projection")
                    .put("fixture", "screen-47-2258502432.jpg").put("state", state())
                    .put("speech_submitted", true).put("acoustic_evidence", false)
                    .put("independent_accuracy_evidence", false);
            try (FileOutputStream output = new FileOutputStream(new File(directory, "diagnostic-replay.json"))) {
                output.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull("actual compositor screenshot", screenshot);
            try (FileOutputStream output = new FileOutputStream(new File(directory, "diagnostic-replay.png"))) {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally { screenshot.recycle(); }
        } finally {
            context.startService(new Intent(context, Match3LiveService.class).setAction(Match3LiveService.ACTION_STOP));
            await("projection stopped", 5000, () -> !Match3LiveService.isRunning());
            frame.recycle();
            android.content.SharedPreferences.Editor restore = prefs.edit();
            for (String key : keys) {
                if (previous.containsKey(key)) restore.putBoolean(key, (Boolean) previous.get(key));
                else restore.remove(key);
            }
            restore.commit();
        }
    }
}
