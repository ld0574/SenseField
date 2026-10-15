package com.openkhub.sensefield;

import java.util.Arrays;

/**
 * Cross-game haptic semantics: one body vocabulary the player learns once.
 *
 * <p>Direction is the only haptic vocabulary the player has today
 * ({@link HapticPolicy#directionPattern}-style pulse order encodes it), so every game asks the
 * player to relearn what a vibration means.  This table adds a second, game-independent axis:
 * <em>what a vibration is about</em>.  The three tags below are deliberately the whole table — a
 * longer list is a list nobody memorises.
 *
 * <p>Pure Java on purpose: no Android import, so the table is unit-testable on the JVM and can be
 * reused by any game channel without dragging in a device.  This class only <em>describes</em>
 * semantics; it is not wired into any playback path yet.
 */
final class HapticSemantics {
    /** What a vibration is about, independent of which game produced it. */
    enum Semantic {
        /** Something new worth a glance: two short taps. Never a judgement about what to do. */
        ATTENTION,
        /** A negative state the player should know about: one long hold. */
        DANGER,
        /** Closing in on a tracked target: pulses that swell, so "nearer" is felt, not read. */
        APPROACH,
    }

    /** Bounded waveform parameters. Mirrors {@link HapticPolicy.Pattern}'s layout. */
    static final class Waveform {
        final long[] timingsMs;
        /** Null means the platform's default amplitude, exactly like {@link HapticPolicy.Pattern}. */
        final int[] amplitudes;

        Waveform(long[] timingsMs, int[] amplitudes) {
            this.timingsMs = timingsMs;
            this.amplitudes = amplitudes;
        }

        long durationMs() {
            long duration = 0;
            for (long part : timingsMs) duration += part;
            return duration;
        }
    }

    /** Upper bound on a single semantic waveform so it never occupies the cue channel. */
    private static final long MAX_DURATION_MS = 600;
    /** Smallest "on" slice that still reaches the motor. */
    private static final long MIN_ON_MS = 1;
    private static final int MIN_AMPLITUDE = 1;
    private static final int MAX_AMPLITUDE = 255;

    private HapticSemantics() {}

    /** The waveform for one semantic tag. Never throws, never returns an empty or silent shape. */
    static Waveform waveform(Semantic tag) {
        if (tag == Semantic.DANGER) {
            // One long hold: unmistakably different from any two-pulse shape.
            return new Waveform(new long[]{0, 420}, null);
        }
        if (tag == Semantic.APPROACH) {
            // Two pulses with rising amplitude: "swelling" is the only readable form of
            // "getting closer", and amplitude is the only field that can express it.
            return new Waveform(new long[]{0, 60, 70, 60}, new int[]{0, 70, 0, 150});
        }
        if (tag == Semantic.ATTENTION) {
            // Two short taps, well under the 200 ms budget for a "just so you know" nudge.
            return new Waveform(new long[]{0, 45, 55, 45}, null);
        }
        // Unknown or null tag falls back to the same neutral double tap the platform default
        // already uses, so a bad tag is a plain "something happened", never silence.
        return new Waveform(new long[]{0, 45}, null);
    }

    /** One readable line for diagnostic replay, in the spirit of {@link HapticPolicy#audit}. */
    static String audit(Semantic tag, Waveform waveform) {
        String name = tag == null ? "UNKNOWN" : tag.name();
        return "HapticSemantic tag=" + name + " durationMs=" + waveform.durationMs()
                + " timingsMs=" + Arrays.toString(waveform.timingsMs).replace(" ", "")
                + " amplitudes=" + (waveform.amplitudes == null ? "system"
                : Arrays.toString(waveform.amplitudes).replace(" ", ""));
    }

    /** True when every field of the waveform stays inside the bounded channel budget. */
    static boolean withinBudget(Waveform waveform) {
        if (waveform == null || waveform.timingsMs == null) return false;
        if (waveform.durationMs() > MAX_DURATION_MS) return false;
        for (int index = 1; index < waveform.timingsMs.length; index += 2) {
            if (waveform.timingsMs[index] < MIN_ON_MS) return false;
        }
        if (waveform.amplitudes == null) return true;
        for (int amplitude : waveform.amplitudes) {
            if (amplitude != 0 && (amplitude < MIN_AMPLITUDE || amplitude > MAX_AMPLITUDE)) {
                return false;
            }
        }
        return true;
    }
}
