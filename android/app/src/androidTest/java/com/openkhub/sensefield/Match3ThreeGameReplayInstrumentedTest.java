package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.Arrays;
import java.io.InputStream;
import android.os.Debug;
import android.os.SystemClock;
import org.json.*;
import org.junit.Test;

/** Opt-in private development JPEGs from levels 107..109; never an accuracy holdout. */
public final class Match3ThreeGameReplayInstrumentedTest {
    private static Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static File directory() { return app().getExternalFilesDir("match3-three-game-replay"); }
    private static JSONArray inputs() throws Exception {
        JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(directory(),"manifest.json").toPath()),StandardCharsets.UTF_8));
        assertFalse(manifest.getBoolean("independent"));
        assertEquals("developer_visual_review",manifest.getString("review_scope"));
        return manifest.getJSONArray("frames");
    }
    private static Bitmap frame(JSONObject entry) throws Exception {
        File file=new File(directory(),entry.getString("file"));
        assertTrue(file.getCanonicalPath().startsWith(directory().getCanonicalPath()+File.separator));
        byte[] bytes=Files.readAllBytes(file.toPath());StringBuilder sha=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        assertEquals(entry.getString("sha256"),sha.toString());
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(bitmap);return bitmap;
    }
    private static BoardGeometry reviewed(Bitmap frame,JSONObject entry) throws Exception {
        JSONObject g=entry.getJSONObject("reviewed_geometry");
        return new BoardGeometry(frame.getWidth(),frame.getHeight(),g.getInt("left"),g.getInt("top"),
                g.getInt("right"),g.getInt("bottom"),g.getInt("rows"),g.getInt("cols"));
    }
    private static JSONObject geometry(BoardGeometry g) throws Exception {
        return g==null?new JSONObject():new JSONObject().put("rows",g.rows).put("cols",g.cols)
                .put("left",g.left).put("top",g.top).put("right",g.right).put("bottom",g.bottom);
    }
    @Test public void exportDevelopmentObservations() throws Exception {
        JSONArray inputs=inputs(),output=new JSONArray();
        Match3VisualCatalog catalog=Match3VisualCatalog.get(app());
        for(int i=0;i<inputs.length();i++) {
            JSONObject entry=inputs.getJSONObject(i);Bitmap frame=frame(entry);
            BoardGeometry auto=Match3Sampler.autoDetectGeometry(frame),grid=reviewed(frame,entry);
            Match3HudReader hud=new Match3HudReader(app());
            try(Match3Sampler sampler=new Match3Sampler(app(),grid)) {
                Match3Position board=sampler.samplePosition(frame);
                Match3Goals goals=hud.read(frame,grid,100);
                JSONArray cells=new JSONArray(),moves=new JSONArray();
                for(int r=0;r<board.rows;r++)for(int c=0;c<board.cols;c++) {
                    Match3Position.Cell cell=board.cell(r,c);
                    int[] patch=sampler.elementPatch(r,c);
                    Match3Position.Cell raw=catalog.classifyObstacle(patch);
                    cells.put(new JSONObject().put("row",r).put("col",c).put("kind",cell.kind.name())
                            .put("color",String.valueOf(cell.color)).put("effect",cell.effect.name()).put("cover",cell.cover.name())
                            .put("layers",cell.layers).put("floor_ice",cell.iceLayers)
                            .put("swap",cell.swapPermission.name()).put("match",cell.matchPermission.name())
                            .put("raw_kind",raw==null?"":raw.kind.name())
                            .put("patch",new JSONArray(patch)).put("envelope",new JSONArray(sampler.elementEnvelope(r,c))));
                }
                for(Match3MoveValue move:Match3MoveRanker.rankedMoves(board,goals))moves.put(Match3RuleDiagnostics.action(move));
                output.put(new JSONObject().put("id",entry.getString("id")).put("auto",geometry(auto)).put("grid",geometry(grid))
                        .put("goals",goals.key()).put("hud_status",hud.status()).put("cells",cells).put("moves",moves));
            } finally {hud.close();frame.recycle();}
        }
        Files.write(new File(directory(),"observations.json").toPath(),output.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    @Test public void threeRecordedFailuresHaveUsefulActionsWithRealAutomaticGeometryAndHud() throws Exception {
        JSONArray entries=inputs();int checked=0;
        assertEquals("loaded",Match3VisualCatalog.get(app()).boardFeedbackStatus);
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);String id=entry.getString("id");
            if(id.equals("109-398") || id.equals("109-384"))continue; // transition or earlier board, not stable feedback
            Bitmap frame=frame(entry);Match3HudReader hud=new Match3HudReader(app());
            try {
                BoardGeometry g=Match3Sampler.autoDetectGeometry(frame);assertNotNull(id,g);
                assertEquals(id,entry.getJSONObject("reviewed_geometry").getInt("rows"),g.rows);assertEquals(id,9,g.cols);
                try(Match3Sampler sampler=new Match3Sampler(app(),g)) {
                    Match3Position board=sampler.samplePosition(frame);
                    Match3Goals goals=hud.read(frame,g,100);
                    assertTrue(id+":"+hud.status(),goals.hudVerified);
                    List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(board,goals);assertFalse(id,moves.isEmpty());
                    Match3MoveValue first=moves.get(0);JSONObject evidence=Match3RuleDiagnostics.action(first);
                    for(Match3MoveValue move:moves) {
                        assertEquals(id,Match3Position.SwapPermission.YES,board.cell(move.swap.fromRow,move.swap.fromCol).swapPermission);
                        assertEquals(id,Match3Position.SwapPermission.YES,board.cell(move.swap.toRow,move.swap.toCol).swapPermission);
                    }
                    if(id.startsWith("107")) {
                        int gray=0;for(int p=0;p<g.rows*g.cols;p++)if(board.cell(p).cover==Match3Position.Cover.GRAY_FURBALL)gray++;
                        assertEquals(id,20,gray);
                        assertEquals(id,Match3Position.Effect.HORIZONTAL,board.cell(6,2).effect);
                        assertTrue(id,first.collected(Match3Goals.Kind.GRAY_FURBALL)>0);
                    } else if(id.equals("108-302") || id.equals("108-315")) {
                        assertEquals(1,goals.remaining(Match3Goals.Kind.SNOW));
                        assertEquals(Match3Position.Kind.SNOW,board.cell(1,8).kind);assertEquals(1,board.cell(1,8).layers);
                        assertTrue(id,evidence.getBoolean("all_targets_finish"));assertEquals(id,1,first.directUnits);
                        boolean removesRightSnow=false;JSONArray events=evidence.getJSONArray("events");
                        for(int n=0;n<events.length();n++) {JSONObject e=events.getJSONObject(n);
                            if(e.getString("type").equals("CLEAR") && e.getString("kind").equals("SNOW")
                                    && e.getInt("cell")==17 && e.getInt("units")==1)removesRightSnow=true;}
                        assertTrue(id,removesRightSnow);
                    } else if(id.startsWith("108")) {
                        assertTrue(id,first.hit(Match3Goals.Kind.SNOW)>=3);
                        assertEquals(id+": unknown layers cannot grant completion",0,first.directUnits);
                    } else {
                        assertEquals(id,Match3Position.Kind.BIRD,board.cell(6,2).kind);
                        assertEquals(id,Match3Position.Effect.HORIZONTAL,board.cell(6,1).effect);
                        assertEquals(id,"BIRD_LINE",evidence.getString("action_type"));
                        assertTrue(id,first.collected(Match3Goals.Kind.ICE)>1);
                        assertFalse(id,evidence.getBoolean("all_targets_finish"));assertFalse(id,evidence.getBoolean("successor_known"));
                        assertEquals(1,evidence.getJSONObject("search").getInt("depth"));assertTrue(first.reason.startsWith("魔力鸟组合"));
                        // Background gaps in the irregular envelope are not white blocks or movable pieces.
                        for(int c:new int[]{3,4,5})assertNotEquals(Match3Position.Kind.SNOW,board.cell(0,c).kind);
                        for(int c:new int[]{0,1,2,6,7,8}) {
                            assertNotEquals(Match3Position.Kind.SNOW,board.cell(7,c).kind);
                            assertNotEquals(Match3Position.SwapPermission.YES,board.cell(7,c).swapPermission);
                        }
                    }
                    checked++;
                }
            } finally {hud.close();frame.recycle();}
        }
        assertEquals(8,checked);
    }
    @Test public void ordinaryAnimalsAndGoldGlowsCannotBorrowTheBlueHorizontalArrowRule() throws Exception {
        JSONArray entries=inputs();int checked=0;
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);String id=entry.getString("id");Bitmap frame=frame(entry);
            try(Match3Sampler sampler=new Match3Sampler(app(),reviewed(frame,entry))) {
                Match3Position p=sampler.samplePosition(frame);
                int[][] negatives=id.startsWith("107")?new int[][]{{1,1},{1,2},{1,3},{3,0},{3,1},{3,3},{6,1}}
                        :id.startsWith("108")?new int[][]{{6,0},{7,0},{8,0},{8,8}}
                        :new int[][]{{1,3},{1,4},{2,3},{2,4},{6,0}};
                for(int[] cell:negatives) {
                    assertNotEquals(id+Arrays.toString(cell),Match3Position.Effect.HORIZONTAL,p.cell(cell[0],cell[1]).effect);checked++;
                }
            } finally {frame.recycle();}
        }
        assertTrue(checked>=50);
    }
    @Test public void thePartialBottomRowSurvivesCaptureScaling() throws Exception {
        JSONArray entries=inputs();
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);if(!entry.getString("id").equals("109-403"))continue;
            Bitmap original=frame(entry),scaled=Bitmap.createScaledBitmap(original,1220,2712,true);
            try {
                BoardGeometry g=Match3Sampler.autoDetectGeometry(scaled);assertNotNull(g);
                assertEquals("three real bottom cells cannot be cropped away",8,g.rows);assertEquals(9,g.cols);
            } finally {original.recycle();scaled.recycle();}
        }
    }
    private static byte[] asset(String name) throws Exception {
        try(InputStream in=app().getAssets().open(name)) {return in.readAllBytes();}
    }
    @Test public void weightedNativeArrowScoresRetainTheJavaDecisions() {
        Match3VisualCatalog catalog=Match3VisualCatalog.get(app());assertTrue(Match3NativeMath.AVAILABLE);
        java.util.Random random=new java.util.Random(109107);int checked=0;
        for(Match3VisualCatalog.Pattern source:catalog.effectEnvelopes)for(int variant=0;variant<32;variant++) {
            int[] observed=source.pixels.clone();
            for(int n=0;n<variant*3;n++)observed[random.nextInt(256)]=0xff000000|random.nextInt(1<<24);
            float first=Float.POSITIVE_INFINITY,second=Float.POSITIVE_INFINITY;String best=null;
            for(Match3VisualCatalog.Pattern p:catalog.effectEnvelopes) {
                // Weighted standalone differences deliberately use the Java path.
                float score=p.difference(observed,.145f);
                if(score<first) {if(!p.kind.equals(best))second=first;first=score;best=p.kind;}
                else if(!p.kind.equals(best))second=Math.min(second,score);
            }
            String expected=first<=.12f && second-first>=.025f?best:null;
            assertEquals(expected,Match3VisualCatalog.recognize(catalog.effectEnvelopes,observed,.12f,.025f));checked++;
        }
        assertTrue(checked>=128);
    }
    private static double p95(long[] samples) {
        long[] sorted=samples.clone();Arrays.sort(sorted);return sorted[(int)Math.ceil(sorted.length*.95)-1]/1e6;
    }
    @Test public void boundedFeedbackCostUsesTheSameEngineAndBothColdAndCachedFrames() throws Exception {
        assertTrue("isolated emulator",android.os.Build.HARDWARE.contains("ranchu"));
        byte[] fixedBytes=asset("match3-fixed-ui-v1.json");
        JSONObject base=new JSONObject(new String(fixedBytes,StandardCharsets.UTF_8));
        byte[] gallery=asset("match3-gallery-v1.json");List<byte[]> covers=Arrays.asList(asset("match3-vine-v1.json"),asset("match3-gray-v1.json"));
        String sha=Match3RuleCatalog.get(app()).sha256;
        Match3VisualCatalog[] catalogs={Match3VisualCatalog.withReviewedSupplements(base,gallery,covers,sha),Match3VisualCatalog.get(app())};
        StringBuilder fixedSha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(fixedBytes))
            fixedSha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        catalogs[0]=Match3VisualCatalog.withHudGlyphSupplement(catalogs[0],asset("match3-hud-glyphs-v1.json"),fixedSha.toString());
        assertEquals("paired readers must use the same glyphs",catalogs[1].hudGlyphCatalogSha256,catalogs[0].hudGlyphCatalogSha256);
        JSONArray entries=inputs();Bitmap[] frames=new Bitmap[entries.length()];BoardGeometry[] grids=new BoardGeometry[frames.length];
        for(int i=0;i<frames.length;i++) {frames[i]=frame(entries.getJSONObject(i));grids[i]=reviewed(frames[i],entries.getJSONObject(i));}
        JSONObject report=new JSONObject().put("scope","emulator_same_engine_sample_confirm_hud_rank_cpu_and_wall; development_JPEGs; excludes_load_io_acoustics_and_thermal")
                .put("feedback_sha256",catalogs[1].boardFeedbackSha256).put("rule_sha256",sha).put("independent",false)
                .put("frames",entries.length()).put("rounds",30).put("max_increase_percent",10);
        boolean passed=true;
        try {
            for(boolean cold:new boolean[]{true,false}) {
                Match3Sampler[][] samplers=new Match3Sampler[2][frames.length];
                Match3HudReader[] readers={new Match3HudReader(catalogs[0]),new Match3HudReader(catalogs[1])};
                long[][] wall=new long[2][30],cpu=new long[2][30];
                try {
                    for(int round=-6;round<30;round++)for(int order=0;order<2;order++) {
                        int mode=(order+(round&1))&1;long at=SystemClock.elapsedRealtimeNanos(),cpuAt=Debug.threadCpuTimeNanos();
                        for(int i=0;i<frames.length;i++) {
                            if(cold || samplers[mode][i]==null) {
                                if(samplers[mode][i]!=null)samplers[mode][i].close();
                                samplers[mode][i]=new Match3Sampler(app(),grids[i],catalogs[mode]);
                            }
                            readers[mode].clear();Match3CellConfirmation confirmation=new Match3CellConfirmation();
                            for(int repeat=0;repeat<3;repeat++) {
                                long timestamp=(round+10)*100000L+i*4000L+repeat*800;
                                Match3Position p=samplers[mode][i].samplePosition(frames[i],timestamp);
                                Match3Position confirmed=confirmation.accept(p,timestamp).position;
                                Match3Goals g=readers[mode].read(frames[i],grids[i],timestamp);
                                Match3MoveRanker.rankedMoves(confirmed,g);
                            }
                        }
                        if(round>=0) {wall[mode][round]=SystemClock.elapsedRealtimeNanos()-at;cpu[mode][round]=Debug.threadCpuTimeNanos()-cpuAt;}
                    }
                } finally {
                    for(Match3Sampler[] row:samplers)for(Match3Sampler sampler:row)if(sampler!=null)sampler.close();
                    for(Match3HudReader reader:readers)reader.close();
                }
                double wallChange=100*(p95(wall[1])/p95(wall[0])-1),cpuChange=100*(p95(cpu[1])/p95(cpu[0])-1);
                report.put(cold?"cold":"cached",new JSONObject().put("baseline_wall_p95_ms",p95(wall[0])).put("candidate_wall_p95_ms",p95(wall[1]))
                        .put("baseline_cpu_p95_ms",p95(cpu[0])).put("candidate_cpu_p95_ms",p95(cpu[1]))
                        .put("wall_change_percent",wallChange).put("cpu_change_percent",cpuChange)
                        .put("wall_samples_ns",new JSONArray(wall)).put("cpu_samples_ns",new JSONArray(cpu)));
                passed&=wallChange<=10 && cpuChange<=10;
            }
        } finally {for(Bitmap frame:frames)frame.recycle();}
        report.put("passed",passed);Files.write(new File(directory(),"cost.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
        assertTrue("unchanged 10% P95 gate; preserve cost.json on failure",passed);
    }
}
