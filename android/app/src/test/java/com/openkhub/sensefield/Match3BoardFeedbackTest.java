package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.json.*;
import org.junit.Test;

public final class Match3BoardFeedbackTest {
    private static byte[] asset(String name) throws Exception {
        Path root=Path.of("src/main/assets");if(!Files.isDirectory(root))root=Path.of("app/src/main/assets");
        return Files.readAllBytes(root.resolve(name));
    }
    private static Match3VisualCatalog load(byte[] feedback) throws Exception {
        JSONObject base=new JSONObject(new String(asset("match3-fixed-ui-v1.json"),StandardCharsets.UTF_8));
        List<byte[]> covers=Arrays.asList(asset("match3-vine-v1.json"),asset("match3-gray-v1.json"));
        return Match3VisualCatalog.withBoardFeedback(base,asset("match3-gallery-v1.json"),covers,feedback,
                Match3RuleCatalog.parse(asset("match3-rules-v1.json")).sha256);
    }
    @Test public void directionTemplatesUseTheFullTileAndAnIndependentPositiveArrowStencil() throws Exception {
        Match3VisualCatalog previous=load(null),candidate=load(asset("match3-board-feedback-v1.json"));
        assertEquals("loaded",candidate.boardFeedbackStatus);assertEquals("loaded",candidate.coverCatalogStatus);
        assertEquals(previous.gallerySha256,candidate.gallerySha256);assertEquals(previous.coverCatalogSha256,candidate.coverCatalogSha256);
        assertEquals(previous.effectEnvelopes.size()+8,candidate.effectEnvelopes.size());
        for(Match3VisualCatalog.Pattern p:candidate.effectEnvelopes) {
            assertEquals(Match3Position.Effect.HORIZONTAL,p.boundState.effect);assertTrue(p.tileEnvelope);
            assertEquals(2,p.positiveWeight);assertNotNull(p.coverEvidence);
            assertEquals(p.boundState,candidate.classifyEffect(p.pixels));
            int[] noArrows=p.pixels.clone();
            for(int i=0;i<256;i++)if(p.coverEvidence[i])noArrows[i]=0xff172c45;
            assertNull("face without positive side arrows",candidate.classifyEffect(noArrows));
        }
    }
    @Test public void wrongProvenanceBudgetOrDirectionProofRejectsOnlyTheNewSidecar() throws Exception {
        Match3VisualCatalog previous=load(null);byte[] bytes=asset("match3-board-feedback-v1.json");
        for(int failure=0;failure<4;failure++) {
            JSONObject extra=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(failure==0)extra.getJSONObject("metadata").put("rule_catalog_sha256","0".repeat(64));
            if(failure==1)while(extra.getJSONArray("cells").length()<25)extra.getJSONArray("cells").put(extra.getJSONArray("cells").getJSONObject(0));
            if(failure==2)for(int i=0;i<extra.getJSONArray("cells").length();i++) {
                JSONObject cell=extra.getJSONArray("cells").getJSONObject(i);
                if(cell.has("effect_evidence_mask_bits"))cell.remove("effect_evidence_mask_bits");
            }
            if(failure==3)extra.getJSONObject("metadata").put("independent",true);
            Match3VisualCatalog rejected=load(extra.toString().getBytes(StandardCharsets.UTF_8));
            assertTrue(rejected.boardFeedbackStatus.startsWith("rejected:"));
            assertEquals(previous.cells.size(),rejected.cells.size());assertEquals(previous.effectEnvelopes.size(),rejected.effectEnvelopes.size());
            assertEquals(previous.coverCatalogSha256,rejected.coverCatalogSha256);assertEquals(previous.gallerySha256,rejected.gallerySha256);
        }
    }
    @Test public void addingPoisonKeepsExistingMissionAndAnimalIdentitiesDistinct() throws Exception {
        Match3VisualCatalog previous=load(null),candidate=load(asset("match3-board-feedback-v1.json"));
        assertEquals("loaded",candidate.boardFeedbackStatus);
        for(Match3VisualCatalog.Pattern pattern:previous.goals) {
            String identity=Match3VisualCatalog.recognize(previous.goals,pattern.pixels,.14f,.025f);
            if(identity!=null)assertEquals(identity,Match3VisualCatalog.recognize(candidate.goals,pattern.pixels,.14f,.025f));
        }
        for(Match3VisualCatalog.Pattern pattern:previous.animals) {
            Match3Position.Cell classified=candidate.classifyObstacle(pattern.pixels);
            assertTrue("An animal descriptor cannot become poison",classified==null || classified.kind!=Match3Position.Kind.POISON);
        }
    }
    @Test public void poisonGoalNeedsItsOwnReviewAndCannotUseCounterPixelsAsIdentity() throws Exception {
        byte[] bytes=asset("match3-board-feedback-v1.json");
        for(int failure=0;failure<5;failure++) {
            JSONObject extra=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            JSONObject goal=extra.getJSONArray("goals").getJSONObject(0);
            if(failure==0)goal.put("kind","COOKIE");
            if(failure==1)goal.put("mask_bits","1".repeat(256));
            if(failure==2)goal.remove("source_sha256");
            if(failure==3)goal.getJSONObject("goal_binding").put("reviewed",false);
            if(failure==4)goal.getJSONObject("goal_binding").put("event_mode","CHARGE");
            assertTrue(load(extra.toString().getBytes(StandardCharsets.UTF_8)).boardFeedbackStatus.startsWith("rejected:"));
        }
    }
}
