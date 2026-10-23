package com.openkhub.sensefield;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline Chinese ASR. A cancelled native call retains its sole slot until it returns. */
final class OnDeviceAsr implements AutoCloseable {
    interface Listener {
        void ready();
        void result(long generation, String turn, String text, long inferenceMs);
        void unavailable(String reason);
        void dropped(String reason);
        default void resourceProgress(int percent) { }
    }
    private final Context context;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AsrUtteranceBuffer buffer = new AsrUtteranceBuffer();
    private volatile boolean ready, closed;
    private boolean started;
    private long handle, epoch, generation;
    private String turn = "";

    OnDeviceAsr(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
    }
    synchronized void start() {
        if (closed || started) return;
        started = true;
        worker.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                System.loadLibrary("ondevice_asr_jni");
                File[] files = AsrModelStore.get(context).ensure(percent -> {
                    if (!closed) listener.resourceProgress(percent);
                }, () -> closed);
                File model = files[0], tokens = files[1];
                if (closed) return;
                handle = nativeCreate(model.getAbsolutePath(), tokens.getAbsolutePath(), 1);
                if (handle == 0) throw new IllegalStateException("model_load");
                if (closed) { release(); return; }
                ready = true; listener.ready();
            } catch (Exception | LinkageError | OutOfMemoryError error) {
                ready = false; release();
                if (!closed) listener.unavailable("语音资源未准备好，请联网在助手设置中重试；本地预警正常");
            }
        });
    }
    boolean ready() { return ready && !closed; }
    synchronized boolean begin(long value, String id) {
        invalidate();
        if (!ready() || id == null || id.isEmpty()) return false;
        generation = value; turn = id; buffer.begin(); return true;
    }
    synchronized void accept(short[] pcm) { if (!closed) buffer.accept(pcm); }
    synchronized boolean finish() {
        short[] pcm = buffer.finish();
        if (!ready() || pcm == null) { wipe(pcm); listener.dropped("empty_or_unavailable"); return false; }
        if (!busy.compareAndSet(false, true)) { wipe(pcm); listener.dropped("local_asr_busy"); return false; }
        long ownedEpoch = epoch, ownedGeneration = generation;
        String ownedTurn = turn;
        try {
            worker.execute(() -> {
                String text = null;
                long elapsed = 0;
                boolean failed = false;
                try {
                    if (!owns(ownedEpoch)) return;
                    long beganAt = SystemClock.elapsedRealtime();
                    text = nativeDecode(handle, pcm);
                    elapsed = SystemClock.elapsedRealtime() - beganAt;
                } catch (RuntimeException | LinkageError | OutOfMemoryError error) {
                    ready = false; release(); failed = true;
                } finally { wipe(pcm); busy.set(false); }
                // Engine failure is a session state, not an utterance result.
                // Even a cancelled turn must not leave a destroyed engine showing "loading".
                if (failed) {
                    if (!closed) listener.unavailable("本机语音识别失败，预警继续；可点击读取画面");
                } else if (owns(ownedEpoch)) {
                    listener.result(ownedGeneration, ownedTurn, text == null ? "" : text.trim(), elapsed);
                }
            });
            return true;
        } catch (RejectedExecutionException error) {
            wipe(pcm); busy.set(false); listener.dropped("closed"); return false;
        }
    }
    private synchronized boolean owns(long value) { return !closed && epoch == value; }
    synchronized void invalidate() { epoch++; turn = ""; buffer.clear(); }
    private void release() {
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; ready = false; invalidate();
        // No interruption/free while native inference owns the recognizer.
        worker.execute(this::release); worker.shutdown();
    }
    private static void wipe(short[] samples) { if (samples != null) Arrays.fill(samples, (short) 0); }
    private static native long nativeCreate(String modelPath, String tokensPath, int threads);
    private static native String nativeDecode(long handle, short[] pcm);
    private static native void nativeDestroy(long handle);
}
