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

    /**
     * Where a vibration came from. Pure provenance: it never changes the waveform shape, only
     * travels with it so diagnostics and future adaptive logic can attribute a cue.
     *
     * <ul>
     *   <li>{@link #TEMPLATE} — a player-authored template asked for it.</li>
     *   <li>{@link #ADAPTIVE} — the learning layer re-timed or re-prioritised it.</li>
     *   <li>{@link #NOISE_FALLBACK} — the audio channel was too noisy, so this cue was
     *       demoted to touch to avoid the player simply not hearing it.</li>
     * </ul>
     */
    enum TriggerSource {
        TEMPLATE,
        ADAPTIVE,
        NOISE_FALLBACK,
    }

    /** Bounded waveform parameters. Mirrors {@link HapticPolicy.Pattern}'s layout. */
    static final class Waveform {
        final long[] timingsMs;
        /** Null means the platform's default amplitude, exactly like {@link HapticPolicy.Pattern}. */
        final int[] amplitudes;
        /** Provenance only; never consulted when building or validating the shape. */
        final TriggerSource source;

        Waveform(long[] timingsMs, int[] amplitudes) {
            this(timingsMs, amplitudes, TriggerSource.TEMPLATE);
        }

        Waveform(long[] timingsMs, int[] amplitudes, TriggerSource source) {
            this.timingsMs = timingsMs;
            this.amplitudes = amplitudes;
            this.source = normalize(source);
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

    /** The fixed shape each semantic resolves to. */
    private static final class Shape {
        final long[] timingsMs;
        final int[] amplitudes;

        Shape(long[] timingsMs, int[] amplitudes) {
            this.timingsMs = timingsMs;
            this.amplitudes = amplitudes;
        }
    }

    private HapticSemantics() {}

    /** Maps a semantic tag onto its fixed shape; null and unknown tags share one neutral shape. */
    private static Shape resolve(Semantic tag) {
        if (tag == Semantic.DANGER) {
            // One long hold: unmistakably different from any two-pulse shape.
            return new Shape(new long[]{0, 420}, null);
        }
        if (tag == Semantic.APPROACH) {
            // Two pulses with rising amplitude: "swelling" is the only readable form of
            // "getting closer", and amplitude is the only field that can express it.
            return new Shape(new long[]{0, 60, 70, 60}, new int[]{0, 70, 0, 150});
        }
        if (tag == Semantic.ATTENTION) {
            // Two short taps, well under the 200 ms budget for a "just so you know" nudge.
            return new Shape(new long[]{0, 45, 55, 45}, null);
        }
        // Unknown or null tag falls back to the same neutral single tap the platform default
        // already uses, so a bad tag is a plain "something happened", never silence.
        return new Shape(new long[]{0, 45}, null);
    }

    /** The waveform for one semantic tag. Never throws, never returns an empty or silent shape. */
    static Waveform waveform(Semantic tag) {
        return waveform(tag, TriggerSource.TEMPLATE);
    }

    /**
     * The waveform for one semantic tag, tagged with where the cue came from.
     *
     * <p>{@code source} is provenance only: the shape is decided by {@code tag} alone, so the
     * same semantic feels identical no matter who asked for it. That is deliberate — a
     * noise-fallback cue must not feel weaker than a template cue, or the demotion would itself
     * lose information.
     */
    static Waveform waveform(Semantic tag, TriggerSource source) {
        Shape resolved = resolve(tag);
        return new Waveform(resolved.timingsMs, resolved.amplitudes, source);
    }

    /** Null source means "nobody said", which is the template path. */
    static TriggerSource normalize(TriggerSource source) {
        return source == null ? TriggerSource.TEMPLATE : source;
    }

    /** One readable line for diagnostic replay, in the spirit of {@link HapticPolicy#audit}. */
    static String audit(Semantic tag, Waveform waveform) {
        String name = tag == null ? "UNKNOWN" : tag.name();
        return "HapticSemantic tag=" + name + " durationMs=" + waveform.durationMs()
                + " source=" + normalize(waveform.source).name()
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
