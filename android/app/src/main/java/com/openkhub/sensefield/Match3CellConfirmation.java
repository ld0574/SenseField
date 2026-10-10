package com.openkhub.sensefield;

/** Fresh, consecutive evidence per cell. Never fills a moving cell with a historical value. */
final class Match3CellConfirmation {
    static final int SAMPLES = 3;
    private Match3Position previous;
    private Match3Position.Cell[][] lastAnimal;
    private int[][] samples;
    private long lastAt = -1;
    private int quietSamples;
    private Snapshot latest;

    static final class Snapshot {
        final Match3Position position;
        final boolean quiet;
        final int unconfirmed;
        Snapshot(Match3Position position, boolean quiet, int unconfirmed) {
            this.position = position; this.quiet = quiet; this.unconfirmed = unconfirmed;
        }
    }

    Snapshot accept(Match3Position observed, long at) {
        if (observed == null || at < 0) { reset(); return null; }
        if (at == lastAt) {
            if(observed.sameCells(previous))return latest;
            reset(); // conflicting pixels with one timestamp are not fresh supporting evidence
        }
        if (previous == null || previous.rows != observed.rows || previous.cols != observed.cols
                || at < lastAt || at - lastAt > 2400) {
            reset();
            samples = new int[observed.rows][observed.cols];
            lastAnimal = new Match3Position.Cell[observed.rows][observed.cols];
        }
        boolean moved = false;
        int unconfirmed = 0;
        Match3Position.Cell[][] stable = new Match3Position.Cell[observed.rows][observed.cols];
        for (int r = 0; r < observed.rows; r++) for (int c = 0; c < observed.cols; c++) {
            Match3Position.Cell cell = observed.cell(r,c), animal = lastAnimal[r][c];
            // A colour change or a visible falling vacancy is motion anywhere on the board.
            // A failed classifier alone only removes this cell's support, not the entire board.
            if (animal != null && (cell.kind == Match3Position.Kind.ANIMAL && cell.color!=animal.color
                    || cell.kind == Match3Position.Kind.EMPTY)) moved = true;
            if (cell.kind == Match3Position.Kind.ANIMAL) lastAnimal[r][c] = cell;
            else if (cell.kind == Match3Position.Kind.EMPTY) lastAnimal[r][c] = null;
            samples[r][c] = previous != null && cell.equals(previous.cell(r,c))
                    ? Math.min(SAMPLES, samples[r][c] + 1) : 1;
            if (samples[r][c] >= SAMPLES) stable[r][c] = cell;
            else { stable[r][c] = Match3Position.Cell.obstacle(Match3Position.Kind.UNKNOWN,-1); unconfirmed++; }
        }
        quietSamples = moved ? 0 : Math.min(SAMPLES, quietSamples + 1);
        previous = observed; lastAt = at;
        latest = new Snapshot(new Match3Position(stable), quietSamples >= SAMPLES, unconfirmed);
        return latest;
    }

    void reset() {
        previous = null; samples = null; lastAnimal = null;
        lastAt = -1; quietSamples = 0; latest = null;
    }
}
