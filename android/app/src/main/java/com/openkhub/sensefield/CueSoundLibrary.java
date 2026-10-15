package com.openkhub.sensefield;

/** Small, deterministic built-in tones; no files supplied by users or network requests. */
final class CueSoundLibrary {
    static final String[] IDS = {"classic", "soft", "bell", "tap"};
    static final String[] LABELS = {"原版电子音", "柔和轻音", "清亮铃音", "短促脉冲"};
    static final int[] KINDS = {7, 2, 1, 3};
    static final String[] EVENTS = {"附近敌人", "新出现的敌方头像", "主画面边缘敌人",
            "危险信号"};

    private CueSoundLibrary() {}

    static String preferenceKey(int kind) { return "cue_sound_" + kind; }

    static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }

    static String valid(String id) { return IDS[index(id)]; }
    static String label(String id) { return LABELS[index(id)]; }

    static int frequency(int kind) {
        switch (kind) {
            case 1: return 840;
            case 2: return 600;
            case 3: return 1100;
            case 4: return 360;
            case 5: return 920;
            default: return 900;
        }
    }

    static int durationMs(int kind, String style) {
        if ("tap".equals(valid(style))) return kind == 7 ? 160 : 75;
        return kind == 7 ? 240 : 90;
    }

    static short[] render(int kind, String style, int sampleRate) {
        if (sampleRate < 8000 || sampleRate > 96000) throw new IllegalArgumentException("sample rate");
        String selected = valid(style);
        int samples = sampleRate * durationMs(kind, selected) / 1000;
        int pulses = kind == 7 ? 2 : 1;
        int gap = pulses > 1 ? sampleRate * ("tap".equals(selected) ? 45 : 65) / 1000 : 0;
        int pulseSamples = (samples - gap * (pulses - 1)) / pulses;
        short[] pcm = new short[samples];
        double frequency = frequency(kind) * ("soft".equals(selected) ? 0.72 : 1.0);
        double phase = 0.0;
        for (int i = 0; i < samples; i++) {
            int inPulse = i % (pulseSamples + gap);
            double envelope = inPulse >= pulseSamples ? 0.0
                    : Math.min(1.0, inPulse / (sampleRate / 120.0))
                    * Math.min(1.0, (pulseSamples - inPulse) / (sampleRate / 60.0));
            double value = Math.sin(phase);
            if ("bell".equals(selected)) {
                value = (value + 0.28 * Math.sin(phase * 2.0)) / 1.28;
                envelope *= Math.exp(-3.0 * inPulse / pulseSamples);
            } else if ("tap".equals(selected)) {
                value = (value + 0.20 * Math.sin(phase * 3.0)) / 1.20;
            }
            double gain = "soft".equals(selected) ? 9000.0 : 13000.0;
            pcm[i] = (short) Math.round(value * gain * envelope);
            phase += 2.0 * Math.PI * frequency / sampleRate;
        }
        return pcm;
    }
}
