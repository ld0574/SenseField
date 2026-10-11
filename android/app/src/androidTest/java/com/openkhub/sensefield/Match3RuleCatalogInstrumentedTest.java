package com.openkhub.sensefield;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;

/** Native JSON/binding contract. Synthetic templates prove plumbing, not game visual accuracy. */
public final class Match3RuleCatalogInstrumentedTest {
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
