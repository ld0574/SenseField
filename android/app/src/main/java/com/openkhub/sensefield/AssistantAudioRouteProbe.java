package com.openkhub.sensefield;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * Holds a silent, app-owned USAGE_GAME track so Android can expose the route
 * selected for this app's default game-usage playback.
 */
final class AssistantAudioRouteProbe implements AutoCloseable {
    interface Listener { void onRouteChanged(boolean headphone); }

    private static final int SAMPLE_RATE = 16_000;
    private static final int SILENCE_FRAMES = SAMPLE_RATE / 5;

    private final Listener listener;
    private final AudioRouting.OnRoutingChangedListener routingChanged = routing -> refreshRoute();
    private volatile AudioTrack track;
    private volatile boolean closed;
    private volatile boolean headphoneRoute;
    private volatile int routedDeviceType = -1;
    private boolean listenerRegistered;

    AssistantAudioRouteProbe(Listener listener) {
        this.listener = listener;
        AudioTrack candidate = null;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
            short[] silence = new short[SILENCE_FRAMES];
            candidate = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(silence.length * Short.BYTES)
                    .build();
            int written = candidate.write(silence, 0, silence.length, AudioTrack.WRITE_BLOCKING);
            if (written != silence.length || candidate.setLoopPoints(0, silence.length, -1) != AudioTrack.SUCCESS) {
                throw new IllegalStateException("Could not initialize silent static game track");
            }
            track = candidate;
            candidate.addOnRoutingChangedListener(routingChanged, new Handler(Looper.getMainLooper()));
            listenerRegistered = true;
            candidate.play();
            refreshRoute();
        } catch (RuntimeException error) {
            failClosed(candidate);
        }
    }

    boolean isHeadphoneRoute() { return headphoneRoute; }

    int routedDeviceType() { return routedDeviceType; }

    private synchronized void refreshRoute() {
        if (closed) return;
        boolean next = false;
        int nextType = -1;
        try {
            AudioTrack current = track;
            if (current != null && current.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                AudioDeviceInfo routed = current.getRoutedDevice();
                if (routed != null) {
                    nextType = routed.getType();
                    next = VoiceInputSafetyPolicy.verifiedHeadphoneRoute(
                            android.os.Build.VERSION.SDK_INT, true, nextType);
                }
            }
        } catch (RuntimeException ignored) {
            next = false;
            nextType = -1;
        }
        routedDeviceType = nextType;
        headphoneRoute = next;
        if (!closed && listener != null) {
            try { listener.onRouteChanged(next); }
            catch (RuntimeException ignored) {
                headphoneRoute = false;
                routedDeviceType = -1;
            }
        }
    }

    private synchronized void failClosed(AudioTrack candidate) {
        closed = true;
        headphoneRoute = false;
        routedDeviceType = -1;
        if (candidate == null) return;
        if (listenerRegistered) {
            try { candidate.removeOnRoutingChangedListener(routingChanged); }
            catch (RuntimeException ignored) { }
            listenerRegistered = false;
        }
        track = null;
        try { candidate.release(); }
        catch (RuntimeException ignored) { }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        headphoneRoute = false;
        routedDeviceType = -1;
        AudioTrack current = track;
        track = null;
        if (current == null) return;
        if (listenerRegistered) {
            try { current.removeOnRoutingChangedListener(routingChanged); }
            catch (RuntimeException ignored) { }
            listenerRegistered = false;
        }
        try { current.stop(); }
        catch (RuntimeException ignored) { }
        try { current.release(); }
        catch (RuntimeException ignored) { }
    }
}
