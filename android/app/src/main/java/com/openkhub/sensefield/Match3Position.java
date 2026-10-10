package com.openkhub.sensefield;

import java.util.Objects;

/** Piece identity, swap permission and the stationary covering layer are independent. */
final class Match3Position {
    enum Kind { ANIMAL, COIN, SNOW, EGG, SPECIAL, EMPTY, SURFACE, UNKNOWN }
    enum SwapPermission { YES, NO, UNKNOWN }
    static final class Cell {
        final Kind kind;
        final char color;
        final boolean swappable;
        final SwapPermission swapPermission;
        final int layers, iceLayers;
        Cell(Kind kind, char color, boolean swappable, int layers, int iceLayers) {
            this(kind,color,swappable?SwapPermission.YES:kind==Kind.SNOW || kind==Kind.EMPTY || kind==Kind.ANIMAL
                    ?SwapPermission.NO:SwapPermission.UNKNOWN,layers,iceLayers);
        }
        private Cell(Kind kind, char color, SwapPermission permission, int layers, int iceLayers) {
            if (kind == null || layers < -1 || iceLayers < -1 || layers > 5 || iceLayers > 3
                    || kind != Kind.ANIMAL && color != '\0'
                    || kind == Kind.ANIMAL && Match3Goals.Kind.animal(color) == Match3Goals.Kind.UNKNOWN
                    || permission == null || permission==SwapPermission.YES
                    && (kind != Kind.ANIMAL || layers < 0 || iceLayers < 0)) throw new IllegalArgumentException("Unverified cell rule");
            this.kind = kind; this.color = color; this.swapPermission=permission;
            this.swappable=permission==SwapPermission.YES;
            this.layers = layers; this.iceLayers = iceLayers;
        }
        /** Explicit ordinary-animal rule, for verified sprites or abstract rule fixtures. */
        static Cell animal(char c) { return new Cell(Kind.ANIMAL, c, SwapPermission.YES, 0, 0); }
        /** Recognizing a face does not establish the covering state or exchange mechanics. */
        static Cell animalIdentity(char c) { return new Cell(Kind.ANIMAL,c,SwapPermission.UNKNOWN,-1,-1); }
        static Cell obstacle(Kind kind, int layers) { return new Cell(kind, '\0', false, layers, 0); }
        char code() {
            // An unverified covering cannot complete a match or lend it a certain task gain.
            if (kind == Kind.ANIMAL) return swapPermission==SwapPermission.UNKNOWN?'#':color;
            if (kind == Kind.SNOW) return 'I';
            if (kind == Kind.COIN) return 'C';
            if (kind == Kind.EGG) return 'E';
            if (kind == Kind.EMPTY) return 'H';
            if (kind == Kind.UNKNOWN) return '.';
            return '#';
        }
        @Override public boolean equals(Object object) {
            if (!(object instanceof Cell)) return false;
            Cell other = (Cell) object;
            return kind == other.kind && color == other.color && swapPermission == other.swapPermission
                    && layers == other.layers && iceLayers == other.iceLayers;
        }
        @Override public int hashCode() { return Objects.hash(kind, color, swapPermission, layers, iceLayers); }
    }
    final int rows, cols;
    private final Cell[][] cells;
    Match3Position(Cell[][] source) {
        if (source == null || source.length == 0 || source[0] == null || source[0].length == 0)
            throw new IllegalArgumentException("Incomplete board");
        rows = source.length; cols = source[0].length;
        if (rows > 12 || cols > 12) throw new IllegalArgumentException("Unsupported board");
        cells = new Cell[rows][];
        for (int r = 0; r < rows; r++) {
            if (source[r] == null || source[r].length != cols) throw new IllegalArgumentException("Ragged board");
            cells[r] = source[r].clone();
            for (Cell cell : cells[r]) if (cell == null) throw new IllegalArgumentException("Missing cell");
        }
    }
    Cell cell(int row, int col) { return cells[row][col]; }
    char[][] matrix() {
        char[][] out = new char[rows][cols];
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) out[r][c] = cells[r][c].code();
        return out;
    }
    boolean sameCells(Match3Position other) {
        if (other == null || rows != other.rows || cols != other.cols) return false;
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++)
            if (!cells[r][c].equals(other.cells[r][c])) return false;
        return true;
    }
}
