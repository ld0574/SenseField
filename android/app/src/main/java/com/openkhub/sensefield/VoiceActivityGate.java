package com.openkhub.sensefield;

import java.util.ArrayDeque;

/** Small local VAD gate with bounded pre-roll; neither silence nor playback is uploaded continuously. */
final class VoiceActivityGate {
    interface Output { void started(); void pcm(short[] samples); void ended(); }
    private final Output output;
    private final ArrayDeque<short[]> preRoll = new ArrayDeque<>();
    private int voicedFrames, quietFrames, activeFrames;
    private double noise = 80;
    private boolean active;
    VoiceActivityGate(Output output) { this.output = output; }
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
        if (!active && !aboveFloor) noise = noise * .98 + rms * .02;
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
    void reset() { active = false; voicedFrames = 0; quietFrames = 0; activeFrames = 0; preRoll.clear(); }
}
