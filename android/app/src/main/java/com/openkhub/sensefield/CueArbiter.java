package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Frame-level output arbitration for native and visual-memory events.
 *
 * <p>The native engine may emit a direct main-screen cue in the same frame in
 * which a minimap track becomes visible.  Both events are useful state, but
 * they must not produce two independent user cues.  The direct cue therefore
 * wins that frame.  Minimap events are consumed even when suppressed, so a
 * suppressed appearance is never replayed after the direct cue expires.</p>
 */
final class CueArbiter {
    static final String SUPPRESSED_DISABLED = "visual_memory_disabled";
    static final String SUPPRESSED_DIRECT = "direct_native_wins";
    static final String SUPPRESSED_COOLDOWN = "minimap_min_gap";
    static final String SUPPRESSED_DUPLICATE = "already_consumed";

    /**
     * The arbiter must ask the same output layer that will submit a direct
     * cue.  A native event whose category is disabled, whose channels are all
     * unavailable, or whose renderer rejects it cannot consume a minimap
     * appearance.
     */
    interface DirectCueOutput {
        boolean categoryEnabled(CueRequest.Category category);

        boolean outputAvailable(CueRequest.Category category, int requestedChannels);

        /** Return true only when the direct cue was actually accepted. */
        boolean dispatch(NativeFrameResult frame, long observedAtMs);
    }

    private static final DirectCueOutput ALWAYS_AVAILABLE = new DirectCueOutput() {
        @Override public boolean categoryEnabled(CueRequest.Category category) {
            return true;
        }

        @Override public boolean outputAvailable(CueRequest.Category category,
                                                  int requestedChannels) {
            return true;
        }

        @Override public boolean dispatch(NativeFrameResult frame, long observedAtMs) {
            return true;
        }
    };

    static final class Decision {
        final boolean directCuePresent;
        final boolean directCueWins;
        /** True only when the direct output was accepted for this frame. */
        final boolean directCueShouldDispatch;
        final List<TrackedEntity> minimapAppearances;
        final List<Suppressed> suppressedMinimap;

        private Decision(boolean directCuePresent, boolean directCueWins,
                         boolean directCueShouldDispatch,
                         List<TrackedEntity> minimapAppearances,
                         List<Suppressed> suppressedMinimap) {
            this.directCuePresent = directCuePresent;
            this.directCueWins = directCueWins;
            this.directCueShouldDispatch = directCueShouldDispatch;
            this.minimapAppearances = Collections.unmodifiableList(
                    new ArrayList<>(minimapAppearances));
            this.suppressedMinimap = Collections.unmodifiableList(
                    new ArrayList<>(suppressedMinimap));
        }
    }

    static final class Suppressed {
        final TrackedEntity entity;
        final String reason;

        Suppressed(TrackedEntity entity, String reason) {
            this.entity = entity;
            this.reason = reason;
        }
    }

    private final Set<String> consumedMinimapEvents = new LinkedHashSet<>();
    private boolean visualMemoryEnabled = true;
    private boolean peripheralThreatEnabled = true;
    private long lastMinimapCueAtMs = CueRouting.NO_MINIMAP_CUE;

    synchronized void setVisualMemoryEnabled(boolean enabled) {
        if (visualMemoryEnabled == enabled) return;
        visualMemoryEnabled = enabled;
        // Disable is a new output epoch. Native tracks remain alive, while
        // pending/consumed output state must not leak across the toggle.
        clearOutputState();
    }

    synchronized boolean isVisualMemoryEnabled() {
        return visualMemoryEnabled;
    }

    synchronized void setPeripheralThreatEnabled(boolean enabled) {
        if (peripheralThreatEnabled == enabled) return;
        peripheralThreatEnabled = enabled;
    }

    synchronized boolean isPeripheralThreatEnabled() {
        return peripheralThreatEnabled;
    }

    synchronized void reset() {
        clearOutputState();
    }

    synchronized long lastMinimapCueAtMsForTest() {
        return lastMinimapCueAtMs;
    }

    synchronized Decision arbitrate(NativeFrameResult frame, long nowMs,
                                    long minimumMinimapGapMs) {
        return arbitrate(frame, nowMs, minimumMinimapGapMs, ALWAYS_AVAILABLE);
    }

    synchronized Decision arbitrate(NativeFrameResult frame, long nowMs,
                                    long minimumMinimapGapMs,
                                    DirectCueOutput output) {
        return arbitrate(frame, nowMs, minimumMinimapGapMs, output, nowMs);
    }

    synchronized Decision arbitrate(NativeFrameResult frame, long nowMs,
                                    long minimumMinimapGapMs,
                                    DirectCueOutput output, long observedAtMs) {
        if (frame == null) {
            return new Decision(false, false, false,
                    Collections.emptyList(), Collections.emptyList());
        }
        if (output == null) output = ALWAYS_AVAILABLE;
        boolean rawDirect = CueRouting.shouldDispatchDirectNativeCue(
                frame.cueKind, frame.cueDirection);
        boolean edge = CueRouting.isPeripheralThreat(frame.cueKind, frame.cueDirection);
        CueRequest.Category directCategory = CueRouting.directCueCategory(
                frame.cueKind, frame.cueDirection);
        int requestedChannels = CueRouting.directCueRequestedChannels(
                frame.cueKind, frame.cueDirection);
        boolean directShouldDispatch = false;
        boolean directPresent = rawDirect && (directCategory != CueRequest.Category.VISION_MEMORY
                || visualMemoryEnabled)
                && (!edge || peripheralThreatEnabled)
                && output.categoryEnabled(directCategory)
                && output.outputAvailable(directCategory, requestedChannels);
        if (directPresent) {
            // Native perception memory owns edge rearming. Java only decides
            // whether this native event is dispatchable and whether it wins
            // the same-frame minimap arbitration.
            if (output.dispatch(frame, observedAtMs)) {
                directShouldDispatch = true;
            }
        }
        List<TrackedEntity> allowed = new ArrayList<>();
        List<Suppressed> suppressed = new ArrayList<>();
        for (TrackedEntity entity : frame.entities) {
            if (!entity.isMinimapEnemy() || !entity.hasAppearanceTransition()) continue;
            String key = entity.eventKey();
            if (!consumedMinimapEvents.add(key)) {
                suppressed.add(new Suppressed(entity, SUPPRESSED_DUPLICATE));
                continue;
            }
            if (!visualMemoryEnabled) {
                suppressed.add(new Suppressed(entity, SUPPRESSED_DISABLED));
            } else if (directShouldDispatch) {
                suppressed.add(new Suppressed(entity, SUPPRESSED_DIRECT));
            } else if (!CueRouting.shouldCueMinimapAppearance(
                    entity.transition, nowMs, lastMinimapCueAtMs, minimumMinimapGapMs)) {
                suppressed.add(new Suppressed(entity, SUPPRESSED_COOLDOWN));
            } else {
                lastMinimapCueAtMs = nowMs;
                allowed.add(entity);
            }
        }
        // Keep the set bounded in a long-running capture session.  Track IDs
        // are monotonically allocated by native, but a defensive cap prevents
        // an unexpected bridge stream from growing this output-only cache.
        while (consumedMinimapEvents.size() > 256) {
            Iterator<String> iterator = consumedMinimapEvents.iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        return new Decision(directPresent, directShouldDispatch, directShouldDispatch,
                allowed, suppressed);
    }

    private void clearOutputState() {
        consumedMinimapEvents.clear();
        lastMinimapCueAtMs = CueRouting.NO_MINIMAP_CUE;
    }

}
