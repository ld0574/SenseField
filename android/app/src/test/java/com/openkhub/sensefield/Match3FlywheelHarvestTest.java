package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

public final class Match3FlywheelHarvestTest {
    private int[] patch() {
        int[] out=new int[256];Arrays.fill(out,0xff203050);
        for(int y=2;y<14;y++)for(int x=2;x<14;x++)out[y*16+x]=0xffb07050;
        for(int y=5;y<8;y++)for(int x:new int[]{5,10})out[y*16+x]=0xff402020;
        return out;
    }
    private Match3UnknownElements.Observation[][] grid() {
        return new Match3UnknownElements.Observation[][]{{new Match3UnknownElements.Observation(Match3Position.Cell.animal('O'),patch())}};
    }
    @Test public void knownAppearancesRequireOptInAndThreeFreshObservations() {
        Match3UnknownElements ordinary=new Match3UnknownElements(),all=new Match3UnknownElements(true);
        for(int i=0;i<2;i++) { assertTrue(ordinary.observe(grid(),100+800*i).isEmpty());assertTrue(all.observe(grid(),100+800*i).isEmpty()); }
        assertTrue(ordinary.observe(grid(),1700).isEmpty());assertEquals(1,all.observe(grid(),1700).size());
        assertTrue(all.observe(grid(),1700).isEmpty());assertTrue(all.observe(grid(),2500).isEmpty());
    }
    @Test public void busyWriterReservationCanRetryWithoutImprovedDetailOrTenSecondDelay() {
        Match3UnknownElements all=new Match3UnknownElements(true);
        all.observe(grid(),100);all.observe(grid(),900);
        Match3UnknownElements.Sample rejected=all.observe(grid(),1700).get(0);rejected.rejected();rejected.rejected();
        List<Match3UnknownElements.Sample> retry=all.observe(grid(),2500);
        assertEquals(1,retry.size());assertEquals(1,retry.get(0).sampleNumber);assertEquals(rejected.familyId,retry.get(0).familyId);
        assertTrue(all.observe(grid(),3300).isEmpty());
    }
    @Test public void staleAndResetObservationsDoNotCountAsStableFrames() {
        Match3UnknownElements all=new Match3UnknownElements(true);
        all.observe(grid(),100);all.observe(grid(),900);assertTrue(all.observe(grid(),5000).isEmpty());
        all.resetStability();assertTrue(all.observe(grid(),5800).isEmpty());assertTrue(all.observe(grid(),6600).isEmpty());
        assertEquals(1,all.observe(grid(),7400).size());
    }
    @Test public void changingCounterDoesNotCreateANewGoalFamilyAndRejectedWriteRetries() {
        Match3FlywheelHarvest collector=new Match3FlywheelHarvest();List<JSONObject> saved=new ArrayList<>();
        int[] icon=patch();String[] kinds={"UNKNOWN"};float[] aspects={1};
        collector.collectGoals(new int[][]{icon},kinds,aspects,100,data->{fail("unstable");return true;});
        icon[200]^=0xabcdef;
        collector.collectGoals(new int[][]{icon},kinds,aspects,900,data->{fail("unstable");return true;});
        collector.collectGoals(new int[][]{icon},kinds,aspects,1700,data->false);
        collector.collectGoals(new int[][]{icon},kinds,aspects,2500,data->{saved.add(data);return true;});
        assertEquals(1,saved.size());
        icon[201]^=0xaaffff;
        collector.collectGoals(new int[][]{icon},kinds,aspects,3300,data->{fail("duplicate");return true;});
        collector.resetStability();
        for(int i=0;i<3;i++)collector.collectGoals(new int[][]{icon},kinds,aspects,5000+800*i,data->{fail("already collected");return true;});
    }
    @Test public void missingGoalObservationResetsTheThreeFreshFrameRequirement() {
        Match3FlywheelHarvest collector=new Match3FlywheelHarvest();int[][] icon={patch()};String[] kinds={"UNKNOWN"};float[] aspects={1};
        collector.collectGoals(icon,kinds,aspects,100,data->{fail("unstable");return true;});
        collector.collectGoals(icon,kinds,aspects,900,data->{fail("unstable");return true;});
        collector.collectGoals(null,null,null,1700,data->true);
        collector.collectGoals(icon,kinds,aspects,2500,data->{fail("fresh observations needed");return true;});
        collector.collectGoals(icon,kinds,aspects,3300,data->{fail("fresh observations needed");return true;});
        List<JSONObject> saved=new ArrayList<>();collector.collectGoals(icon,kinds,aspects,4100,data->{saved.add(data);return true;});
        assertEquals(1,saved.size());
    }
}
