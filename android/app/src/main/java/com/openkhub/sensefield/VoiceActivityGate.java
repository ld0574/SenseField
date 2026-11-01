package com.openkhub.sensefield;

import java.util.ArrayDeque;

/** Small local VAD gate with bounded pre-roll; neither silence nor playback is uploaded continuously. */
final class VoiceActivityGate {
    interface Output { void started(); void pcm(short[] samples); void ended(); }
    /** Transport hook for the adaptive floor: one callback per recomputed reading. */
    interface NoiseFloorObserver { void onFloorUpdated(); }
    private final Output output;
    private NoiseFloorObserver noiseFloorObserver;
    private final ArrayDeque<short[]> preRoll = new ArrayDeque<>();
    private int voicedFrames, quietFrames, activeFrames;
    private double noise = 80;
    private boolean active;
    VoiceActivityGate(Output output) { this.output = output; }
    /**
     * Registers the consumer that carries this gate's adaptive floor to the cue side.
     * Transport only: the observer fires at the same place the single EMA above is
     * recomputed, so it sees the very reading the speech threshold gates on. No second
     * smoothing, no second sampling, no second timer.
     */
    void observeNoiseFloor(NoiseFloorObserver observer) { this.noiseFloorObserver = observer; }
    void accept(short[] samples) {
        accept(samples, true);
    }
    /** Production input supplies the local WebRTC speech classifier result. */
    void accept(short[] samples, boolean speechDetected) {
        if (samples.length != 160) throw new IllegalArgumentException("VAD requires 10 ms PCM");
        double energy = 0; for (short sample : samples) energy += (double) sample * sample;
        double rms = Math.sqrt(energy / samples.length);
        boolean aboveFloor = rms > Math.max(350, noise * 3.2);
        boolean voice = speechDetected && aboveFloor;
        if (!active && !aboveFloor) {
            noise = noise * .98 + rms * .02;
            NoiseFloorObserver observer = noiseFloorObserver;
            if (observer != null) observer.onFloorUpdated();
        }
        if (!active) {
            preRoll.addLast(samples.clone()); if (preRoll.size() > 30) preRoll.removeFirst();
            voicedFrames = voice ? voicedFrames + 1 : 0;
            if (voicedFrames < 15) return;
            active = true; quietFrames = 0; activeFrames = 0; output.started();
            for (short[] previous : preRoll) output.pcm(previous); preRoll.clear();
        } else {
            output.pcm(samples); activeFrames++;
            quietFrames = voice ? 0 : quietFrames + 1;
            if (quietFrames >= 45 || activeFrames >= 1500) {
                active = false; voicedFrames = 0; quietFrames = 0; output.ended();
            }
        }
    }
    boolean active() { return active; }
    /**
     * Read-only view of the adaptive noise floor. This is the very same EMA that the speech
     * gate above compares against ({@code max(350, noise * 3.2)}); no second smoothing is
     * introduced here. The output side ({@link AudioContextLevel}) consumes this reading.
     */
    double noiseFloor() { return noise; }
    void reset() { active = false; voicedFrames = 0; quietFrames = 0; activeFrames = 0; preRoll.clear(); }
}
