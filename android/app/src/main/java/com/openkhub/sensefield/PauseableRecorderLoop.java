package com.openkhub.sensefield;

import java.util.Arrays;

/** One-owner read loop for a recorder that can be stopped and started in place. */
final class PauseableRecorderLoop implements AutoCloseable {
    interface Recorder {
        void start();
        int read(short[] samples, int offset, int length);
        void stop();
        void release();
    }

    interface Listener {
        /** Called on the read worker before frames from a new capture epoch are accepted. */
        void reset();
        /** The frame is owned by the listener and will not be reused by this loop. */
        void frame(long epoch, short[] samples);
        void failed();
        /** Testable notification that the worker has drained the stopped read and is idle. */
        default void waiting() { }
        /** Detach platform callbacks before the worker releases its recorder. */
        default void beforeRelease() { }
        /** Called after the recorder has been released on the read worker. */
        void stopped();
    }

    private final Object lock = new Object();
    private final Recorder recorder;
    private final Listener listener;
    private final int frameSamples;
    private Thread worker;
    private long epoch;
    private boolean opened;
    private boolean closed;
    private boolean paused;
    private boolean recorderStarted;

    PauseableRecorderLoop(Recorder recorder, int frameSamples, Listener listener) {
        if (recorder == null || listener == null) throw new NullPointerException();
        if (frameSamples <= 0) throw new IllegalArgumentException("frameSamples must be positive");
        this.recorder = recorder;
        this.listener = listener;
        this.frameSamples = frameSamples;
    }

    void start() {
        synchronized (lock) {
            if (opened || closed) return;
            launchLocked();
        }
    }

    /** Stops the active recorder immediately; the worker handles the expected read error. */
    void pause(boolean value) {
        pause(value, null);
    }

    /** Applies owner-visible state under the same lock as the recorder transition. */
    void pause(boolean value, Runnable stateUpdate) {
        synchronized (lock) {
            if (closed) return;
            if (paused != value) {
                epoch++;
                paused = value;
                if (value) stopLocked();
            }
            if (stateUpdate != null) stateUpdate.run();
            lock.notifyAll();
        }
    }

    /** Invalidates partial audio after an input route change without polling the route. */
    void invalidate() {
        invalidate(null);
    }

    /** Runs a small route-state update at the same point the old input epoch is invalidated. */
    void invalidate(Runnable stateUpdate) {
        synchronized (lock) {
            if (closed) return;
            epoch++;
            if (stateUpdate != null) stateUpdate.run();
            lock.notifyAll();
        }
    }

    boolean isCurrentEpoch(long expectedEpoch) {
        synchronized (lock) {
            return !closed && !paused && epoch == expectedEpoch;
        }
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            epoch++;
            stopLocked();
            if (!opened) launchLocked();
            lock.notifyAll();
        }
    }

    private void run() {
        short[] frame = new short[frameSamples];
        int filled = 0;
        long handledEpoch = Long.MIN_VALUE;
        long notifiedPauseEpoch = Long.MIN_VALUE;
        boolean failed = false;
        try {
            while (true) {
                long readEpoch;
                boolean reset;
                boolean reportWaiting;
                synchronized (lock) {
                    reportWaiting = false;
                    while (paused && !closed) {
                        if (notifiedPauseEpoch != epoch) {
                            notifiedPauseEpoch = epoch;
                            reportWaiting = true;
                            break;
                        }
                        lock.wait();
                    }
                    if (closed) break;
                    if (reportWaiting) {
                        readEpoch = epoch;
                        reset = false;
                    } else {
                        readEpoch = epoch;
                        reset = handledEpoch != readEpoch;
                        if (reset) {
                            handledEpoch = readEpoch;
                            filled = 0;
                        }
                    }
                }

                if (reportWaiting) {
                    listener.waiting();
                    continue;
                }

                if (reset) {
                    try { listener.reset(); }
                    catch (RuntimeException error) {
                        if (isStale(readEpoch)) {
                            filled = 0;
                            continue;
                        }
                        throw error;
                    }
                }

                synchronized (lock) {
                    if (closed) break;
                    if (paused || epoch != readEpoch) continue;
                    if (!recorderStarted) {
                        recorder.start();
                        recorderStarted = true;
                    }
                }

                int count;
                try {
                    count = recorder.read(frame, filled, frame.length - filled);
                } catch (RuntimeException error) {
                    if (isStale(readEpoch)) {
                        filled = 0;
                        continue;
                    }
                    failed = true;
                    break;
                }

                if (count <= 0) {
                    if (isStale(readEpoch)) {
                        filled = 0;
                        continue;
                    }
                    failed = true;
                    break;
                }
                if (count > frame.length - filled) {
                    failed = true;
                    break;
                }
                if (isStale(readEpoch)) {
                    filled = 0;
                    continue;
                }

                filled += count;
                if (filled < frame.length) continue;
                synchronized (lock) {
                    if (closed || paused || epoch != readEpoch) {
                        filled = 0;
                        continue;
                    }
                    // Frame delivery is the lifecycle linearization point. A pause or route
                    // invalidation waits only for the current frame consumer; it does not run
                    // ASR inference, image work, or network calls. Old audio cannot enter a
                    // resumed VAD/pre-roll state.
                    listener.frame(readEpoch, Arrays.copyOf(frame, frame.length));
                }
                filled = 0;
            }
        } catch (InterruptedException interrupted) {
            synchronized (lock) { failed = !closed; }
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            synchronized (lock) { failed = !closed; }
        } finally {
            synchronized (lock) {
                closed = true;
                stopLocked();
            }
            try { listener.beforeRelease(); }
            catch (RuntimeException ignored) { }
            try { recorder.release(); }
            catch (RuntimeException ignored) { }
            if (failed) {
                try { listener.failed(); }
                catch (RuntimeException ignored) { }
            }
            try { listener.stopped(); }
            catch (RuntimeException ignored) { }
        }
    }

    private boolean isStale(long readEpoch) {
        synchronized (lock) { return closed || paused || epoch != readEpoch; }
    }

    /** Caller holds lock. A close-before-start still releases on the sole owner thread. */
    private void launchLocked() {
        opened = true;
        worker = new Thread(this::run, "SenseFieldVoice");
        worker.start();
    }

    /** Caller holds lock. A stop error is harmless during pause and close. */
    private void stopLocked() {
        if (!recorderStarted) return;
        recorderStarted = false;
        try { recorder.stop(); }
        catch (RuntimeException ignored) { }
    }
}
