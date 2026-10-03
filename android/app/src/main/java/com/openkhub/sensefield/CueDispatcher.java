package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.HashSet;
import java.util.Set;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLongArray;

/** Applies expiry, policy, deduplication and bounded priority ordering before rendering. */
final class CueDispatcher implements AutoCloseable {
    static final int MAX_PENDING = 8;

    interface Clock { long nowMs(); }
    interface Policy {
        boolean categoryEnabled(CueRequest.Category category);
        int enabledChannels();
        default int enabledChannels(CueRequest.Category category) {
            return enabledChannels();
        }
        long dedupeWindowMs(CueRequest.Category category);
    }
    interface PlaybackCallback {
        void onStarted(long atMs);
        void onFinished(long atMs, boolean success);
    }
    interface Renderer {
        boolean playTone(CueRequest request, PlaybackCallback callback);
        boolean vibrate(CueRequest request);
        boolean speak(CueRequest request, boolean interrupt, PlaybackCallback callback);
        void stopSpeech();
        /** Cancel only conversational speech when the user starts talking. */
        default void cancelAssistantSpeech() { stopSpeech(); }
        default void cancelPendingTone() {}
        default void cancelPendingTone(CueRequest.Category category) {}
    }
    interface Listener {
        void onDispatch(CueRequest request, DispatchResult result);
        void onPlayback(CueRequest request, String channel, long atMs, String result);
    }

    static final class DispatchResult {
        final int acceptedChannels;
        final String outcome;
        final String reason;

        DispatchResult(int acceptedChannels, String outcome, String reason) {
            this.acceptedChannels = acceptedChannels;
            this.outcome = outcome;
            this.reason = reason;
        }

        boolean audioQueued() {
            return (acceptedChannels & (CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH)) != 0;
        }
    }

    private static final class Pending {
        final CueRequest request;
        final long sequence;
        Pending(CueRequest request, long sequence) {
            this.request = request;
            this.sequence = sequence;
        }
    }

    private final Renderer renderer;
    private final Policy policy;
    private final Listener listener;
    private final Clock clock;
    private final Map<String, Long> lastAccepted = new HashMap<>();
    private final Map<String, CueRequest.Category> acceptedCategories = new HashMap<>();
    private final Map<CueRequest.Category, Long> lastSpeech =
            new EnumMap<>(CueRequest.Category.class);
    private final Map<CueRequest.Category, Long> lastSpeechStarted =
            new EnumMap<>(CueRequest.Category.class);
    private final Set<String> cancelledSpeech = new HashSet<>();
    /**
     * Tone callbacks arrive on SoundPool's load/callback thread, while
     * category clears happen on the capture worker.  Keep the per-category
     * epoch in atomics so the callback can validate it without taking the
     * dispatcher monitor (which would invert the audioLock/dispatcher lock
     * order during a synchronous SoundPool callback).
     */
    private final AtomicLongArray toneCategoryEpoch =
            new AtomicLongArray(CueRequest.Category.values().length);
    private final PriorityQueue<Pending> speechQueue = new PriorityQueue<>(
            Comparator.<Pending>comparingInt(value -> value.request.priority).reversed()
                    .thenComparingLong(value -> value.sequence));
    private long sequence;
    private CueRequest speaking;
    private long lastHapticAtMs = Long.MIN_VALUE / 2;
    private volatile long lastAlertAtMs = Long.MIN_VALUE;
    private volatile boolean closed;
    private volatile boolean paused;
    private volatile long speechEpoch;
    private volatile long toneEpoch;

    CueDispatcher(Renderer renderer, Policy policy, Listener listener, Clock clock) {
        this.renderer = renderer;
        this.policy = policy;
        this.listener = listener;
        this.clock = clock;
    }

    synchronized DispatchResult submit(CueRequest request) {
        if (closed) return report(request, 0, "DROPPED", "closed");
        if (paused) return report(request, 0, "DROPPED", "paused");
        long now = clock.nowMs();
        if (now > request.expiresAtMs) return report(request, 0, "DROPPED", "expired");
        if (!policy.categoryEnabled(request.category))
            return report(request, 0, "DROPPED", "category_disabled");
        int accepted = request.requestedChannels & policy.enabledChannels(request.category);
        if (accepted == 0) return report(request, 0, "DROPPED", "channels_disabled");
        Long previous = lastAccepted.get(request.eventKey);
        if (previous != null && now - previous < policy.dedupeWindowMs(request.category))
            return report(request, 0, "DROPPED", "deduplicated");

        if (preemptsAssistant(request.category)
                && (accepted & (CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH
                | CueRequest.CHANNEL_HAPTIC)) != 0) {
            preemptAssistantForAlert();
        }

        int rendered = accepted & CueRequest.CHANNEL_VISUAL;
        String suppressionReason = null;
        if ((accepted & CueRequest.CHANNEL_TONE) != 0) {
            // Tone completion can be posted after pause/close (for example the
            // SoundPool short-tone timer). Tie callbacks to a session epoch and
            // a category epoch so late renderer work cannot add audit records
            // after a reset or a category clear.
            final long playbackEpoch = toneEpoch;
            final long categoryToneEpoch = toneEpoch(request.category);
            boolean toneAccepted = renderer.playTone(request, new PlaybackCallback() {
                @Override public void onStarted(long atMs) {
                    if (!toneCallbackIsCurrent(request.category, playbackEpoch,
                            categoryToneEpoch)) return;
                    if (isAlert(request.category)) lastAlertAtMs = atMs;
                    listener.onPlayback(request, "TONE", atMs, "STARTED");
                }
                @Override public void onFinished(long atMs, boolean success) {
                    if (!toneCallbackIsCurrent(request.category, playbackEpoch,
                            categoryToneEpoch)) return;
                    listener.onPlayback(request, "TONE", atMs,
                            success ? "COMPLETED" : "FAILED");
                }
            });
            if (toneAccepted) rendered |= CueRequest.CHANNEL_TONE;
            else suppressionReason = "tone_unavailable";
        }
        if ((accepted & CueRequest.CHANNEL_HAPTIC) != 0) {
            boolean critical = request.priority >= 100;
            if ((critical || now - lastHapticAtMs >= 500) && renderer.vibrate(request)) {
                rendered |= CueRequest.CHANNEL_HAPTIC;
                lastHapticAtMs = now;
                if (isAlert(request.category)) lastAlertAtMs = now;
                listener.onPlayback(request, "HAPTIC", now, "STARTED");
            } else if (now - lastHapticAtMs < 500) suppressionReason = "haptic_cooldown";
        }
        if ((accepted & CueRequest.CHANNEL_SPEECH) != 0 && request.speech != null
                && !request.speech.isEmpty()) {
            Long last = lastSpeech.get(request.category);
            if (request.priority != 100 && request.category != CueRequest.Category.NEAR_ZONE
                    && request.category != CueRequest.Category.ASSISTANT
                    && last != null &&
                    now - last < policy.dedupeWindowMs(request.category)) {
                accepted &= ~CueRequest.CHANNEL_SPEECH;
                suppressionReason = "speech_cooldown";
            } else if (!enqueueSpeech(request)) {
                accepted &= ~CueRequest.CHANNEL_SPEECH;
                suppressionReason = "speech_unavailable_or_queue_full";
            } else {
                rendered |= CueRequest.CHANNEL_SPEECH;
                lastSpeech.put(request.category, now);
            }
        } else {
            accepted &= ~CueRequest.CHANNEL_SPEECH;
        }
        String reason = suppressionReason != null ? suppressionReason
                : rendered == 0 ? "renderer_unavailable" : "none";
        if (rendered != 0) {
            lastAccepted.put(request.eventKey, now);
            acceptedCategories.put(request.eventKey, request.category);
        }
        return report(request, rendered, rendered == 0 ? "DROPPED" : "ACCEPTED", reason);
    }

    private boolean enqueueSpeech(CueRequest request) {
        if (request.category != CueRequest.Category.ASSISTANT
                && request.priority >= 90 && speaking != null
                && speaking.priority < request.priority) {
            CueRequest preempted = speaking;
            cancelledSpeech.add(preempted.cueId);
            speechEpoch++;
            renderer.stopSpeech();
            speaking = null;
            listener.onPlayback(preempted, "SPEECH", clock.nowMs(), "PREEMPTED");
        }
        if (request.category == CueRequest.Category.NEAR_ZONE) {
            // A near-zone phrase becomes stale quickly. Keep one pending
            // near-zone utterance and replace it with the newest episode.
            Pending replaced = null;
            for (Pending candidate : speechQueue) {
                if (candidate.request.category == CueRequest.Category.NEAR_ZONE) {
                    if (replaced == null || candidate.sequence < replaced.sequence) {
                        replaced = candidate;
                    }
                }
            }
            if (replaced != null) {
                speechQueue.remove(replaced);
                listener.onPlayback(replaced.request, "SPEECH", clock.nowMs(),
                        "QUEUE_REPLACED");
            }
        }
        if (speechQueue.size() >= MAX_PENDING) {
            Pending worst = null;
            for (Pending candidate : speechQueue) {
                if (worst == null || candidate.request.priority < worst.request.priority ||
                        (candidate.request.priority == worst.request.priority &&
                                candidate.sequence < worst.sequence)) worst = candidate;
            }
            if (worst != null && worst.request.priority < request.priority) {
                speechQueue.remove(worst);
                listener.onPlayback(worst.request, "SPEECH", clock.nowMs(), "QUEUE_EVICTED");
            } else return false;
        }
        speechQueue.add(new Pending(request, sequence++));
        drainSpeech();
        if (speaking == request) return true;
        for (Pending pending : speechQueue) {
            if (pending.request == request) return true;
        }
        return false;
    }

    private static boolean preemptsAssistant(CueRequest.Category category) {
        return category == CueRequest.Category.NEAR_ZONE
                || category == CueRequest.Category.PERIPHERAL_THREAT
                || category == CueRequest.Category.DANGER;
    }

    private void preemptAssistantForAlert() {
        // Assistant answers are tied to the question and current scene. An
        // alert invalidates both an active answer and any answer waiting
        // behind another utterance, even if speech is disabled for that cue.
        discardQueuedAssistant("PREEMPTED");
        if (speaking != null && speaking.category == CueRequest.Category.ASSISTANT) {
            CueRequest preempted = speaking;
            cancelledSpeech.add(preempted.cueId);
            speechEpoch++;
            renderer.cancelAssistantSpeech();
            speaking = null;
            listener.onPlayback(preempted, "SPEECH", clock.nowMs(), "PREEMPTED");
        }
        drainSpeech();
    }

    private void discardQueuedAssistant(String result) {
        List<Pending> discarded = new ArrayList<>();
        for (Pending pending : speechQueue) {
            if (pending.request.category == CueRequest.Category.ASSISTANT)
                discarded.add(pending);
        }
        for (Pending pending : discarded) {
            speechQueue.remove(pending);
            listener.onPlayback(pending.request, "SPEECH", clock.nowMs(), result);
        }
    }

    private long toneEpoch(CueRequest.Category category) {
        return category == null ? 0L : toneCategoryEpoch.get(category.ordinal());
    }

    private void bumpToneEpoch(CueRequest.Category category) {
        if (category != null) toneCategoryEpoch.incrementAndGet(category.ordinal());
    }

    private boolean toneCallbackIsCurrent(CueRequest.Category category,
                                           long playbackEpoch,
                                           long categoryEpoch) {
        return !closed && !paused && toneEpoch == playbackEpoch
                && toneEpoch(category) == categoryEpoch;
    }

    private void drainSpeech() {
        if (closed || paused || speaking != null) return;
        while (!speechQueue.isEmpty()) {
            CueRequest next = speechQueue.poll().request;
            if (clock.nowMs() > next.expiresAtMs) {
                listener.onPlayback(next, "SPEECH", clock.nowMs(), "EXPIRED");
                continue;
            }
            if (!policy.categoryEnabled(next.category) ||
                    (policy.enabledChannels(next.category) & CueRequest.CHANNEL_SPEECH) == 0) {
                listener.onPlayback(next, "SPEECH", clock.nowMs(), "SETTINGS_DISABLED");
                continue;
            }
            if (!next.playbackAllowedAt(clock.nowMs())) {
                listener.onPlayback(next, "SPEECH", clock.nowMs(), "EXPIRED");
                continue;
            }
            Long lastStarted = lastSpeechStarted.get(next.category);
            if (next.priority != 100 && next.category != CueRequest.Category.NEAR_ZONE
                    && next.category != CueRequest.Category.ASSISTANT
                    && lastStarted != null &&
                    clock.nowMs() - lastStarted < policy.dedupeWindowMs(next.category)) {
                listener.onPlayback(next, "SPEECH", clock.nowMs(), "COOLDOWN");
                continue;
            }
            final long playbackEpoch = speechEpoch;
            cancelledSpeech.remove(next.cueId);
            speaking = next;
            final boolean[] started = {false};
            boolean accepted = renderer.speak(next, next.priority >= 90,
                    new PlaybackCallback() {
                        @Override public void onStarted(long atMs) {
                            synchronized (CueDispatcher.this) {
                                if (cancelledSpeech.contains(next.cueId)
                                        || paused || playbackEpoch != speechEpoch) return;
                                if (!next.playbackAllowedAt(atMs)) {
                                    // A TTS engine may accept an utterance and
                                    // start it only after its short-lived cue
                                    // window has expired.  Emit the queued
                                    // terminal state the parser expects and
                                    // suppress the engine's later onDone.
                                    cancelledSpeech.add(next.cueId);
                                    if (speaking == next) speaking = null;
                                    if (next.category == CueRequest.Category.ASSISTANT)
                                        renderer.cancelAssistantSpeech();
                                    else renderer.stopSpeech();
                                    listener.onPlayback(next, "SPEECH", atMs, "EXPIRED");
                                    drainSpeech();
                                    return;
                                }
                                started[0] = true;
                                lastSpeechStarted.put(next.category, atMs);
                                if (isAlert(next.category)) lastAlertAtMs = atMs;
                            }
                            listener.onPlayback(next, "SPEECH", atMs, "STARTED");
                        }
                        @Override public void onFinished(long atMs, boolean success) {
                            synchronized (CueDispatcher.this) {
                                if (cancelledSpeech.remove(next.cueId)) return;
                                if (paused || playbackEpoch != speechEpoch) return;
                                if (speaking == next) speaking = null;
                                listener.onPlayback(next, "SPEECH", atMs,
                                        !started[0] && !next.playbackAllowedAt(atMs) ? "EXPIRED"
                                                : success ? "COMPLETED" : "FAILED");
                                drainSpeech();
                            }
                        }
                    });
            if (accepted) return;
            speaking = null;
            listener.onPlayback(next, "SPEECH", clock.nowMs(), "UNAVAILABLE");
        }
    }

    private static boolean isAlert(CueRequest.Category category) {
        return category == CueRequest.Category.NEAR_ZONE
                || category == CueRequest.Category.PERIPHERAL_THREAT
                || category == CueRequest.Category.DANGER;
    }

    private DispatchResult report(CueRequest request, int channels, String outcome, String reason) {
        DispatchResult result = new DispatchResult(channels, outcome, reason);
        listener.onDispatch(request, result);
        return result;
    }

    synchronized List<String> pendingCueIdsForTest() {
        List<Pending> pending = new ArrayList<>(speechQueue);
        pending.sort(speechQueue.comparator());
        List<String> ids = new ArrayList<>();
        for (Pending value : pending) ids.add(value.request.cueId);
        return ids;
    }

    /** Stop only assistant speech and its queued follow-ups, keeping alerts intact. */
    synchronized void cancelAssistantSpeech() {
        if (closed) return;
        discardQueuedAssistant("CANCELLED");
        if (speaking != null && speaking.category == CueRequest.Category.ASSISTANT) {
            CueRequest cancelled = speaking;
            cancelledSpeech.add(cancelled.cueId);
            speaking = null;
            speechEpoch++;
            renderer.cancelAssistantSpeech();
            listener.onPlayback(cancelled, "SPEECH", clock.nowMs(), "CANCELLED");
        }
        drainSpeech();
    }

    synchronized boolean isSpeaking() { return speaking != null; }

    /** Start time of the most recently started near/peripheral/danger alert. */
    long recentAlertAtMs() { return lastAlertAtMs; }

    /** Clear output queued for one category when its feature is disabled/reset. */
    synchronized void clearCategory(CueRequest.Category category) {
        if (closed || category == null) return;
        speechQueue.removeIf(pending -> pending.request.category == category);
        if (speaking != null && speaking.category == category) {
            cancelledSpeech.add(speaking.cueId);
            speaking = null;
            speechEpoch++;
            renderer.stopSpeech();
        }
        lastSpeech.remove(category);
        lastSpeechStarted.remove(category);
        lastAccepted.entrySet().removeIf(entry -> category == acceptedCategories.get(entry.getKey()));
        acceptedCategories.entrySet().removeIf(entry -> entry.getValue() == category);
        // SoundPool has one bounded pending load slot. The renderer keeps the
        // category on that slot so clearing one category cannot cancel another.
        bumpToneEpoch(category);
        renderer.cancelPendingTone(category);
        // Clearing the active category may have exposed a queued cue from a
        // different category. Continue draining while the dispatcher lock is
        // held so settings changes do not leave speech stranded.
        drainSpeech();
    }

    /** Clear all output atomically when native/session state is reset. */
    synchronized void clearAll() {
        if (closed) return;
        speechQueue.clear();
        if (speaking != null) {
            cancelledSpeech.add(speaking.cueId);
            speaking = null;
            speechEpoch++;
            renderer.stopSpeech();
        }
        lastSpeech.clear();
        lastSpeechStarted.clear();
        lastAlertAtMs = Long.MIN_VALUE;
        lastAccepted.clear();
        acceptedCategories.clear();
        toneEpoch++;
        renderer.cancelPendingTone();
        lastHapticAtMs = Long.MIN_VALUE / 2;
        cancelledSpeech.clear();
    }

    /** Stop all queued and active output while the capture session is paused. */
    synchronized void pause() {
        if (closed || paused) return;
        paused = true;
        speechEpoch++;
        toneEpoch++;
        if (speaking != null) {
            cancelledSpeech.add(speaking.cueId);
            speaking = null;
        }
        speechQueue.clear();
        renderer.stopSpeech();
        renderer.cancelPendingTone();
        // A pause starts a fresh cue window when resumed. This prevents an
        // event accepted just before the pause from suppressing the first new
        // event after it.
        lastAccepted.clear();
        acceptedCategories.clear();
        lastSpeech.clear();
        lastSpeechStarted.clear();
        lastAlertAtMs = Long.MIN_VALUE;
        lastHapticAtMs = Long.MIN_VALUE / 2;
        // The epoch makes any late callbacks harmless, even if stopSpeech()
        // does not produce a completion callback.
        cancelledSpeech.clear();
    }

    synchronized void resume() {
        if (!closed) paused = false;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        speechEpoch++;
        toneEpoch++;
        renderer.stopSpeech();
        renderer.cancelPendingTone();
        speechQueue.clear();
        speaking = null;
        lastAccepted.clear();
        acceptedCategories.clear();
        lastSpeech.clear();
        lastSpeechStarted.clear();
        cancelledSpeech.clear();
    }
}
