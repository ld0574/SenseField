package com.openkhub.sensefield;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/** Bounded appearances from the existing stable frame; never a rule/identity authority. */
final class Match3FlywheelHarvest {
    private final Match3UnknownElements cells=new Match3UnknownElements(true);
    private final Match3UnknownElements objects=new Match3UnknownElements(true);
    private Match3UnknownElements.Observation[][] objectCache;
    private final List<Match3AnimalAppearance.Body> goalAnchors=new ArrayList<>();
    private int[][] previousGoals;
    private int goalFrames;
    private long lastGoalAt=-1;
    void resetStability() { cells.resetStability();objects.resetStability();previousGoals=null;goalFrames=0;lastGoalAt=-1; }
    int saturated() { return cells.saturatedObservations+objects.saturatedObservations; }
    static JSONArray pixels(int[] patch) { JSONArray out=new JSONArray();for(int value:patch)out.put(value);return out; }
    void collect(Match3Sampler sampler,Match3Position position,Match3HudReader hud,long at,Predicate<JSONObject> writer) {
        String frame="live-"+at+".png";
        int[] attempts={0};
        for(Match3UnknownElements.Sample sample:cells.observe(sampler.reviewElements(position,true),at)) {
            if(attempts[0]>=DiagnosticRecorder.MAX_IMAGE_JOBS) { sample.rejected();continue; }
            attempts[0]++;
            JSONObject data=DiagnosticRecorder.object("role","cell","id",frame+":r"+sample.row+"c"+sample.col,
                    "frame",frame,"row",sample.row,"col",sample.col,"observed_at_ms",at,
                    "envelope",pixels(sample.observation.patch),"patch",pixels(sampler.elementPatch(sample.row,sample.col)),
                    "kind",sample.observation.cell.kind.name(),"color",String.valueOf(sample.observation.cell.color),
                    "swap_permission",sample.observation.cell.swapPermission.name(),"rule_supported",false);
            if(!writer.test(data))sample.rejected();
        }
        if(position.rows>1 && position.cols>1) {
            Match3UnknownElements.Observation[][] envelopes=new Match3UnknownElements.Observation[position.rows-1][position.cols-1];
            if(objectCache==null || objectCache.length!=envelopes.length || objectCache[0].length!=envelopes[0].length)
                objectCache=new Match3UnknownElements.Observation[envelopes.length][envelopes[0].length];
            for(int r=0;r<envelopes.length;r++)for(int c=0;c<envelopes[r].length;c++) {
                boolean eligible=true;
                for(int rr=r;rr<=r+1;rr++)for(int cc=c;cc<=c+1;cc++) {
                    Match3Position.Kind kind=position.cell(rr,cc).kind;
                    if(kind!=Match3Position.Kind.UNKNOWN && kind!=Match3Position.Kind.SURFACE && kind!=Match3Position.Kind.COOKIE)eligible=false;
                }
                if(!eligible)continue;
                int[] patch=new int[256];
                for(int y=0;y<16;y++)for(int x=0;x<16;x++)
                    patch[y*16+x]=sampler.elementEnvelope(r+y/8,c+x/8)[(2*(y%8)+1)*16+2*(x%8)+1];
                if(objectCache[r][c]==null || !Arrays.equals(objectCache[r][c].patch,patch))
                    objectCache[r][c]=new Match3UnknownElements.Observation(Match3Position.Cell.obstacle(Match3Position.Kind.UNKNOWN,-1),patch);
                envelopes[r][c]=objectCache[r][c];
            }
            for(Match3UnknownElements.Sample sample:objects.observe(envelopes,at)) {
                if(attempts[0]>=DiagnosticRecorder.MAX_IMAGE_JOBS) { sample.rejected();continue; }
                attempts[0]++;
                JSONObject data=DiagnosticRecorder.object("role","object","id",frame+":object:r"+sample.row+"c"+sample.col,
                        "frame",frame,"row",sample.row,"col",sample.col,"observed_at_ms",at,
                        "pixels",pixels(sample.observation.patch),"rule_supported",false);
                if(!writer.test(data))sample.rejected();
            }
        }
        collectGoals(hud.diagnosticGoalPatches(),hud.diagnosticGoalKinds(),hud.diagnosticGoalAspects(),at,writer,attempts);
    }
    void collectGoals(int[][] current,String[] kinds,float[] aspects,long at,Predicate<JSONObject> writer) {
        collectGoals(current,kinds,aspects,at,writer,new int[]{0});
    }
    private void collectGoals(int[][] current,String[] kinds,float[] aspects,long at,Predicate<JSONObject> writer,int[] attempts) {
        if(current==null || at<0) { previousGoals=null;goalFrames=0;lastGoalAt=-1;return; }
        if(at==lastGoalAt)return;
        int[][] normalized=new int[current.length][];
        for(int i=0;i<current.length;i++) { normalized[i]=current[i].clone();Arrays.fill(normalized[i],128,256,0xff1e2a58); }
        boolean same=Arrays.deepEquals(previousGoals,normalized) && at>lastGoalAt && at-lastGoalAt<=2400;
        goalFrames=same?Math.min(3,goalFrames+1):1;previousGoals=normalized;lastGoalAt=at;
        if(goalFrames<3)return;
        String frame="live-"+at+".png";
        for(int i=0;i<current.length && goalAnchors.size()<24;i++) {
            if(attempts[0]>=DiagnosticRecorder.MAX_IMAGE_JOBS)break;
            Match3AnimalAppearance.Body observed=new Match3AnimalAppearance.Body(normalized[i]);
            boolean known=false;
            for(Match3AnimalAppearance.Body anchor:goalAnchors)if(observed.difference(anchor)<=.12f) { known=true;break; }
            if(known)continue;
            attempts[0]++;
            JSONObject data=DiagnosticRecorder.object("role","goal","id",frame+":goal"+i,"frame",frame,
                    "observed_at_ms",at,"pixels",pixels(current[i]),"aspect",aspects[i],
                    "kind",kinds[i],"rule_supported",false);
            if(writer.test(data))goalAnchors.add(observed);
        }
    }
}
