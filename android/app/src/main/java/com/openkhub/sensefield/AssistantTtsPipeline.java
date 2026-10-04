package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure-Java planning and lifecycle state for bounded, sentence-first assistant TTS. */
final class AssistantTtsPipeline {
    static final int MAX_SEGMENTS = 4;
    static final int MAX_CHARS = 400;
    private static final int LONG_SENTENCE_CHARS = 120;

    private AssistantTtsPipeline() {}

    static final class Segment {
        final int index;
        final String text;
        final int chars;

        Segment(int index, String text) {
            this.index = index;
            this.text = text;
            this.chars = text.codePointCount(0, text.length());
        }
    }

    static final class Plan {
        final List<Segment> segments;
        final int chars;
        /** Characters intentionally omitted to keep the queued reply bounded. */
        final int droppedChars;

        Plan(List<Segment> segments, int chars, int droppedChars) {
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
            this.chars = chars;
            this.droppedChars = droppedChars;
        }
    }

    static Plan split(String speech) {
        if (speech == null || speech.isEmpty()) return new Plan(Collections.emptyList(), 0, 0);
        List<Sentence> sentences = sentences(speech);
        List<Segment> result = new ArrayList<>();
        int acceptedChars = 0;
        boolean bounded = false;
        int droppedStart = -1;
        for (Sentence sentence : sentences) {
            List<Range> candidates = sentence.candidates(speech);
            for (Range candidate : candidates) {
                String part = trim(speech.substring(candidate.start, candidate.end));
                if (part.isEmpty()) continue;
                int chars = part.codePointCount(0, part.length());
                if (result.size() >= MAX_SEGMENTS || acceptedChars + chars > MAX_CHARS) {
                    bounded = true;
                    droppedStart = candidate.start;
                    break;
                }
                result.add(new Segment(result.size(), part));
                acceptedChars += chars;
            }
            if (bounded) break;
        }
        // Every cut above occurs after sentence/clause punctuation. Any tail that does not fit is
        // omitted whole; never manufacture a mid-word, numeric, or emoji boundary to hit the cap.
        int droppedChars = bounded && droppedStart >= 0
                ? trim(speech.substring(droppedStart)).codePointCount(0,
                trim(speech.substring(droppedStart)).length()) : 0;
        return new Plan(result, acceptedChars, droppedChars);
    }

    private static List<Sentence> sentences(String text) {
        List<Sentence> result = new ArrayList<>();
        List<Integer> clauseEnds = new ArrayList<>();
        int start = 0;
        int cursor = 0;
        while (cursor < text.length()) {
            int cp = text.codePointAt(cursor);
            int count = Character.charCount(cp);
            if (isClauseEnd(text, cursor, cp)) {
                int end = consumeBoundaryRun(text, cursor + count, false);
                end = consumeClosers(text, end);
                clauseEnds.add(end);
                cursor = end;
                continue;
            }
            if (isSentenceEnd(text, cursor, cp)) {
                int end = consumeBoundaryRun(text, cursor + count, true);
                end = consumeClosers(text, end);
                result.add(new Sentence(start, end, new ArrayList<>(clauseEnds)));
                start = end;
                clauseEnds.clear();
                cursor = end;
                continue;
            }
            cursor += count;
        }
        if (start < text.length())
            result.add(new Sentence(start, text.length(), new ArrayList<>(clauseEnds)));
        return result;
    }

    private static boolean isClauseEnd(String text, int index, int cp) {
        if (cp == ',' || cp == '\uFF0C' || cp == ';' || cp == '\uFF1B')
            return !betweenDigits(text, index, cp);
        return false;
    }

    private static boolean isSentenceEnd(String text, int index, int cp) {
        if (cp == '\n' || cp == '\r' || cp == '.' || cp == '!' || cp == '?'
                || cp == '\u3002' || cp == '\uFF01' || cp == '\uFF1F') {
            return !betweenDigits(text, index, cp)
                    && (cp != '.' || !betweenAsciiWordCharacters(text, index));
        }
        return false;
    }

    private static boolean betweenDigits(String text, int index, int cp) {
        if (cp != '.' && cp != ',' && cp != '\uFF0C' && cp != ':') return false;
        int previous = index > 0 ? text.codePointBefore(index) : -1;
        int nextIndex = index + Character.charCount(cp);
        int next = nextIndex < text.length() ? text.codePointAt(nextIndex) : -1;
        return Character.isDigit(previous) && Character.isDigit(next);
    }

    private static boolean betweenAsciiWordCharacters(String text, int index) {
        int previous = index > 0 ? text.codePointBefore(index) : -1;
        int nextIndex = index + 1;
        int next = nextIndex < text.length() ? text.codePointAt(nextIndex) : -1;
        return isAsciiWordCharacter(previous) && isAsciiWordCharacter(next);
    }

    private static boolean isAsciiWordCharacter(int cp) {
        return cp >= 0 && cp < 128 && (Character.isLetterOrDigit(cp) || cp == '_');
    }

    private static int consumeBoundaryRun(String text, int cursor, boolean sentenceBoundary) {
        while (cursor < text.length()) {
            int cp = text.codePointAt(cursor);
            boolean sameKind = sentenceBoundary ? isSentencePunctuation(cp)
                    : isClausePunctuation(cp);
            if (!sameKind) break;
            cursor += Character.charCount(cp);
        }
        return cursor;
    }

    private static boolean isSentencePunctuation(int cp) {
        return cp == '.' || cp == '!' || cp == '?' || cp == '\u3002'
                || cp == '\uFF01' || cp == '\uFF1F';
    }

    private static boolean isClausePunctuation(int cp) {
        return cp == ',' || cp == '\uFF0C' || cp == ';' || cp == '\uFF1B';
    }

    private static int consumeClosers(String text, int cursor) {
        while (cursor < text.length()) {
            int cp = text.codePointAt(cursor);
            if (!isClosingPunctuation(cp)) break;
            cursor += Character.charCount(cp);
        }
        return cursor;
    }

    private static boolean isClosingPunctuation(int cp) {
        switch (cp) {
            case '\u2019': // ’
            case '\u201D': // ”
            case '\'':
            case '"':
            case '\u3009': // 〉
            case '\u300B': // 》
            case '\u300D': // 」
            case '\u300F': // 』
            case '\u3011': // 】
            case '\uFF09': // ）
            case ')':
            case ']':
            case '}':
                return true;
            default:
                return false;
        }
    }

    private static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int cp = value.codePointAt(start);
            if (!Character.isWhitespace(cp)) break;
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = value.codePointBefore(end);
            if (!Character.isWhitespace(cp)) break;
            end -= Character.charCount(cp);
        }
        return value.substring(start, end);
    }

    private static final class Sentence {
        final int start;
        final int end;
        final List<Integer> clauseEnds;

        Sentence(int start, int end, List<Integer> clauseEnds) {
            this.start = start;
            this.end = end;
            this.clauseEnds = clauseEnds;
        }

        List<Range> candidates(String text) {
            int sentenceChars = text.codePointCount(start, end);
            if (sentenceChars <= LONG_SENTENCE_CHARS || clauseEnds.isEmpty())
                return Collections.singletonList(new Range(start, end));
            List<Range> result = new ArrayList<>();
            int cursor = start;
            for (int clauseEnd : clauseEnds) {
                if (clauseEnd <= cursor || clauseEnd > end) continue;
                result.add(new Range(cursor, clauseEnd));
                cursor = clauseEnd;
            }
            if (cursor < end) result.add(new Range(cursor, end));
            return result;
        }
    }

    private static final class Range {
        final int start;
        final int end;
        Range(int start, int end) { this.start = start; this.end = end; }
    }

    static final class DropSummary {
        final int index;
        final int segments;
        final int chars;

        DropSummary(int index, int segments, int chars) {
            this.index = index;
            this.segments = segments;
            this.chars = chars;
        }

        boolean isEmpty() { return segments == 0 && chars == 0; }
    }

    enum Action { NEXT, FINISH, IGNORED }

    static final class Transition {
        final Action action;
        final boolean success;
        final DropSummary dropped;

        Transition(Action action, boolean success, DropSummary dropped) {
            this.action = action;
            this.success = success;
            this.dropped = dropped;
        }
    }

    /**
     * Synchronized state gate shared by TTS callbacks, the PCM worker, and cancellation. An
     * utterance callback can advance only the exact active segment it was created for.
     */
    static final class Group {
        private enum Phase { NONE, SYNTHESIZING, READY, PLAYING }

        final String groupId;
        final String cueId;
        final Plan plan;
        private int nextIndex;
        private int activeIndex = -1;
        private Phase phase = Phase.NONE;
        private boolean activeWritten;
        private boolean started;
        private boolean cancelled;
        private boolean terminal;
        private boolean finishDelivered;

        Group(String groupId, String cueId, Plan plan) {
            this.groupId = groupId;
            this.cueId = cueId;
            this.plan = plan;
        }

        synchronized Segment reserveNext() {
            if (cancelled || terminal || activeIndex >= 0 || nextIndex >= plan.segments.size())
                return null;
            Segment segment = plan.segments.get(nextIndex++);
            activeIndex = segment.index;
            phase = Phase.SYNTHESIZING;
            activeWritten = false;
            return segment;
        }

        synchronized boolean synthesisReady(int index) {
            if (!isCurrent(index) || phase != Phase.SYNTHESIZING) return false;
            phase = Phase.READY;
            return true;
        }

        synchronized boolean isReadyToPlay(int index) {
            return isCurrent(index) && phase == Phase.READY;
        }

        synchronized boolean isSynthesizing(int index) {
            return isCurrent(index) && phase == Phase.SYNTHESIZING;
        }

        /** Called after the first successful PCM write for this segment. */
        synchronized boolean markFirstWrite(int index) {
            if (!isCurrent(index) || (phase != Phase.READY && phase != Phase.PLAYING))
                return false;
            phase = Phase.PLAYING;
            activeWritten = true;
            if (started) return false;
            started = true;
            return true;
        }

        synchronized Transition playbackCompleted(int index) {
            if (!isCurrent(index) || phase != Phase.PLAYING || !activeWritten)
                return ignored();
            activeIndex = -1;
            phase = Phase.NONE;
            if (nextIndex < plan.segments.size()) return next();
            terminal = true;
            return finish(true, emptyDrop());
        }

        /** Drop the active not-yet-started segment and all its tail at a fresh expiry check. */
        synchronized Transition expiredBeforePlayback(int index) {
            if (!isCurrent(index)) return ignored();
            DropSummary dropped = remainingFromActive();
            activeIndex = -1;
            phase = Phase.NONE;
            terminal = true;
            return finish(started, dropped);
        }

        synchronized Transition failed(int index) {
            if (!isCurrent(index)) return ignored();
            DropSummary dropped = remainingFromActive();
            activeIndex = -1;
            phase = Phase.NONE;
            terminal = true;
            return finish(false, dropped);
        }

        /** Atomically invalidates this group; late onDone or worker completions then do nothing. */
        synchronized DropSummary cancel() {
            if (cancelled || terminal) return emptyDrop();
            DropSummary dropped = remainingFromActive();
            cancelled = true;
            terminal = true;
            activeIndex = -1;
            phase = Phase.NONE;
            return dropped;
        }

        synchronized boolean isCancelled() { return cancelled; }

        synchronized boolean isTerminal() { return terminal; }

        /** At most one cue-level onStarted callback may be emitted by this group. */
        synchronized boolean started() { return started; }

        /** Guard cue-level onFinished from duplicate or late engine callbacks. */
        synchronized boolean claimFinishCallback() {
            if (cancelled || finishDelivered) return false;
            finishDelivered = true;
            terminal = true;
            return true;
        }

        private boolean isCurrent(int index) {
            return !cancelled && !terminal && activeIndex == index;
        }

        private DropSummary remainingFromActive() {
            int from = activeIndex >= 0 && !activeWritten ? activeIndex : nextIndex;
            int chars = 0;
            int count = 0;
            for (int i = from; i < plan.segments.size(); i++) {
                chars += plan.segments.get(i).chars;
                count++;
            }
            return new DropSummary(from, count, chars);
        }

        private static Transition next() {
            return new Transition(Action.NEXT, true, emptyDrop());
        }

        private static Transition finish(boolean success, DropSummary dropped) {
            return new Transition(Action.FINISH, success, dropped);
        }

        private static Transition ignored() {
            return new Transition(Action.IGNORED, false, emptyDrop());
        }

        private static DropSummary emptyDrop() { return new DropSummary(0, 0, 0); }
    }
}
