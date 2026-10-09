package com.openkhub.sensefield;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public final class Match3GoalStripTest {

    @Test
    public void emptyOrMissingStripAbstainsInsteadOfClaimingNoGoal() {
        Match3GoalStrip.Snapshot empty = Match3GoalStrip.read(new char[0]);
        assertFalse(empty.trusted());
        assertEquals("empty_strip", empty.reason());
        assertTrue(empty.kinds().isEmpty());
        assertFalse(empty.collects('Y'));

        Match3GoalStrip.Snapshot missing = Match3GoalStrip.read(null);
        assertFalse(missing.trusted());
        assertEquals("empty_strip", missing.reason());
    }

    @Test
    public void unrecognizedMiddleSlotAbstainsTheWholeStripNotJustThatSlot() {
        Match3GoalStrip.Snapshot goals = Match3GoalStrip.read(new char[]{'Y', '.', 'B'});
        assertFalse(goals.trusted());
        assertEquals("slot2=unknown", goals.reason());
        assertEquals(3, goals.slotCount());
        assertTrue("abstain must not keep the two readable cards", goals.kinds().isEmpty());
        assertEquals(0, goals.countOf('Y'));
    }

    @Test
    public void singleGoalCardReadsOneKind() {
        Match3GoalStrip.Snapshot goals = Match3GoalStrip.read(new char[]{'Y'});
        assertTrue(goals.trusted());
        assertEquals(Arrays.asList('Y'), goals.kinds());
        assertEquals(1, goals.countOf('Y'));
        assertTrue(goals.collects('Y'));
        assertFalse(goals.collects('B'));
    }

    @Test
    public void twoDifferentCardsReadTwoKindsInScanOrder() {
        Match3GoalStrip.Snapshot goals = Match3GoalStrip.read(new char[]{'B', 'Y'});
        assertTrue(goals.trusted());
        assertEquals(Arrays.asList('B', 'Y'), goals.kinds());
        assertTrue(goals.collects('B'));
        assertTrue(goals.collects('Y'));
        assertFalse(goals.collects('O'));
    }

    @Test
    public void duplicateCardsCollapseToOneKindButKeepSlotCount() {
        Match3GoalStrip.Snapshot goals = Match3GoalStrip.read(new char[]{'Y', 'Y', 'B'});
        assertTrue(goals.trusted());
        assertEquals(Arrays.asList('Y', 'B'), goals.kinds());
        assertEquals(2, goals.countOf('Y'));
        assertEquals(1, goals.countOf('B'));
        assertEquals(3, goals.slotCount());
    }

    @Test
    public void iceCardIsNotAnAnimalSoTheStripAbstains() {
        Match3GoalStrip.Snapshot goals = Match3GoalStrip.read(new char[]{'Y', 'I'});
        assertFalse("ice rules are not read yet, so a goal card showing ice abstains",
                goals.trusted());
        assertEquals("slot2=ice", goals.reason());
    }

    @Test
    public void learnedLabelAndEmptyCellSlotsBothAbstain() {
        assertEquals("slot2=learned_label", Match3GoalStrip.read(new char[]{'Y', '3'}).reason());
        assertFalse(Match3GoalStrip.read(new char[]{'Y', '3'}).trusted());
        assertEquals("slot1=empty", Match3GoalStrip.read(
                new char[]{Match3Sampler.EMPTY_CELL, 'Y'}).reason());
        assertFalse(Match3GoalStrip.read(new char[]{Match3Sampler.EMPTY_CELL, 'Y'}).trusted());
    }

    @Test
    public void describeNamesKindsWhenTrustedAndReasonWhenAbstained() {
        assertEquals("goal_strip=trusted kinds=YB slots=2",
                Match3GoalStrip.read(new char[]{'Y', 'B'}).describe());
        assertTrue(Match3GoalStrip.read(new char[]{'Y', '.'}).describe()
                .startsWith("goal_strip=abstain reason=slot2=unknown slots=2"));
    }

    @Test
    public void factoryForNoCaptureKeepsItsReasonAndNeverClaimsAGoal() {
        Match3GoalStrip.Snapshot goals = Match3GoalStrip.untrusted("no_strip");
        assertFalse(goals.trusted());
        assertEquals("no_strip", goals.reason());
        assertEquals(0, goals.slotCount());
        assertFalse(goals.collects('Y'));
    }
}
