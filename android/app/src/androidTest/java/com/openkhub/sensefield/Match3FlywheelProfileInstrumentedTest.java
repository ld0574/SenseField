package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;
import org.junit.Test;

/** Explicit developer method trace; tracing overhead is never a cost-gate result. */
public final class Match3FlywheelProfileInstrumentedTest {
    @Test public void traceOneColdPairedBatchForAttribution() throws Exception {
        String hardware=android.os.Build.HARDWARE;assertTrue(hardware.contains("ranchu") || hardware.contains("goldfish"));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir=context.getExternalFilesDir("match3-holdout");assertNotNull(dir);
        JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(dir,"manifest.json").toPath()),StandardCharsets.UTF_8));
        JSONArray entries=manifest.getJSONArray("frames");assertEquals(10,entries.length());
        Bitmap[] frames=new Bitmap[entries.length()];BoardGeometry[] grids=new BoardGeometry[frames.length];
        for(int i=0;i<frames.length;i++) {
            byte[] bytes=Files.readAllBytes(new File(dir,entries.getJSONObject(i).getString("file")).toPath());
            assertEquals(entries.getJSONObject(i).getString("sha256"),Match3HoldoutAccuracyInstrumentedTest.sha256(bytes));
            frames[i]=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(frames[i]);
            grids[i]=Match3Sampler.autoDetectGeometry(frames[i]);assertNotNull(grids[i]);
        }
        JSONObject base;
        try(InputStream in=context.getAssets().open("match3-fixed-ui-v1.json")) {base=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        Match3VisualCatalog[] catalogs={Match3VisualCatalog.withGallery(base,null),Match3VisualCatalog.get(context)};
        try {
            for(int mode=0;mode<2;mode++) {
                Match3HudReader hud=new Match3HudReader(catalogs[mode]);Match3FlywheelHarvest collector=new Match3FlywheelHarvest();
                Debug.startMethodTracingSampling(new File(dir,mode==0?"baseline-method.trace":"candidate-method.trace").toString(),16*1024*1024,100);
                try {
                    for(int i=0;i<frames.length;i++)try(Match3Sampler sampler=new Match3Sampler(context,grids[i],catalogs[mode])) {
                        hud.clear();Match3UnknownElements unknown=new Match3UnknownElements();Match3CellConfirmation confirmation=new Match3CellConfirmation();
                        for(int repeat=0;repeat<3;repeat++) {
                            long at=100000+i*4000L+repeat*800;
                            Match3Position position=sampler.samplePosition(frames[i],at);
                            unknown.observe(sampler.reviewElements(position),at);confirmation.accept(position,at);
                            Match3Goals goals=hud.read(frames[i],grids[i],at);Match3MoveRanker.rankedMoves(position,goals);
                            if(mode==1)collector.collect(sampler,position,hud,at,data->{data.toString();return true;});
                        }
                        collector.resetStability();
                    }
                } finally {Debug.stopMethodTracing();hud.close();}
            }
        } finally {for(Bitmap frame:frames)frame.recycle();}
    }
}
