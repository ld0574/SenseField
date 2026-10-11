package com.openkhub.sensefield;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;

/** Native JSON/binding contract. Synthetic templates prove plumbing, not game visual accuracy. */
public final class Match3RuleCatalogInstrumentedTest {
    @Test public void nativeBatchPreservesFullFamilyCompetitionAndMaskDecisions() {
        assertTrue(Match3NativeMath.AVAILABLE);
        Match3VisualCatalog catalog=Match3VisualCatalog.get(InstrumentationRegistry.getInstrumentation().getTargetContext());
        java.util.Random random=new java.util.Random(293168);
        for(java.util.List<Match3VisualCatalog.Pattern> patterns:java.util.Arrays.asList(catalog.cells,catalog.animals,catalog.goals,catalog.iceSurfaces,catalog.largeObjects)) {
            java.util.List<Match3VisualCatalog.Pattern> individual=new java.util.ArrayList<>(patterns);
            for(Match3VisualCatalog.Pattern pattern:patterns)for(int noise:new int[]{0,8,32}) {
                int[] patch=pattern.pixels.clone();
                for(int i=0;i<noise;i++)patch[random.nextInt(256)]=0xff000000|random.nextInt(1<<24);
                assertEquals(Match3VisualCatalog.recognize(individual,patch,.12f,.025f),Match3VisualCatalog.recognize(patterns,patch,.12f,.025f));
            }
        }
        assertNull(Match3NativeMath.patternDifferences(new int[256],java.nio.ByteBuffer.allocate(1544),1,.145f));
        assertNull(Match3NativeMath.patternDifferences(new int[256],java.nio.ByteBuffer.allocateDirect(1543),1,.145f));
    }
    @Test public void nativeBodiesKeepJavaAdmissionScoresAndUnknownCoverRejection() {
        assertTrue("The candidate must package the exact accelerator",Match3NativeMath.AVAILABLE);
        java.util.Random random=new java.util.Random(471692);
        for(int fixture=0;fixture<12;fixture++) {
            int[] reference=new int[256];java.util.Arrays.fill(reference,0xff203050);
            for(int y=3;y<13;y++)for(int x=3;x<13;x++)reference[y*16+x]=0xff000000|random.nextInt(1<<24);
            Match3AnimalAppearance.Body anchor=new Match3AnimalAppearance.Body(reference);
            for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)for(int noise:new int[]{0,6,48}) {
                int[] observed=new int[256];java.util.Arrays.fill(observed,0xff203050);
                for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(x+dx>=0 && x+dx<16 && y+dy>=0 && y+dy<16)
                    observed[(y+dy)*16+x+dx]=reference[y*16+x];
                for(int i=0;i<noise;i++)observed[random.nextInt(256)]=0xfffaffff;
                Match3AnimalAppearance.Body body=new Match3AnimalAppearance.Body(observed);
                assertEquals(body.differenceJava(anchor),body.difference(anchor),0f);
                assertEquals(body.differenceJava(anchor),Match3NativeMath.bodyDifference(observed,reference,null),0f);
            }
        }
        assertEquals(Float.POSITIVE_INFINITY,Match3NativeMath.bodyDifference(new int[255],new int[256],null),0f);
    }
    @Test public void registeredCoverPruningKeepsFullScoresAndMarginContenders() throws Exception {
        Match3VisualCatalog catalog=Match3VisualCatalog.get(InstrumentationRegistry.getInstrumentation().getTargetContext());
        java.util.Random random=new java.util.Random(914592);
        for(Match3VisualCatalog.Pattern pattern:catalog.cells) {
            if(pattern.coverEvidence==null)continue;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)for(int noise:new int[]{0,16,48}) {
                int[] observed=pattern.pixels.clone();
                for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(x+dx>=0 && x+dx<16 && y+dy>=0 && y+dy<16)
                    observed[(y+dy)*16+x+dx]=pattern.pixels[y*16+x];
                for(int i=0;i<noise;i++)observed[random.nextInt(256)]=0xff000000|random.nextInt(1<<24);
                float full=Float.POSITIVE_INFINITY;
                for(int yy=-1;yy<=1;yy++)for(int xx=-1;xx<=1;xx++) {
                    long whole=0,cover=0;int count=0,support=0;
                    for(int y=Math.max(0,-yy);y<Math.min(16,16-yy);y++)for(int x=Math.max(0,-xx);x<Math.min(16,16-xx);x++) {
                        int at=y*16+x,a=pattern.pixels[at],b=observed[(y+yy)*16+x+xx],delta=0;
                        for(int shift:new int[]{16,8,0})delta+=Math.abs((a>>shift&255)-(b>>shift&255));
                        whole+=delta;count++;if(pattern.coverEvidence[at]) { cover+=delta;support++; }
                    }
                    if(support>=24)full=Math.min(full,Math.max(whole/(count*765f),cover/(support*765f)));
                }
                float bounded=pattern.difference(observed,.145f);
                if(full<=.145f)assertEquals("All admissible scores stay exact",full,bounded,0f);
                else assertTrue(bounded>.145f);
            }
        }
    }
    private byte[] asset(String name) throws Exception {
        try(java.io.InputStream in=InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets().open(name)) { return in.readAllBytes(); }
    }
    private JSONObject binding() throws Exception {
        return new JSONObject().put("reviewed",true).put("evidence","explicit_frozen_fox_fixture").put("rule_id","ordinary_uncovered_animal")
                .put("rule_version",1).put("state",new JSONObject().put("kind","ANIMAL").put("color","R").put("layers",0)
                .put("floor_ice",0).put("cover","FROZEN_ICE").put("cover_layers",2).put("effect","NONE").put("gate","OPEN"));
    }
    @Test public void shippedCatalogHasAllFiniteRulesAndCannotUpgradeFutureOrUnreviewedMetadata() throws Exception {
        byte[] bytes=asset("match3-rules-v1.json");Match3RuleCatalog catalog=Match3RuleCatalog.parse(bytes);
        assertTrue(catalog.available);assertEquals(Match3HoldoutAccuracyInstrumentedTest.sha256(bytes),catalog.sha256);
        JSONObject bad=new JSONObject(new String(bytes,StandardCharsets.UTF_8));bad.getJSONArray("rules").getJSONObject(0).put("live_validation","verified");
        try { Match3RuleCatalog.parse(bad.toString().getBytes(StandardCharsets.UTF_8));fail("Unreviewed acceptance promoted"); }
        catch(IllegalArgumentException expected) { }
    }
    @Test public void explicitCoverDirectionAndEnergyStatesRemainIndependent() throws Exception {
        JSONObject binding=binding();Match3Position.Cell cell=Match3StateBinding.parse(binding);
        assertEquals(Match3Position.Cover.FROZEN_ICE,cell.cover);assertEquals(2,cell.coverLayers);
        assertFalse(cell.swappable);assertEquals(Match3Position.SwapPermission.NO,cell.matchPermission);assertEquals("frozen_ice",cell.coverRuleId);
        binding.getJSONObject("state").put("cover","LOCK").put("cover_layers",1);
        cell=Match3StateBinding.parse(binding);assertFalse(cell.swappable);assertEquals(Match3Position.SwapPermission.YES,cell.matchPermission);
        binding.getJSONObject("state").put("object_id",9);
        try { Match3StateBinding.parse(binding);fail("Appearance assigned an absolute object id"); }catch(IllegalArgumentException expected) { }
    }
    @Test public void galleryAdmitsReviewedStateWithoutGrantingAnOrdinaryEnvelopeAndRejectsWrongVersion() throws Exception {
        JSONObject base=new JSONObject(new String(asset("match3-fixed-ui-v1.json"),StandardCharsets.UTF_8));
        StringBuilder patch=new StringBuilder();for(int i=0;i<256;i++)patch.append(i%2==0?"773355":"226688");
        JSONObject item=new JSONObject().put("kind","bound_0123456789abcdefabcd").put("rule","reviewed_state").put("rule_version",1)
                .put("pixels_rgb",patch).put("mask_bits","1".repeat(256)).put("reviewed",true).put("source","manual/train/a:r0c0")
                .put("family_id","frozen-fox").put("rule_binding",binding());
        JSONObject gallery=new JSONObject().put("format","match3-gallery-v1").put("id","fixture-state").put("cells",new JSONArray().put(item))
                .put("metadata",new JSONObject().put("rule_catalog_id",Match3Rules.CATALOG_ID));
        Match3VisualCatalog catalog=Match3VisualCatalog.withGallery(base,gallery.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("loaded",catalog.galleryStatus);assertEquals(base.getJSONArray("ordinary_envelopes").length(),catalog.animalBodies.size());
        Match3VisualCatalog.Pattern bound=null;for(Match3VisualCatalog.Pattern p:catalog.cells)if(p.kind.startsWith("bound_"))bound=p;
        assertNotNull(bound);assertFalse(bound.boundState.swappable);
        item.getJSONObject("rule_binding").put("rule_version",2);
        catalog=Match3VisualCatalog.withGallery(base,gallery.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(catalog.galleryStatus.startsWith("rejected:"));assertTrue(catalog.available);
    }
    @Test public void chickIconCannotBecomeAHatchOrClearTaskWithoutModeEvidence() {
        Match3Goals.Target target=Match3Goals.Target.observed(0,Match3Goals.Kind.CHICK,8,false,"");
        assertEquals(Match3Goals.Mode.UNKNOWN,target.mode);
    }
    @Test public void reviewedTypedAppearanceKeepsAllUnobservedMechanicsUnknown() throws Exception {
        JSONObject base=new JSONObject(new String(asset("match3-fixed-ui-v1.json"),StandardCharsets.UTF_8));
        base.put("cells",new JSONArray());
        StringBuilder pixels=new StringBuilder();for(int i=0;i<256;i++)pixels.append(i%16<8?"4488bb":"8866aa");
        JSONObject item=new JSONObject().put("kind","identity_FLOWER").put("rule","reviewed_identity_only")
                .put("rule_version",1).put("reviewed",true).put("source","synthetic/train/flower")
                .put("family_id","flower").put("pixels_rgb",pixels).put("mask_bits","1".repeat(256));
        JSONObject gallery=new JSONObject().put("format","match3-gallery-v1").put("id","flower-fixture")
                .put("cells",new JSONArray().put(item));
        Match3VisualCatalog catalog=Match3VisualCatalog.withGallery(base,gallery.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("loaded",catalog.galleryStatus);
        Match3Position.Cell cell=catalog.classifyObstacle(catalog.cells.get(0).pixels);
        assertNotNull(cell);assertEquals(Match3Position.Kind.FLOWER,cell.kind);assertEquals("flower_cluster",cell.ruleId);
        assertFalse(cell.swappable);assertEquals(-1,cell.layers);assertEquals(-1,cell.iceLayers);
        assertEquals(Match3Position.Cover.UNKNOWN,cell.cover);assertEquals(Match3Position.Gate.UNKNOWN,cell.gate);
        assertEquals(-1,cell.charge);assertEquals(-1,cell.capacity);
        assertEquals(Match3Position.SwapPermission.UNKNOWN,cell.adjacentDamagePermission);
        assertEquals(Match3Position.SwapPermission.UNKNOWN,cell.specialDamagePermission);
        item.put("reviewed",false);
        assertTrue(Match3VisualCatalog.withGallery(base,gallery.toString().getBytes(StandardCharsets.UTF_8)).galleryStatus.startsWith("rejected:"));
        item.put("reviewed",true).put("kind","identity_ANIMAL");
        assertTrue(Match3VisualCatalog.withGallery(base,gallery.toString().getBytes(StandardCharsets.UTF_8)).galleryStatus.startsWith("rejected:"));
    }
    @Test public void reviewedGoalModeNeedsSeparateEvidenceAndAbstainsOnConflictingTemplates() throws Exception {
        StringBuilder pixels=new StringBuilder();for(int i=0;i<256;i++)pixels.append(i%2==0?"779933":"335577");
        JSONObject item=new JSONObject().put("kind","CHICK").put("rule","reviewed_goal_icon").put("reviewed",true)
                .put("pixels_rgb",pixels).put("mask_bits","1".repeat(128)+"0".repeat(128))
                .put("goal_binding",new JSONObject().put("reviewed",true).put("event_mode","HATCH").put("evidence","human_task_description"));
        Match3VisualCatalog.Pattern bound=new Match3VisualCatalog.Pattern(item);
        Match3Goals.Target icon=Match3Goals.Target.observed(0,Match3Goals.Kind.CHICK,8,false,"");
        assertEquals(Match3Goals.Mode.HATCH,Match3VisualCatalog.goalTarget(java.util.Collections.singletonList(bound),bound.pixels,icon).mode);
        item.remove("goal_binding");Match3VisualCatalog.Pattern identityOnly=new Match3VisualCatalog.Pattern(item);
        assertEquals(Match3Goals.Mode.UNKNOWN,Match3VisualCatalog.goalTarget(java.util.Arrays.asList(bound,identityOnly),bound.pixels,icon).mode);
        item.put("goal_binding",new JSONObject().put("reviewed",true).put("event_mode","DELIVER").put("evidence","wrong_mode"));
        try { new Match3VisualCatalog.Pattern(item);fail("Goal mode contradicted identity"); }catch(IllegalArgumentException expected) { }
    }
}
