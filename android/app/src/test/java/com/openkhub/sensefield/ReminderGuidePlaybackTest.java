package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class ReminderGuidePlaybackTest {
    private final List<ReminderGuide.Step> steps = ReminderGuide.build(
            new ReminderGuide.Outputs(7, 0, 0, 0, false, true));
    private final FakeOutput output = new FakeOutput();
    private final List<Boolean> endings = new ArrayList<>();
    private final ReminderGuidePlayback playback = new ReminderGuidePlayback(output,
            new ReminderGuidePlayback.Listener() {
                @Override public void onStep(ReminderGuide.Step step) {}
                @Override public void onEnded(boolean success) { endings.add(success); }
            });

    @Test public void explainsBeforePlayingTheSampleAndWaitsForCompletion() {
        playback.start(steps);
        assertTrue(playback.isRunning());
        assertEquals(1, output.played.size());
        output.completions.get(0).finish(true);
        assertFalse(output.played.get(1).sample);
        assertEquals(2, output.played.size());
        output.completions.get(1).finish(true);
        assertTrue(output.played.get(2).sample);
        assertTrue(endings.isEmpty());
    }

    @Test public void stopAndLateEngineCallbackCannotPlayAnotherExample() {
        playback.start(steps);
        playback.stop();
        output.completions.get(0).finish(true);
        assertEquals(1, output.played.size());
        assertFalse(playback.isRunning());
        assertTrue(endings.isEmpty());
    }

    @Test public void restartIgnoresThePreviousRunCompletion() {
        playback.start(steps);
        ReminderGuidePlayback.Completion stale = output.completions.get(0);
        playback.start(steps);
        stale.finish(true);
        assertEquals(2, output.played.size());
        output.completions.get(1).finish(true);
        assertEquals(3, output.played.size());
        assertTrue(endings.isEmpty());
    }

    @Test public void duplicateEngineCallbackCannotSkipAnExplanation() {
        playback.start(steps);
        output.completions.get(0).finish(true);
        output.completions.get(0).finish(true);
        assertEquals(2, output.played.size());
        assertFalse(output.played.get(1).sample);
    }

    @Test public void failedSpeechStopsBeforeTheUnexplainedSample() {
        playback.start(steps);
        output.completions.get(0).finish(true);
        output.completions.get(1).finish(false);
        assertEquals(2, output.played.size());
        assertEquals(Collections.singletonList(false), endings);
        assertFalse(playback.isRunning());
        output.completions.get(1).finish(true);
        assertEquals(2, output.played.size());
    }

    @Test public void successfulPlaybackEndsExactlyOnce() {
        playback.start(steps);
        for (int i = 0; i < steps.size(); i++) output.completions.get(i).finish(true);
        assertEquals(steps.size(), output.played.size());
        assertEquals(Collections.singletonList(true), endings);
        assertFalse(playback.isRunning());
        output.completions.get(steps.size() - 1).finish(true);
        assertEquals(1, endings.size());
    }

    @Test public void repeatingOneSampleNeverAdvancesIntoAnotherItem() {
        ReminderGuide.Step sample = steps.stream().filter(step -> step.sample).findFirst().get();
        playback.start(Collections.singletonList(sample));
        ReminderGuidePlayback.Completion old = output.completions.get(0);
        playback.start(Collections.singletonList(sample));
        old.finish(true);
        assertTrue(playback.isRunning());
        assertEquals(2, output.played.size());
        output.completions.get(1).finish(true);
        assertFalse(playback.isRunning());
        assertEquals(Collections.singletonList(true), endings);
        assertEquals(2, output.played.size());
    }

    private static final class FakeOutput implements ReminderGuidePlayback.Output {
        final List<ReminderGuide.Step> played = new ArrayList<>();
        final List<ReminderGuidePlayback.Completion> completions = new ArrayList<>();
        @Override public void play(ReminderGuide.Step step, ReminderGuidePlayback.Completion completion) {
            played.add(step);
            completions.add(completion);
        }
        @Override public void stop() {}
    }
}
