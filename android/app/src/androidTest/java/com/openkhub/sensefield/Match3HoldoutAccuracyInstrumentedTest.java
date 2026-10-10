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
    static String sha256(byte[] bytes) throws Exception {
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out=new StringBuilder();for(byte value:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return out.toString();
    }

    @Test public void realEngineLabelsHoldoutCellsForScoring() throws Exception {
        String hardware = android.os.Build.HARDWARE;
        Assume.assumeTrue("isolated emulator only", hardware.contains("ranchu") || hardware.contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = context.getExternalFilesDir("match3-holdout");
        assertNotNull(dir);
        File manifestFile = new File(dir, "manifest.json");
        Assume.assumeTrue("push a holdout manifest to " + dir, manifestFile.isFile());

        byte[] manifestBytes=Files.readAllBytes(manifestFile.toPath());
        JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        JSONArray frames = manifest.getJSONArray("frames");
        JSONArray samples = new JSONArray();
        JSONObject boards = new JSONObject();
        JSONObject goalCards = new JSONObject();
        JSONArray goalSamples=new JSONArray(),objects=new JSONArray(),rankings=new JSONArray();
        JSONArray frameResults=new JSONArray();
        JSONObject frameHashes=new JSONObject();
        Match3HudReader hud = new Match3HudReader(context);
        boolean strict=Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("match3Strict","false"));
        for (int f = 0; f < frames.length(); f++) {
            JSONObject entry = frames.getJSONObject(f);
            String name = entry.getString("file");
            File input=new File(dir,name);
            assertTrue("frame stays in holdout",input.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator));
            byte[] frameBytes=Files.readAllBytes(input.toPath());
            String hash=sha256(frameBytes);frameHashes.put(name,hash);
            if(entry.has("sha256"))assertEquals("frozen frame bytes: "+name,entry.getString("sha256"),hash);
            Bitmap frame = BitmapFactory.decodeByteArray(frameBytes,0,frameBytes.length);
            assertNotNull("decodable frame " + name, frame);
            BoardGeometry geometry = geometryFor(entry, frame);
            if (geometry == null) {
                frameResults.put(new JSONObject().put("file",name).put("status","no_geometry"));
                frame.recycle();assertFalse("expected board geometry: "+name,strict && !entry.optBoolean("no_board",false));continue;
            }
            assertFalse("non-board fixture was detected as a board",entry.optBoolean("no_board",false));
            frameResults.put(new JSONObject().put("file",name).put("status","board").put("rows",geometry.rows).put("cols",geometry.cols));
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
                Match3Goals goals=hud.read(frame, geometry, 0L);
                for(Match3Goals.Target target:goals.targets)goalSamples.put(new JSONObject()
                        .put("id",name+":goal"+target.slot).put("kind",target.kind.name())
                        .put("remaining",target.remaining).put("completed",target.completed));
                JSONArray candidates=new JSONArray();
                java.util.List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(board,goals);
                for(Match3MoveValue move:moves)candidates.put(new JSONObject()
                        .put("from_row",move.swap.fromRow).put("from_col",move.swap.fromCol)
                        .put("to_row",move.swap.toRow).put("to_col",move.swap.toCol)
                        .put("reason",move.reason).put("direct_units",move.directUnits));
                rankings.put(new JSONObject().put("id",name).put("candidates",candidates)
                        .put("hud_verified",goals.hudVerified).put("steps",goals.steps));
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
                            .put("ice", cell.iceLayers).put("object_id",cell.objectId);
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
                // Export every 2x2 envelope, not only recognized cookies. Human
                // review supplies object identity; inference is never the truth.
                for(int r=0;r+1<board.rows;r++)for(int c=0;c+1<board.cols;c++) {
                    boolean eligible=true;
                    for(int rr=r;rr<=r+1;rr++)for(int cc=c;cc<=c+1;cc++) {
                        Match3Position.Kind kind=board.cell(rr,cc).kind;
                        if(kind!=Match3Position.Kind.UNKNOWN && kind!=Match3Position.Kind.SURFACE && kind!=Match3Position.Kind.COOKIE)eligible=false;
                    }
                    if(!eligible)continue;
                    JSONArray patch=new JSONArray();
                    for(int y=0;y<16;y++)for(int x=0;x<16;x++)patch.put(
                            sampler.elementEnvelope(r+y/8,c+x/8)[(2*(y%8)+1)*16+2*(x%8)+1]);
                    objects.put(new JSONObject().put("id",name+":object:r"+r+"c"+c)
                            .put("frame",name).put("row",r).put("col",c).put("pixels",patch));
                }
            } finally {
                frame.recycle();
            }
        }

        hud.close();Match3VisualCatalog catalog=Match3VisualCatalog.get(context);
        JSONObject out = new JSONObject()
                .put("manifest_sha256",sha256(manifestBytes)).put("frame_sha256",frameHashes)
                .put("independent",manifest.optBoolean("independent",false)).put("frozen",manifest.optBoolean("frozen",false))
                .put("source_groups",manifest.optJSONArray("source_groups")!=null?manifest.getJSONArray("source_groups"):
                        new JSONArray().put(manifest.optString("session_id","")))
                .put("catalog_id", catalog.id).put("gallery_id",catalog.galleryId)
                .put("gallery_sha256",catalog.gallerySha256).put("gallery_status",catalog.galleryStatus)
                .put("gallery_goals_pending_review",catalog.galleryGoalsPendingReview)
                .put("frame_results",frameResults).put("goal_samples",goalSamples)
                .put("large_objects",objects).put("rankings",rankings)
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
