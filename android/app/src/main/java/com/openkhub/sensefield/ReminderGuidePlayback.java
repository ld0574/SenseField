package com.openkhub.sensefield;

import java.util.List;

/** One explanation/sample at a time; stopped runs cannot advance from late callbacks. */
final class ReminderGuidePlayback {
    interface Completion { void finish(boolean success); }
    interface Output {
        void play(ReminderGuide.Step step, Completion completion);
        void stop();
    }
    interface Listener {
        void onStep(ReminderGuide.Step step);
        void onEnded(boolean success);
    }

    private final Output output;
    private final Listener listener;
    private List<ReminderGuide.Step> steps;
    private int generation;
    private int index;
    private boolean running;
    private boolean paused;

    ReminderGuidePlayback(Output output, Listener listener) {
        this.output = output;
        this.listener = listener;
    }

    void start(List<ReminderGuide.Step> steps) {
        startAt(steps, 0);
    }

    /** Restores an unfinished sentence after recreation; old completions remain invalid. */
    void startAt(List<ReminderGuide.Step> steps, int unfinishedIndex) {
        if (steps == null || unfinishedIndex < 0 || unfinishedIndex > steps.size())
            throw new IllegalArgumentException("Invalid guide position");
        stop();
        this.steps = steps;
        index = unfinishedIndex;
        running = true;
        paused = false;
        next(generation);
    }

    /** Pauses the current item and keeps it as the next item to play on resume. */
    void pause() {
        if (!running || paused) return;
        paused = true;
        generation++;
        output.stop();
    }

    /** Repeats the unfinished current item, ignoring callbacks from before the pause. */
    void resume() {
        if (!running || !paused) return;
        paused = false;
        generation++;
        next(generation);
    }

    void stop() {
        running = false;
        paused = false;
        generation++;
        output.stop();
    }

    boolean isRunning() { return running; }
    boolean isPaused() { return paused; }

    /** The index of the current unfinished step, or the list size when playback ended. */
    int currentIndex() { return index; }

    int stepCount() { return steps == null ? 0 : steps.size(); }

    private void next(int run) {
        if (!running || paused || run != generation) return;
        if (index == steps.size()) {
            running = false;
            listener.onEnded(true);
            return;
        }
        int expected = index;
        ReminderGuide.Step step = steps.get(index);
        listener.onStep(step);
        // A listener can synchronously pause or stop playback while updating its UI.
        if (!running || paused || run != generation) return;
        output.play(step, success -> {
            if (!running || paused || run != generation || index != expected) return;
            if (!success) {
                running = false;
                output.stop();
                listener.onEnded(false);
                return;
            }
            index++;
            next(run);
        });
    }
}
