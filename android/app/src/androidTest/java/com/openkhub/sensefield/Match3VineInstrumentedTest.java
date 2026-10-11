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
import java.nio.file.Files;
import java.io.File;
import java.security.MessageDigest;

/** Explicit local development fixtures. They are never shipped in the app APK. */
public final class Match3VineInstrumentedTest {
    private static Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static byte[] asset(String name) throws Exception {
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) { return in.readAllBytes(); }
    }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static Bitmap frame(JSONObject entry) throws Exception {
        String name=entry.getString("file");assertTrue(name.matches("vine-[A-Za-z0-9-]+\\.(png|jpg)"));
        byte[] bytes=asset(name);assertEquals(entry.getString("sha256"),sha(bytes));
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(bitmap);return bitmap;
    }
    private static BoardGeometry geometry(Bitmap bitmap, JSONObject entry) throws Exception {
        if (!entry.has("reviewed_geometry")) {
            BoardGeometry found=Match3Sampler.autoDetectGeometry(bitmap);
            assertNotNull(entry.getString("file")+": automatic geometry",found);return found;
        }
        // Sparse frozen boards have insufficient periodic evidence. A supplied
        // development grid validates HUD/cells without claiming auto detection.
        JSONObject g=entry.getJSONObject("reviewed_geometry");
        return new BoardGeometry(bitmap.getWidth(),bitmap.getHeight(),g.getInt("left"),g.getInt("top"),
                g.getInt("right"),g.getInt("bottom"),g.getInt("rows"),g.getInt("cols"));
    }
    @Test public void exportDevelopmentTrainingDescriptors() throws Exception {
        JSONArray inputs=new JSONObject(new String(asset("vine-training.json"),StandardCharsets.UTF_8)).getJSONArray("frames");
        JSONArray output=new JSONArray();
        for(int i=0;i<inputs.length();i++) {
            JSONObject entry=inputs.getJSONObject(i);Bitmap bitmap=frame(entry);
            BoardGeometry geometry=geometry(bitmap,entry);
            Match3HudReader reader=new Match3HudReader(app());
            try(Match3Sampler sampler=new Match3Sampler(app(),geometry)) {
                sampler.samplePosition(bitmap);JSONArray cells=new JSONArray();
                for(int k=0;k<entry.getJSONArray("cells").length();k++) {
                    JSONObject label=entry.getJSONArray("cells").getJSONObject(k);
                    cells.put(new JSONObject(label.toString()).put("patch",new JSONArray(sampler.elementPatch(label.getInt("row"),label.getInt("col")))));
                }
                reader.read(bitmap,geometry,100);int[][] patches=reader.diagnosticGoalPatches();
                JSONObject descriptor=new JSONObject().put("source_sha256",entry.getString("sha256")).put("cells",cells).put("hud_status",reader.status());
                if(entry.has("goal_slot")) {
                    assertNotNull(entry.getString("file")+":"+reader.status(),patches);
                    descriptor.put("goal_patch",new JSONArray(patches[entry.getInt("goal_slot")]));
                }
                output.put(descriptor);
            } finally {reader.close();bitmap.recycle();}
        }
        Files.write(new File(app().getExternalFilesDir(null),"vine-descriptors.json").toPath(),output.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    @Test public void frozenFramesSeparateVinesFromCookiesAndReadFullCounters() throws Exception {
        JSONObject inputs=new JSONObject(new String(asset("vine-cases.json"),StandardCharsets.UTF_8));
        assertFalse(inputs.getBoolean("independent"));JSONArray entries=inputs.getJSONArray("frames");assertTrue(entries.length()>=4);
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);Bitmap bitmap=frame(entry);
            BoardGeometry geometry=geometry(bitmap,entry);
            Match3HudReader reader=new Match3HudReader(app());
            try(Match3Sampler sampler=new Match3Sampler(app(),geometry)) {
                Match3Position position=sampler.samplePosition(bitmap);JSONArray labels=entry.getJSONArray("cells");
                for(int k=0;k<labels.length();k++) {
                    JSONObject label=labels.getJSONObject(k);Match3Position.Cell cell=position.cell(label.getInt("row"),label.getInt("col"));
                    String id=entry.getString("file")+":"+label;
                    assertEquals(id,Match3Position.Kind.ANIMAL,cell.kind);assertEquals(id,label.getString("color").charAt(0),cell.color);
                    assertEquals(id,Match3Position.Cover.LOCK,cell.cover);assertEquals(id,1,cell.coverLayers);
                    assertEquals(id,Match3Position.SwapPermission.NO,cell.swapPermission);assertEquals(id,Match3Position.SwapPermission.YES,cell.matchPermission);
                }
                if(entry.optBoolean("no_locks",false))for(int r=0;r<position.rows;r++)for(int c=0;c<position.cols;c++)
                    assertNotEquals(entry.getString("file"),Match3Position.Cover.LOCK,position.cell(r,c).cover);
                if(labels.length()>0) {
                    int actualLocks=0;for(int r=0;r<position.rows;r++)for(int c=0;c<position.cols;c++)
                        if(position.cell(r,c).cover==Match3Position.Cover.LOCK)actualLocks++;
                    assertEquals(entry.getString("file")+": no extra vine identities",labels.length(),actualLocks);
                }
                Match3Goals goals=reader.read(bitmap,geometry,100);
                if(entry.optBoolean("hud_abstain",false)) {
                    assertFalse(entry.getString("file")+":"+reader.status(),goals.hudVerified);
                    assertFalse(goals.finished());continue;
                }
                assertTrue(entry.getString("file")+":"+reader.status(),goals.hudVerified);
                JSONObject expected=entry.getJSONObject("goals");
                for(java.util.Iterator<String> keys=expected.keys();keys.hasNext();) {
                    String name=keys.next();assertEquals(entry.getString("file")+":"+name,expected.getInt(name),goals.remaining(Match3Goals.Kind.valueOf(name)));
                }
                if(expected.has("LOCK")) {
                    assertEquals(Match3Goals.Mode.UNLOCK,goals.mode(Match3Goals.Kind.LOCK));
                    assertFalse(goals.hasTarget(Match3Goals.Kind.COOKIE));
                }
                for(Match3Action action:Match3Action.enumerate(position)) {
                    assertNotEquals(Match3Position.Cover.LOCK,position.cell(action.geometry.fromRow,action.geometry.fromCol).cover);
                    assertNotEquals(Match3Position.Cover.LOCK,position.cell(action.geometry.toRow,action.geometry.toCol).cover);
                }
                reader.clear();Match3Goals again=reader.read(bitmap,geometry,900);assertEquals(goals.key(),again.key());
            } finally {reader.close();bitmap.recycle();}
        }
    }
    @Test public void exportDevelopmentCaseObservations() throws Exception {
        JSONArray entries=new JSONObject(new String(asset("vine-cases.json"),StandardCharsets.UTF_8)).getJSONArray("frames");
        JSONArray output=new JSONArray();
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);Bitmap bitmap=frame(entry);
            BoardGeometry geometry=geometry(bitmap,entry);
            Match3HudReader reader=new Match3HudReader(app());
            try(Match3Sampler sampler=new Match3Sampler(app(),geometry)) {
                Match3Position position=sampler.samplePosition(bitmap);JSONArray cells=new JSONArray();
                for(int r=0;r<position.rows;r++)for(int c=0;c<position.cols;c++) {
                    Match3Position.Cell cell=position.cell(r,c);
                    cells.put(new JSONObject().put("row",r).put("col",c).put("kind",cell.kind.name()).put("color",String.valueOf(cell.color))
                            .put("cover",cell.cover.name()).put("swap",cell.swapPermission.name()).put("match",cell.matchPermission.name())
                            .put("patch",new JSONArray(sampler.elementPatch(r,c))));
                }
                Match3Goals goals=reader.read(bitmap,geometry,100);
                output.put(new JSONObject().put("file",entry.getString("file")).put("sha256",entry.getString("sha256"))
                        .put("hud",goals.key()).put("status",reader.status()).put("cells",cells));
            } finally {reader.close();bitmap.recycle();}
        }
        Files.write(new File(app().getExternalFilesDir(null),"vine-case-observations.json").toPath(),output.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
