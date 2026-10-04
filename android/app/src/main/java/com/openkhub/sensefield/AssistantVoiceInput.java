package com.openkhub.sensefield;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioRouting;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ordinary-app input: never requests privacy-sensitive priority over the game. */
final class AssistantVoiceInput implements AutoCloseable {
    interface Listener extends VoiceActivityGate.Output {
        void inputStateChanged(VoiceInputSafetyPolicy.InputState state);
        default void safetyMetadata(int flags, int routedDeviceType) { }
        default void acousticSummary(int frames, int speechFrames, int rms, int peak,
                int clippedSamples, int inputDeviceType) { }
        void unavailable(String status);
    }

    private final Context context;
    private final Listener listener;
    private final VoiceActivityGate gate;
    private final VoiceEchoProcessor echo = new VoiceEchoProcessor();
    private final VoiceSpeechDetector speech = new VoiceSpeechDetector();
    private final VoiceInputMetrics metrics = new VoiceInputMetrics();
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor();
    private final AudioManager manager;
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private volatile boolean closed, paused, headset;
    private volatile boolean otherRecording = true;
    private volatile boolean silenced = true;
    private volatile int ownSessionId;
    private volatile int routedInputDeviceType = -1;
    private volatile AudioRecord record;
    private volatile PauseableRecorderLoop recorderLoop;
    private int lastSafetyFlags = Integer.MIN_VALUE;
    private int lastRoutedDeviceType = Integer.MIN_VALUE;
    private AssistantAudioRouteProbe routeProbe;
    private AudioManager.AudioRecordingCallback inputRecordings;
    private AudioRouting.OnRoutingChangedListener inputRouting;
    private Handler callbackHandler;
    private final AudioManager.AudioRecordingCallback recordings = new AudioManager.AudioRecordingCallback() {
        @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configurations) {
            if (closed) return;
            try {
                int[] ids = new int[configurations.size()];
                for (int i = 0; i < ids.length; i++) ids[i] = configurations.get(i).getClientAudioSessionId();
                otherRecording = VoiceInputSafetyPolicy.otherRecorder(ownSessionId, ids);
            } catch (RuntimeException ignored) { otherRecording = true; }
        }
    };

    AssistantVoiceInput(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.gate = new VoiceActivityGate(listener);
        manager = context.getSystemService(AudioManager.class);
    }

    void start() {
        if (closed || record != null) return;
        if (!speech.available()) {
            reportUnavailable("本地语音检测不可用，助手输入暂停；预警继续");
            close();
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reportUnavailable("未授权麦克风，预警继续运行");
            close();
            return;
        }
        try {
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                reportUnavailable("麦克风格式不可用，预警继续运行");
                close();
                return;
            }
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum, 6400));
            if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(false);
            final AudioRecord owned = builder.build();
            record = owned;
            final int sessionId = owned.getAudioSessionId();
            ownSessionId = sessionId;
            inputRecordings = new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configurations) {
                    if (closed) return;
                    boolean currentSilenced = true;
                    for (AudioRecordingConfiguration configuration : configurations) {
                        if (configuration.getClientAudioSessionId() == sessionId) {
                            currentSilenced = configuration.isClientSilenced();
                            break;
                        }
                    }
                    silenced = currentSilenced;
                }
            };
            owned.registerAudioRecordingCallback(callbacks, inputRecordings);

            routeProbe = new AssistantAudioRouteProbe(value -> headset = value);
            headset = routeProbe.isHeadphoneRoute();
            callbackHandler = new Handler(Looper.getMainLooper());
            if (manager != null) {
                manager.registerAudioRecordingCallback(recordings, callbackHandler);
                recordings.onRecordingConfigChanged(manager.getActiveRecordingConfigurations());
            }

            routedInputDeviceType = routedInputType(owned);
            inputRouting = routing -> {
                if (closed) return;
                int nextInputType = routedInputType(routing);
                PauseableRecorderLoop current = recorderLoop;
                if (current != null) current.invalidate(() -> routedInputDeviceType = nextInputType);
                else routedInputDeviceType = nextInputType;
            };
            owned.addOnRoutingChangedListener(inputRouting, callbackHandler);

            PauseableRecorderLoop current = new PauseableRecorderLoop(new PauseableRecorderLoop.Recorder() {
                @Override public void start() {
                    // AudioRecord.startRecording() flushes capture samples buffered before restart.
                    owned.startRecording();
                }
                @Override public int read(short[] samples, int offset, int length) {
                    return owned.read(samples, offset, length, AudioRecord.READ_BLOCKING);
                }
                @Override public void stop() { owned.stop(); }
                @Override public void release() { owned.release(); }
            }, 160, new PauseableRecorderLoop.Listener() {
                @Override public void reset() { resetCaptureState(); }
                @Override public void frame(long epoch, short[] samples) { consumeFrame(owned, epoch, samples); }
                @Override public void failed() { onReadFailure(); }
                @Override public void beforeRelease() { detachInputCallbacks(owned); }
                @Override public void stopped() { onReadLoopStopped(owned); }
            });
            recorderLoop = current;
            current.pause(paused);
            current.start();
        } catch (RuntimeException error) {
            reportUnavailable("麦克风暂不可用，预警继续运行");
            close();
        }
    }

    private void consumeFrame(AudioRecord owned, long epoch, short[] frame) {
        if (!isCurrentFrame(epoch)) return;
        VoiceInputSafetyPolicy.InputState state = VoiceInputSafetyPolicy.inputState(
                paused, silenced, otherRecording, headset);
        int safetyFlags = VoiceInputSafetyPolicy.safetyFlags(
                paused, silenced, otherRecording, headset);
        AssistantAudioRouteProbe probe = routeProbe;
        int safetyRoutedDeviceType = probe == null ? -1 : probe.routedDeviceType();
        if (safetyFlags != lastSafetyFlags || safetyRoutedDeviceType != lastRoutedDeviceType) {
            lastRoutedDeviceType = safetyRoutedDeviceType;
            listener.safetyMetadata(safetyFlags, safetyRoutedDeviceType);
        }
        if (VoiceInputSafetyPolicy.requiresSessionReset(lastSafetyFlags, safetyFlags)) {
            gate.reset();
            echo.reset();
            metrics.reset();
            lastSafetyFlags = safetyFlags;
            listener.inputStateChanged(state);
            if (!speech.reset()) {
                reportUnavailable("本地语音检测不可用，助手输入暂停；预警继续");
                close();
                return;
            }
        }
        if (state != VoiceInputSafetyPolicy.InputState.READY || !isCurrentFrame(epoch)) return;
        if (!speech.available()) {
            reportUnavailable("本地语音检测不可用，助手输入暂停；预警继续");
            close();
            return;
        }
        short[] processed = echo.process(frame);
        if (!isCurrentFrame(epoch)) return;
        boolean speechDetected = speech.isSpeech(processed);
        if (!isCurrentFrame(epoch)) return;
        boolean wasActive = gate.active();
        gate.accept(processed, speechDetected);
        if (wasActive || gate.active()) metrics.accept(processed, speechDetected);
        if (wasActive && !gate.active()) {
            listener.acousticSummary(metrics.frames, metrics.speechFrames, metrics.rms(),
                    metrics.peak, metrics.clippedSamples, routedInputDeviceType);
            metrics.reset();
        }
    }

    private boolean isCurrentFrame(long epoch) {
        PauseableRecorderLoop current = recorderLoop;
        return !closed && !paused && current != null && current.isCurrentEpoch(epoch);
    }

    private void resetCaptureState() {
        if (closed) return;
        gate.reset();
        echo.reset();
        metrics.reset();
        if (!speech.reset()) {
            reportUnavailable("本地语音检测不可用，助手输入暂停；预警继续");
            close();
        }
    }

    private void onReadFailure() {
        if (closed) return;
        reportUnavailable("语音输入中断，预警继续运行");
        close();
    }

    private void onReadLoopStopped(AudioRecord owned) {
        recorderLoop = null;
        cleanup();
        if (record == owned) record = null;
    }

    private static int routedInputType(AudioRouting routing) {
        try {
            AudioDeviceInfo device = routing.getRoutedDevice();
            return device == null ? -1 : device.getType();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private void reportUnavailable(String status) {
        int flags = lastSafetyFlags == Integer.MIN_VALUE
                ? VoiceInputSafetyPolicy.FLAG_UNAVAILABLE
                : lastSafetyFlags | VoiceInputSafetyPolicy.FLAG_UNAVAILABLE;
        if (VoiceInputSafetyPolicy.requiresSessionReset(lastSafetyFlags, flags)) {
            lastSafetyFlags = flags;
            listener.inputStateChanged(VoiceInputSafetyPolicy.InputState.UNAVAILABLE);
        }
        listener.unavailable(status);
    }

    void feedRender(short[] pcm) {
        if (!closed && !paused) echo.feedRender(pcm);
    }

    void pause(boolean value) {
        if (closed) return;
        PauseableRecorderLoop current = recorderLoop;
        if (current != null) current.pause(value, () -> paused = value);
        else paused = value;
    }

    private void detachInputCallbacks(AudioRecord owned) {
        AudioManager.AudioRecordingCallback ownCallback = inputRecordings;
        inputRecordings = null;
        if (ownCallback != null) {
            try { owned.unregisterAudioRecordingCallback(ownCallback); }
            catch (RuntimeException ignored) { }
        }
        AudioRouting.OnRoutingChangedListener routing = inputRouting;
        inputRouting = null;
        if (routing != null) {
            try { owned.removeOnRoutingChangedListener(routing); }
            catch (RuntimeException ignored) { }
        }
    }

    private void cleanup() {
        if (!cleanupStarted.compareAndSet(false, true)) return;
        if (manager != null) {
            try { manager.unregisterAudioRecordingCallback(recordings); }
            catch (RuntimeException ignored) { }
        }
        AudioRecord owned = record;
        if (owned != null) detachInputCallbacks(owned);
        AssistantAudioRouteProbe probe = routeProbe;
        routeProbe = null;
        if (probe != null) probe.close();
        callbacks.shutdownNow();
        echo.close();
        speech.close();
    }

    @Override public void close() {
        closed = true;
        paused = true;
        PauseableRecorderLoop current = recorderLoop;
        if (current != null) {
            current.close();
            return;
        }
        AudioRecord owned = record;
        if (owned != null) {
            detachInputCallbacks(owned);
            try { owned.stop(); }
            catch (RuntimeException ignored) { }
            try { owned.release(); }
            catch (RuntimeException ignored) { }
            record = null;
        }
        cleanup();
    }
}
