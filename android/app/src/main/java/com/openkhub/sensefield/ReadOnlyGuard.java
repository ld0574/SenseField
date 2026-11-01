package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Line-oriented read-only guard: it reports every source line that reaches for an
 * Android input-injection API, so the public claim "we only perceive, we never
 * inject" can be re-checked by anyone instead of being asserted in prose.
 *
 * <p>Deliberately pure JVM: no Android type is imported, so the guard and its tests
 * run on a plain unit-test JVM with no device and no emulator. A hit is a
 * (file name, line number, API name) triple; matching happens per line, never per
 * file, which means a comment or javadoc that names an injection API is reported
 * too. That is intended: an unexplained mention is worth one sentence of
 * explanation, a hidden call is worth an incident.</p>
 *
 * <p>{@link #INJECTION_APIS} is the blacklist and no line of it is suppressible.
 * {@link #READ_ONLY_INPUT_MANAGER_CALLS} is the first whitelisted pairing:
 * {@code InputManager} is the class that owns the injection surface, so a
 * member reached directly through that class name is only allowed when it is the
 * read-only obscuring-opacity query. The whitelist is data, not a comment; it never
 * exempts a file and never exempts a blacklisted API.</p>
 *
 * <p>The whitelist now carries two pairings, and both are pairings rather than
 * exemptions. The second one, {@link #READ_ONLY_ACCESSIBILITY_ACTIONS}, is written in the
 * same shape and pairs one call with one action constant: handing the accessibility focus
 * back to a view is a focus return, not a click, so the pairing is admitted only while the
 * action named in the argument is the read-only focus action. An argument that is another
 * action constant, a variable, or that cannot be resolved is reported. The pairing never
 * exempts a file either, and the four node-action constants in the blacklist stay reported
 * no matter what else the line says.</p>
 *
 * <p>Two line forms are not treated as calls, because there is no member being
 * invoked: the class literal {@code InputManager.class} (a type token) and a line
 * that ends with a dangling {@code InputManager.} with no member name yet. Imports,
 * declarations and the variable lookup {@code getSystemService(InputManager.class)}
 * therefore read as zero hits, while any other member reached that way is reported.</p>
 */
public final class ReadOnlyGuard {

    /** Injection-capable APIs. A line naming any of these is always reported. */
    public static final List<String> INJECTION_APIS = Collections.unmodifiableList(asList(
            "dispatchGesture",
            "GestureDescription",
            "injectInputEvent",
            "performGlobalAction",
            "GLOBAL_ACTION_",
            "enqueueInputEvent",
            "MotionEvent.obtain",
            "performAccessibilityAction",
            "ACTION_CLICK",
            "ACTION_LONG_CLICK",
            "ACTION_SET_TEXT",
            "ACTION_PASTE"));

    /**
     * The second admitted pairing, written as data in the same shape as the one above:
     * the only action that may be handed to the accessibility action call is this
     * read-only focus return, which gives focus back to the panel's own trigger and its
     * own heading after the panel closes. It does not touch the game process and does not
     * produce a click.
     */
    public static final List<String> READ_ONLY_ACCESSIBILITY_ACTIONS =
            Collections.unmodifiableList(asList("ACTION_ACCESSIBILITY_FOCUS"));

    /** The call whose action argument is gated by the pairing above. */
    private static final String ACCESSIBILITY_ACTION_CALL = "performAccessibilityAction";

    /** The prefix every node-action constant spells with. */
    private static final String ACTION_CONSTANT = "ACTION_";

    /** How many following lines a wrapped call may borrow to state its action argument. */
    private static final int MAX_CONTINUATION_LINES = 8;

    /**
     * The single admitted exception, written as data: the only member that may be
     * reached straight through the {@code InputManager} class name is this read-only
     * obscuring-opacity query.
     */
    public static final List<String> READ_ONLY_INPUT_MANAGER_CALLS =
            Collections.unmodifiableList(asList("getMaximumObscuringOpacityForTouch"));

    /** The class whose class-qualified members are gated by the whitelist above. */
    public static final String INPUT_MANAGER = "InputManager";

    /** One reported line: where it is, and which API name made it a hit. */
    public static final class Hit {
        public final String fileName;
        public final int lineNumber;
        public final String apiName;

        Hit(String fileName, int lineNumber, String apiName) {
            this.fileName = fileName;
            this.lineNumber = lineNumber;
            this.apiName = apiName;
        }

        public String fileName() {
            return fileName;
        }

        public int lineNumber() {
            return lineNumber;
        }

        public String apiName() {
            return apiName;
        }

        @Override public String toString() {
            return fileName + ":" + lineNumber + " " + apiName;
        }
    }

    private ReadOnlyGuard() {
    }

    /**
     * Scans one named file.
     *
     * @param fileName evidence label only; it never changes the verdict
     * @param lines    text lines of that file, index 0 is line 1
     * @return every hit, in line order then blacklist order
     */
    public static List<Hit> scan(String fileName, List<String> lines) {
        List<Hit> hits = new ArrayList<>();
        if (lines == null) return hits;
        String file = fileName == null ? "" : fileName;
        for (int index = 0; index < lines.size(); index++) {
            String raw = lines.get(index);
            if (raw == null) continue;
            String squashed = squash(raw);
            if (squashed.length() == 0) continue;
            String folded = lower(squashed);
            int lineNumber = index + 1;

            for (String api : INJECTION_APIS) {
                if (!folded.contains(lower(api))) continue;
                if (isAccessibilityActionCall(api) && readOnlyFocusReturn(lines, index, folded)) continue;
                hits.add(new Hit(file, lineNumber, api));
            }
            hits.addAll(inputManagerCalls(file, lineNumber, squashed, folded));
        }
        return hits;
    }

    private static boolean isAccessibilityActionCall(String api) {
        return ACCESSIBILITY_ACTION_CALL.equalsIgnoreCase(api);
    }

    /**
     * True only when every accessibility action call named on this line is the whitelisted
     * pairing. The argument is read on this line and, when the call is wrapped, from the
     * continuation lines that complete it: the guard still reports per line, but a call
     * that states its action one line later would otherwise be judged without it. An
     * unresolvable argument, a variable, or any blacklisted action constant in it is
     * reported rather than excused.
     */
    private static boolean readOnlyFocusReturn(List<String> lines, int index, String folded) {
        String handle = lower(ACCESSIBILITY_ACTION_CALL);
        int from = 0;
        int seen = 0;
        while (true) {
            int at = folded.indexOf(handle, from);
            if (at < 0) return seen > 0;
            seen++;
            if (!isWhitelistedAction(actionArgument(folded, lines, index, at + handle.length()))) return false;
            from = at + handle.length();
        }
    }

    /** The call's first argument in folded form, or empty when the call never states one. */
    private static String actionArgument(String folded, List<String> lines, int index, int afterName) {
        int open = folded.indexOf('(', afterName);
        if (open < 0) return "";
        StringBuilder argument = new StringBuilder();
        String current = folded.substring(open + 1);
        for (int i = 0; i < current.length(); i++) {
            char c = current.charAt(i);
            if (c == ',' || c == ')') return argument.toString();
            argument.append(c);
        }
        int limit = Math.min(lines.size(), index + 1 + MAX_CONTINUATION_LINES);
        for (int next = index + 1; next < limit; next++) {
            String raw = lines.get(next);
            if (raw == null) return "";
            String foldedNext = lower(squash(raw));
            for (int i = 0; i < foldedNext.length(); i++) {
                char c = foldedNext.charAt(i);
                if (c == ',' || c == ')') return argument.toString();
                argument.append(c);
            }
        }
        return "";
    }

    /**
     * True when the argument names a whitelisted read-only action and no action constant
     * the blacklist already names. Checked in that order on purpose: the whitelist cannot
     * silence a blacklisted action, and a line that pairs both is reported.
     */
    private static boolean isWhitelistedAction(String argument) {
        if (argument.length() == 0) return false;
        for (String api : INJECTION_APIS) {
            if (startsWithActionConstant(api) && argument.contains(lower(api))) return false;
        }
        for (String allowed : READ_ONLY_ACCESSIBILITY_ACTIONS) {
            if (allowed.length() > 0 && argument.contains(lower(allowed))) return true;
        }
        return false;
    }

    private static boolean startsWithActionConstant(String api) {
        return api.regionMatches(true, 0, ACTION_CONSTANT, 0, ACTION_CONSTANT.length());
    }

    /** Convenience wrapper for a whole file body; line 1 is the first line of text. */
    public static List<Hit> scanText(String fileName, String text) {
        List<String> lines = new ArrayList<>();
        if (text != null) {
            int start = 0;
            for (int i = 0; i <= text.length(); i++) {
                if (i == text.length() || text.charAt(i) == '\n') {
                    String piece = text.substring(start, i);
                    lines.add(piece.endsWith("\r") ? piece.substring(0, piece.length() - 1) : piece);
                    start = i + 1;
                }
            }
        }
        return scan(fileName, lines);
    }

    /**
     * Members reached directly through the {@code InputManager} class name, minus the
     * whitelisted read-only query. A member already covered by the blacklist is not
     * counted twice on the same line: the blacklist hit stands on its own.
     */
    private static List<Hit> inputManagerCalls(String fileName, int lineNumber, String squashed, String folded) {
        List<Hit> hits = new ArrayList<>();
        String handle = lower(INPUT_MANAGER) + ".";
        int from = 0;
        while (true) {
            int at = folded.indexOf(handle, from);
            if (at < 0) return hits;
            int memberStart = at + handle.length();
            int memberEnd = memberStart;
            while (memberEnd < folded.length() && isMemberChar(folded.charAt(memberEnd))) memberEnd++;
            String member = squashed.substring(memberStart, memberEnd);
            from = Math.max(memberEnd, at + 1);
            if (member.length() == 0 || member.equalsIgnoreCase("class")) continue;
            if (isReadOnlyInputManagerCall(member) || coversBlacklistedMember(member)) continue;
            hits.add(new Hit(fileName, lineNumber, INPUT_MANAGER + "." + member));
        }
    }

    private static boolean isReadOnlyInputManagerCall(String member) {
        for (String allowed : READ_ONLY_INPUT_MANAGER_CALLS) {
            if (allowed.equalsIgnoreCase(member)) return true;
        }
        return false;
    }

    /**
     * True when the member name already spells out a blacklisted API, so the same
     * line is not counted twice. One-directional on purpose: a partial member name
     * such as {@code inject} is still reported, because only the full blacklisted
     * spelling deduplicates.
     */
    private static boolean coversBlacklistedMember(String member) {
        String foldedMember = lower(member);
        for (String api : INJECTION_APIS) {
            if (foldedMember.contains(lower(api))) return true;
        }
        return false;
    }

    private static boolean isMemberChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** Drops whitespace so spacing cannot change the verdict; case is kept for display. */
    private static String squash(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) out.append(c);
        }
        return out.toString();
    }

    /** Character-wise lower-casing: keeps the index alignment {@link #squash} relies on. */
    private static String lower(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) out.append(Character.toLowerCase(value.charAt(i)));
        return out.toString();
    }

    private static List<String> asList(String... values) {
        return java.util.Arrays.asList(values);
    }
}
