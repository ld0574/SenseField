package com.openkhub.sensefield;

/** Bounded JNI diagnostics, independent of the versioned cue/entity packet. */
final class DiagnosticSnapshot {
    static final int STRIDE = 8;
    final long engineAtMs;
    final long[][] observations;

    private DiagnosticSnapshot(long engineAtMs, long[][] observations) {
        this.engineAtMs = engineAtMs;
        this.observations = observations;
    }

    static DiagnosticSnapshot parse(long[] packed) {
        if (packed == null || packed.length < 2 || packed[1] < 0 || packed[1] > 64
                || packed.length != 2 + packed[1] * STRIDE)
            return new DiagnosticSnapshot(-1, new long[0][]);
        int count = (int) packed[1];
        long[][] rows = new long[count][STRIDE];
        for (int i = 0; i < count; i++)
            System.arraycopy(packed, 2 + i * STRIDE, rows[i], 0, STRIDE);
        return new DiagnosticSnapshot(packed[0], rows);
    }
}
