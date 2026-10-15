package com.openkhub.sensefield;

/**
 * Noise-level to output mapping for Adaptive Audio Context (AAC).
 *
 * <p>The perception layer already exists: {@link VoiceActivityGate} keeps an adaptive noise
 * floor ({@code noise} EMA, threshold {@code max(350, noise*3.2)}). What was missing is the
 * <em>output</em> side — turning that floor into a volume decision and a decision about
 * whether the audio channel is trustworthy at all.
 *
 * <p>This class is deliberately pure Java with no Android import and no audio handle: it maps
 * a noise reading onto a gain and a fallback flag. Wiring it into {@code CuePlayer} is a
 * separate step, because that touches the playback path.
 *
 * <p>Hysteresis lives here rather than in the caller so the "entering noisy" and "leaving
 * noisy" rules stay testable and cannot drift apart between call sites.
 */
final class AudioContextLevel {
    /** Coarse environment classes. Three is the whole table — more is a table nobody reads. */
    enum Level {
        /** Speech at the player's own setting; nothing is wrong. */
        QUIET,
        /** Speech boosted; the channel is strained but still usable. */
        MODERATE,
        /** Speech cannot be trusted; touch must carry the critical cues. */
        NOISY,
    }

    /** Where a cue should be delivered once the environment is known. */
    enum Channel {
        SPEECH,
        SPEECH_PLUS_TOUCH,
    }

    /**
     * Bounded gain and channel decision for one environment class.
     *
     * @param gain multiplier applied on top of the player's own volume setting
     * @param channel whether touch must also carry critical cues
     */
    static final class Decision {
        final float gain;
        final Channel channel;

        Decision(float gain, Channel channel) {
            this.gain = gain;
            this.channel = channel;
        }
    }

    /**
     * Consecutive readings required to enter NOISY. Asymmetric on purpose: entering needs
     * fewer confirmations than leaving, so a passing siren cannot drop the player back into
     * a mode that assumes speech is audible.
     */
    private static final int ENTER_NOISY_STREAK = 3;
    private static final int LEAVE_NOISY_STREAK = 5;
    /** A calm floor below this is "quiet enough for the player's own setting again". */
    private static final int ENTER_QUIET_STREAK = 10;

    private static final float NOISY_FLOOR = 350f;
    private static final float QUIET_FLOOR = 120f;

    private static final float QUIET_GAIN = 0.70f;
    private static final float MODERATE_GAIN = 1.00f;
    private static final float NOISY_GAIN = 1.00f;

    private Level level = Level.QUIET;
    private int noisyStreak;
    private int calmStreak;

    private AudioContextLevel() {}

    static AudioContextLevel create() {
        return new AudioContextLevel();
    }

    /** The level after the most recent reading. Never null. */
    Level level() {
        return level;
    }

    /**
     * Feed one noise-floor reading and advance the hysteresis state.
     *
     * <p>{@code floor} is the adaptive noise floor in PCM units, the same quantity
     * {@link VoiceActivityGate} maintains. A non-finite or negative reading is treated as
     * "no information" and leaves the state untouched rather than guessing.
     */
    Level accept(float floor) {
        if (!Float.isFinite(floor) || floor < 0f) return level;
        boolean noisy = floor >= NOISY_FLOOR;
        boolean calm = floor < QUIET_FLOOR;
        if (noisy) {
            noisyStreak++;
            calmStreak = 0;
        } else if (calm) {
            calmStreak++;
            noisyStreak = 0;
        } else {
            // Between the two floors: neither confirms nor breaks a streak, so a brief
            // excursion cannot flip the mode. The streak is deliberately preserved.
        }
        if (level != Level.NOISY && noisyStreak >= ENTER_NOISY_STREAK) {
            level = Level.NOISY;
            calmStreak = 0;
        } else if (level == Level.NOISY && !noisy && calmStreak >= LEAVE_NOISY_STREAK) {
            // Leaving NOISY lands in MODERATE, not QUIET: the floor is still elevated.
            level = Level.MODERATE;
            calmStreak = 0;
            noisyStreak = 0;
        } else if (level == Level.MODERATE && calm && calmStreak >= ENTER_QUIET_STREAK) {
            level = Level.QUIET;
            calmStreak = 0;
            noisyStreak = 0;
        }
        return level;
    }

    /** The output decision for the current level. */
    Decision decision() {
        if (level == Level.NOISY) {
            return new Decision(NOISY_GAIN, Channel.SPEECH_PLUS_TOUCH);
        }
        if (level == Level.MODERATE) {
            return new Decision(MODERATE_GAIN, Channel.SPEECH);
        }
        return new Decision(QUIET_GAIN, Channel.SPEECH);
    }

    /** Forget everything, e.g. on a native reset or an accessibility-service restart. */
    void reset() {
        level = Level.QUIET;
        noisyStreak = 0;
        calmStreak = 0;
    }
}
