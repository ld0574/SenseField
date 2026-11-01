package com.openkhub.sensefield;

/**
 * Reads back, in plain JVM arithmetic, what the player is about to <em>hear</em> from a cue the
 * app itself mixed: how loud it is, and whether it leans to the left or the right ear.
 *
 * <p>Input is the <b>interleaved stereo 16-bit PCM</b> buffer that {@code CuePlayer} hands to
 * {@code AudioTrack} — even indices are the left channel, odd indices the right channel
 * (see the two fill sites in {@code CuePlayer}). This class never synthesises anything and never
 * re-derives the balance law: it only measures a buffer that already exists. The relationship to
 * {@link NearZoneRouting#stereoGains(float, float)} is therefore "does the measurement agree",
 * which {@code BroadcastSoundMeterTest} checks; it is not a second copy of that formula.
 *
 * <p>Deliberately un-importable: this file touches no Android framework type, so the reading is
 * checkable by a JVM unit test. It also refuses to work on a mono buffer — a single-channel capture
 * path has no left/right energy difference, so any balance claim from one would be fabricated.
 * Callers must hand it the stereo playback buffer only.
 */
final class BroadcastSoundMeter {

    /** Private constructor: this is a pure function bag, never an instance. */
    private BroadcastSoundMeter() {
    }

    /**
     * Fixed number of loudness bands. Band {@code 0} always means "nothing was audible", and no
     * input — however loud — may read outside {@code [0, LOUDNESS_BAND_COUNT - 1]}.
     */
    static final int LOUDNESS_BAND_COUNT = 5;

    /**
     * Upper edge, in signed-16-bit RMS units, of bands 0..3. A reading at or above the last entry
     * saturates in the top band, so a louder cue can never wrap back down to a quieter band.
     * Each band is eight times the previous one, which is coarse on purpose: the question a
     * hearing-impaired player asks is "was that a whisper or a shout", not a decibel figure.
     */
    static final double[] LOUDNESS_BAND_UPPER_RMS = {8.0, 64.0, 512.0, 4096.0};

    /**
     * How much stronger one channel's RMS must be, before this reader stops calling the pair
     * centred and starts naming a side. Exposed so a unit test can stand exactly on the edge.
     */
    static final double BALANCE_RATIO = 1.5;

    /** The only three balance readings this class can ever produce. */
    static final String BALANCE_LEFT = "偏左";
    static final String BALANCE_CENTER = "居中";
    static final String BALANCE_RIGHT = "偏右";

    /** Loudness words, indexed by band. No band number reaches the player-facing sentence. */
    static final String[] LOUDNESS_WORDS = {"没有播出声音", "极轻", "很轻", "中等", "很响"};

    /** One pass over the buffer produces all three deliverables of the contract. */
    static final class Reading {
        final int level;
        final String balance;
        final String sentence;

        private Reading(int level, String balance, String sentence) {
            this.level = level;
            this.balance = balance;
            this.sentence = sentence;
        }
    }

    /**
     * Measures one interleaved stereo buffer. {@code null}, empty, and a trailing half frame are
     * all treated as silence instead of throwing: a meter that can crash a playback worker is
     * worse than no meter.
     *
     * @param interleavedStereoPcm16 stereo frames only; never a mono capture buffer
     * @return the loudness band, the balance word, and one sentence for the player
     */
    static Reading read(short[] interleavedStereoPcm16) {
        double left = channelRms(interleavedStereoPcm16, 0);
        double right = channelRms(interleavedStereoPcm16, 1);
        int level = bandOf(left, right);
        String balance = balanceOf(left, right);
        return new Reading(level, balance, sentenceOf(level, balance));
    }

    /** Convenience for a caller that only needs the sentence. */
    static String describe(short[] interleavedStereoPcm16) {
        return read(interleavedStereoPcm16).sentence;
    }

    /**
     * Root-mean-square of one channel, over that channel's own samples only. The denominator is
     * the frame count, never the total number of shorts: dividing a stereo buffer by its sample
     * count would silently report a mono reading and hide half the energy.
     */
    static double channelRms(short[] interleavedStereoPcm16, int channel) {
        long frames = frameCount(interleavedStereoPcm16);
        if (frames <= 0L || channel < 0 || channel > 1) return 0.0;
        double sumOfSquares = 0.0;
        short[] samples = interleavedStereoPcm16;
        for (long frame = 0; frame < frames; frame++) {
            double value = samples[(int) (frame * 2L + channel)];
            sumOfSquares += value * value;
        }
        return Math.sqrt(sumOfSquares / frames);
    }

    /** Complete stereo frames; a dangling half sample is ignored rather than guessed at. */
    static long frameCount(short[] interleavedStereoPcm16) {
        return interleavedStereoPcm16 == null ? 0L : interleavedStereoPcm16.length / 2L;
    }

    /**
     * Loudness band from the energy actually present in both channels. Monotonic by construction:
     * every band test is a comparison against a fixed upper edge, so doubling the signal can only
     * move the reading up, and anything past the last edge saturates at the top band.
     */
    static int bandOf(double channelRmsLeft, double channelRmsRight) {
        double loudness = Math.sqrt(
                (channelRmsLeft * channelRmsLeft + channelRmsRight * channelRmsRight) / 2.0);
        if (Double.isNaN(loudness) || Double.isInfinite(loudness) || loudness <= 0.0) return 0;
        int band = 0;
        while (band < LOUDNESS_BAND_UPPER_RMS.length && loudness >= LOUDNESS_BAND_UPPER_RMS[band]) {
            band++;
        }
        return Math.min(band, LOUDNESS_BAND_COUNT - 1);
    }

    /** Loudness band of a buffer, measured rather than passed in. */
    static int levelOf(short[] interleavedStereoPcm16) {
        return bandOf(channelRms(interleavedStereoPcm16, 0), channelRms(interleavedStereoPcm16, 1));
    }

    /**
     * Left / centre / right from the ratio of the two channels' own RMS values. Comparison is done
     * by multiplication so that a fully silent channel cannot divide by zero, and so that both
     * silence and an exactly-equal pair land on {@link #BALANCE_CENTER}.
     */
    static String balanceOf(short[] interleavedStereoPcm16) {
        return balanceOf(channelRms(interleavedStereoPcm16, 0),
                channelRms(interleavedStereoPcm16, 1));
    }

    static String balanceOf(double channelRmsLeft, double channelRmsRight) {
        if (channelRmsLeft > 0.0 && channelRmsLeft >= BALANCE_RATIO * channelRmsRight) {
            return BALANCE_LEFT;
        }
        if (channelRmsRight > 0.0 && channelRmsRight >= BALANCE_RATIO * channelRmsLeft) {
            return BALANCE_RIGHT;
        }
        return BALANCE_CENTER;
    }

    /**
     * The one sentence the player sees. It carries no raw number, no coordinate literal, and — by
     * design — never claims that a sound inside the game came from a direction: the only thing this
     * meter can honestly attribute is the cue this app just mixed and pushed to the audio system.
     */
    static String sentenceOf(int level, String balance) {
        String word = level <= 0 ? LOUDNESS_WORDS[0]
                : LOUDNESS_WORDS[Math.min(Math.max(level, 0), LOUDNESS_WORDS.length - 1)];
        if (level <= 0) return "本机这条提示没有播出声音。";
        return "本机刚播出的提示音，响度" + word + "，" + balance + "。";
    }
}
