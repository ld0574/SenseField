package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Admission and local diagnosis tests on real native pixels, including the final minified APK. */
public final class Match3ElementEvidenceInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static void drain() throws Exception { DiagnosticRecorder.IO.submit(()->{}).get(5,TimeUnit.SECONDS); }
    @Test public void repeatedAndChangedSourcesMatchAFreshFullComparison() throws Exception {
        String[] names={"screen-284-2285557292.jpg","screen-297-2285567809.jpg","screen-310-2285578323.jpg"};
        Bitmap[] photos=new Bitmap[names.length];
        for(int i=0;i<names.length;i++)try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(names[i])) {
            photos[i]=BitmapFactory.decodeStream(in);
        }
        Bitmap covered=photos[0].copy(Bitmap.Config.ARGB_8888,true);
        Canvas canvas=new Canvas(covered);Paint paint=new Paint();paint.setColor(0xffffffff);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(9);canvas.drawRect(12,322,57,367,paint);
        BoardGeometry geometry=new BoardGeometry(432,960,12,322,420,730,9,9);
        Bitmap[] sequence={photos[0],photos[0],photos[1],photos[1],covered,photos[2],photos[0]};
        try(Match3Sampler cached=new Match3Sampler(context(),geometry)) {
            for(int i=0;i<sequence.length;i++)try(Match3Sampler fresh=new Match3Sampler(context(),geometry)) {
                Match3Position actual=cached.samplePosition(sequence[i],100+800L*i);
                Match3Position full=fresh.samplePosition(sequence[i],100+800L*i);
                assertTrue("Cached and full admission must agree at frame "+i,actual.sameCells(full));
                assertEquals(fresh.directAnimals,cached.directAnimals);assertEquals(fresh.inferredAnimals,cached.inferredAnimals);
                assertEquals(fresh.uncertainAnimals,cached.uncertainAnimals);
            }
        } finally { covered.recycle();for(Bitmap photo:photos)if(photo!=null)photo.recycle(); }
    }
    @Test public void anOuterOnlyCoverChangeInvalidatesTheCachedPlainPermissionDespiteAnIdenticalFace() {
        Bitmap frame=Bitmap.createBitmap(432,960,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(frame);Paint paint=new Paint();
        canvas.drawColor(0xff1e2a58);new Match3TestSprites(context()).draw(canvas,20,320,40,'O');
        try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,20,320,340,640,8,8))) {
            Match3Position first=sampler.samplePosition(frame,100);assertTrue(first.cell(0,0).swappable);
            int[] face=sampler.elementPatch(0,0).clone();
            paint.setColor(0xffffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(8);
            canvas.drawRect(20,320,60,360,paint);
            Match3Position covered=sampler.samplePosition(frame,900);
            assertArrayEquals("The central identity patch was not changed",face,sampler.elementPatch(0,0));
            assertEquals('O',covered.cell(0,0).color);assertFalse("The envelope must be rechecked",covered.cell(0,0).swappable);
            assertEquals(0,sampler.directAnimals);
        } finally {frame.recycle();}
    }
    @Test public void repeatedAnimalColouredShapesCannotBecomeAnimalsOrSeedOneAnother() {
        Bitmap frame=Bitmap.createBitmap(432,960,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(frame);Paint paint=new Paint();
        canvas.drawColor(0xff34c8ed);paint.setColor(0xff1e2a58);canvas.drawRect(20,320,356,656,paint);
        int[] colors={0xffed2020,0xffb07020,0xffeeba20,0xff40da35,0xff50a9dd,0xffba40dd};
        for(int r=0;r<8;r++)for(int c=0;c<8;c++) {
            paint.setColor(colors[(r+c)%6]);canvas.drawCircle(20+(c+.5f)*42,320+(r+.5f)*42,17,paint);
        }
        try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,20,320,356,656,8,8))) {
            Match3CellConfirmation confirmation=new Match3CellConfirmation();
            for(int i=0;i<3;i++) {
                Match3Position position=sampler.samplePosition(frame,100+800L*i);
                for(int r=0;r<8;r++)for(int c=0;c<8;c++)assertFalse("Hue alone cannot certify "+r+","+c,position.cell(r,c).swappable);
                assertTrue(Match3MoveRanker.rankedMoves(confirmation.accept(position,100+800L*i).position,Match3Goals.unknown(0)).isEmpty());
                assertEquals(0,sampler.directAnimals);assertEquals(0,sampler.inferredAnimals);
            }
        } finally {frame.recycle();}
    }
    @Test public void fixedIdentityAndPlainEnvelopeAreSeparateAndUncertainCoversCannotSeedReferences() throws Exception {
        // This invariant's truth is the reviewed fixed catalog. Experimental
        // sidecar labels are evaluated against human truth by the holdout gate.
        Match3VisualCatalog catalog;
        try(InputStream in=context().getAssets().open("match3-fixed-ui-v1.json")) {
            catalog=Match3VisualCatalog.withGallery(new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)),null);
        }
        int verified=0;
        for(Match3VisualCatalog.Pattern pattern:catalog.animals) {
            Match3VisualCatalog.CellCache plain=new Match3VisualCatalog.CellCache();plain.patch=pattern.pixels.clone();plain.envelope=pattern.pixels.clone();
            Match3Position.Cell animal=catalog.animal(plain);
            assertNotNull(pattern.kind,animal);assertEquals(pattern.kind.charAt(7),animal.color);verified++;
            Match3VisualCatalog.CellCache covered=new Match3VisualCatalog.CellCache();covered.patch=pattern.pixels.clone();covered.envelope=pattern.pixels.clone();
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(x<3||x>12||y<3||y>12)covered.patch[y*16+x]=0xfffaffff;
            Arrays.fill(covered.envelope,0xfffaffff);
            Match3Position.Cell identity=catalog.animal(covered);assertNotNull(pattern.kind,identity);
            assertEquals(pattern.kind.charAt(7),identity.color);assertFalse(pattern.kind,identity.swappable);
            assertEquals(Match3Position.SwapPermission.UNKNOWN,identity.swapPermission);assertEquals('#',identity.code());
            assertNull(catalog.trustedReference(covered));
        }
        assertEquals(20,verified);
        for(int index=0;index<catalog.animalBodies.size();index++) {
            Match3VisualCatalog.CellCache actual=new Match3VisualCatalog.CellCache();actual.envelope=catalog.animalBodies.get(index).copyPatch();
            actual.patch=Match3VisualCatalog.patch(actual.envelope,16,1,1,15,15);
            Match3Position.Cell cell=catalog.animal(actual);
            assertNotNull("Complete sprite "+index,cell);assertEquals((char)catalog.bodyColors.get(index),cell.color);assertTrue("Complete sprite "+index,cell.swappable);
        }
    }
    @Test public void existingManuallyLabelledFaultBoardSeparatesMissesWrongColoursAndUnsafeAdmissions() throws Exception {
        Bitmap frame;
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("screen-284-2285557292.jpg")) {
            frame=BitmapFactory.decodeStream(in);
        }
        String[] labels={"OPGGROPI#","OPRGPRGOI","GRPRGORGI","PGGOORPRP","RPOROPRGR","ROROROGOP","#POGRRPRI","PRGRPGORI","HHHRGOHHH"};
        int missed=0,wrong=0,unsafe=0,animals=0,uncertain=0;org.json.JSONArray details=new org.json.JSONArray();
        try(Match3Sampler sampler=new Match3Sampler(context(),new BoardGeometry(432,960,12,322,420,730,9,9))) {
            Match3Position p=sampler.samplePosition(frame,100);
            char[][] legacy=Match3Sampler.sample(frame,new BoardGeometry(432,960,12,322,420,730,9,9),java.util.Collections.emptyList());
            Match3VisualCatalog catalog=Match3VisualCatalog.get(context());
            for(int r=0;r<9;r++)for(int c=0;c<9;c++) {
                char label=labels[r].charAt(c);Match3Position.Cell cell=p.cell(r,c);
                if(Match3Sampler.isMovable(label)) {
                    animals++;if(cell.kind!=Match3Position.Kind.ANIMAL)missed++;else if(cell.color!=label)wrong++;
                    if(!cell.swappable)uncertain++;
                    if(!cell.swappable) {
                        int[] patch=sampler.elementPatch(r,c);Match3AnimalAppearance.Face face=new Match3AnimalAppearance.Face('.',patch);
                        Match3AnimalAppearance.Body body=new Match3AnimalAppearance.Body(sampler.elementEnvelope(r,c));float nearest=Float.POSITIVE_INFINITY;
                        for(int i=0;i<catalog.animalBodies.size();i++)if(catalog.bodyColors.get(i)==label)
                            nearest=Math.min(nearest,body.difference(catalog.animalBodies.get(i)));
                        details.put(new JSONObject().put("row",r+1).put("col",c+1).put("label",String.valueOf(label))
                                .put("kind",cell.kind.name()).put("color",String.valueOf(cell.color)).put("legacy",String.valueOf(legacy[r][c]))
                                .put("fixed_identity",Match3VisualCatalog.recognize(catalog.animals,patch,.12f,.025f))
                                .put("face_identity",String.valueOf(Match3AnimalAppearance.recognize(face,catalog.animalFaces,1)))
                                .put("body_error",Float.isFinite(nearest)?nearest:-1));
                    }
                } else if(cell.swappable)unsafe++;
            }
        } finally {frame.recycle();}
        JSONObject report=new JSONObject().put("source","manually_labelled_development_fault_board")
                .put("known_animals",animals).put("identity_misses",missed).put("wrong_colours",wrong)
                .put("cover_abstentions",uncertain).put("unknown_or_obstacle_admitted_as_swappable",unsafe)
                .put("abstention_details",details)
                .put("independent_holdout_accuracy",false);
        File out=context().getExternalFilesDir("match3-release-capture");assertNotNull(out);out.mkdirs();
        Files.write(new File(out,"element-error-categories.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(report.toString(),0,missed);assertEquals(report.toString(),0,wrong);assertEquals(report.toString(),0,unsafe);
        // Manual identity labels do not require the matcher to certify a partially seen envelope.
        // Report cover abstentions separately; they must never be silently counted as identity errors or accuracy successes.
        assertTrue("The development board must still have certified ordinary animals: "+report,animals>uncertain);
    }
    @Test public void unknownRepresentativesExportLocallyAndRespectTheExistingImageSwitch() throws Exception {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        assertFalse(Match3LiveService.isRunning());assertFalse(CaptureService.isRunning());
        Bitmap frame=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);frame.eraseColor(0xffaa7040);
        int[] patch=new int[256];for(int i=0;i<256;i++)patch[i]=0xff000000|(i*1279&0xffffff);
        Match3UnknownElements groups=new Match3UnknownElements();
        Match3UnknownElements.Observation[][] observations={{new Match3UnknownElements.Observation(
                Match3Position.Cell.obstacle(Match3Position.Kind.UNKNOWN,-1),patch)}};
        groups.observe(observations,100);groups.observe(observations,900);
        Match3UnknownElements.Sample sample=groups.observe(observations,1700).get(0);
        DiagnosticRecorder recorder=DiagnosticRecorder.start(context(),UUID.randomUUID().toString(),SystemClock.elapsedRealtime(),DiagnosticGame.MATCH3);
        try {
            drain();recorder.setImagesEnabled(false);recorder.elementSample(frame,new BoardGeometry(128,128,0,0,128,128,1,1),sample);drain();
            assertFalse(new File(recorder.directory,"elements/"+sample.filename()).exists());
            recorder.setImagesEnabled(true);recorder.elementSample(frame,new BoardGeometry(128,128,0,0,128,128,1,1),sample);drain();
            File image=new File(recorder.directory,"elements/"+sample.filename());assertTrue(image.isFile());
            Bitmap saved=BitmapFactory.decodeFile(image.getPath());assertEquals(64,saved.getWidth());assertEquals(64,saved.getHeight());saved.recycle();
            File zip=new File(context().getCacheDir(),"element-evidence-test.zip");DiagnosticArchive.export(recorder.directory,zip,"");
            try(java.util.zip.ZipFile exported=new java.util.zip.ZipFile(zip)) { assertNotNull(exported.getEntry("elements/"+sample.filename())); }
            zip.delete();
            // Force the buffered event writer to flush through an ordinary checkpoint before finish below.
            recorder.finish("element_evidence_test");drain();String log=new String(Files.readAllBytes(
                    new File(recorder.directory,"events.jsonl").toPath()),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(log.contains("\"rule_supported\":false"));assertTrue(log.contains("cover") || log.contains("identity_or_rule_unverified"));
        } finally {
            recorder.finish("element_evidence_test_cleanup");drain();DiagnosticArchive.delete(recorder.directory);
            if(DiagnosticRecorder.current==recorder)DiagnosticRecorder.current=null;frame.recycle();
        }
    }
}
