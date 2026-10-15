package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Manually reviewed level 121 fault stills; development regression, not independent accuracy. */
@RunWith(AndroidJUnit4.class)
public final class Match3PoisonInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    private static Bitmap load(String name, int width) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            Bitmap source = BitmapFactory.decodeStream(input);
            assertNotNull("Required fault fixture " + name, source);
            int height = width == 1220 ? 2712 : Math.round(width * 960f / 432);
            Bitmap result = Bitmap.createScaledBitmap(source, width, height, true);
            if (result != source) source.recycle();
            return result;
        }
    }

    private static boolean poison(int board, int row, int col) {
        return board == 0 ? row >= 3 : board == 1 ? row >= 4 && col >= 1 : row == 7 && (col == 1 || col == 2);
    }

    @Test public void recordedPoisonBoardsReadTheirTaskAndFinishTheLastTwoInsteadOfAnUnrelatedMatch() throws Exception {
        String[] names = {"poison-start.jpg", "poison-28.jpg", "poison-2.jpg"};
        int[] counts = {40, 28, 2}, steps = {20, 19, 14};
        JSONArray records = new JSONArray(); JSONObject training = new JSONObject();
        List<String> failures = new ArrayList<>();
        try {
            for (int width : new int[]{432, 1080, 1220}) for (int board = 0; board < names.length; board++) {
                Bitmap frame = load(names[board], width); Match3HudReader reader = new Match3HudReader(context());
                JSONObject record = new JSONObject().put("fixture", names[board]).put("width", width);
                records.put(record);
                try {
                    BoardGeometry g = Match3Sampler.autoDetectGeometry(frame);
                    if (g == null || g.rows != 8 || g.cols != 8) {
                        failures.add(names[board] + " " + width + " geometry=" + g); continue;
                    }
                    record.put("geometry", new JSONArray(new int[]{g.left, g.top, g.right, g.bottom}));
                    Match3Goals goals = reader.read(frame, g, 100);
                    record.put("goal_key", goals.key()).put("hud_status", reader.status());
                    int remaining = goals.remaining(Match3Goals.Kind.POISON);
                    // The high-resolution training JPEG loses a counter stroke when first reduced
                    // to 432px. Preserve an explicit unread; native-size and both recorded fault
                    // stills must read the full count, and no resolution may invent another number.
                    boolean countVerified = remaining == counts[board] || board == 0 && width == 432 && remaining == -1;
                    if (!goals.hudVerified || goals.steps != steps[board]
                            || !countVerified || goals.targets.size() != 1 || goals.targets.get(0).kind != Match3Goals.Kind.POISON
                            || goals.mode(Match3Goals.Kind.POISON) != Match3Goals.Mode.CLEAR)
                        failures.add(names[board] + " " + width + " HUD=" + goals.key());
                    try (Match3Sampler sampler = new Match3Sampler(context(), g)) {
                        Match3Position position = sampler.samplePosition(frame);
                        JSONArray cells = new JSONArray(); int recognized = 0, falsePoison = 0, falseSwap = 0;
                        for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++) {
                            Match3Position.Cell cell = position.cell(row, col);
                            cells.put(cell.kind.name() + ":" + cell.color + ":" + cell.swapPermission);
                            if (poison(board, row, col)) {
                                if (cell.kind == Match3Position.Kind.POISON) recognized++;
                                if (cell.swappable) falseSwap++;
                            } else if (cell.kind == Match3Position.Kind.POISON) falsePoison++;
                        }
                        record.put("cells", cells).put("poison_recognized", recognized)
                                .put("poison_expected", counts[board]).put("false_poison", falsePoison).put("false_swap", falseSwap);
                        if (recognized != counts[board] || falsePoison != 0 || falseSwap != 0)
                            failures.add(names[board] + " " + width + " poison=" + recognized + "/" + counts[board]
                                    + " false=" + falsePoison + " false_swap=" + falseSwap);
                        List<Match3MoveValue> moves = Match3MoveRanker.rankedMoves(position, goals);
                        if (moves.isEmpty()) failures.add(names[board] + " " + width + " no legal recommendation");
                        else {
                            Match3MoveValue best = moves.get(0);
                            record.put("best_action", best.action.key()).put("poison_units", best.collected(Match3Goals.Kind.POISON))
                                    .put("reason", best.reason).put("all_targets_finish", best.allTargetsFinish);
                            for (Match3MoveValue move : moves) {
                                if (position.cell(move.swap.fromRow, move.swap.fromCol).kind == Match3Position.Kind.POISON
                                        || position.cell(move.swap.toRow, move.swap.toCol).kind == Match3Position.Kind.POISON)
                                    failures.add("Poison was admitted as an action endpoint");
                            }
                            if (board == 2 && (!best.allTargetsFinish || best.collected(Match3Goals.Kind.POISON) != 2))
                                failures.add("Last two poison cells must finish in one observed legal exchange: " + width);
                        }
                        // Export exact engine descriptors only from the separately supplied training still.
                        if (board == 0 && width == 1220) {
                            training.put("cell_3_0", new JSONArray(sampler.elementPatch(3, 0)))
                                    .put("cell_7_7", new JSONArray(sampler.elementPatch(7, 7)));
                            int[][] patches = reader.diagnosticGoalPatches();
                            if (patches != null && patches.length == 1) training.put("goal", new JSONArray(patches[0]));
                        }
                    }
                } finally { reader.close(); frame.recycle(); }
            }
        } finally {
            File directory = context().getExternalFilesDir("match3-poison"); assertNotNull(directory); directory.mkdirs();
            JSONObject report = new JSONObject().put("independent", false).put("records", records)
                    .put("training", training).put("failures", new JSONArray(failures));
            try (FileOutputStream output = new FileOutputStream(new File(directory, "poison-replay.json"))) {
                output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
        assertTrue("Level 121 fault regression: " + failures, failures.isEmpty());
    }

    @Test public void actualProjectionKeepsThePoisonFinishAndDispatchesItsDirectionHint() throws Exception {
        assertTrue("Isolated emulator only", android.os.Build.HARDWARE.contains("ranchu")
                || android.os.Build.HARDWARE.contains("goldfish"));
        assertTrue(android.provider.Settings.canDrawOverlays(context()));
        android.content.SharedPreferences prefs = GameProfile.settings(context());
        Map<String, ?> previous = prefs.getAll();
        String[] keys = {"match3_overlay_permission_explained", "match3_hint_highlight_enabled",
                "cue_channel_speech", "cue_category_system"};
        android.content.SharedPreferences.Editor edit = prefs.edit();
        for (String key : keys) edit.putBoolean(key, true);
        edit.commit();
        Bitmap frame = load("poison-2.jpg", 432);
        JSONArray checkpoints = new JSONArray();
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(Match3AssistActivity.class)) {
            scenario.onActivity(a -> Match3DiagnosticReplayInstrumentedTest.clickStart(a.getWindow().getDecorView()));
            Match3TaskFeedbackInstrumentedTest.await("projection starts", 10000, () -> {
                Match3DiagnosticReplayInstrumentedTest.consent(InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation().getRootInActiveWindow());
                return Match3LiveService.isRunning();
            });
            scenario.onActivity(a -> Match3TaskFeedbackInstrumentedTest.show(a, frame));
            Match3TaskFeedbackInstrumentedTest.await("confirmed poison task and visible finish", 25000, () -> {
                JSONObject s = Match3TaskFeedbackInstrumentedTest.state(), g = s.optJSONObject("goal_state");
                if (g == null || !g.optBoolean("hud_verified") || g.optInt("steps") != 14
                        || !s.optBoolean("highlight_visible") || !"ready".equals(s.optString("recommendation_gate"))) return false;
                JSONArray targets = g.optJSONArray("targets");
                return targets != null && targets.length() == 1
                        && "POISON".equals(targets.getJSONObject(0).optString("kind"))
                        && targets.getJSONObject(0).optInt("remaining") == 2 && s.optJSONObject("hint") != null;
            });
            JSONObject first = Match3TaskFeedbackInstrumentedTest.state(), hint = first.getJSONObject("hint");
            checkpoints.put(first);
            assertEquals(8, first.getInt("rows")); assertEquals(8, first.getInt("cols"));
            assertEquals("清理毒液", hint.getString("reason"));
            assertTrue(hint.getString("ranking_scope").contains("direct_units=2"));
            assertTrue(hint.getString("ranking_scope").contains("complete=1"));
            // Diagnostic endpoints are explicitly one-based; rule actions above are zero-based.
            assertEquals(7, hint.getInt("from_row")); assertEquals(3, hint.getInt("from_col"));
            assertEquals(7, hint.getInt("to_row")); assertEquals(4, hint.getInt("to_col"));
            long revision = first.getLong("board_revision"), started = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - started < 5000) {
                SystemClock.sleep(200);
                JSONObject s = Match3TaskFeedbackInstrumentedTest.state();
                assertTrue("The current arrow must not invalidate its own recommendation", s.getBoolean("highlight_visible"));
                assertEquals(revision, s.getLong("board_revision"));
                assertEquals(hint.toString(), s.getJSONObject("hint").toString());
            }
            JSONObject retained = Match3TaskFeedbackInstrumentedTest.state(); checkpoints.put(retained);
            assertTrue("New capture frames were actually processed",
                    retained.getLong("processed_frames") >= first.getLong("processed_frames") + 4);
            File events = new File(DiagnosticRecorder.current.directory, "events.jsonl");
            Match3TaskFeedbackInstrumentedTest.await("direction hint reaches speech dispatcher", 5000, () -> {
                DiagnosticRecorder.IO.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
                String trace = new String(Files.readAllBytes(events.toPath()), StandardCharsets.UTF_8);
                for (String line : trace.split("\n")) {
                    if (line.isEmpty()) continue;
                    JSONObject event = new JSONObject(line), data = event.optJSONObject("data");
                    if ("CueDispatch".equals(event.optString("type")) && data != null
                            && data.optString("event_key").startsWith("m3live:hint:")
                            && data.optInt("accepted_channels") == 2) return true;
                }
                return false;
            });
            File directory = context().getExternalFilesDir("match3-poison"); assertNotNull(directory); directory.mkdirs();
            try (FileOutputStream output = new FileOutputStream(new File(directory, "poison-projection.json"))) {
                output.write(new JSONObject().put("source", "player_fault_still_actual_projection")
                        .put("independent", false).put("acoustic_evidence", false)
                        .put("checkpoints", checkpoints).toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            context().startService(new Intent(context(), Match3LiveService.class).setAction(Match3LiveService.ACTION_STOP));
            Match3TaskFeedbackInstrumentedTest.await("projection stopped", 5000, () -> !Match3LiveService.isRunning());
            frame.recycle();
            android.content.SharedPreferences.Editor restore = prefs.edit();
            for (String key : keys) if (previous.containsKey(key)) restore.putBoolean(key, (Boolean) previous.get(key));
            else restore.remove(key);
            restore.commit();
        }
    }
}
