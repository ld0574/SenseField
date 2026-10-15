package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 三帧逐格多数票稳定窗测试（棋子 idle 摇摆抖动抹平）。 */
public class Match3MajorityVoteTest {

    private static char[][] m(String... rows) {
        char[][] out = new char[rows.length][rows[0].length()];
        for (int r = 0; r < rows.length; r++)
            for (int c = 0; c < rows[r].length(); c++)
                out[r][c] = rows[r].charAt(c);
        return out;
    }

    @Test
    public void allAgreeWins() {
        char[][][] w = {m("OY"), m("OY"), m("OY")};
        assertEquals("OY", new String(Match3LiveService.majorityMatrix(w)[0]));
    }

    @Test
    public void twoOfThreeWinsPerCell() {
        /* 摇摆只抖第 2 格：帧1/帧3 读 B，帧2 读 G → 多数票应出 B，其余格不受影响 */
        char[][][] w = {m("OYB"), m("OYG"), m("OBB".replace("B", "B"))};
        // 显式构造：帧2 第2格 G，其余 OYB
        w[1] = m("OYG");
        char[][] out = Match3LiveService.majorityMatrix(w);
        assertEquals('O', out[0][0]);
        assertEquals('Y', out[0][1]);
        assertEquals('B', out[0][2]);
    }

    @Test
    public void allDifferentBecomesUnknown() {
        char[][][] w = {m("OY"), m("GB"), m("PR")};
        char[][] out = Match3LiveService.majorityMatrix(w);
        assertEquals('.', out[0][0]);
        assertEquals('.', out[0][1]);
    }
}
