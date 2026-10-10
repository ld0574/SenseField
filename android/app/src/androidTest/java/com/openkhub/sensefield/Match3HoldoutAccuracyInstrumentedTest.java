package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Phase 0 producer for the recognition flywheel. Runs the real
 * {@link Match3Sampler#samplePosition} over a holdout of frames and writes the
 * engine's per-cell verdict as predictions.json for
 * scripts/evaluate_match3_accuracy.py to score. It never asserts an accuracy
 * number itself — ground truth is human-supplied, and this test only faithfully
 * reproduces what the shipping recognizer decides.
 *
 * Supply a holdout by pushing frames and a manifest to the target app's
 * external dir, e.g.:
 *   adb -s emulator-5554 push frame.png \
 *     /sdcard/Android/data/com.openkhub.sensefield/files/match3-holdout/
 *   adb -s emulator-5554 push manifest.json \
 *     /sdcard/Android/data/com.openkhub.sensefield/files/match3-holdout/
 * manifest.json: {"frames":[{"file":"frame.png","rows":9,"cols":9,
 *                            "bounds":[l,t,r,b]}]}  // bounds (percent) optional
 * With no manifest the test skips, so it never destabilizes the gate.
 */
@RunWith(AndroidJUnit4.class)
public final class Match3HoldoutAccuracyInstrumentedTest {

    @Test public void realEngineLabelsHoldoutCellsForScoring() throws Exception {
        String hardware = android.os.Build.HARDWARE;
        Assume.assumeTrue("isolated emulator only", hardware.contains("ranchu") || hardware.contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = context.getExternalFilesDir("match3-holdout");
        assertNotNull(dir);
        File manifestFile = new File(dir, "manifest.json");
        Assume.assumeTrue("push a holdout manifest to " + dir, manifestFile.isFile());

        JSONObject manifest = new JSONObject(new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8));
        JSONArray frames = manifest.getJSONArray("frames");
        JSONArray samples = new JSONArray();
        JSONObject boards = new JSONObject();
        JSONObject goalCards = new JSONObject();
        Match3HudReader hud = new Match3HudReader(context);
        for (int f = 0; f < frames.length(); f++) {
            JSONObject entry = frames.getJSONObject(f);
            String name = entry.getString("file");
            Bitmap frame = BitmapFactory.decodeFile(new File(dir, name).getAbsolutePath());
            assertNotNull("decodable frame " + name, frame);
            BoardGeometry geometry = geometryFor(entry, frame);
            if (geometry == null) { frame.recycle(); continue; }
            try (Match3Sampler sampler = new Match3Sampler(context, geometry)) {
                Match3Position board = sampler.samplePosition(frame);
                // Export the board's pixel geometry so clear, full-resolution cell
                // crops can be cut from the source frame offline for human review.
                boards.put(name, new JSONObject()
                        .put("left", (int) geometry.cellLeft(0)).put("top", (int) geometry.cellTop(0))
                        .put("cellW", geometry.cellWidth()).put("cellH", geometry.cellHeight())
                        .put("rows", board.rows).put("cols", board.cols));
                // Diagnostic: export each HUD goal-card icon patch so a goal-icon
                // template (e.g. ice-flower / honey) can be captured and labeled.
                hud.read(frame, geometry, 0L);
                int[][] gp = hud.diagnosticGoalPatches();
                if (gp != null) {
                    String[] gk = hud.diagnosticGoalKinds();
                    float[] ga = hud.diagnosticGoalAspects();
                    JSONArray cards = new JSONArray();
                    for (int i = 0; i < gp.length; i++) {
                        JSONArray px = new JSONArray();
                        for (int v : gp[i]) px.put(v);
                        cards.put(new JSONObject().put("kind", gk[i] == null ? JSONObject.NULL : gk[i])
                                .put("aspect", (double) ga[i]).put("pixels", px));
                    }
                    goalCards.put(name, cards);
                }
                for (int r = 0; r < board.rows; r++) for (int c = 0; c < board.cols; c++) {
                    Match3Position.Cell cell = board.cell(r, c);
                    JSONObject sample = new JSONObject()
                            .put("id", name + ":r" + r + "c" + c)
                            .put("row", r).put("col", c)
                            .put("kind", cell.kind.name())
                            .put("color", cell.color == '\0' ? "" : String.valueOf(cell.color))
                            .put("swap_permission", cell.swapPermission.name())
                            .put("ice", cell.iceLayers > 0 ? 1 : 0);
                    int[] envelope = sampler.elementEnvelope(r, c);
                    if (envelope != null) {
                        JSONArray pixels = new JSONArray();
                        for (int value : envelope) pixels.put(value);
                        sample.put("envelope", pixels);
                    }
                    // The inset patch is what obstacle recognition actually compares,
                    // so export it too for seeding obstacle (e.g. coin) gallery entries.
                    int[] patch = sampler.elementPatch(r, c);
                    if (patch != null) {
                        JSONArray pixels = new JSONArray();
                        for (int value : patch) pixels.put(value);
                        sample.put("patch", pixels);
                    }
                    samples.put(sample);
                }
            } finally {
                frame.recycle();
            }
        }

        JSONObject out = new JSONObject()
                .put("catalog_id", Match3VisualCatalog.get(context).id)
                .put("boards", boards)
                .put("goal_cards", goalCards)
                .put("samples", samples);
        File predictions = new File(dir, "predictions.json");
        try (FileOutputStream stream = new FileOutputStream(predictions)) {
            stream.write(out.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        assertTrue("holdout produced at least one labeled cell", samples.length() > 0);
    }

    /** Manifest bounds pin the geometry; otherwise fall back to the shipping auto-detector. */
    private static BoardGeometry geometryFor(JSONObject entry, Bitmap frame) throws Exception {
        if (entry.has("bounds") && entry.has("rows") && entry.has("cols")) {
            JSONArray b = entry.getJSONArray("bounds");
            return BoardGeometry.fromPercent(frame.getWidth(), frame.getHeight(),
                    entry.getInt("rows"), entry.getInt("cols"),
                    new int[]{b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)});
        }
        return Match3Sampler.autoDetectGeometry(frame);
    }
}
