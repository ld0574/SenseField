package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class Match3HudGlyphTest {
    private static Path assets() {
        Path p=Path.of("src/main/assets");return Files.isDirectory(p)?p:Path.of("app/src/main/assets");
    }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    private static byte[] fixed() throws Exception { return Files.readAllBytes(assets().resolve("match3-fixed-ui-v1.json")); }
    private static JSONObject supplement() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(assets().resolve("match3-hud-glyphs-v1.json")),StandardCharsets.UTF_8));
    }
    private static Match3VisualCatalog catalog() throws Exception { return Match3VisualCatalog.withGallery(new JSONObject(new String(fixed(),StandardCharsets.UTF_8)),null); }
    private static Match3VisualCatalog load(JSONObject extra) throws Exception {
        return Match3VisualCatalog.withHudGlyphSupplement(catalog(),extra.toString().getBytes(StandardCharsets.UTF_8),sha(fixed()));
    }
    @Test public void anotherFramesReviewedSevenCompetesWithoutChangingExistingGlyphDecisions() throws Exception {
        Match3VisualCatalog original=catalog(),augmented=load(supplement());
        assertEquals("loaded",augmented.hudGlyphCatalogStatus);assertEquals(64,augmented.hudGlyphCatalogSha256.length());
        assertEquals(original.glyphs.size()+1,augmented.glyphs.size());
        String observedSeven="1111111111111111111111111111111111111111000000111100000011110000011110100001111010000111101000111000000011100000001110000011111000001111000000111100000011110000";
        assertEquals(2,original.digit(observedSeven,8/11f));assertEquals(7,augmented.digit(observedSeven,8/11f));
        for(Match3VisualCatalog.Glyph glyph:original.glyphs)
            assertEquals("Frozen digit "+glyph.digit,original.digit(glyph.bits,glyph.aspect),augmented.digit(glyph.bits,glyph.aspect));
        assertEquals(-1,augmented.digit("0".repeat(160),8/11f));assertEquals(-1,augmented.digit("1".repeat(160),8/11f));
    }
    @Test public void conflictingLabelsAbstainUnderTheExistingMargin() throws Exception {
        JSONObject extra=supplement();JSONObject conflict=new JSONObject(extra.getJSONArray("glyphs").getJSONObject(0).toString());
        conflict.put("digit",2);extra.getJSONArray("glyphs").put(conflict);
        Match3VisualCatalog augmented=load(extra);
        assertEquals("loaded",augmented.hudGlyphCatalogStatus);
        assertEquals(-1,augmented.digit(conflict.getString("bits"),(float)conflict.getDouble("aspect")));
    }
    @Test public void invalidLaterGlyphCannotPartiallyAppendOrDisableTheCatalog() throws Exception {
        int size=catalog().glyphs.size();JSONObject extra=supplement();
        JSONObject bad=new JSONObject(extra.getJSONArray("glyphs").getJSONObject(0).toString());bad.put("bits","broken");extra.getJSONArray("glyphs").put(bad);
        Match3VisualCatalog rejected=load(extra);assertTrue(rejected.available);assertEquals(size,rejected.glyphs.size());
        assertTrue(rejected.hudGlyphCatalogStatus.startsWith("rejected:"));assertEquals("",rejected.hudGlyphCatalogSha256);
        for(String key:new String[]{"reviewed","source_sha256","source_box","digit"}) {
            extra=supplement();JSONObject item=extra.getJSONArray("glyphs").getJSONObject(0);
            if(key.equals("reviewed"))item.put(key,false);
            else if(key.equals("source_sha256"))item.put(key,"unknown");
            else if(key.equals("source_box"))item.put(key,new JSONArray().put(0).put(0).put(500).put(1));
            else item.put(key,7.5);
            assertEquals(key,size,load(extra).glyphs.size());
        }
    }
    @Test public void provenanceAndResourceBudgetsFallBackAtomically() throws Exception {
        int size=catalog().glyphs.size();JSONObject extra=supplement();
        extra.getJSONObject("metadata").put("fixed_catalog_sha256","0".repeat(64));assertEquals(size,load(extra).glyphs.size());
        extra=supplement();extra.getJSONObject("metadata").put("independent",true);assertEquals(size,load(extra).glyphs.size());
        extra=supplement();JSONObject glyph=extra.getJSONArray("glyphs").getJSONObject(0);
        for(int i=1;i<9;i++)extra.getJSONArray("glyphs").put(glyph);assertEquals(size,load(extra).glyphs.size());
        Match3VisualCatalog rejected=Match3VisualCatalog.withHudGlyphSupplement(catalog(),new byte[4097],sha(fixed()));
        assertEquals(size,rejected.glyphs.size());assertTrue(rejected.hudGlyphCatalogStatus.startsWith("rejected:"));
        assertEquals("absent",Match3VisualCatalog.withHudGlyphSupplement(catalog(),null,sha(fixed())).hudGlyphCatalogStatus);
    }
}
