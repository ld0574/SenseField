package com.openkhub.sensefield;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Contract tests for the AAC noise-to-output mapping.
 *
 * <p>These pin the hysteresis rules, because the whole point of the class is that a transient
 * noise cannot flip the player's delivery mode. They also pin the invariant that NOISY never
 * produces a <em>quieter</em> cue than QUIET — a demotion that loses volume loses the very
 * information it was meant to protect.
 */
public final class AudioContextLevelTest {
    private static final float CALM = 60f;
    private static final float MID = 200f;
    private static final float NOISY = 900f;

    private static AudioContextLevel quietContext() {
        AudioContextLevel context = AudioContextLevel.create();
        context.accept(CALM);
        return context;
    }

    @Test public void startsQuietWithThePlayersOwnSetting() {
        AudioContextLevel context = AudioContextLevel.create();

        assertEquals(AudioContextLevel.Level.QUIET, context.level());
        AudioContextLevel.Decision decision = context.decision();
        assertEquals(0.70f, decision.gain, 1e-6f);
        assertEquals(AudioContextLevel.Channel.SPEECH, decision.channel);
    }

    @Test public void threeNoisyReadingsEnterNoisy() {
        AudioContextLevel context = quietContext();

        context.accept(NOISY);
        context.accept(NOISY);
        assertEquals("two readings are not enough", AudioContextLevel.Level.QUIET, context.level());

        context.accept(NOISY);
        assertEquals(AudioContextLevel.Level.NOISY, context.level());
    }

    @Test public void aSingleCalmReadingBreaksTheNoisyStreak() {
        AudioContextLevel context = quietContext();

        context.accept(NOISY);
        context.accept(NOISY);
        context.accept(CALM);
        context.accept(NOISY);
        context.accept(NOISY);

        assertEquals("the streak was broken", AudioContextLevel.Level.QUIET, context.level());
    }

    @Test public void noisyNeverDeliversQuieterThanQuiet() {
        AudioContextLevel context = quietContext();
        float quietGain = context.decision().gain;

        for (int i = 0; i < 3; i++) context.accept(NOISY);

        AudioContextLevel.Decision noisy = context.decision();
        assertTrue("a noisy cue must not be quieter than a quiet one",
                noisy.gain >= quietGain);
        assertEquals(AudioContextLevel.Channel.SPEECH_PLUS_TOUCH, noisy.channel);
    }

    @Test public void noisyNeedsFiveCalmReadingsToLeave() {
        AudioContextLevel context = quietContext();
        for (int i = 0; i < 3; i++) context.accept(NOISY);
        assertEquals(AudioContextLevel.Level.NOISY, context.level());

        for (int i = 0; i < 4; i++) context.accept(CALM);
        assertEquals("four calm readings are not enough", AudioContextLevel.Level.NOISY,
                context.level());

        context.accept(CALM);
        assertEquals("leaving NOISY lands in MODERATE, not straight back to QUIET",
                AudioContextLevel.Level.MODERATE, context.level());
    }

    @Test public void moderateFallsBackToQuietOnlyAfterASustainedCalm() {
        AudioContextLevel context = quietContext();
        for (int i = 0; i < 3; i++) context.accept(NOISY);
        for (int i = 0; i < 5; i++) context.accept(CALM);
        assertEquals(AudioContextLevel.Level.MODERATE, context.level());

        for (int i = 0; i < 9; i++) context.accept(CALM);
        assertEquals("nine calm readings are not enough", AudioContextLevel.Level.MODERATE,
                context.level());

        context.accept(CALM);
        assertEquals(AudioContextLevel.Level.QUIET, context.level());
    }

    @Test public void moderateBoostsSpeechButStillSpeechOnly() {
        AudioContextLevel context = quietContext();
        for (int i = 0; i < 3; i++) context.accept(NOISY);
        for (int i = 0; i < 5; i++) context.accept(CALM);

        AudioContextLevel.Decision decision = context.decision();
        assertEquals(1.00f, decision.gain, 1e-6f);
        assertEquals(AudioContextLevel.Channel.SPEECH, decision.channel);
    }

    @Test public void aMidReadingNeitherConfirmsNorBreaksAStreak() {
        AudioContextLevel context = quietContext();

        context.accept(NOISY);
        context.accept(MID);
        context.accept(NOISY);
        context.accept(MID);
        context.accept(NOISY);

        assertEquals("a mid reading must not extend the noisy streak",
                AudioContextLevel.Level.NOISY, context.level());
    }

    @Test public void aGarbageReadingLeavesTheStateUntouched() {
        AudioContextLevel context = quietContext();
        for (int i = 0; i < 3; i++) context.accept(NOISY);
        assertEquals(AudioContextLevel.Level.NOISY, context.level());

        context.accept(Float.NaN);
        context.accept(Float.POSITIVE_INFINITY);
        context.accept(-1f);

        assertEquals("a non-finite or negative floor carries no information",
                AudioContextLevel.Level.NOISY, context.level());
    }

    @Test public void resetReturnsToQuiet() {
        AudioContextLevel context = quietContext();
        for (int i = 0; i < 3; i++) context.accept(NOISY);
        assertEquals(AudioContextLevel.Level.NOISY, context.level());

        context.reset();

        assertEquals(AudioContextLevel.Level.QUIET, context.level());
        assertEquals(0.70f, context.decision().gain, 1e-6f);
    }

    @Test public void everyLevelProducesABoundedGainAndAChannel() {
        AudioContextLevel context = quietContext();
        for (AudioContextLevel.Level level : AudioContextLevel.Level.values()) {
            context.reset();
            if (level == AudioContextLevel.Level.MODERATE) {
                for (int i = 0; i < 3; i++) context.accept(NOISY);
                for (int i = 0; i < 5; i++) context.accept(CALM);
            } else if (level == AudioContextLevel.Level.NOISY) {
                for (int i = 0; i < 3; i++) context.accept(NOISY);
            }
            assertEquals(level, context.level());

            AudioContextLevel.Decision decision = context.decision();
            assertTrue("gain must stay inside 0..1", decision.gain > 0f && decision.gain <= 1f);
            assertNotNull(decision.channel);
        }
    }
}
