package com.openkhub.sensefield;

import org.junit.Test;
import static org.junit.Assert.*;

public final class Match3SpeechTextTest {
    @Test public void rowsSoundLikeHangInBothSwapDirections() {
        assertEquals("右下区域，第5航，第4列和第5列交换。",
                Match3SpeechText.forTts("右下区域，第 5 行，第 4 列和第 5 列交换。"));
        assertEquals("右上区域，第二航和第三航，第六列交换。",
                Match3SpeechText.forTts("右上区域，第二行和第三行，第六列交换。"));
    }
    @Test public void unrelatedCharactersAndVisibleSourceAreNotChanged() {
        String text = "执行交换，第三行第四列。";
        assertEquals("执行交换，第三航第四列。", Match3SpeechText.forTts(text));
        assertEquals("执行交换，第三行第四列。", text);
        assertFalse(Match3SpeechText.isBoardCue("near_zone"));
        assertTrue(Match3SpeechText.isBoardCue("m3live:hint:2"));
        assertTrue(Match3SpeechText.isBoardCue("m3:announce"));
    }
    @Test public void numberingExplanationHasUnambiguousWords() {
        assertEquals("横排编号从1开始，横排从上往下。", Match3SpeechText.forTts("行号从1开始，行从上往下。"));
    }
}
