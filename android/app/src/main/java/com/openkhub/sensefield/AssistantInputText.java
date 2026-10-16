package com.openkhub.sensefield;

/** Rejects non-linguistic input without restricting wording or short replies. */
final class AssistantInputText {
    private AssistantInputText() { }

    static String normalize(String value) {
        if (value == null) return "";
        boolean content = false;
        for (int at = 0; at < value.length();) {
            int codePoint = value.codePointAt(at);
            if (Character.isLetterOrDigit(codePoint)) {
                content = true;
                break;
            }
            at += Character.charCount(codePoint);
        }
        // An ASR decoder can produce a full stop from non-speech audio.
        // Length alone cannot distinguish that from a real one-character reply.
        if (!content) return "";
        int first = 0, last = value.length();
        while (first < last && boundarySpace(value.codePointAt(first))) {
            first += Character.charCount(value.codePointAt(first));
        }
        while (last > first && boundarySpace(value.codePointBefore(last))) {
            last -= Character.charCount(value.codePointBefore(last));
        }
        return value.substring(first, last);
    }

    private static boolean boundarySpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }
}
