package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import org.junit.Test;

public final class CueArbiterTest {
    private static final class FakeDirectOutput implements CueArbiter.DirectCueOutput {
        final Map<CueRequest.Category, Boolean> categories =
                new EnumMap<>(CueRequest.Category.class);
        boolean outputAvailable = true;
        boolean dispatch = true;
        int dispatches;

        @Override public boolean categoryEnabled(CueRequest.Category category) {
            Boolean enabled = categories.get(category);
            return enabled == null || enabled;
        }

        @Override public boolean outputAvailable(CueRequest.Category category,
                                                  int requestedChannels) {
            return outputAvailable;
        }

        @Override public boolean dispatch(NativeFrameResult frame, long observedAtMs) {
            dispatches++;
            return dispatch;
        }
    }

    private static NativeFrameResult frame(int cueKind, int trackId, int transition) {
        return frame(cueKind, 0, trackId, transition);
    }

    private static NativeFrameResult frame(int cueKind, int direction,
                                           int trackId, int transition) {
        int[] packet = new int[NativeFrameResult.LEGACY_HEADER_SIZE
                + NativeFrameResult.LEGACY_MARKER_STRIDE];
        packet[0] = cueKind;
        packet[1] = direction;
        packet[2] = 40;
        packet[7] = 100_000;
        packet[8] = 100_000;
        packet[9] = 800_000;
        packet[10] = 800_000;
        packet[11] = 1;
        packet[12] = TrackedEntity.STATE_VISIBLE;
        packet[14] = 200_000;
        packet[15] = 200_000;
        packet[16] = 100_000;
        packet[17] = 100_000;
        packet[19] = transition;
        packet[20] = trackId;
        return NativeFrameResult.parse(packet, 1000);
    }

    @Test public void directMainEdgeWinsAndSuppressedAppearanceIsConsumed() {
        CueArbiter arbiter = new CueArbiter();
        CueArbiter.Decision first = arbiter.arbitrate(
                frame(TrackedEntity.KIND_MAIN_ENEMY, 1, 7,
                        TrackedEntity.TRANSITION_APPEAR), 100, 15_000);
        assertTrue(first.directCueWins);
        assertTrue(first.directCueShouldDispatch);
        assertTrue(first.minimapAppearances.isEmpty());
        assertEquals(CueArbiter.SUPPRESSED_DIRECT,
                first.suppressedMinimap.get(0).reason);

        CueArbiter.Decision replay = arbiter.arbitrate(
                frame(0, 7, TrackedEntity.TRANSITION_APPEAR), 20_000, 15_000);
        assertTrue(replay.minimapAppearances.isEmpty());
        assertTrue(replay.suppressedMinimap.size() == 1);
    }

    @Test public void firstAppearanceIsAllowedAndCooldownSuppressesNewTrack() {
        CueArbiter arbiter = new CueArbiter();
        CueArbiter.Decision first = arbiter.arbitrate(
                frame(0, 1, TrackedEntity.TRANSITION_APPEAR), 100, 15_000);
        assertEquals(1, first.minimapAppearances.size());

        CueArbiter.Decision second = arbiter.arbitrate(
                frame(0, 2, TrackedEntity.TRANSITION_APPEAR), 14_999, 15_000);
        assertEquals(CueArbiter.SUPPRESSED_COOLDOWN,
                second.suppressedMinimap.get(0).reason);
        CueArbiter.Decision third = arbiter.arbitrate(
                frame(0, 3, TrackedEntity.TRANSITION_APPEAR), 15_100, 15_000);
        assertEquals(1, third.minimapAppearances.size());
    }

    @Test public void disablingClearsConsumedOutputStateWithoutTouchingNativeSnapshot() {
        CueArbiter arbiter = new CueArbiter();
        arbiter.arbitrate(frame(0, 1, TrackedEntity.TRANSITION_APPEAR), 100, 15_000);
        arbiter.setVisualMemoryEnabled(false);
        CueArbiter.Decision disabled = arbiter.arbitrate(
                frame(0, 1, TrackedEntity.TRANSITION_APPEAR), 200, 15_000);
        assertEquals(CueArbiter.SUPPRESSED_DISABLED,
                disabled.suppressedMinimap.get(0).reason);
        arbiter.setVisualMemoryEnabled(true);
        CueArbiter.Decision enabled = arbiter.arbitrate(
                frame(0, 1, TrackedEntity.TRANSITION_APPEAR), 300, 15_000);
        assertEquals(1, enabled.minimapAppearances.size());
    }

    @Test public void peripheralDirectEventsTrustNativeTransitionState() {
        CueArbiter arbiter = new CueArbiter();
        CueArbiter.Decision first = arbiter.arbitrate(
                frame(TrackedEntity.KIND_MAIN_ENEMY, 1, 3,
                        TrackedEntity.TRANSITION_NONE), 100, 15_000);
        assertTrue(first.directCuePresent);
        assertTrue(first.directCueShouldDispatch);

        CueArbiter.Decision duplicate = arbiter.arbitrate(
                frame(TrackedEntity.KIND_MAIN_ENEMY, 1, 4,
                        TrackedEntity.TRANSITION_NONE), 2_099, 15_000);
        assertTrue(duplicate.directCuePresent);
        assertTrue(duplicate.directCueShouldDispatch);
    }

    @Test public void disabledDirectCategoryLeavesMinimapAppearanceEligible() {
        FakeDirectOutput output = new FakeDirectOutput();
        output.categories.put(CueRequest.Category.DANGER, false);
        CueArbiter arbiter = new CueArbiter();

        CueArbiter.Decision decision = arbiter.arbitrate(
                frame(TrackedEntity.KIND_DANGER_PING, 1,
                        TrackedEntity.TRANSITION_APPEAR),
                100, 15_000, output);

        assertFalse(decision.directCueWins);
        assertFalse(decision.directCuePresent);
        assertEquals(0, output.dispatches);
        assertEquals(1, decision.minimapAppearances.size());
    }

    @Test public void unavailableDirectOutputLeavesMinimapAppearanceEligible() {
        FakeDirectOutput output = new FakeDirectOutput();
        output.outputAvailable = false;
        CueArbiter arbiter = new CueArbiter();

        CueArbiter.Decision decision = arbiter.arbitrate(
                frame(TrackedEntity.KIND_PLAYER_STATE, 1,
                        TrackedEntity.TRANSITION_APPEAR),
                100, 15_000, output);

        assertFalse(decision.directCueWins);
        assertFalse(decision.directCuePresent);
        assertEquals(0, output.dispatches);
        assertEquals(1, decision.minimapAppearances.size());
    }

    @Test public void rejectedDirectSubmissionDoesNotConsumeMinimapAppearance() {
        FakeDirectOutput output = new FakeDirectOutput();
        output.dispatch = false;
        CueArbiter arbiter = new CueArbiter();

        CueArbiter.Decision decision = arbiter.arbitrate(
                frame(TrackedEntity.KIND_MAIN_ENEMY, 1, 1,
                        TrackedEntity.TRANSITION_APPEAR),
                100, 0, output);

        assertFalse(decision.directCueWins);
        assertEquals(1, output.dispatches);
        assertEquals(1, decision.minimapAppearances.size());
    }

    @Test public void disabledPeripheralAndVerticalMainCueDoNotSuppressMinimap() {
        FakeDirectOutput peripheralOutput = new FakeDirectOutput();
        CueArbiter peripheralArbiter = new CueArbiter();
        peripheralArbiter.setPeripheralThreatEnabled(false);
        CueArbiter.Decision peripheral = peripheralArbiter.arbitrate(
                frame(TrackedEntity.KIND_MAIN_ENEMY, 1, 3,
                        TrackedEntity.TRANSITION_APPEAR),
                100, 0, peripheralOutput);
        assertEquals(1, peripheral.minimapAppearances.size());
        assertEquals(0, peripheralOutput.dispatches);

        FakeDirectOutput verticalOutput = new FakeDirectOutput();
        CueArbiter verticalArbiter = new CueArbiter();
        CueArbiter.Decision vertical = verticalArbiter.arbitrate(
                frame(TrackedEntity.KIND_MAIN_ENEMY, 3, 1,
                        TrackedEntity.TRANSITION_APPEAR),
                100, 0, verticalOutput);
        assertEquals(1, vertical.minimapAppearances.size());
        assertEquals(0, verticalOutput.dispatches);
    }

    @Test public void dangerAndPlayerCategorySwitchesAreCheckedBeforeDirectDispatch() {
        for (CueRequest.Category category : new CueRequest.Category[] {
                CueRequest.Category.DANGER, CueRequest.Category.PLAYER_STATE}) {
            FakeDirectOutput output = new FakeDirectOutput();
            output.categories.put(category, false);
            CueArbiter arbiter = new CueArbiter();
            int kind = category == CueRequest.Category.DANGER
                    ? TrackedEntity.KIND_DANGER_PING : TrackedEntity.KIND_PLAYER_STATE;
            CueArbiter.Decision decision = arbiter.arbitrate(
                    frame(kind, 1, TrackedEntity.TRANSITION_APPEAR),
                    100, 0, output);
            assertEquals(1, decision.minimapAppearances.size());
            assertEquals(0, output.dispatches);
        }
    }
}
