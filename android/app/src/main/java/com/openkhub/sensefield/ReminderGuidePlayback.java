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

    ReminderGuidePlayback(Output output, Listener listener) {
        this.output = output;
        this.listener = listener;
    }

    void start(List<ReminderGuide.Step> steps) {
        stop();
        this.steps = steps;
        index = 0;
        running = true;
        next(generation);
    }

    void stop() {
        running = false;
        generation++;
        output.stop();
    }

    boolean isRunning() { return running; }

    private void next(int run) {
        if (!running || run != generation) return;
        if (index == steps.size()) {
            running = false;
            listener.onEnded(true);
            return;
        }
        int expected = index;
        ReminderGuide.Step step = steps.get(index);
        listener.onStep(step);
        output.play(step, success -> {
            if (!running || run != generation || index != expected) return;
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
