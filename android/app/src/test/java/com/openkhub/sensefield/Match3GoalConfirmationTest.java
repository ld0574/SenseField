package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.util.Collections;
import org.junit.Test;

public final class Match3GoalConfirmationTest {
    private static Match3Goals read(int level, int count, int steps, long at) {
        return new Match3Goals(level, steps, Collections.singletonList(new Match3Goals.Target(0, Match3Goals.Kind.CHICK, count, false)), true, at);
    }
    @Test public void threeDistinctSamplesAreNeededAndReusingAnImageCannotConfirmIt() {
        Match3GoalConfirmation gate = new Match3GoalConfirmation();
        assertFalse(gate.accept(read(42,66,32,100),100).hudVerified);
        assertFalse(gate.accept(read(42,66,32,100),110).hudVerified);
        assertFalse(gate.accept(read(42,66,32,900),900).hudVerified);
        assertTrue(gate.accept(read(42,66,32,1700),1700).hudVerified);
    }
    @Test public void aChangedGoalRetiresTheOldValueBeforeItCanBeReconfirmed() {
        Match3GoalConfirmation gate = new Match3GoalConfirmation();
        for (long at:new long[]{100,900,1700}) gate.accept(read(42,66,32,at),at);
        assertFalse(gate.accept(read(42,63,31,2500),2500).hudVerified);
        assertFalse(gate.accept(read(42,63,31,3300),3300).hudVerified);
        assertEquals(63,gate.accept(read(42,63,31,4100),4100).remaining(Match3Goals.Kind.CHICK));
    }
    @Test public void levelChangesInvalidImagesAndLongGapsCannotReuseThePreviousMission() {
        for (boolean gap:new boolean[]{false,true}) {
            Match3GoalConfirmation gate = new Match3GoalConfirmation();
            for (long at:new long[]{100,900,1700}) gate.accept(read(42,66,32,at),at);
            assertFalse(gate.accept(read(gap?42:43,66,32,gap?5000:2500),gap?5000:2500).hudVerified);
            gate.accept(Match3Goals.unknown(6000),6000);
            assertFalse(gate.accept(read(42,66,32,6800),6800).hudVerified);
        }
    }
    @Test public void anUnknownNumberStaysUnknownAfterTemporalConfirmation() {
        Match3GoalConfirmation gate = new Match3GoalConfirmation(); Match3Goals result=null;
        for (long at:new long[]{100,900,1700}) result=gate.accept(read(42,-1,32,at),at);
        assertNotNull(result); assertEquals(-1,result.remaining(Match3Goals.Kind.CHICK)); assertFalse(result.fullyKnown());
    }
}
