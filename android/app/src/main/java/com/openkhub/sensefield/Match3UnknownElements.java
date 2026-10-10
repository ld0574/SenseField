package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded diagnostic families. Their prototypes never enter the recognition/rule catalog. */
final class Match3UnknownElements {
    static final int MAX_FAMILIES=24, MAX_SAMPLES_PER_FAMILY=2, STABLE_SAMPLES=3;
    static final long SAMPLE_GAP_MS=10000, FRESH_MS=2400;
    static final class Observation {
        final Match3Position.Cell cell;
        final int[] patch;
        final Match3AnimalAppearance.Body body;
        Observation(Match3Position.Cell cell,int[] patch) {
            this.cell=cell;this.patch=patch.clone();body=new Match3AnimalAppearance.Body(patch);
        }
        boolean sameRuleState(Observation other) { return cell.equals(other.cell); }
    }
    static final class Sample {
        final String familyId;
        final int sampleNumber,row,col;
        final long at;
        final Observation observation;
        Sample(Family family,int row,int col,long at,Observation observation) {
            familyId=family.id;sampleNumber=family.samples;this.row=row;this.col=col;this.at=at;this.observation=observation;
        }
        String filename() { return familyId+"-"+sampleNumber+".png"; }
        String reason() { return observation.cell.kind==Match3Position.Kind.ANIMAL?"cover_unverified":"identity_or_rule_unverified"; }
    }
    private static final class Family {
        final String id;
        final Observation anchor;
        int samples,detail;
        long sampledAt;
        Family(String id,Observation anchor) { this.id=id;this.anchor=anchor; }
    }
    private final List<Family> families=new ArrayList<>();
    private Observation[][] anchors;
    private Observation[][] previous;
    private int[][] counts;
    private Family[][] assigned;
    private Observation[][] assignedObservation;
    private int[][] assignedVersion;
    private long lastAt=-1;
    int saturatedObservations,ambiguousObservations;
    int familyCount() { return families.size(); }
    void resetStability() {
        anchors=null;previous=null;counts=null;assigned=null;assignedObservation=null;assignedVersion=null;lastAt=-1;
    }
    static boolean needsReview(Match3Position.Cell cell) {
        return cell!=null && (cell.kind==Match3Position.Kind.UNKNOWN || cell.kind==Match3Position.Kind.SURFACE
                || cell.kind==Match3Position.Kind.ANIMAL && cell.swapPermission==Match3Position.SwapPermission.UNKNOWN);
    }
    List<Sample> observe(Observation[][] observations,long at) {
        if(observations==null || observations.length==0 || observations[0].length==0 || at<0) {
            resetStability();return Collections.emptyList();
        }
        int rows=observations.length,cols=observations[0].length;
        if(rows>12 || cols>12)throw new IllegalArgumentException("Element grid");
        for(Observation[] row:observations)if(row==null || row.length!=cols)throw new IllegalArgumentException("Element grid");
        if(at==lastAt) {
            boolean same=previous!=null && previous.length==rows && previous[0].length==cols;
            for(int r=0;r<rows && same;r++)for(int c=0;c<cols;c++) {
                Observation a=observations[r][c],b=previous[r][c];
                if(a==null || b==null) { if(a!=b)same=false; }
                else if(!a.sameRuleState(b) || !java.util.Arrays.equals(a.patch,b.patch))same=false;
            }
            if(!same)resetStability(); // exact comparison, not a collision-prone appearance hash
            return Collections.emptyList();
        }
        if(anchors==null || anchors.length!=rows || anchors[0].length!=cols || at<lastAt || at-lastAt>FRESH_MS) {
            anchors=new Observation[rows][cols];counts=new int[rows][cols];
            assigned=new Family[rows][cols];assignedObservation=new Observation[rows][cols];assignedVersion=new int[rows][cols];
        }
        lastAt=at;previous=new Observation[rows][];
        for(int r=0;r<rows;r++)previous[r]=observations[r].clone();
        List<Sample> samples=new ArrayList<>();
        for(int r=0;r<rows;r++)for(int c=0;c<cols;c++) {
            Observation current=observations[r][c],anchor=anchors[r][c];
            if(current==null || !needsReview(current.cell)) {
                anchors[r][c]=null;counts[r][c]=0;assigned[r][c]=null;assignedObservation[r][c]=null;continue;
            }
            if(anchor==null || !current.sameRuleState(anchor) || current.body.difference(anchor.body)>Match3AnimalAppearance.MAXIMUM) {
                anchors[r][c]=current;counts[r][c]=1;assigned[r][c]=null;assignedObservation[r][c]=null;continue;
            }
            counts[r][c]=Math.min(STABLE_SAMPLES,counts[r][c]+1);
            if(counts[r][c]<STABLE_SAMPLES || !new Match3AnimalAppearance.Face('.',current.patch).detailed)continue;
            Family family=assigned[r][c];Observation cached=assignedObservation[r][c];
            // A fixed catalog and exactly unchanged pixels have the same result.
            // New families, new rule states, movement and changed pixels all recheck.
            if(family==null || assignedVersion[r][c]!=families.size() || cached==null
                    || !current.sameRuleState(cached) || !java.util.Arrays.equals(current.patch,cached.patch)) {
                family=find(current);assigned[r][c]=family;assignedObservation[r][c]=current;assignedVersion[r][c]=families.size();
            }
            if(family==null)continue;
            if(family.samples>=MAX_SAMPLES_PER_FAMILY)continue;
            int detail=current.body.detail();
            if(family.samples==0 || family.samples<MAX_SAMPLES_PER_FAMILY
                    && at-family.sampledAt>=SAMPLE_GAP_MS && detail>family.detail) {
                family.samples++;family.detail=detail;family.sampledAt=at;
                samples.add(new Sample(family,r,c,at,current));
            }
        }
        return samples;
    }
    private Family find(Observation observation) {
        Family best=null;float first=Float.POSITIVE_INFINITY,second=Float.POSITIVE_INFINITY;
        for(Family family:families)if(observation.sameRuleState(family.anchor)) {
            float error=observation.body.difference(family.anchor.body);
            if(error<first) { second=first;first=error;best=family; }
            else second=Math.min(second,error);
        }
        if(first<=Match3AnimalAppearance.MAXIMUM) {
            if(second-first<Match3AnimalAppearance.MARGIN) { ambiguousObservations++;return null; }
            return best;
        }
        if(families.size()>=MAX_FAMILIES) { saturatedObservations++;return null; }
        Family family=new Family("u"+(families.size()+1),observation);families.add(family);return family;
    }
}
