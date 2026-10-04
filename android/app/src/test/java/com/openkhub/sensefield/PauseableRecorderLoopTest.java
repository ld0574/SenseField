package com.openkhub.sensefield;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class PauseableRecorderLoopTest {
    private static final long WAIT_SECONDS = 3;

    @Test public void pauseStopsReadsAndResumeStartsWithFreshFrame() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RecordingListener listener = new RecordingListener();
        PauseableRecorderLoop loop = new PauseableRecorderLoop(recorder, 160, listener);
        loop.start();

        assertTrue(recorder.firstRead.await(WAIT_SECONDS, TimeUnit.SECONDS));
        recorder.offer(filled(80, (short) 3));
        assertTrue(recorder.reads.await(2, TimeUnit.SECONDS));

        CountDownLatch waiting = listener.nextPause();
        loop.pause(true);
        assertTrue(waiting.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, listener.failed);
        int readsWhenPaused = recorder.readCalls;
        recorder.offer(filled(160, (short) 9));
        assertEquals(readsWhenPaused, recorder.readCalls);
        assertTrue(listener.frames.isEmpty());

        CountDownLatch resumedFrame = listener.nextFrame();
        loop.pause(false);
        assertTrue(recorder.secondStart.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(recorder.readAfterResume.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue("AudioRecord.startRecording clears samples queued before resume", listener.frames.isEmpty());
        recorder.offer(filled(160, (short) 11));
        assertTrue(resumedFrame.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, listener.frames.size());
        assertAll(listener.frames.get(0), (short) 11);
        assertTrue("initial and resumed epochs must each reset state", listener.resets >= 2);

        loop.close();
        assertTrue(listener.stopped.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, listener.failed);
    }

    @Test public void invalidationDropsPartialFrameInsteadOfJoiningAcrossRoutes() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RecordingListener listener = new RecordingListener();
        PauseableRecorderLoop loop = new PauseableRecorderLoop(recorder, 160, listener);
        loop.start();
        assertTrue(recorder.firstRead.await(WAIT_SECONDS, TimeUnit.SECONDS));
        recorder.offer(filled(80, (short) 4));
        assertTrue(recorder.reads.await(2, TimeUnit.SECONDS));

        loop.invalidate();
        recorder.offer(filled(80, (short) 4));
        recorder.offer(filled(160, (short) 7));
        CountDownLatch frame = listener.nextFrame();
        assertTrue(frame.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, listener.frames.size());
        assertAll(listener.frames.get(0), (short) 7);
        assertTrue(listener.resets >= 2);

        loop.close();
        assertTrue(listener.stopped.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, listener.failed);
    }

    @Test public void closeWhilePausedCannotRestartSameRecorder() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RecordingListener listener = new RecordingListener();
        PauseableRecorderLoop loop = new PauseableRecorderLoop(recorder, 160, listener);
        loop.start();
        assertTrue(recorder.firstRead.await(WAIT_SECONDS, TimeUnit.SECONDS));

        CountDownLatch waiting = listener.nextPause();
        loop.pause(true);
        assertTrue(waiting.await(WAIT_SECONDS, TimeUnit.SECONDS));
        int startsBeforeClose = recorder.starts;
        loop.close();
        assertTrue(listener.stopped.await(WAIT_SECONDS, TimeUnit.SECONDS));
        loop.pause(false);
        loop.start();
        assertEquals(startsBeforeClose, recorder.starts);
        assertTrue(recorder.released);
        assertTrue(listener.frames.isEmpty());
        assertEquals(0, listener.failed);
    }

    @Test public void closeBeforeStartStillReleasesOnlyOnOwnerWorker() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RecordingListener listener = new RecordingListener();
        PauseableRecorderLoop loop = new PauseableRecorderLoop(recorder, 160, listener);
        loop.close();
        assertTrue(listener.stopped.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(recorder.released);
        assertNotEquals(Thread.currentThread(), recorder.releaseThread);
        loop.start();
        assertEquals(0, recorder.starts);
    }

    @Test public void staleResetErrorDuringPauseIsIgnoredAndResumeResetsAgain() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        ResetPauseListener listener = new ResetPauseListener();
        PauseableRecorderLoop loop = new PauseableRecorderLoop(recorder, 160, listener);
        loop.start();
        assertTrue(listener.firstReset.await(WAIT_SECONDS, TimeUnit.SECONDS));

        CountDownLatch waiting = listener.waiting;
        loop.pause(true);
        listener.finishFirstReset.countDown();
        assertTrue(waiting.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, listener.failed);
        loop.pause(false);
        assertTrue(recorder.firstRead.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(2, listener.resets);

        loop.close();
        assertTrue(listener.stopped.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, listener.failed);
    }

    @Test public void genuineReadErrorIsTerminal() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RecordingListener listener = new RecordingListener();
        PauseableRecorderLoop loop = new PauseableRecorderLoop(recorder, 160, listener);
        loop.start();
        assertTrue(recorder.firstRead.await(WAIT_SECONDS, TimeUnit.SECONDS));
        recorder.failNextRead();
        assertTrue(listener.failedLatch.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(listener.stopped.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, listener.failed);
        assertTrue(recorder.released);
        loop.pause(false);
        assertEquals(1, recorder.starts);
    }

    @Test public void pauseLinearizesAfterAnInFlightFrameBeforeResumeReset() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        BlockingFrameListener listener = new BlockingFrameListener();
        PauseableRecorderLoop loop = new PauseableRecorderLoop(recorder, 160, listener);
        loop.start();
        assertTrue(recorder.firstRead.await(WAIT_SECONDS, TimeUnit.SECONDS));
        recorder.offer(filled(160, (short) 5));
        assertTrue(listener.frameEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

        CountDownLatch pauseAttempted = new CountDownLatch(1);
        CountDownLatch pauseReturned = new CountDownLatch(1);
        Thread pauser = new Thread(() -> {
            pauseAttempted.countDown();
            loop.pause(true);
            pauseReturned.countDown();
        });
        pauser.start();
        assertTrue(pauseAttempted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse("pause must serialize after the current frame consumer",
                pauseReturned.await(100, TimeUnit.MILLISECONDS));

        listener.allowFrame.countDown();
        assertTrue(pauseReturned.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(listener.waiting.await(WAIT_SECONDS, TimeUnit.SECONDS));
        loop.pause(false);
        loop.close();
        assertTrue(listener.stopped.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, listener.frames);
        assertEquals(0, listener.failed);
    }

    private static short[] filled(int count, short value) {
        short[] result = new short[count];
        java.util.Arrays.fill(result, value);
        return result;
    }

    private static void assertAll(short[] samples, short expected) {
        for (short sample : samples) assertEquals(expected, sample);
    }

    private static final class RecordingListener implements PauseableRecorderLoop.Listener {
        final List<short[]> frames = new ArrayList<>();
        final CountDownLatch stopped = new CountDownLatch(1);
        final CountDownLatch failedLatch = new CountDownLatch(1);
        volatile int failed;
        volatile int resets;
        private volatile CountDownLatch nextFrame = new CountDownLatch(1);
        private volatile CountDownLatch nextPause = new CountDownLatch(1);

        @Override public void reset() { resets++; }
        @Override public void frame(long epoch, short[] samples) {
            synchronized (frames) { frames.add(samples); }
            nextFrame.countDown();
        }
        @Override public void failed() { failed++; failedLatch.countDown(); }
        @Override public void waiting() { nextPause.countDown(); }
        @Override public void stopped() { stopped.countDown(); }

        CountDownLatch nextFrame() {
            CountDownLatch result = new CountDownLatch(1);
            nextFrame = result;
            return result;
        }
        CountDownLatch nextPause() {
            CountDownLatch result = new CountDownLatch(1);
            nextPause = result;
            return result;
        }
    }

    private static final class BlockingFrameListener implements PauseableRecorderLoop.Listener {
        final CountDownLatch frameEntered = new CountDownLatch(1);
        final CountDownLatch allowFrame = new CountDownLatch(1);
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        volatile int frames;
        volatile int failed;

        @Override public void reset() { }
        @Override public void frame(long epoch, short[] samples) {
            frames++;
            frameEntered.countDown();
            try { allowFrame.await(WAIT_SECONDS, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        @Override public void failed() { failed++; }
        @Override public void waiting() { waiting.countDown(); }
        @Override public void stopped() { stopped.countDown(); }
    }

    private static final class FakeRecorder implements PauseableRecorderLoop.Recorder {
        private final Object lock = new Object();
        private final ArrayDeque<short[]> chunks = new ArrayDeque<>();
        private short[] current;
        private int currentOffset;
        private boolean recording;
        volatile boolean released;
        volatile int starts;
        volatile int readCalls;
        volatile Thread releaseThread;
        final CountDownLatch firstRead = new CountDownLatch(1);
        final CountDownLatch secondStart = new CountDownLatch(1);
        final CountDownLatch readAfterResume = new CountDownLatch(1);
        final CountDownLatch reads = new CountDownLatch(2);
        private volatile boolean failNext;

        @Override public void start() {
            synchronized (lock) {
                if (released) throw new IllegalStateException("released");
                recording = true;
                starts++;
                if (starts > 1) {
                    // AudioRecord.startRecording() flushes capture samples buffered before restart.
                    chunks.clear();
                    current = null;
                    currentOffset = 0;
                    secondStart.countDown();
                }
                lock.notifyAll();
            }
        }

        @Override public int read(short[] samples, int offset, int length) {
            synchronized (lock) {
                readCalls++;
                firstRead.countDown();
                if (starts > 1) readAfterResume.countDown();
                reads.countDown();
                while (recording && chunks.isEmpty() && !failNext) {
                    try { lock.wait(); }
                    catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                if (!recording) return -3;
                if (failNext) {
                    failNext = false;
                    return -6;
                }
                if (current == null) {
                    current = chunks.removeFirst();
                    currentOffset = 0;
                }
                int count = Math.min(length, current.length - currentOffset);
                System.arraycopy(current, currentOffset, samples, offset, count);
                currentOffset += count;
                if (currentOffset == current.length) current = null;
                return count;
            }
        }

        @Override public void stop() {
            synchronized (lock) {
                recording = false;
                lock.notifyAll();
            }
        }

        @Override public void release() {
            synchronized (lock) {
                recording = false;
                released = true;
                releaseThread = Thread.currentThread();
                lock.notifyAll();
            }
        }

        void offer(short[] chunk) {
            synchronized (lock) {
                chunks.addLast(chunk);
                lock.notifyAll();
            }
        }

        void failNextRead() {
            synchronized (lock) {
                failNext = true;
                lock.notifyAll();
            }
        }
    }

    private static final class ResetPauseListener implements PauseableRecorderLoop.Listener {
        final CountDownLatch firstReset = new CountDownLatch(1);
        final CountDownLatch finishFirstReset = new CountDownLatch(1);
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        volatile int resets;
        volatile int failed;

        @Override public void reset() {
            if (++resets != 1) return;
            firstReset.countDown();
            try { finishFirstReset.await(WAIT_SECONDS, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("reset became stale while paused");
        }
        @Override public void frame(long epoch, short[] samples) { }
        @Override public void failed() { failed++; }
        @Override public void waiting() { waiting.countDown(); }
        @Override public void stopped() { stopped.countDown(); }
    }
}
