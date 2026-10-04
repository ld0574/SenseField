package com.openkhub.sensefield;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ordinary-app input: never requests privacy-sensitive priority over the game. */
final class AssistantVoiceInput implements AutoCloseable {
    interface Listener extends VoiceActivityGate.Output {
        void inputStateChanged(VoiceInputSafetyPolicy.InputState state);
        default void safetyMetadata(int flags, int routedDeviceType) { }
        void unavailable(String status);
    }
    private final Context context;
    private final Listener listener;
    private final VoiceActivityGate gate;
    private final VoiceEchoProcessor echo = new VoiceEchoProcessor();
    private final VoiceSpeechDetector speech = new VoiceSpeechDetector();
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor();
    private final AudioManager manager;
    private volatile boolean closed, paused, headset;
    private volatile boolean otherRecording = true;
    private volatile boolean silenced = true;
    private volatile int ownSessionId;
    private volatile AudioRecord record;
    private int lastSafetyFlags = Integer.MIN_VALUE;
    private int lastRoutedDeviceType = Integer.MIN_VALUE;
    private AssistantAudioRouteProbe routeProbe;
    private Thread thread;
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
        this.context = context; this.listener = listener; this.gate = new VoiceActivityGate(listener);
        manager = context.getSystemService(AudioManager.class);
    }
    void start() {
        if (closed || record != null) return;
        if (!speech.available()) { reportUnavailable("本地语音检测不可用，助手输入暂停；预警继续"); close(); return; }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reportUnavailable("未授权麦克风，预警继续运行"); close(); return;
        }
        try {
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) { reportUnavailable("麦克风格式不可用，预警继续运行"); close(); return; }
            AudioRecord.Builder builder = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(minimum, 6400));
            if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(false);
            final AudioRecord owned = builder.build(); record = owned;
            final int sessionId = owned.getAudioSessionId();
            ownSessionId = sessionId;
            owned.registerAudioRecordingCallback(callbacks, new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configurations) {
                    if (closed) return;
                    boolean currentSilenced = true;
                    for (AudioRecordingConfiguration configuration : configurations) {
                        if (configuration.getClientAudioSessionId() == sessionId) {
                            currentSilenced = configuration.isClientSilenced(); break;
                        }
                    }
                    silenced = currentSilenced;
                }
            });
            routeProbe = new AssistantAudioRouteProbe(value -> headset = value);
            headset = routeProbe.isHeadphoneRoute();
            if (manager != null) {
                Handler callbackHandler = new Handler(Looper.getMainLooper());
                manager.registerAudioRecordingCallback(recordings, callbackHandler);
                recordings.onRecordingConfigChanged(manager.getActiveRecordingConfigurations());
            }
            owned.startRecording();
            thread = new Thread(() -> read(owned), "SenseFieldVoice"); thread.start();
        } catch (RuntimeException error) { reportUnavailable("麦克风暂不可用，预警继续运行"); close(); }
    }
    private void read(AudioRecord owned) {
        short[] frame = new short[160]; int filled = 0;
        try {
            while (!closed) {
                int count = owned.read(frame, filled, frame.length - filled, AudioRecord.READ_BLOCKING);
                if (count <= 0) { if (!closed) reportUnavailable("语音输入中断，预警继续运行"); break; }
                filled += count; if (filled < frame.length) continue; filled = 0;
                VoiceInputSafetyPolicy.InputState state = VoiceInputSafetyPolicy.inputState(
                        paused, silenced, otherRecording, headset);
                int safetyFlags = VoiceInputSafetyPolicy.safetyFlags(
                        paused, silenced, otherRecording, headset);
                AssistantAudioRouteProbe probe = routeProbe;
                int routedDeviceType = probe == null ? -1 : probe.routedDeviceType();
                if (safetyFlags != lastSafetyFlags || routedDeviceType != lastRoutedDeviceType) {
                    lastRoutedDeviceType = routedDeviceType;
                    listener.safetyMetadata(safetyFlags, routedDeviceType);
                }
                if (VoiceInputSafetyPolicy.requiresSessionReset(lastSafetyFlags, safetyFlags)) {
                    gate.reset(); echo.reset(); lastSafetyFlags = safetyFlags;
                    listener.inputStateChanged(state);
                    if (!speech.reset()) {
                        reportUnavailable("本地语音检测不可用，助手输入暂停；预警继续");
                        break;
                    }
                }
                if (state != VoiceInputSafetyPolicy.InputState.READY) continue;
                if (!speech.available()) { reportUnavailable("本地语音检测不可用，助手输入暂停；预警继续"); break; }
                short[] processed = echo.process(frame);
                gate.accept(processed, speech.isSpeech(processed));
            }
        } catch (RuntimeException error) { if (!closed) reportUnavailable("语音输入中断，预警继续运行"); }
        finally {
            closed = true;
            try { owned.stop(); } catch (RuntimeException ignored) { }
            try { owned.release(); } catch (RuntimeException ignored) { }
            record = null; cleanup();
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
    void feedRender(short[] pcm) { if (!closed) echo.feedRender(pcm); }
    void pause(boolean value) { paused = value; }
    private void cleanup() {
        if (manager != null) try { manager.unregisterAudioRecordingCallback(recordings); } catch (RuntimeException ignored) { }
        AssistantAudioRouteProbe probe = routeProbe;
        routeProbe = null;
        if (probe != null) probe.close();
        callbacks.shutdownNow(); echo.close(); speech.close();
    }
    @Override public void close() {
        closed = true;
        AudioRecord owned = record;
        if (owned != null) {
            try { owned.stop(); } catch (RuntimeException ignored) { }
            if (thread == null) { try { owned.release(); } catch (RuntimeException ignored) { } record = null; }
        }
        cleanup();
    }
}
