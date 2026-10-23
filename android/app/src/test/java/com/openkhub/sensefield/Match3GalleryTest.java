package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public final class Match3GalleryTest {
    private JSONObject base() throws Exception {
        Path path=Path.of("src/main/assets/match3-fixed-ui-v1.json");
        if(!Files.isRegularFile(path))path=Path.of("app/src/main/assets/match3-fixed-ui-v1.json");
        return new JSONObject(new String(Files.readAllBytes(path),StandardCharsets.UTF_8));
    }
    private JSONObject pattern(String kind,String rule) throws Exception {
        return new JSONObject().put("kind",kind).put("rule",rule)
                .put("pixels_rgb","123456".repeat(256)).put("mask_bits","1".repeat(256));
    }
    private byte[] gallery(JSONArray cells) throws Exception {
        return new JSONObject().put("format","match3-gallery-v1").put("id","test-gallery")
                .put("cells",cells).toString().getBytes(StandardCharsets.UTF_8);
    }
    @Test public void compactDescriptorsMatchArgbArraysExactly() throws Exception {
        Match3VisualCatalog.Pattern compact=new Match3VisualCatalog.Pattern(pattern("honey","single_clear_honey"));
        int[] pixels=new int[256];java.util.Arrays.fill(pixels,0xff123456);
        assertEquals(0,compact.difference(pixels),0);
        assertEquals(256,compact.supported);
    }
    @Test public void packedDescriptorsPreserveEveryRgbByteAndRejectAmbiguousFormats() throws Exception {
        JSONObject input=pattern("honey","single_clear_honey");byte[] rgb=new byte[768];
        new java.util.Random(8283).nextBytes(rgb);input.remove("pixels_rgb");
        input.put("pixels_base64",java.util.Base64.getEncoder().encodeToString(rgb));
        Match3VisualCatalog.Pattern packed=new Match3VisualCatalog.Pattern(input);
        for(int i=0;i<256;i++)assertEquals(0xff000000|(rgb[i*3]&255)<<16|(rgb[i*3+1]&255)<<8|(rgb[i*3+2]&255),packed.pixels[i]);
        input.put("pixels_rgb","123456".repeat(256));
        try {new Match3VisualCatalog.Pattern(input);fail("Ambiguous pixels admitted");}catch(IllegalArgumentException expected) { }
        input.remove("pixels_rgb");input.put("pixels_base64","A".repeat(1023));
        try {new Match3VisualCatalog.Pattern(input);fail("Truncated pixels admitted");}catch(IllegalArgumentException expected) { }
    }
    @Test public void channelSumPruningPreservesFullMaskDistancesAndAmbiguity() throws Exception {
        java.util.Random random=new java.util.Random(16238);
        java.util.List<Match3VisualCatalog.Pattern> references=new java.util.ArrayList<>();
        for(int n=0;n<6;n++) {
            JSONObject value=pattern("kind"+n,"unverified");StringBuilder pixels=new StringBuilder(),mask=new StringBuilder();
            for(int i=0;i<256;i++) { pixels.append(String.format(java.util.Locale.ROOT,"%06x",random.nextInt(1<<24)));mask.append(n%2==0 || i%16>=2 && i%16<14?'1':'0'); }
            value.put("pixels_rgb",pixels.toString()).put("mask_bits",mask.toString());references.add(new Match3VisualCatalog.Pattern(value));
        }
        for(int variant=0;variant<120;variant++) {
            int[] observed=references.get(variant%6).pixels.clone();
            for(int i=0;i<variant*2;i++)observed[random.nextInt(256)]=0xff000000|random.nextInt(1<<24);
            float best=Float.POSITIVE_INFINITY,second=Float.POSITIVE_INFINITY;String winner=null;
            for(Match3VisualCatalog.Pattern reference:references) {
                float full=reference.difference(observed);
                if(full<best) { second=best;best=full;winner=reference.kind; }else second=Math.min(second,full);
            }
            String expected=best<=.12f && second-best>=.025f?winner:null;
            assertEquals(expected,Match3VisualCatalog.recognize(references,observed,.12f,.025f));
        }
    }
    @Test public void plainCyanCannotAcquireAnIceFlowerIdentityFromColourDistance() throws Exception {
        JSONObject flower=pattern("iceflower","multistage_iceflower");
        StringBuilder rgb=new StringBuilder();
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)rgb.append((x+y)%3==0?"0066cc":"33aacc");
        flower.put("pixels_rgb",rgb.toString());
        Match3VisualCatalog.Pattern reference=new Match3VisualCatalog.Pattern(flower);
        int[] sky=new int[256];java.util.Arrays.fill(sky,0xff3399cc);
        assertFalse(Match3VisualCatalog.obstacleIdentity(Collections.singletonList(reference),"iceflower",sky));
        assertTrue(Match3VisualCatalog.obstacleIdentity(Collections.singletonList(reference),"iceflower",reference.pixels));
    }
    @Test public void rejectedGalleryCannotPartiallyAppendOrDisableTheBaseCatalog() throws Exception {
        JSONObject base=base();String before=base.toString();
        Match3VisualCatalog plain=Match3VisualCatalog.withGallery(base,null);
        Match3VisualCatalog rejected=Match3VisualCatalog.withGallery(base,gallery(new JSONArray()
                .put(pattern("honey","single_clear_honey")).put(pattern("coin","unverified"))));
        assertTrue(rejected.available);assertTrue(rejected.galleryStatus.startsWith("rejected:"));
        assertEquals(plain.cells.size(),rejected.cells.size());assertEquals(before,base.toString());
    }
    @Test public void loadedGalleryPinsItsBytesAndOverBudgetGalleryFallsBack() throws Exception {
        Match3VisualCatalog loaded=Match3VisualCatalog.withGallery(base(),gallery(new JSONArray().put(pattern("honey","single_clear_honey"))));
        assertEquals("loaded",loaded.galleryStatus);assertEquals("test-gallery",loaded.galleryId);
        assertEquals(64,loaded.gallerySha256.length());
        assertTrue(Match3VisualCatalog.withGallery(base(),new byte[1048577]).galleryStatus.startsWith("rejected:"));
    }
    @Test public void legacyGoalIconsWaitForExplicitReviewAndDoNotConfoundTheFixedGoals() throws Exception {
        JSONObject legacy=new JSONObject().put("format","match3-gallery-v1").put("id","legacy")
                .put("goals",new JSONArray().put(pattern("ICEFLOWER","iceflower_goal_icon")));
        Match3VisualCatalog loaded=Match3VisualCatalog.withGallery(base(),legacy.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("loaded",loaded.galleryStatus);assertEquals(1,loaded.galleryGoalsPendingReview);
        assertEquals(Match3VisualCatalog.withGallery(base(),null).goals.size(),loaded.goals.size());
        legacy.getJSONArray("goals").getJSONObject(0).put("reviewed",true).put("rule","reviewed_goal_icon");
        Match3VisualCatalog reviewed=Match3VisualCatalog.withGallery(base(),legacy.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(0,reviewed.galleryGoalsPendingReview);assertEquals(loaded.goals.size()+1,reviewed.goals.size());
    }
    @Test public void reviewedHoneyAndIceFlowerRemainNonSwappableAndContributeOnlyTheDirectRule() {
        for(Match3Position.Kind kind:new Match3Position.Kind[]{Match3Position.Kind.HONEY,Match3Position.Kind.ICEFLOWER}) {
            Match3Position.Cell[][] cells=new Match3Position.Cell[3][3];
            String[] rows={"ROR","YRY","BGB"};
            for(int r=0;r<3;r++)for(int c=0;c<3;c++)cells[r][c]=Match3Position.Cell.animal(rows[r].charAt(c));
            cells[1][0]=Match3Position.Cell.obstacle(kind,1);assertFalse(cells[1][0].swappable);
            Match3Position board=new Match3Position(cells);
            Match3Goals.Kind target=kind==Match3Position.Kind.HONEY?Match3Goals.Kind.HONEY:Match3Goals.Kind.ICEFLOWER;
            Match3Goals goals=new Match3Goals(1,10,Collections.singletonList(new Match3Goals.Target(0,target,1,false)),true,0);
            boolean found=false;
            for(Match3MoveValue move:Match3MoveRanker.rankedMoves(board,goals))if(move.swap.fromRow==0 && move.swap.fromCol==1
                    && move.swap.toRow==1 && move.swap.toCol==1) {
                found=true;assertEquals(1,move.hit(target));assertEquals(1,move.collected(target));
            }
            assertTrue(found);
        }
    }
    @Test public void flowerHudIdentityLoadsWithoutAcquiringIceFlowerOrTaskRules() throws Exception {
        JSONObject icon=pattern("FLOWER","reviewed_goal_icon").put("reviewed",true);
        StringBuilder rgb=new StringBuilder();
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)rgb.append(x<8?"334488":"88ccff");
        icon.put("pixels_rgb",rgb.toString());
        JSONObject gallery=new JSONObject().put("format","match3-gallery-v1").put("id","synthetic-flower-goal")
                .put("goals",new JSONArray().put(icon));
        Match3VisualCatalog loaded=Match3VisualCatalog.withGallery(base(),gallery.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("loaded",loaded.galleryStatus);assertEquals(0,loaded.galleryGoalsPendingReview);
        Match3VisualCatalog.Pattern flower=null;
        for(Match3VisualCatalog.Pattern candidate:loaded.goals)if(candidate.kind.equals("FLOWER"))flower=candidate;
        assertNotNull(flower);
        Match3Goals.Target target=loaded.goalTarget(0,Match3Goals.Kind.FLOWER,7,false,"",flower.pixels);
        assertEquals(Match3Goals.Kind.FLOWER,target.kind);assertEquals(7,target.remaining);
        assertEquals(Match3Goals.Mode.UNKNOWN,target.mode);
        assertEquals("FLOWER",Match3VisualCatalog.recognize(Collections.singletonList(flower),flower.pixels,.14f,.025f));
        Match3VisualCatalog.Pattern uniform=new Match3VisualCatalog.Pattern(pattern("FLOWER","reviewed_goal_icon"));
        assertNull(Match3VisualCatalog.recognize(Collections.singletonList(uniform),uniform.pixels,.14f,.025f));
        for(String mode:new String[]{"CLEAR","TRIGGER","CHARGE"}) {
            icon.put("goal_binding",new JSONObject().put("reviewed",true).put("event_mode",mode).put("evidence","unverified_flower_event"));
            try { new Match3VisualCatalog.Pattern(icon);fail("Flower acquired an unverified goal mode"); }
            catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void coverSupplementPinsRulesAndCannotPartiallyChangeTheLegacyGallery() throws Exception {
        Path assets=Path.of("src/main/assets");if(!Files.isDirectory(assets))assets=Path.of("app/src/main/assets");
        byte[] old=Files.readAllBytes(assets.resolve("match3-gallery-v1.json"));
        byte[] extra=Files.readAllBytes(assets.resolve("match3-vine-v1.json"));
        StringBuilder sha=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(
                Files.readAllBytes(assets.resolve("match3-rules-v1.json"))))sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        JSONObject base=base();String before=base.toString();byte[] preserved=old.clone();
        Match3VisualCatalog original=Match3VisualCatalog.withGallery(base,old);
        Match3VisualCatalog admitted=Match3VisualCatalog.withReviewedSupplement(base,old,extra,sha.toString());
        assertEquals("loaded",admitted.galleryStatus);assertTrue(admitted.cells.size()>original.cells.size());
        assertTrue(admitted.goals.size()>original.goals.size());assertEquals(original.galleryGoalsPendingReview,admitted.galleryGoalsPendingReview);
        assertEquals(original.gallerySha256,admitted.gallerySha256);assertEquals("loaded",admitted.coverCatalogStatus);
        assertEquals(64,admitted.coverCatalogSha256.length());
        Match3VisualCatalog rejected=Match3VisualCatalog.withReviewedSupplement(base,old,extra,"0".repeat(64));
        assertTrue(rejected.coverCatalogStatus.startsWith("rejected:"));assertEquals(original.cells.size(),rejected.cells.size());
        assertEquals(original.goals.size(),rejected.goals.size());assertEquals(original.gallerySha256,rejected.gallerySha256);
        assertArrayEquals(preserved,old);assertEquals(before,base.toString());
    }
    @Test public void grayAndVineSidecarsShareTheTotalBudgetAndRejectAtomically() throws Exception {
        Path assets=Path.of("src/main/assets");if(!Files.isDirectory(assets))assets=Path.of("app/src/main/assets");
        byte[] gallery=Files.readAllBytes(assets.resolve("match3-gallery-v1.json"));
        byte[] vine=Files.readAllBytes(assets.resolve("match3-vine-v1.json"));
        byte[] gray=Files.readAllBytes(assets.resolve("match3-gray-v1.json"));
        JSONObject grayJson=new JSONObject(new String(gray,StandardCharsets.UTF_8));
        String ruleSha=grayJson.getJSONObject("metadata").getString("rule_catalog_sha256");
        Match3VisualCatalog original=Match3VisualCatalog.withGallery(base(),gallery);
        Match3VisualCatalog combined=Match3VisualCatalog.withReviewedSupplements(base(),gallery,java.util.Arrays.asList(vine,gray),ruleSha);
        assertEquals("loaded",combined.galleryStatus);assertEquals("loaded",combined.coverCatalogStatus);
        assertEquals(original.cells.size()+32,combined.cells.size());
        assertEquals(original.gallerySha256,combined.gallerySha256);assertTrue(combined.coverCatalogId.contains("+gray-"));
        assertEquals(Match3Position.Cover.GRAY_FURBALL,combined.classifyObstacle(
                new Match3VisualCatalog.Pattern(grayJson.getJSONArray("cells").getJSONObject(0)).pixels).cover);
        grayJson.getJSONObject("metadata").put("rule_catalog_sha256","0".repeat(64));
        Match3VisualCatalog rejected=Match3VisualCatalog.withReviewedSupplements(base(),gallery,
                java.util.Arrays.asList(vine,grayJson.toString().getBytes(StandardCharsets.UTF_8)),ruleSha);
        assertTrue(rejected.coverCatalogStatus.startsWith("rejected:"));assertEquals(original.cells.size(),rejected.cells.size());
    }
    @Test public void registeredCoverPruningRetainsEveryScoreInsideTheDecisionBudget() throws Exception {
        Path path=Path.of("src/main/assets/match3-vine-v1.json");if(!Files.isRegularFile(path))path=Path.of("app/src/main/assets/match3-vine-v1.json");
        JSONObject asset=new JSONObject(new String(Files.readAllBytes(path),StandardCharsets.UTF_8));JSONArray entries=asset.getJSONArray("cells");java.util.Random random=new java.util.Random(82983);
        int checked=0;
        for(int n=0;n<entries.length();n++) {
            JSONObject entry=entries.getJSONObject(n);
            if(entry.has("rule_binding_ref"))entry=new JSONObject(entry.toString()).put("rule_binding",asset.getJSONObject("state_bindings").getJSONObject(entry.getString("rule_binding_ref")));
            Match3VisualCatalog.Pattern pattern=new Match3VisualCatalog.Pattern(entry);if(pattern.coverEvidence==null)continue;
            for(int variant=0;variant<12;variant++) {
                int[] observed=pattern.pixels.clone();for(int p=0;p<variant*8;p++)observed[random.nextInt(256)]=0xff000000|random.nextInt(1<<24);
                float full=pattern.difference(observed),bounded=pattern.difference(observed,.145f);
                if(full<=.145f)assertEquals(full,bounded,0);else assertTrue(Float.isInfinite(bounded));checked++;
            }
        }
        assertTrue(checked>=240);
    }
    @Test public void unresolvedSharedBindingAtomicallyRejectsTheSupplement() throws Exception {
        Path assets=Path.of("src/main/assets");if(!Files.isDirectory(assets))assets=Path.of("app/src/main/assets");
        byte[] old=Files.readAllBytes(assets.resolve("match3-gallery-v1.json"));
        JSONObject extra=new JSONObject(new String(Files.readAllBytes(assets.resolve("match3-vine-v1.json")),StandardCharsets.UTF_8));
        String rules=extra.getJSONObject("metadata").getString("rule_catalog_sha256");
        JSONObject cell=extra.getJSONArray("cells").getJSONObject(0);assertTrue(cell.has("rule_binding_ref"));
        extra.getJSONObject("state_bindings").remove(cell.getString("rule_binding_ref"));
        Match3VisualCatalog original=Match3VisualCatalog.withGallery(base(),old);
        Match3VisualCatalog rejected=Match3VisualCatalog.withReviewedSupplement(base(),old,extra.toString().getBytes(StandardCharsets.UTF_8),rules);
        assertTrue(rejected.coverCatalogStatus.startsWith("rejected:"));assertEquals(original.cells.size(),rejected.cells.size());
        assertEquals(original.gallerySha256,rejected.gallerySha256);
    }
}
