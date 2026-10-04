package com.openkhub.sensefield;

import java.util.Arrays;

/** One temporary PCM utterance, never a queue or an audio file. */
final class AsrUtteranceBuffer {
    static final int MAX_SAMPLES = 16 * 16000;
    private final short[] samples = new short[MAX_SAMPLES];
    private int size;
    private boolean active, overflow;
    void begin() { clear(); active = true; }
    void accept(short[] pcm) {
        if (!active || overflow || pcm == null) return;
        if (pcm.length > MAX_SAMPLES - size) {
            clear(); overflow = true; return;
        }
        System.arraycopy(pcm, 0, samples, size, pcm.length); size += pcm.length;
    }
    short[] finish() {
        short[] result = active && !overflow && size > 0 ? Arrays.copyOf(samples, size) : null;
        clear(); return result;
    }
    void clear() {
        Arrays.fill(samples, 0, size, (short) 0);
        size = 0; active = false; overflow = false;
    }
}
