package com.openkhub.sensefield;

import java.util.regex.Pattern;

/** Pronunciation-only normalization. Visible hints and diagnostic source text keep “行”. */
final class Match3SpeechText {
    private static final Pattern ROW = Pattern.compile("第\\s*([0-9]+|[一二三四五六七八九十百]+)\\s*行");
    private static final Pattern COLUMN = Pattern.compile("第\\s*([0-9]+|[一二三四五六七八九十百]+)\\s*列");

    private Match3SpeechText() { }

    static boolean isBoardCue(String eventKey) {
        return eventKey != null && (eventKey.startsWith("m3:") || eventKey.startsWith("m3live:"));
    }

    static String forTts(String text) {
        if (text == null) return null;
        // Vendor engines, including MultiTTS plug-ins, do not all honor TtsSpan phonemes.
        // “航” gives the intended háng sound without changing the visible row/column wording.
        return COLUMN.matcher(ROW.matcher(text).replaceAll("第$1航")).replaceAll("第$1列")
                .replace("行号", "横排编号").replace("行从上往下", "横排从上往下");
    }
}
