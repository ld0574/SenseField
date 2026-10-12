package com.openkhub.sensefield;

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
    private int[][] knownGoalPatches;
    private int[] goalFrames;
    private long lastGoalAt=-1;
    void resetStability() { cells.resetStability();objects.resetStability();previousGoals=null;knownGoalPatches=null;goalFrames=null;lastGoalAt=-1; }
    int saturated() { return cells.saturatedObservations+objects.saturatedObservations; }
    /** The descriptor is RGB throughout the catalog. Encode those exact bytes
     * without 256 boxed JSON numbers; collection/admission budgets are unchanged. */
    static String pixels(int[] patch) {
        char[] out=new char[patch.length*6];String hex="0123456789abcdef";
        for(int i=0;i<patch.length;i++)for(int k=0;k<6;k++)out[i*6+k]=hex.charAt(patch[i]>>(20-k*4)&15);
        return new String(out);
    }
    void collect(Match3Sampler sampler,Match3Position position,Match3HudReader hud,long at,Predicate<JSONObject> writer) {
        String frame="live-"+at+".png";
        int[] attempts={0};
        for(Match3UnknownElements.Sample sample:cells.observe(sampler.reviewElements(position,true),at)) {
            if(attempts[0]>=DiagnosticRecorder.MAX_IMAGE_JOBS) { sample.rejected();continue; }
            attempts[0]++;
            JSONObject data=DiagnosticRecorder.object("role","cell","descriptor_encoding","rgb24_hex","id",frame+":r"+sample.row+"c"+sample.col,
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
                    if(kind!=Match3Position.Kind.UNKNOWN && kind!=Match3Position.Kind.SURFACE && kind!=Match3Position.Kind.COOKIE
                            || position.cell(rr,cc).cover!=Match3Position.Cover.NONE)eligible=false;
                }
                if(!eligible)continue;
                int[] patch=Match3VisualCatalog.combineEnvelopes(sampler.elementEnvelope(r,c),sampler.elementEnvelope(r,c+1),
                        sampler.elementEnvelope(r+1,c),sampler.elementEnvelope(r+1,c+1));
                if(objectCache[r][c]==null || !Arrays.equals(objectCache[r][c].patch,patch))
                    objectCache[r][c]=new Match3UnknownElements.Observation(Match3Position.Cell.obstacle(Match3Position.Kind.UNKNOWN,-1),patch);
                envelopes[r][c]=objectCache[r][c];
            }
            for(Match3UnknownElements.Sample sample:objects.observe(envelopes,at)) {
                if(attempts[0]>=DiagnosticRecorder.MAX_IMAGE_JOBS) { sample.rejected();continue; }
                attempts[0]++;
                JSONObject data=DiagnosticRecorder.object("role","object","descriptor_encoding","rgb24_hex","id",frame+":object:r"+sample.row+"c"+sample.col,
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
        if(current==null || at<0) { previousGoals=null;goalFrames=null;lastGoalAt=-1;return; }
        if(at==lastGoalAt)return;
        int[][] normalized=new int[current.length][];
        for(int i=0;i<current.length;i++) { normalized[i]=current[i].clone();Arrays.fill(normalized[i],128,256,0xff1e2a58); }
        boolean fresh=previousGoals!=null && previousGoals.length==normalized.length
                && at>lastGoalAt && at-lastGoalAt<=2400;
        if(!fresh)goalFrames=new int[normalized.length];
        // A glittering/completed neighboring icon must not reset another
        // stable icon's three fresh observations or prevent its collection.
        for(int i=0;i<normalized.length;i++)goalFrames[i]=fresh && Arrays.equals(previousGoals[i],normalized[i])
                ?Math.min(3,goalFrames[i]+1):1;
        previousGoals=normalized;lastGoalAt=at;
        if(knownGoalPatches==null || knownGoalPatches.length!=current.length)knownGoalPatches=new int[current.length][];
        String frame="live-"+at+".png";
        for(int i=0;i<current.length && goalAnchors.size()<24;i++) {
            if(goalFrames[i]<3)continue;
            if(attempts[0]>=DiagnosticRecorder.MAX_IMAGE_JOBS)break;
            // Only exactly unchanged, previously accepted appearances reuse a
            // match. Rejected writes and changed pixels still take the full path.
            if(Arrays.equals(knownGoalPatches[i],normalized[i]))continue;
            Match3AnimalAppearance.Body observed=new Match3AnimalAppearance.Body(normalized[i]);
            boolean known=false;
            for(Match3AnimalAppearance.Body anchor:goalAnchors)if(observed.difference(anchor)<=.12f) { known=true;break; }
            if(known) { knownGoalPatches[i]=normalized[i];continue; }
            attempts[0]++;
            JSONObject data=DiagnosticRecorder.object("role","goal","descriptor_encoding","rgb24_hex","id",frame+":goal"+i,"frame",frame,
                    "observed_at_ms",at,"pixels",pixels(current[i]),"aspect",aspects[i],
                    "kind",kinds[i],"rule_supported",false);
            if(writer.test(data)) { goalAnchors.add(observed);knownGoalPatches[i]=normalized[i]; }
        }
    }
}
