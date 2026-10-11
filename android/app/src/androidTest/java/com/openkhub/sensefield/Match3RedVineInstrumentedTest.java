package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reviewed development failure pixels; they remain private test assets. */
public final class Match3RedVineInstrumentedTest {
    private static Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static byte[] asset(String name) throws Exception {
        try (InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) { return in.readAllBytes(); }
    }
    private static JSONObject cases() throws Exception {
        JSONObject cases=new JSONObject(new String(asset("red-vine-cases.json"),StandardCharsets.UTF_8));
        assertFalse(cases.getBoolean("independent"));return cases;
    }
    private static void locked(String id,Match3Position.Cell cell,char color) {
        assertNotNull(id,cell);assertEquals(id,Match3Position.Kind.ANIMAL,cell.kind);
        assertEquals(id,color,cell.color);assertEquals(id,Match3Position.Cover.LOCK,cell.cover);
        assertEquals(id,1,cell.coverLayers);assertEquals(id,Match3Position.SwapPermission.NO,cell.swapPermission);
        assertEquals(id,Match3Position.SwapPermission.YES,cell.matchPermission);
    }
    @Test public void aHeldOutRawPoseMatchesWhileUncoveredRedAnimalsStayUnlocked() throws Exception {
        JSONArray patches=cases().getJSONArray("patches");Match3VisualCatalog catalog=Match3VisualCatalog.get(app());
        int heldOut=0,negative=0;
        for(int i=0;i<patches.length();i++) {
            JSONObject entry=patches.getJSONObject(i);JSONArray values=entry.getJSONArray("patch");assertEquals(256,values.length());
            int[] patch=new int[256];for(int k=0;k<256;k++)patch[k]=values.getInt(k);
            Match3Position.Cell cell=catalog.classifyObstacle(patch);String id=entry.getString("id");
            if(entry.getBoolean("locked")) {
                locked(id,cell,'R');if("evaluation".equals(entry.getString("role")))heldOut++;
            } else {
                if(cell!=null) {assertNotEquals(id,Match3Position.Cover.LOCK,cell.cover);assertNotEquals(id,Match3Position.Kind.BIRD,cell.kind);}
                negative++;
            }
        }
        assertEquals(1,heldOut);assertTrue(negative>=10);
    }
    @Test public void automaticBoardsRecognizeBothPreviouslyMissedRedVinesWithoutExtraLocks() throws Exception {
        verifyBoards(false);
    }
    @Test public void lowResolutionContextKeepsUnverifiedPermissionsConservative() throws Exception {
        verifyBoards(true);
    }
    private void verifyBoards(boolean lowResolution) throws Exception {
        JSONArray frames=cases().getJSONArray("frames");int checked=0;
        for(int i=0;i<frames.length();i++) {
            JSONObject entry=frames.getJSONObject(i);if(entry.getBoolean("low_resolution")!=lowResolution)continue;
            byte[] bytes=asset(entry.getString("file"));assertEquals(entry.getString("sha256"),Match3HoldoutAccuracyInstrumentedTest.sha256(bytes));
            Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(bitmap);
            BoardGeometry grid=Match3Sampler.autoDetectGeometry(bitmap);assertNotNull(grid);assertEquals(9,grid.rows);assertEquals(9,grid.cols);
            boolean[][] expected=new boolean[9][9];JSONArray labels=entry.getJSONArray("vines");
            try(Match3Sampler sampler=new Match3Sampler(app(),grid)) {
                Match3Position board=sampler.samplePosition(bitmap);int locks=0;
                for(int k=0;k<labels.length();k++) {
                    JSONObject label=labels.getJSONObject(k);int r=label.getInt("row"),c=label.getInt("col");expected[r][c]=true;
                    Match3Position.Cell cell=board.cell(r,c);String id=entry.getString("file")+":r"+r+"c"+c;
                    assertNotEquals(id,Match3Position.SwapPermission.YES,cell.swapPermission);
                    if(!lowResolution)locked(id,cell,label.getString("color").charAt(0));
                }
                for(int r=0;r<9;r++)for(int c=0;c<9;c++)if(board.cell(r,c).cover==Match3Position.Cover.LOCK) {
                    assertTrue("no extra lock",expected[r][c]);locks++;
                }
                if(!lowResolution)assertEquals(labels.length(),locks);
                for(Match3Action action:Match3Action.enumerate(board)) {
                    assertFalse("known vine cannot be an endpoint",expected[action.geometry.fromRow][action.geometry.fromCol]);
                    assertFalse("known vine cannot be an endpoint",expected[action.geometry.toRow][action.geometry.toCol]);
                }
                if(!lowResolution) {
                    Match3HudReader hud=new Match3HudReader(app());
                    try {Match3Goals goals=hud.read(bitmap,grid,100);assertEquals(entry.getInt("lock_goal"),goals.remaining(Match3Goals.Kind.LOCK));assertEquals(Match3Goals.Mode.UNLOCK,goals.mode(Match3Goals.Kind.LOCK));}
                    finally {hud.close();}
                }
                checked++;
            } finally {bitmap.recycle();}
        }
        assertEquals(lowResolution?1:2,checked);
    }
}
