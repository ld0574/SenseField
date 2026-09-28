package org.openrd.mapassist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.HashSet;
import java.util.Set;
import java.util.EnumMap;

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
        default void cancelPendingTone() {}
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
    private final Map<CueRequest.Category, Long> lastSpeech =
            new EnumMap<>(CueRequest.Category.class);
    private final Map<CueRequest.Category, Long> lastSpeechStarted =
            new EnumMap<>(CueRequest.Category.class);
    private final Set<String> cancelledSpeech = new HashSet<>();
    private final PriorityQueue<Pending> speechQueue = new PriorityQueue<>(
            Comparator.<Pending>comparingInt(value -> value.request.priority).reversed()
                    .thenComparingLong(value -> value.sequence));
    private long sequence;
    private CueRequest speaking;
    private long lastHapticAtMs = Long.MIN_VALUE / 2;
    private volatile boolean closed;
    private volatile boolean paused;
    private volatile long speechEpoch;

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

        int rendered = accepted & CueRequest.CHANNEL_VISUAL;
        String suppressionReason = null;
        if ((accepted & CueRequest.CHANNEL_TONE) != 0) {
            // Tone completion can be posted after pause/close (for example the
            // SoundPool short-tone timer).  Tie callbacks to the same epoch as
            // speech so late renderer work cannot add audit records to the
            // paused or destroyed session.
            final long playbackEpoch = speechEpoch;
            boolean toneAccepted = renderer.playTone(request, new PlaybackCallback() {
                @Override public void onStarted(long atMs) {
                    if (!callbackIsCurrent(playbackEpoch)) return;
                    listener.onPlayback(request, "TONE", atMs, "STARTED");
                }
                @Override public void onFinished(long atMs, boolean success) {
                    if (!callbackIsCurrent(playbackEpoch)) return;
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
                listener.onPlayback(request, "HAPTIC", now, "STARTED");
            } else if (now - lastHapticAtMs < 500) suppressionReason = "haptic_cooldown";
        }
        if ((accepted & CueRequest.CHANNEL_SPEECH) != 0 && request.speech != null
                && !request.speech.isEmpty()) {
            Long last = lastSpeech.get(request.category);
            if (request.priority != 100 && last != null &&
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
        if (rendered != 0) lastAccepted.put(request.eventKey, now);
        return report(request, rendered, rendered == 0 ? "DROPPED" : "ACCEPTED", reason);
    }

    private boolean enqueueSpeech(CueRequest request) {
        if (request.priority == 100 && speaking != null && speaking.priority < request.priority) {
            CueRequest preempted = speaking;
            cancelledSpeech.add(preempted.cueId);
            renderer.stopSpeech();
            speaking = null;
            listener.onPlayback(preempted, "SPEECH", clock.nowMs(), "PREEMPTED");
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

    private boolean callbackIsCurrent(long playbackEpoch) {
        return !closed && !paused && speechEpoch == playbackEpoch;
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
            Long lastStarted = lastSpeechStarted.get(next.category);
            if (next.priority != 100 && lastStarted != null &&
                    clock.nowMs() - lastStarted < policy.dedupeWindowMs(next.category)) {
                listener.onPlayback(next, "SPEECH", clock.nowMs(), "COOLDOWN");
                continue;
            }
            final long playbackEpoch = speechEpoch;
            speaking = next;
            boolean accepted = renderer.speak(next, next.priority == 100,
                    new PlaybackCallback() {
                        @Override public void onStarted(long atMs) {
                            synchronized (CueDispatcher.this) {
                                if (cancelledSpeech.contains(next.cueId)
                                        || paused || playbackEpoch != speechEpoch) return;
                                if (atMs > next.expiresAtMs) {
                                    // A TTS engine may accept an utterance and
                                    // start it only after its short-lived cue
                                    // window has expired.  Emit the queued
                                    // terminal state the parser expects and
                                    // suppress the engine's later onDone.
                                    cancelledSpeech.add(next.cueId);
                                    if (speaking == next) speaking = null;
                                    renderer.stopSpeech();
                                    listener.onPlayback(next, "SPEECH", atMs, "EXPIRED");
                                    drainSpeech();
                                    return;
                                }
                                lastSpeechStarted.put(next.category, atMs);
                            }
                            listener.onPlayback(next, "SPEECH", atMs, "STARTED");
                        }
                        @Override public void onFinished(long atMs, boolean success) {
                            synchronized (CueDispatcher.this) {
                                if (cancelledSpeech.remove(next.cueId)) return;
                                if (paused || playbackEpoch != speechEpoch) return;
                                if (speaking == next) speaking = null;
                                listener.onPlayback(next, "SPEECH", atMs,
                                        success ? "COMPLETED" : "FAILED");
                                drainSpeech();
                            }
                        }
                    });
            if (accepted) return;
            speaking = null;
            listener.onPlayback(next, "SPEECH", clock.nowMs(), "UNAVAILABLE");
        }
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

    /** Stop all queued and active output while the capture session is paused. */
    synchronized void pause() {
        if (closed || paused) return;
        paused = true;
        speechEpoch++;
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
        lastSpeech.clear();
        lastSpeechStarted.clear();
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
        renderer.stopSpeech();
        renderer.cancelPendingTone();
        speechQueue.clear();
        speaking = null;
        lastAccepted.clear();
        lastSpeech.clear();
        lastSpeechStarted.clear();
        cancelledSpeech.clear();
    }
}
