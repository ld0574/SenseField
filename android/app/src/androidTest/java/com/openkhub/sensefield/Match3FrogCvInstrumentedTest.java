package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.*;
import org.junit.Test;

/** Private, reviewed development frames. No manifest geometry or goal overrides. */
public final class Match3FrogCvInstrumentedTest {
    private static Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static File directory() { return app().getExternalFilesDir("match3-frog-cv"); }
    private static JSONArray inputs() throws Exception {
        JSONObject manifest = new JSONObject(new String(Files.readAllBytes(
                new File(directory(), "manifest.json").toPath()), StandardCharsets.UTF_8));
        assertFalse(manifest.getBoolean("independent"));
        assertEquals("developer_visual_review", manifest.getString("review_scope"));
        JSONArray frames = manifest.getJSONArray("frames");
        assertEquals(6, frames.length());
        return frames;
    }
    private static Bitmap frame(JSONObject entry) throws Exception {
        File file = new File(directory(), entry.getString("file"));
        assertTrue(file.getCanonicalPath().startsWith(directory().getCanonicalPath() + File.separator));
        byte[] bytes = Files.readAllBytes(file.toPath());
        assertEquals(entry.getString("sha256"), Match3HoldoutAccuracyInstrumentedTest.sha256(bytes));
        Bitmap frame = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(frame);
        return frame;
    }

    @Test public void reviewedVerticalFrogsAdmitTheOrdinarySwapThatClearsTwoSnowCells() throws Exception {
        JSONArray inputs = inputs();
        for (int i = 0; i < inputs.length(); i++) {
            Bitmap frame = frame(inputs.getJSONObject(i));
            Match3HudReader hud = new Match3HudReader(app());
            try {
                BoardGeometry geometry = Match3Sampler.autoDetectGeometry(frame);
                assertNotNull(geometry); assertEquals(9, geometry.rows); assertEquals(9, geometry.cols);
                try (Match3Sampler sampler = new Match3Sampler(app(), geometry)) {
                    Match3Position board = sampler.samplePosition(frame);
                    for (int col : new int[]{3, 4}) {
                        Match3Position.Cell frog = board.cell(7, col);
                        assertEquals(Match3Position.Kind.ANIMAL, frog.kind); assertEquals('G', frog.color);
                        assertEquals(Match3Position.Effect.VERTICAL, frog.effect);
                        assertEquals(Match3Position.SwapPermission.YES, frog.matchPermission);
                        assertEquals(Match3Position.SwapPermission.YES, frog.swapPermission);
                        assertEquals(Match3Position.Cover.NONE, frog.cover);
                    }
                    Match3Goals goals = hud.read(frame, geometry, 0);
                    assertTrue(hud.status(), goals.hudVerified);
                    assertEquals(3, goals.remaining(Match3Goals.Kind.SNOW));
                    List<Match3MoveValue> moves = Match3MoveRanker.rankedMoves(board, goals);
                    assertFalse(moves.isEmpty());
                    // Use the shipping diagnostic entry point retained in Release;
                    // internal settlement methods may be folded by R8.
                    JSONObject first = Match3RuleDiagnostics.action(moves.get(0));
                    assertEquals("SWAP", first.getString("action_type"));
                    assertEquals(6, first.getInt("from_row")); assertEquals(5, first.getInt("from_col"));
                    assertEquals(7, first.getInt("to_row")); assertEquals(5, first.getInt("to_col"));
                    assertEquals(2, first.getInt("direct_units"));
                    int snow = 0;
                    JSONArray events = first.getJSONArray("events");
                    for (int e = 0; e < events.length(); e++) {
                        JSONObject event = events.getJSONObject(e);
                        if ("SNOW".equals(event.getString("kind")) && "CLEAR".equals(event.getString("type")))
                            snow += event.getInt("units");
                    }
                    assertEquals(2, snow);
                    assertFalse(first.getBoolean("successor_known"));
                }
            } finally { hud.close(); frame.recycle(); }
        }
    }

    @Test public void ordinaryFacesAndOtherGlowsDoNotBorrowTheVerticalFrogRule() throws Exception {
        JSONArray inputs = inputs();
        for (int i = 0; i < inputs.length(); i++) {
            Bitmap frame = frame(inputs.getJSONObject(i));
            try {
                BoardGeometry geometry = Match3Sampler.autoDetectGeometry(frame); assertNotNull(geometry);
                try (Match3Sampler sampler = new Match3Sampler(app(), geometry)) {
                    Match3Position board = sampler.samplePosition(frame);
                    Match3Position.Cell ordinary = board.cell(6, 5);
                    assertEquals('G', ordinary.color); assertEquals(Match3Position.Effect.NONE, ordinary.effect);
                    for (int[] cell : new int[][]{{3, 0}, {5, 4}, {6, 2}}) {
                        Match3Position.Effect effect = board.cell(cell[0], cell[1]).effect;
                        assertNotEquals(Match3Position.Effect.VERTICAL, effect);
                        assertNotEquals(Match3Position.Effect.HORIZONTAL, effect);
                    }
                }
            } finally { frame.recycle(); }
        }
    }
}
