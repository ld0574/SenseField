package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/** Isolate visual-asset overhead with the unchanged sampler/HUD/ranker in one process. */
public final class Match3FunctionalCostInstrumentedTest {
    private static byte[] asset(Context app,String name) throws Exception {
        try(java.io.InputStream stream=app.getAssets().open(name)){return stream.readAllBytes();}
    }
    private static byte[] frozenAsset(File dir,JSONObject assets,String name) throws Exception {
        JSONObject entry=assets.getJSONObject(name);File file=new File(dir,entry.getString("file"));
        assertTrue(file.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator));
        byte[] bytes=Files.readAllBytes(file.toPath());
        assertEquals(entry.getString("sha256"),Match3HoldoutAccuracyInstrumentedTest.sha256(bytes));return bytes;
    }
    private static double p95(JSONArray values) throws Exception {
        long[] sorted=new long[values.length()];for(int i=0;i<sorted.length;i++)sorted[i]=values.getLong(i);
        Arrays.sort(sorted);return sorted[(int)Math.ceil(sorted.length*.95)-1]/1e6;
    }
    private static long sum(JSONArray values) throws Exception {
        long total=0;for(int i=0;i<values.length();i++)total+=values.getLong(i);return total;
    }
    @Test public void pairedFrozenVisualCatalogsStayWithinTheTenPercentBudget() throws Exception {
        Assume.assumeTrue("isolated emulator",android.os.Build.HARDWARE.contains("ranchu")
                || android.os.Build.HARDWARE.contains("goldfish"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir=app.getExternalFilesDir("match3-holdout");assertNotNull(dir);
        File receiptFile=new File(dir,"baseline-assets.json"),manifestFile=new File(dir,"manifest.json");
        Assume.assumeTrue("explicit frozen catalog and input receipts",receiptFile.isFile() && manifestFile.isFile());
        byte[] receiptBytes=Files.readAllBytes(receiptFile.toPath()),manifestBytes=Files.readAllBytes(manifestFile.toPath());
        JSONObject receipt=new JSONObject(new String(receiptBytes,StandardCharsets.UTF_8)),assets=receipt.getJSONObject("assets");
        byte[] fixed=frozenAsset(dir,assets,"match3-fixed-ui-v1.json");
        List<byte[]> covers=Arrays.asList(asset(app,"match3-vine-v1.json"),asset(app,"match3-gray-v1.json"));
        Match3VisualCatalog old=Match3VisualCatalog.withBoardFeedback(new JSONObject(new String(fixed,StandardCharsets.UTF_8)),
                asset(app,"match3-gallery-v1.json"),covers,frozenAsset(dir,assets,"match3-board-feedback-v1.json"),Match3RuleCatalog.get(app).sha256);
        old=Match3VisualCatalog.withHudGlyphSupplement(old,frozenAsset(dir,assets,"match3-hud-glyphs-v1.json"),
                Match3HoldoutAccuracyInstrumentedTest.sha256(fixed));
        Match3VisualCatalog current=Match3VisualCatalog.get(app);
        assertEquals("loaded",old.boardFeedbackStatus);assertEquals("loaded",old.hudGlyphCatalogStatus);
        assertEquals(old.gallerySha256,current.gallerySha256);assertEquals(old.coverCatalogSha256,current.coverCatalogSha256);
        Match3VisualCatalog[] catalogs={old,current};
        JSONArray entries=new JSONObject(new String(manifestBytes,StandardCharsets.UTF_8)).getJSONArray("frames");
        Bitmap[] frames=new Bitmap[entries.length()];BoardGeometry[] grids=new BoardGeometry[frames.length];
        JSONArray pairs=new JSONArray();boolean passed=true;
        try {
            for(int i=0;i<frames.length;i++) {
                JSONObject entry=entries.getJSONObject(i);File file=new File(dir,entry.getString("file"));
                assertTrue(file.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator));
                byte[] bytes=Files.readAllBytes(file.toPath());assertEquals(entry.getString("sha256"),Match3HoldoutAccuracyInstrumentedTest.sha256(bytes));
                frames[i]=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(frames[i]);
                grids[i]=Match3Sampler.autoDetectGeometry(frames[i]);assertNotNull(grids[i]);
            }
            for(int pair=0;pair<3;pair++) {
                Match3Sampler[][] samplers=new Match3Sampler[2][frames.length];
                Match3HudReader[] readers={new Match3HudReader(old),new Match3HudReader(current)};
                JSONArray[] wall={new JSONArray(),new JSONArray()},worker={new JSONArray(),new JSONArray()},process={new JSONArray(),new JSONArray()};
                try {
                    for(int mode=0;mode<2;mode++)for(int i=0;i<frames.length;i++)samplers[mode][i]=new Match3Sampler(app,grids[i],catalogs[mode]);
                    for(int round=-6;round<15;round++)for(int order=0;order<2;order++) {
                        int mode=Math.floorMod(pair+round+order,2);long processAt=Process.getElapsedCpuTime();
                        for(int i=0;i<frames.length;i++) {
                            Match3CellConfirmation confirmation=new Match3CellConfirmation();readers[mode].clear();
                            for(int repeat=0;repeat<3;repeat++) {
                                long at=(round+10L)*100000+i*4000L+repeat*800;
                                long wallAt=SystemClock.elapsedRealtimeNanos(),cpuAt=Debug.threadCpuTimeNanos();
                                Match3Position position=samplers[mode][i].samplePosition(frames[i],at);
                                Match3Position confirmed=confirmation.accept(position,at).position;
                                Match3Goals goals=readers[mode].read(frames[i],grids[i],at);
                                Match3MoveRanker.rankedMoves(confirmed,goals);
                                if(round>=0){wall[mode].put(SystemClock.elapsedRealtimeNanos()-wallAt);worker[mode].put(Debug.threadCpuTimeNanos()-cpuAt);}
                            }
                        }
                        if(round>=0)process[mode].put(Process.getElapsedCpuTime()-processAt);
                    }
                    double wallChange=100*(p95(wall[1])/p95(wall[0])-1),workerChange=100*(p95(worker[1])/p95(worker[0])-1);
                    passed&=wallChange<=10 && workerChange<=10;
                    pairs.put(new JSONObject().put("pair",pair+1).put("baseline_wall_p95_ms",p95(wall[0])).put("candidate_wall_p95_ms",p95(wall[1]))
                            .put("wall_p95_change_percent",wallChange).put("worker_p95_change_percent",workerChange)
                            .put("process_cpu_change_percent",100*(sum(process[1])/(double)sum(process[0])-1))
                            .put("wall_samples_ns",new JSONArray(wall)).put("worker_samples_ns",new JSONArray(worker))
                            .put("process_cpu_batches_ms",new JSONArray(process)));
                } finally {
                    for(Match3Sampler[] mode:samplers)for(Match3Sampler sampler:mode)if(sampler!=null)sampler.close();
                    for(Match3HudReader reader:readers)reader.close();
                }
            }
            JSONObject out=new JSONObject().put("scope","same-process alternating frozen visual catalogs; unchanged production sampler/HUD/ranker; excludes capture/audio/recorder/game heat")
                    .put("source_sha256",InstrumentationRegistry.getArguments().getString("match3SourceSha256",""))
                    .put("manifest_sha256",Match3HoldoutAccuracyInstrumentedTest.sha256(manifestBytes))
                    .put("baseline_assets_receipt_sha256",Match3HoldoutAccuracyInstrumentedTest.sha256(receiptBytes))
                    .put("baseline_assets",assets).put("candidate_feedback_sha256",current.boardFeedbackSha256)
                    .put("candidate_hud_glyph_sha256",current.hudGlyphCatalogSha256).put("pairs",pairs).put("passed",passed);
            Files.write(new File(dir,"functional-paired-cost.json").toPath(),out.toString(2).getBytes(StandardCharsets.UTF_8));
            assertTrue("All three pairs retain the existing 10% wall/worker P95 budget; failed data are preserved",passed);
        } finally {for(Bitmap frame:frames)if(frame!=null)frame.recycle();}
    }
}
