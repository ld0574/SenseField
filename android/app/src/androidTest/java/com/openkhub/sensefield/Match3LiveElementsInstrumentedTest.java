package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;

/** Explicit development pixels and independently specified game-state expectations; no human holdout claim. */
public final class Match3LiveElementsInstrumentedTest {
    private static Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static byte[] asset(String name) throws Exception {
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) { return in.readAllBytes(); }
    }
    private static JSONObject input(String name) throws Exception {
        JSONObject input = new JSONObject(new String(asset(name), StandardCharsets.UTF_8));
        assertFalse(input.getBoolean("independent")); return input;
    }
    private static int[] pixels(JSONArray values) throws Exception {
        assertEquals(256, values.length()); int[] out = new int[256];
        for (int i=0;i<256;i++) out[i]=values.getInt(i); return out;
    }
    private static String sha(byte[] data) throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))
            out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static Bitmap frame(JSONObject entry) throws Exception {
        byte[] bytes=asset(entry.getString("file"));assertEquals(entry.getString("sha256"),sha(bytes));
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(bitmap);return bitmap;
    }
    private static BoardGeometry grid(Bitmap bitmap,JSONObject entry) throws Exception {
        JSONObject g=entry.getJSONObject("reviewed_geometry");
        return new BoardGeometry(bitmap.getWidth(),bitmap.getHeight(),g.getInt("left"),g.getInt("top"),
                g.getInt("right"),g.getInt("bottom"),g.getInt("rows"),g.getInt("cols"));
    }
    @Test public void exportFrozenDevelopmentDescriptorsAndObservations() throws Exception {
        JSONArray frames=input("live-elements-frames.json").getJSONArray("frames"),output=new JSONArray();
        for(int i=0;i<frames.length();i++) {
            JSONObject entry=frames.getJSONObject(i);Bitmap bitmap=frame(entry);BoardGeometry geometry=grid(bitmap,entry);
            Match3HudReader hud=new Match3HudReader(app());
            try(Match3Sampler sampler=new Match3Sampler(app(),geometry)) {
                Match3Position position=sampler.samplePosition(bitmap);Match3Goals goals=hud.read(bitmap,geometry,100);
                JSONArray cells=new JSONArray();
                for(int r=0;r<position.rows;r++)for(int c=0;c<position.cols;c++) {
                    Match3Position.Cell cell=position.cell(r,c);
                    cells.put(new JSONObject().put("row",r).put("col",c).put("kind",cell.kind.name()).put("color",String.valueOf(cell.color))
                            .put("cover",cell.cover.name()).put("swap",cell.swapPermission.name()).put("match",cell.matchPermission.name())
                            .put("patch",new JSONArray(sampler.elementPatch(r,c)))
                            .put("envelope",new JSONArray(sampler.elementEnvelope(r,c))));
                }
                JSONObject observed=new JSONObject().put("id",entry.getString("id")).put("sha256",entry.getString("sha256"))
                        .put("hud",goals.key()).put("hud_status",hud.status()).put("cells",cells)
                        .put("actions",new JSONArray());
                int[][] patches=hud.diagnosticGoalPatches();
                if(patches!=null)observed.put("goals",new JSONArray(patches));
                for(Match3MoveValue move:Match3MoveRanker.rankedMoves(position,goals))
                    observed.getJSONArray("actions").put(Match3RuleDiagnostics.action(move));
                output.put(observed);
            } finally { hud.close();bitmap.recycle(); }
        }
        Files.write(new File(app().getExternalFilesDir(null),"live-elements-observations.json").toPath(),
                output.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    @Test public void actualPatchesKeepCoverAndBirdPermissionsSeparateFromOrdinaryAnimals() throws Exception {
        JSONArray cases=input("live-elements-patches.json").getJSONArray("cases");Match3VisualCatalog catalog=Match3VisualCatalog.get(app());
        int verified=0,negative=0;
        for(int i=0;i<cases.length();i++) {
            JSONObject entry=cases.getJSONObject(i);int[] patch=pixels(entry.getJSONArray("patch"));
            Match3Position.Cell cell=catalog.classifyObstacle(patch);String label=entry.getString("expected"),id=entry.getString("source");
            if("ANIMAL".equals(label)) {
                if(cell!=null) {assertNotEquals(id,Match3Position.Kind.BIRD,cell.kind);assertNotEquals(id,Match3Position.Cover.LOCK,cell.cover);}
                negative++;continue;
            }
            assertNotNull(id,cell);
            if("LOCK".equals(label)) {
                assertEquals(id,Match3Position.Kind.ANIMAL,cell.kind);assertEquals(id,entry.getString("color").charAt(0),cell.color);
                assertEquals(id,Match3Position.Cover.LOCK,cell.cover);assertEquals(id,1,cell.coverLayers);
                assertEquals(id,Match3Position.SwapPermission.NO,cell.swapPermission);assertEquals(id,Match3Position.SwapPermission.YES,cell.matchPermission);
            } else {
                assertEquals(id,Match3Position.Kind.BIRD,cell.kind);assertEquals(id,Match3Position.Cover.NONE,cell.cover);
                assertEquals(id,Match3Position.SwapPermission.YES,cell.swapPermission);assertEquals(id,Match3Position.SwapPermission.NO,cell.matchPermission);
                assertEquals(id,Match3Position.SwapPermission.YES,cell.activationPermission);
            }
            verified++;
        }
        assertTrue(verified>=13);assertTrue(negative>=14);
    }
    @Test public void developmentBoardsAdmitVineGoalsAndRealBirdColorActions() throws Exception {
        JSONArray frames=input("live-elements-frames.json").getJSONArray("frames");int boards=0;
        for(int i=0;i<frames.length();i++) {
            JSONObject entry=frames.getJSONObject(i);String id=entry.getString("id");if(id.equals("82later"))continue;
            Bitmap bitmap=frame(entry);Match3HudReader hud=new Match3HudReader(app());
            try(Match3Sampler sampler=new Match3Sampler(app(),grid(bitmap,entry))) {
                Match3Position position=sampler.samplePosition(bitmap);Match3Goals goals=hud.read(bitmap,grid(bitmap,entry),100);
                assertTrue(id+":"+hud.status(),goals.hudVerified);
                int birdRow=id.startsWith("82")?6:2,birdCol=id.startsWith("82")?6:0;
                assertEquals(id,Match3Position.Kind.BIRD,position.cell(birdRow,birdCol).kind);
                int locks=0,birds=0;
                for(int r=0;r<position.rows;r++)for(int c=0;c<position.cols;c++) {
                    if(position.cell(r,c).cover==Match3Position.Cover.LOCK)locks++;
                    if(position.cell(r,c).kind==Match3Position.Kind.BIRD)birds++;
                }
                assertEquals(id+": no extra bird",1,birds);
                assertEquals(id+": no extra lock",id.startsWith("82")?7:0,locks);
                if(id.startsWith("82")) {
                    assertEquals(id+": reviewed ordinary bird neighbor",Match3Position.SwapPermission.YES,position.cell(5,6).swapPermission);
                    assertEquals(id+": ordinary neighbor has no cover",Match3Position.Cover.NONE,position.cell(5,6).cover);
                    String colors="PPBPBPR";
                    for(int r=1;r<=7;r++) {
                        Match3Position.Cell cell=position.cell(r,8);assertEquals(id+":r"+r,Match3Position.Cover.LOCK,cell.cover);
                        assertEquals(id+":r"+r,colors.charAt(r-1),cell.color);assertEquals(Match3Position.SwapPermission.NO,cell.swapPermission);
                    }
                    assertEquals(7,goals.remaining(Match3Goals.Kind.LOCK));assertEquals(Match3Goals.Mode.UNLOCK,goals.mode(Match3Goals.Kind.LOCK));
                    assertEquals(19,goals.remaining(Match3Goals.Kind.HONEY));assertEquals(Match3Goals.Mode.CLEAR,goals.mode(Match3Goals.Kind.HONEY));
                }
                List<Match3Action> actions=Match3Action.enumerate(position);boolean birdColor=false;
                for(Match3Action action:actions) {
                    assertNotEquals(id,Match3Position.Cover.LOCK,position.cell(action.geometry.fromRow,action.geometry.fromCol).cover);
                    assertNotEquals(id,Match3Position.Cover.LOCK,position.cell(action.geometry.toRow,action.geometry.toCol).cover);
                    if(action.type==Match3Action.Type.BIRD_COLOR)birdColor=true;
                }
                assertTrue(id+": actual bird color action admitted",birdColor);boards++;
            } finally {hud.close();bitmap.recycle();}
        }
        assertEquals(4,boards);
    }
    @Test public void aLaterRealBoardDropsTheConsumedBirdAndKeepsTheRemainingBlueVineLocked() throws Exception {
        JSONArray entries=input("live-elements-frames.json").getJSONArray("frames");JSONObject before=null,after=null;
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);
            if("82train".equals(entry.getString("id")))before=entry;
            if("82later".equals(entry.getString("id")))after=entry;
        }
        assertNotNull(before);assertNotNull(after);Bitmap first=frame(before),later=frame(after);
        Match3HudReader hud=new Match3HudReader(app());Match3CellConfirmation confirmation=new Match3CellConfirmation();
        try(Match3Sampler sampler=new Match3Sampler(app(),grid(first,before))) {
            Match3Position original=sampler.samplePosition(first);
            assertEquals(Match3Position.Kind.BIRD,original.cell(6,6).kind);
            // Test-generated observation timestamps exercise the confirmation
            // gate; periodic JPEGs cannot prove continuous animation stability.
            for(int i=0;i<3;i++)confirmation.accept(original,100+800L*i);
            Match3Position observed=sampler.samplePosition(later);
            int locks=0,birds=0;
            for(int r=0;r<observed.rows;r++)for(int c=0;c<observed.cols;c++) {
                if(observed.cell(r,c).cover==Match3Position.Cover.LOCK)locks++;
                if(observed.cell(r,c).kind==Match3Position.Kind.BIRD)birds++;
            }
            assertEquals(1,locks);assertEquals(0,birds);
            Match3Position.Cell vine=observed.cell(3,8);
            assertEquals('B',vine.color);assertEquals(Match3Position.Cover.LOCK,vine.cover);
            assertEquals(Match3Position.SwapPermission.NO,vine.swapPermission);assertEquals(Match3Position.SwapPermission.YES,vine.matchPermission);
            Match3Position changed=confirmation.accept(observed,2500).position;
            assertEquals("old bird identity cannot fill a changed cell",Match3Position.Kind.UNKNOWN,changed.cell(6,6).kind);
            Match3Goals goals=hud.read(later,grid(later,after),2500);
            assertEquals(1,goals.remaining(Match3Goals.Kind.LOCK));assertEquals(0,goals.remaining(Match3Goals.Kind.HONEY));
            for(Match3Action action:Match3Action.enumerate(changed)) {
                assertNotEquals(Match3Action.Type.BIRD_COLOR,action.type);
                assertNotEquals(Match3Position.Cover.LOCK,changed.cell(action.geometry.fromRow,action.geometry.fromCol).cover);
                assertNotEquals(Match3Position.Cover.LOCK,changed.cell(action.geometry.toRow,action.geometry.toCol).cover);
            }
            confirmation.accept(observed,3300);
            assertNotEquals(Match3Position.Kind.BIRD,confirmation.accept(observed,4100).position.cell(6,6).kind);
        } finally {hud.close();first.recycle();later.recycle();}
    }
}
