package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NearZoneCombatPolicyTest {
    private static NativeFrameResult frame(long atMs, int relationEvent, float distance,
                                            TrackedEntity... entities) {
        int[] packet = new int[NativeFrameResult.VERSION_2_HEADER_SIZE
                + NativeFrameResult.VERSIONED_RECORD_STRIDE * entities.length];
        packet[0] = NativeFrameResult.MAGIC;
        packet[1] = NativeFrameResult.VERSION_2;
        packet[2] = NativeFrameResult.VERSION_2_HEADER_SIZE;
        packet[3] = NativeFrameResult.VERSIONED_RECORD_STRIDE;
        packet[15] = entities.length;
        packet[16] = NearZoneRouting.STATE_OCCUPIED;
        packet[17] = relationEvent;
        packet[18] = 3;
        packet[20] = Math.round(distance * 1000f);
        int base = NativeFrameResult.VERSION_2_HEADER_SIZE;
        for (TrackedEntity entity : entities) {
            packet[base] = entity.entityKind;
            packet[base + 1] = entity.trackId;
            packet[base + 2] = entity.state;
            packet[base + 3] = entity.transition;
            packet[base + 5] = Math.round(entity.bbox.left * 1_000_000f);
            packet[base + 6] = Math.round(entity.bbox.top * 1_000_000f);
            packet[base + 7] = Math.round(entity.bbox.width() * 1_000_000f);
            packet[base + 8] = Math.round(entity.bbox.height() * 1_000_000f);
            packet[base + 10] = entity.freshnessMs;
            base += NativeFrameResult.VERSIONED_RECORD_STRIDE;
        }
        return NativeFrameResult.parse(packet, atMs);
    }

    private static TrackedEntity enemy(int id, int age) {
        return new TrackedEntity(TrackedEntity.KIND_MINIMAP_ENEMY, id,
                TrackedEntity.STATE_VISIBLE, 0, .4f, .4f, .02f, .02f,
                .9f, 1000L, 0f, 0f, age, TrackedEntity.TRANSITION_NONE);
    }

    @Test public void scoreRaisesPriorityForFreshCloseThreats() {
        assertEquals(2f, NearZoneCombatPolicy.score(1, true, 1f), 1e-6f);
        assertTrue(NearZoneCombatPolicy.priorityForScore(10f)
                > NearZoneCombatPolicy.priorityForScore(4f));
        assertTrue(NearZoneCombatPolicy.priorityForScore(40f)
                > NearZoneCombatPolicy.priorityForScore(10f));
        assertTrue(NearZoneCombatPolicy.priorityForScore(400f) < 100);
    }

    @Test public void twoFreshTargetsEnterDenseAndSuppressLowerSpeech() {
        NearZoneCombatPolicy policy = new NearZoneCombatPolicy();
        NativeFrameResult first = frame(1000, NearZoneRouting.EVENT_NEAR_ENTER, .2f,
                enemy(1, 10), enemy(2, 12));
        NearZoneCombatPolicy.Decision decision = policy.observe(first, 1000, 250);
        assertTrue(decision.dense);
        assertTrue(decision.enteredDense);
        assertTrue(decision.allowSpeech);

        NativeFrameResult lower = frame(1100, NearZoneRouting.EVENT_NEAR_ENTER, .8f,
                enemy(1, 10), enemy(2, 12));
        decision = policy.observe(lower, 1100, 250);
        assertTrue(decision.dense);
        assertFalse(decision.allowSpeech);
    }

    @Test public void denseModeExitsAfterQuietWindow() {
        NearZoneCombatPolicy policy = new NearZoneCombatPolicy();
        policy.observe(frame(1000, NearZoneRouting.EVENT_NEAR_ENTER, .2f,
                enemy(1, 10), enemy(2, 10)), 1000, 250);
        NearZoneCombatPolicy.Decision decision = policy.observe(
                frame(2601, NearZoneRouting.EVENT_NONE, .2f, enemy(1, 10)),
                2601, 250);
        assertTrue(decision.exitedDense);
        assertFalse(decision.dense);
    }

    @Test public void unspokenFrameCannotSetTheDenseWinner() {
        NearZoneCombatPolicy policy = new NearZoneCombatPolicy();
        policy.observe(frame(1000, NearZoneRouting.EVENT_NONE, .05f,
                enemy(1, 10), enemy(2, 12), enemy(3, 10)), 1000, 250);
        NearZoneCombatPolicy.Decision decision = policy.observe(frame(1100,
                NearZoneRouting.EVENT_NEAR_ENTER, .2f, enemy(1, 10), enemy(2, 10)), 1100, 250);
        assertTrue(decision.dense);
        assertTrue(decision.allowSpeech);
    }

    @Test public void newWindowAcceptsLowerScoreDuringContinuingDenseCombat() {
        NearZoneCombatPolicy policy = new NearZoneCombatPolicy();
        policy.observe(frame(1000, NearZoneRouting.EVENT_NEAR_ENTER, .05f,
                enemy(1, 10), enemy(2, 10)), 1000, 250);
        assertFalse(policy.observe(frame(1200, NearZoneRouting.EVENT_NEAR_ENTER, .3f,
                enemy(1, 10), enemy(2, 10)), 1200, 250).allowSpeech);
        NearZoneCombatPolicy.Decision decision = policy.observe(frame(1800,
                NearZoneRouting.EVENT_NEAR_ENTER, .3f, enemy(1, 10), enemy(2, 10)), 1800, 250);
        assertTrue(decision.dense);
        assertFalse(decision.exitedDense);
        assertTrue(decision.allowSpeech);
    }

    @Test public void staleAndInvisibleTargetsDoNotInflateThreat() {
        NearZoneCombatPolicy policy = new NearZoneCombatPolicy();
        NearZoneCombatPolicy.Decision decision = policy.observe(frame(1000,
                NearZoneRouting.EVENT_NEAR_ENTER, .2f,
                enemy(1, 10), enemy(2, 251), new TrackedEntity(
                        TrackedEntity.KIND_MINIMAP_ENEMY, 3, TrackedEntity.STATE_LOST,
                        0, .4f, .4f, .02f, .02f, .9f, 1000, 0, 0, 10,
                        TrackedEntity.TRANSITION_NONE)), 1000, 250);
        assertEquals(1, decision.freshEnemyCount);
        assertFalse(decision.dense);
    }

    @Test public void higherScoreWithinTheWindowCanReplaceTheWinner() {
        NearZoneCombatPolicy policy = new NearZoneCombatPolicy();
        policy.observe(frame(1000, NearZoneRouting.EVENT_NEAR_ENTER, .3f,
                enemy(1, 10), enemy(2, 10)), 1000, 250);
        NearZoneCombatPolicy.Decision higher = policy.observe(frame(1100,
                NearZoneRouting.EVENT_NEAR_ENTER, .1f, enemy(1, 10), enemy(2, 10)), 1100, 250);
        assertTrue(higher.allowSpeech);
        assertEquals(higher.score, higher.bestWindowScore, 1e-6f);
        policy.reset();
        assertTrue(policy.observe(frame(1150, NearZoneRouting.EVENT_NEAR_ENTER, .3f,
                enemy(1, 10), enemy(2, 10)), 1150, 250).allowSpeech);
    }
}
