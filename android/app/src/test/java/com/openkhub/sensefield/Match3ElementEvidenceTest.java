package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class Match3ElementEvidenceTest {
    @Test public void chromaRejectionPreservesFullFaceAdmissionAndMotionDecisions() {
        Random random=new Random(7391);int[] original=face();
        for(int variant=0;variant<160;variant++) {
            int[] other=original.clone();
            for(int i=0;i<256;i++) {
                int p=other[i],r=p>>16&255,g=p>>8&255,b=p&255;
                if(variant%4==0)other[i]=0xff000000 | g<<16 | b<<8 | r;
                else if(variant%4==1)other[i]=0xff000000 | b<<16 | r<<8 | g;
                else if(variant%4==2)other[i]=0xff000000 | Math.min(255,r+18)<<16 | Math.min(255,g+18)<<8 | Math.min(255,b+18);
            }
            for(int i=0;i<variant%11;i++)other[random.nextInt(256)]=0xff000000|random.nextInt(1<<24);
            float full=fullFaceDifference(original,other);
            float bounded=new Match3AnimalAppearance.Face('.',original).difference(new Match3AnimalAppearance.Face('.',other));
            assertEquals(full<=Match3AnimalAppearance.MAXIMUM,bounded<=Match3AnimalAppearance.MAXIMUM);
            assertEquals(full>Match3AnimalAppearance.MAXIMUM+Match3AnimalAppearance.MARGIN,
                    bounded>Match3AnimalAppearance.MAXIMUM+Match3AnimalAppearance.MARGIN);
            if(full<=Match3AnimalAppearance.MAXIMUM+Match3AnimalAppearance.MARGIN)assertEquals(full,bounded,0f);
        }
    }
    private static float fullFaceDifference(int[] a,int[] b) {
        int[][] mean=new int[2][3];int[][][] pixels=new int[2][100][3];long[] variation=new long[2];
        for(int side=0;side<2;side++) {
            int[] patch=side==0?a:b;int index=0;
            for(int y=3;y<13;y++)for(int x=3;x<13;x++,index++)for(int channel=0;channel<3;channel++) {
                pixels[side][index][channel]=patch[y*16+x]>>(16-channel*8)&255;mean[side][channel]+=pixels[side][index][channel];
            }
            for(int channel=0;channel<3;channel++)mean[side][channel]/=100;
            for(int i=0;i<100;i++)for(int channel=0;channel<3;channel++) {
                pixels[side][i][channel]-=mean[side][channel];variation[side]+=Math.abs(pixels[side][i][channel]);
            }
            if(variation[side]/(100*765f)<Match3AnimalAppearance.MARGIN)return Float.POSITIVE_INFINITY;
        }
        float best=fullFaceAlignment(pixels,0,0);
        if(best>Match3AnimalAppearance.MAXIMUM)for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)
            best=Math.min(best,fullFaceAlignment(pixels,dx,dy));
        int chroma=0;for(int channel=0;channel<3;channel++) {
            int next=(channel+1)%3;chroma+=Math.abs((mean[0][channel]-mean[0][next])-(mean[1][channel]-mean[1][next]));
        }
        return Math.max(best,chroma/765f);
    }
    private static float fullFaceAlignment(int[][][] pixels,int dx,int dy) {
        long error=0,variation=0;int count=0;
        for(int y=0;y<10;y++)for(int x=0;x<10;x++)if(y+dy>=0 && y+dy<10 && x+dx>=0 && x+dx<10) {
            for(int channel=0;channel<3;channel++) {
                int a=pixels[0][(y+dy)*10+x+dx][channel],b=pixels[1][y*10+x][channel];
                error+=Math.abs(a-b);variation+=Math.abs(a)+Math.abs(b);
            }
            count++;
        }
        return error*2>variation?Float.POSITIVE_INFINITY:error/(count*765f);
    }
    @Test public void boundedBodyComparisonPreservesEveryAdmissibleScoreAndAmbiguityContender() {
        int[] original=face();Random random=new Random(18421);
        for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)for(int variant=0;variant<6;variant++) {
            int[] observed=new int[256];Arrays.fill(observed,0xff203050);
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(y+dy>=0 && y+dy<16 && x+dx>=0 && x+dx<16)
                observed[(y+dy)*16+x+dx]=original[y*16+x];
            for(int i=0;i<variant*5;i++)observed[random.nextInt(256)]=0xfffaffff;
            float full=fullBodyDifference(observed,original);
            float bounded=new Match3AnimalAppearance.Body(observed).difference(new Match3AnimalAppearance.Body(original));
            assertEquals(full<=Match3AnimalAppearance.MAXIMUM,bounded<=Match3AnimalAppearance.MAXIMUM);
            if(full<=Match3AnimalAppearance.MAXIMUM+Match3AnimalAppearance.MARGIN)assertEquals(full,bounded,0f);
            else assertTrue(bounded>Match3AnimalAppearance.MAXIMUM+Match3AnimalAppearance.MARGIN);
        }
    }
    // Independent full Manhattan calculation for these fixtures. Their only
    // permitted registration backdrop is the unchanged dark-blue board pixel.
    private static float fullBodyDifference(int[] observed,int[] original) {
        float first=fullAligned(observed,original,0,0);
        if(first<=Match3AnimalAppearance.MAXIMUM)return first;
        for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)first=Math.min(first,fullAligned(observed,original,dx,dy));
        return first;
    }
    private static float fullAligned(int[] observed,int[] original,int dx,int dy) {
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(x-dx<0 || x-dx>=16 || y-dy<0 || y-dy>=16)
            if(observed[y*16+x]!=0xff203050)return Float.POSITIVE_INFINITY;
        int[] counts=new int[4];long[] errors=new long[4];long total=0;int pixels=0;
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(x+dx>=0 && x+dx<16 && y+dy>=0 && y+dy<16) {
            int a=observed[(y+dy)*16+x+dx],b=original[y*16+x],difference=0;
            for(int shift:new int[]{16,8,0})difference+=Math.abs((a>>shift&255)-(b>>shift&255));
            int quarter=(y/8)*2+x/8;errors[quarter]+=difference;counts[quarter]++;total+=difference;pixels++;
        }
        for(int i=0;i<4;i++)if(errors[i]/(counts[i]*765f)>Match3AnimalAppearance.MAXIMUM+Match3AnimalAppearance.MARGIN)
            return Float.POSITIVE_INFINITY;
        return total/(pixels*765f);
    }
    @Test public void unchangedDiagnosticAssignmentsRecheckWhenANewFamilyMakesThemAmbiguous() {
        int[] a=face(),b=a.clone(),c=a.clone();
        for(int i=0;i<256;i++) {
            a[i]=0xff000000 | Math.min(200,a[i]>>16&255)<<16
                    | Math.min(200,a[i]>>8&255)<<8 | Math.min(200,a[i]&255);
            // All channels have room for these fixed offsets; none wraps.
            b[i]=a[i]+0x00141414;c[i]=a[i]+0x00282828;
        }
        Match3UnknownElements groups=new Match3UnknownElements();
        Match3UnknownElements.Observation[][] initial={{observation(a),observation(b),null}};
        for(int i=0;i<3;i++)groups.observe(initial,100+800L*i);
        assertEquals(1,groups.familyCount());
        Match3UnknownElements.Observation[][] withNewFamily={{observation(a),observation(b),observation(c)}};
        for(int i=0;i<3;i++)groups.observe(withNewFamily,2500+800L*i);
        assertEquals(2,groups.familyCount());
        int before=groups.ambiguousObservations;
        groups.observe(withNewFamily,4900);
        assertTrue("A cached family cannot conceal new ambiguity",groups.ambiguousObservations>before);
    }
    @Test public void rigidRegistrationCannotHideAnUnmatchedCoverBeyondTheComparedWindow() {
        int[] original=face(),shifted=new int[256];Arrays.fill(shifted,0xff203050);
        for(int y=0;y<14;y++)System.arraycopy(original,(y+2)*16,shifted,y*16,16);
        Match3AnimalAppearance.Body known=new Match3AnimalAppearance.Body(original);
        assertTrue(new Match3AnimalAppearance.Body(shifted).difference(known)<=Match3AnimalAppearance.MAXIMUM);
        for(int y=14;y<16;y++)for(int x=0;x<16;x++)shifted[y*16+x]=0xfffaffff;
        assertTrue(new Match3AnimalAppearance.Body(shifted).difference(known)>Match3AnimalAppearance.MAXIMUM);
    }
    @Test public void representativeRefreshKeepsAFixedFamilyAnchorAndAQuotaOfTwoSamples() {
        Match3UnknownElements groups=new Match3UnknownElements();int[] ordinary=face();
        feed(groups,ordinary,100);feed(groups,ordinary,900);assertEquals(1,feed(groups,ordinary,1700).size());
        int[] sharper=ordinary.clone();sharper[6*16+6]=0xff201010;
        feed(groups,sharper,13000);feed(groups,sharper,13800);
        List<Match3UnknownElements.Sample> update=feed(groups,sharper,14600);assertEquals(1,update.size());assertEquals("u1",update.get(0).familyId);assertEquals(2,update.get(0).sampleNumber);
        sharper[7*16+7]=0xff100000;
        for(int i=0;i<3;i++)assertTrue(feed(groups,sharper,30000+800L*i).isEmpty());assertEquals(1,groups.familyCount());
    }
    private static int[] face() {
        int[] patch=new int[256];Arrays.fill(patch,0xff203050);
        for(int y=2;y<14;y++)for(int x=2;x<14;x++)patch[y*16+x]=0xffb07050;
        for(int y=5;y<8;y++)for(int x:new int[]{5,10})patch[y*16+x]=0xff402020;
        for(int x=5;x<11;x++)patch[10*16+x]=0xffe0b090;
        return patch;
    }
    private static Match3Position.Cell unknown() { return Match3Position.Cell.obstacle(Match3Position.Kind.SURFACE,-1); }
    private static Match3UnknownElements.Observation observation(int[] patch) { return new Match3UnknownElements.Observation(unknown(),patch); }
    private static List<Match3UnknownElements.Sample> feed(Match3UnknownElements groups,int[] patch,long at) {
        return groups.observe(new Match3UnknownElements.Observation[][]{{observation(patch)}},at);
    }
    @Test public void aKnownFaceWithoutCoverEvidenceCannotSwapCompleteAMatchOrClaimCollection() {
        Match3Position.Cell animal=Match3Position.Cell.animalIdentity('R');
        assertEquals(Match3Position.Kind.ANIMAL,animal.kind);assertEquals('R',animal.color);
        assertEquals(Match3Position.SwapPermission.UNKNOWN,animal.swapPermission);assertFalse(animal.swappable);assertEquals('#',animal.code());
        Match3Position.Cell[][] cells={{Match3Position.Cell.animal('R'),Match3Position.Cell.animal('G'),animal},
                {unknown(),Match3Position.Cell.animal('R'),unknown()}};
        Match3Position board=new Match3Position(cells);
        assertTrue(Match3Board.findSwaps(board).isEmpty());
        Match3CellConfirmation confirmation=new Match3CellConfirmation();
        for(int i=0;i<3;i++)confirmation.accept(board,100+800L*i);
        assertTrue(Match3MoveRanker.rankedMoves(confirmation.accept(board,2500).position,Match3Goals.unknown(0)).isEmpty());
    }
    @Test public void theSameCenterUnderAnUnfamiliarBorderKeepsIdentityButFailsPlainSpriteEvidence() {
        int[] original=face(),covered=face();
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(x<3||x>12||y<3||y>12)covered[y*16+x]=0xfffaffff;
        Match3AnimalAppearance.Face known=new Match3AnimalAppearance.Face('O',original),observed=new Match3AnimalAppearance.Face('.',covered);
        assertEquals('O',Match3AnimalAppearance.recognize(observed,Arrays.asList(known,known),2));
        List<Match3AnimalAppearance.Reference> refs=Arrays.asList(new Match3AnimalAppearance.Reference(known,new Match3AnimalAppearance.Body(original)),
                new Match3AnimalAppearance.Reference(known,new Match3AnimalAppearance.Body(original)));
        Match3Position.Cell candidate=Match3AnimalAppearance.infer(observed,new Match3AnimalAppearance.Body(covered),refs);
        assertEquals('O',candidate.color);assertEquals(Match3Position.SwapPermission.UNKNOWN,candidate.swapPermission);
        assertEquals(2,refs.size());assertFalse(candidate.swappable);
        assertTrue(Match3AnimalAppearance.infer(new Match3AnimalAppearance.Face('.',original),new Match3AnimalAppearance.Body(original),refs).swappable);
        assertNull(Match3AnimalAppearance.infer(observed,new Match3AnimalAppearance.Body(covered),Collections.emptyList()));
    }
    @Test public void severalCopiesInOneFrameAndDuplicateTimestampsDoNotCertifyAnUnknown() {
        Match3UnknownElements groups=new Match3UnknownElements();int[] patch=face();
        Match3UnknownElements.Observation[][] grid={{observation(patch),observation(patch),observation(patch)}};
        for(int i=0;i<6;i++)assertTrue(groups.observe(grid,100).isEmpty());
        assertTrue(groups.observe(grid,900).isEmpty());
        List<Match3UnknownElements.Sample> samples=groups.observe(grid,1700);
        assertEquals(1,samples.size());assertEquals("u1",samples.get(0).familyId);assertEquals(1,groups.familyCount());
        assertFalse(samples.get(0).observation.cell.swappable);
    }
    @Test public void aConflictingTimestampGapOrNewGeometryRequiresFreshStabilityButKeepsTheFamily() {
        Match3UnknownElements groups=new Match3UnknownElements();int[] patch=face(),different=face();Arrays.fill(different,0xff607040);
        feed(groups,patch,100);feed(groups,patch,900);feed(groups,different,900);
        assertTrue(feed(groups,patch,1700).isEmpty());assertTrue(feed(groups,patch,2500).isEmpty());
        assertEquals(1,feed(groups,patch,3300).size());groups.resetStability();
        assertTrue(feed(groups,patch,5000).isEmpty());assertTrue(feed(groups,patch,9000).isEmpty());
        assertTrue(feed(groups,patch,9800).isEmpty());assertTrue(feed(groups,patch,10600).isEmpty());
        assertEquals(1,groups.familyCount());
        Match3UnknownElements.Observation[][] next={{observation(patch)},{observation(patch)}};
        for(int i=0;i<3;i++)assertTrue(groups.observe(next,11400+800L*i).isEmpty());
        assertEquals(1,groups.familyCount());
    }
    @Test public void unknownFamiliesAreBoundedAndNeverBecomePlayableOrAutomaticallyAdmitted() {
        Match3UnknownElements groups=new Match3UnknownElements();Random random=new Random(9127);int captures=0;
        for(int i=0;i<40;i++) {
            int[] patch=new int[256];for(int p=0;p<256;p++)patch[p]=0xff000000|random.nextInt(1<<24);
            for(int frame=0;frame<3;frame++)for(Match3UnknownElements.Sample sample:feed(groups,patch,100+800L*(3*i+frame))) {
                captures++;assertFalse(sample.observation.cell.swappable);assertEquals("identity_or_rule_unverified",sample.reason());
            }
        }
        assertEquals(Match3UnknownElements.MAX_FAMILIES,groups.familyCount());assertEquals(24,captures);assertTrue(groups.saturatedObservations>0);
    }
    @Test public void knownAnimalsAndLowDetailPatchesDoNotGenerateUnknownSampleSpam() {
        Match3UnknownElements groups=new Match3UnknownElements();int[] flat=new int[256];Arrays.fill(flat,0xffaa7040);
        for(int i=0;i<6;i++)assertTrue(feed(groups,flat,100+800L*i).isEmpty());
        Match3UnknownElements.Observation[][] known={{new Match3UnknownElements.Observation(Match3Position.Cell.animal('O'),face())}};
        for(int i=0;i<6;i++)assertTrue(groups.observe(known,4900+800L*i).isEmpty());assertEquals(0,groups.familyCount());
    }
    @Test public void unfamiliarCoveringRemovesOnlyItsOwnEvidenceWithoutInventingMotionElsewhere() {
        Match3Position original=new Match3Position(new Match3Position.Cell[][]{{Match3Position.Cell.animal('R'),Match3Position.Cell.animal('G')}});
        Match3CellConfirmation gate=new Match3CellConfirmation();for(int i=0;i<3;i++)gate.accept(original,100+800L*i);
        Match3Position observed=new Match3Position(new Match3Position.Cell[][]{{Match3Position.Cell.animalIdentity('R'),Match3Position.Cell.animal('G')}});
        Match3CellConfirmation.Snapshot current=gate.accept(observed,2500);assertTrue(current.quiet);assertEquals('G',current.position.cell(0,1).color);
        assertFalse(current.position.cell(0,0).swappable);
    }
}
