package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The diagnostic side channel must say which half of the admission line stopped a cell:
 * the absolute distance or the lead over the nearest other kind.
 */
public final class Match3AdmissionReadoutTest {
    private static Match3VisualCatalog.Pattern pattern(String kind,int rgb) throws Exception {
        StringBuilder pixels=new StringBuilder();
        for(int i=0;i<256;i++)pixels.append(String.format(Locale.ROOT,"%06x",rgb));
        return new Match3VisualCatalog.Pattern(new JSONObject().put("kind",kind).put("rule","unverified")
                .put("pixels_rgb",pixels.toString()).put("mask_bits","1".repeat(256)));
    }
    private static int[] flat(int rgb) { int[] pixels=new int[256]; Arrays.fill(pixels,0xff000000|rgb); return pixels; }
    private static final List<Match3VisualCatalog.Pattern> PAIR = pair();
    private static List<Match3VisualCatalog.Pattern> pair() {
        try { return Arrays.asList(pattern("kind_a",0x123456),pattern("kind_b",0x17385b)); }
        catch(Exception error) { throw new IllegalStateException(error); }
    }

    @Test public void leadTooSmallNamesTheWinnerAndBothDistances() throws Exception {
        // Nearest other kind sits 14/765 away: below the 0.025 lead the admission requires.
        Match3VisualCatalog.Admission read=new Match3VisualCatalog.Admission();
        assertNull(Match3VisualCatalog.recognize(PAIR,flat(0x123456),.12f,.025f,read));
        assertEquals("kind_a",read.template);
        assertEquals(0f,read.error,1e-4f);
        assertEquals(0.0183f,read.otherKind,1e-4f);
        assertEquals(0.0183f,Match3VisualCatalog.Admission.distance(read.otherKind),1e-4f);
    }

    @Test public void acceptedReadingKeepsTheSameTwoDistances() throws Exception {
        List<Match3VisualCatalog.Pattern> separated=Arrays.asList(
                pattern("kind_a",0x123456),pattern("kind_b",0x173c64));   // 27/765 apart
        Match3VisualCatalog.Admission read=new Match3VisualCatalog.Admission();
        assertEquals("kind_a",Match3VisualCatalog.recognize(separated,flat(0x123456),.12f,.025f,read));
        assertEquals("kind_a",read.template);
        assertEquals(0f,read.error,1e-4f);
        assertEquals(0.0353f,read.otherKind,1e-4f);
    }

    @Test public void absoluteDistanceRejectionStillNamesTheTemplate() throws Exception {
        // 100/765 from kind_a: farther than the 0.12 line, closer than anything else.
        Match3VisualCatalog.Admission read=new Match3VisualCatalog.Admission();
        List<Match3VisualCatalog.Pattern> far=Arrays.asList(
                pattern("kind_a",0x123456),pattern("kind_b",0xf0f0f0));
        assertNull(Match3VisualCatalog.recognize(far,flat(0x3a5472),.12f,.025f,read));
        assertEquals("kind_a",read.template);
        assertEquals(0.1307f,read.error,1e-4f);
        assertTrue("A contender cut by the lower bound stays unknown, not fabricated",
                Float.isInfinite(read.otherKind));
        assertEquals(-1f,Match3VisualCatalog.Admission.distance(read.otherKind),0f);
    }

    @Test public void everyCallClearsThePreviousCellReading() {
        Match3VisualCatalog.Admission read=new Match3VisualCatalog.Admission();
        Match3VisualCatalog.recognize(PAIR,flat(0x123456),.12f,.025f,read);
        assertEquals("kind_a",read.template);
        Match3VisualCatalog.recognize(Collections.emptyList(),flat(0x123456),.12f,.025f,read);
        assertNull("A reused holder cannot report the previous cell",read.template);
        assertEquals(-1f,Match3VisualCatalog.Admission.distance(read.error),0f);
        assertEquals(-1f,Match3VisualCatalog.Admission.distance(read.otherKind),0f);
    }

    private static Match3UnknownElements.Observation review() {
        int[] patch=flat(0x203040);
        for(int y=4;y<12;y++)for(int x=4;x<12;x++)patch[y*16+x]=0xffe0d0a0;
        return new Match3UnknownElements.Observation(
                Match3Position.Cell.obstacle(Match3Position.Kind.SURFACE,-1),patch);
    }
    private static Match3UnknownElements.Sample firstSample() {
        Match3UnknownElements groups=new Match3UnknownElements();
        Match3UnknownElements.Observation[][] grid={{review()}};
        for(long at:new long[]{100,600,1100}) {
            List<Match3UnknownElements.Sample> samples=groups.observe(grid,at);
            if(!samples.isEmpty())return samples.get(0);
        }
        throw new IllegalStateException("A stable review cell produced no sample");
    }

    @Test public void elementLineCarriesTheAdmissionReadoutAndNeverDropsDistances() {
        Match3UnknownElements.Sample sample=firstSample();
        Match3VisualCatalog.Admission read=new Match3VisualCatalog.Admission();
        read.template="kind_a";read.error=.1307f;read.otherKind=Float.POSITIVE_INFINITY;
        JSONObject data=DiagnosticRecorder.elementObservedData(sample,read);
        assertEquals("kind_a",data.optString("admission_template"));
        assertEquals(.1307f,(float)data.optDouble("admission_best_distance"),1e-4f);
        assertEquals(-1f,(float)data.optDouble("admission_second_kind_distance"),1e-4f);
        assertEquals(sample.row+1,data.optInt("row"));
        assertEquals(sample.col+1,data.optInt("col"));

        JSONObject without=DiagnosticRecorder.elementObservedData(sample,null);
        assertFalse(without.has("admission_template"));
        assertTrue("A cell never classified must still report both distances as -1",
                without.has("admission_best_distance") && without.has("admission_second_kind_distance"));
        assertEquals(-1f,(float)without.optDouble("admission_best_distance"),1e-4f);
        assertEquals(-1f,(float)without.optDouble("admission_second_kind_distance"),1e-4f);
    }

    @Test public void sideChannelDoesNotChangeAnyAdmissionDecision() throws Exception {
        List<Match3VisualCatalog.Pattern> mixed=new ArrayList<>(PAIR);
        mixed.add(pattern("kind_c",0x990022));
        for(int variant=0;variant<40;variant++) {
            int[] observed=flat(0x123456+variant*0x000100);
            Match3VisualCatalog.Admission read=new Match3VisualCatalog.Admission();
            assertEquals(Match3VisualCatalog.recognize(mixed,observed,.12f,.025f),
                    Match3VisualCatalog.recognize(mixed,observed,.12f,.025f,read));
        }
    }
}
