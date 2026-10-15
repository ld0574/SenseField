package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public final class CueDispatcherTest {
    private static final class MutableClock implements CueDispatcher.Clock {
        long now;
        @Override public long nowMs() { return now; }
    }

    private static final class FakeRenderer implements CueDispatcher.Renderer {
        CueDispatcher.PlaybackCallback callback;
        CueDispatcher.PlaybackCallback toneCallback;
        boolean stopped;
        boolean autoStartSpeech = true;
        boolean autoFinishSpeech;
        boolean failImmediately;
        boolean autoStartTone = true;
        boolean acceptHaptics = true;
        int tones;
        int haptics;
        int assistantCancels;
        boolean preparedSpeech;
        boolean acceptSynchronized = true;
        int synchronizedStarts;
        String assistantRemainder;
        final List<String> started = new ArrayList<>();
        final Map<String, CueDispatcher.PlaybackCallback> speechCallbacks = new HashMap<>();
        final Map<String, CueRequest> speechRequests = new HashMap<>();
        @Override public boolean playTone(CueRequest request,
                                          CueDispatcher.PlaybackCallback callback) {
            tones++;
            toneCallback = callback;
            if (autoStartTone) callback.onStarted(request.createdAtMs);
            return true;
        }
        @Override public boolean vibrate(CueRequest request) {
            haptics++;
            return acceptHaptics;
        }
        @Override public boolean speak(CueRequest request, boolean interrupt,
                                       CueDispatcher.PlaybackCallback callback) {
            this.callback = callback;
            speechCallbacks.put(request.cueId, callback);
            speechRequests.put(request.cueId, request);
            started.add(request.cueId);
            if (failImmediately) { callback.onFailed(request.createdAtMs,"test_failure"); return false; }
            if (autoStartSpeech) callback.onStarted(request.createdAtMs);
            if (autoFinishSpeech) callback.onFinished(request.createdAtMs, true);
            return true;
        }
        @Override public void stopSpeech() { stopped = true; }
        @Override public boolean hasPreparedSpeech(CueRequest request) { return preparedSpeech; }
        @Override public boolean speakSynchronized(CueRequest request,
                CueDispatcher.PlaybackCallback tone, CueDispatcher.PlaybackCallback speech) {
            synchronizedStarts++;
            if (!acceptSynchronized) return false;
            toneCallback = tone;
            callback = speech;
            speechCallbacks.put(request.cueId, speech);
            started.add(request.cueId);
            if (autoStartTone) tone.onStarted(request.createdAtMs);
            if (autoStartSpeech) speech.onStarted(request.createdAtMs);
            return true;
        }
        @Override public void cancelAssistantSpeech() {
            assistantCancels++;
            stopped = true;
        }
        @Override public String yieldAssistantSpeech(CueRequest request) {
            cancelAssistantSpeech();
            return assistantRemainder == null ? request.speech : assistantRemainder;
        }
    }

    private static final class FakePolicy implements CueDispatcher.Policy {
        boolean category = true;
        int channels = 15;
        @Override public boolean categoryEnabled(CueRequest.Category ignored) { return category; }
        @Override public int enabledChannels() { return channels; }
        @Override public long dedupeWindowMs(CueRequest.Category ignored) { return 500; }
    }

    private static final class Events implements CueDispatcher.Listener {
        final List<String> events = new ArrayList<>();
        @Override public void onDispatch(CueRequest request, CueDispatcher.DispatchResult result) {
            events.add(request.cueId + ":" + result.outcome + ":" + result.reason);
        }
        @Override public void onPlayback(CueRequest request, String channel,
                                         long atMs, String result) {
            events.add(request.cueId + ":" + channel + ":" + result);
        }
    }

    private static CueRequest request(String id, String key, int priority, long expires) {
        return new CueRequest("session", id, key, "TEST",
                CueRequest.Category.PLAYER_STATE, priority, 0, expires,
                CueRequest.CHANNEL_SPEECH, 4, 0, 0, "测试");
    }

    @Test public void synchronousCompletionStillReportsAcceptedSpeech() {
        FakeRenderer renderer = new FakeRenderer(); renderer.autoFinishSpeech = true;
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, new MutableClock());
        assertTrue(dispatcher.submit(request("sync", "sync", 70, 10000)).audioQueued());
        assertFalse(dispatcher.isSpeaking());
        assertEquals(1, java.util.Collections.frequency(events.events, "sync:SPEECH:COMPLETED"));
    }

    @Test public void synchronousRendererFailureDoesNotAlsoReportUnavailable() {
        FakeRenderer renderer=new FakeRenderer();renderer.failImmediately=true;
        Events events=new Events();
        CueDispatcher dispatcher=new CueDispatcher(renderer,new FakePolicy(),events,new MutableClock());
        assertFalse(dispatcher.submit(request("failure","failure",70,10000)).audioQueued());
        assertEquals(1,java.util.Collections.frequency(events.events,"failure:SPEECH:FAILED"));
        assertFalse(events.events.contains("failure:SPEECH:UNAVAILABLE"));
        assertFalse(dispatcher.isSpeaking());
    }

    @Test public void toneCallbackReportsOneStartAndOneTerminalWithoutDispatcherLock() {
        FakeRenderer renderer=new FakeRenderer();Events events=new Events();
        CueDispatcher dispatcher=new CueDispatcher(renderer,new FakePolicy(),events,new MutableClock());
        dispatcher.submit(request("tone","tone","tone",CueRequest.Category.NEAR_ZONE,
                CueRequest.CHANNEL_TONE,10000,""));
        renderer.toneCallback.onStarted(1);renderer.toneCallback.onFinished(2,true);
        renderer.toneCallback.onFinished(3,false);renderer.toneCallback.onStarted(4);
        assertEquals(1,java.util.Collections.frequency(events.events,"tone:TONE:STARTED"));
        assertEquals(1,java.util.Collections.frequency(events.events,"tone:TONE:COMPLETED"));
        assertFalse(events.events.contains("tone:TONE:FAILED"));
    }

    @Test public void completedSpeechIgnoresRepeatedAndLateCallbacksWithoutBlockingNextCue() {
        MutableClock clock = new MutableClock(); FakeRenderer renderer = new FakeRenderer();
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
        dispatcher.submit(request("first", "first", 70, 10000));
        CueDispatcher.PlaybackCallback old = renderer.callback;
        old.onStarted(10);
        old.onFinished(100, true);
        clock.now = 1000;
        dispatcher.submit(request("next", "next", 70, 10000));
        old.onStarted(1001);
        old.onFinished(1002, false);
        assertEquals(1, java.util.Collections.frequency(events.events, "first:SPEECH:STARTED"));
        assertEquals(1, java.util.Collections.frequency(events.events, "first:SPEECH:COMPLETED"));
        assertFalse(events.events.contains("first:SPEECH:FAILED"));
        assertTrue(dispatcher.isSpeaking());
    }

    @Test public void disabledSpeechCannotStartFromDelayedRendererCallback() {
        FakeRenderer renderer = new FakeRenderer(); renderer.autoStartSpeech = false;
        FakePolicy policy = new FakePolicy(); Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, policy, events, new MutableClock());
        dispatcher.submit(request("disabled", "disabled", 70, 10000));
        policy.channels = CueRequest.CHANNEL_VISUAL;
        renderer.callback.onStarted(100);
        renderer.callback.onFinished(101, true);
        assertTrue(renderer.stopped);
        assertTrue(events.events.contains("disabled:SPEECH:SETTINGS_DISABLED"));
        assertFalse(events.events.contains("disabled:SPEECH:STARTED"));
        assertFalse(events.events.contains("disabled:SPEECH:COMPLETED"));
    }

    @Test public void lifecycleClearsGiveActiveAndQueuedSpeechOneTerminalState() {
        for (String operation : List.of("CLEAR_CATEGORY", "CLEAR_ALL", "PAUSED", "CLOSED")) {
            FakeRenderer renderer = new FakeRenderer(); Events events = new Events();
            MutableClock clock = new MutableClock();
            CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
            dispatcher.submit(request("active", "active", 70, 10000));
            CueDispatcher.PlaybackCallback old = renderer.callback;
            clock.now = 600;
            dispatcher.submit(request("queued", "queued", 70, 10000));
            switch (operation) {
                case "CLEAR_CATEGORY": dispatcher.clearCategory(CueRequest.Category.PLAYER_STATE); break;
                case "CLEAR_ALL": dispatcher.clearAll(); break;
                case "PAUSED": dispatcher.pause(); dispatcher.resume(); break;
                default: dispatcher.close();
            }
            old.onStarted(700); old.onFinished(701, true); old.onFinished(702, false);
            assertTrue(operation, events.events.contains("active:SPEECH:CANCELLED_" + operation));
            assertTrue(operation, events.events.contains("queued:SPEECH:CANCELLED_" + operation));
            assertFalse(events.events.contains("active:SPEECH:COMPLETED"));
            assertFalse(renderer.started.contains("queued"));
        }
    }

    private static CueRequest request(String id, String key, String kind,
                                      CueRequest.Category category, int channels,
                                      long expires, String speech) {
        return new CueRequest("session", id, key, kind, category, 60,
                0, expires, channels, 2, 1, 1, speech);
    }

    private static CueRequest near(String id, int episode, long expires, float pan) {
        return new CueRequest("session", id, CueEventKeys.nearZone(0, episode), "NEAR_ZONE",
                CueRequest.Category.NEAR_ZONE, NearZoneRouting.NEAR_PRIORITY, 0, expires,
                NearZoneRouting.nearChannels(false), NearZoneRouting.TONE_NEAR, 0, 0,
                NearZoneRouting.speech(4), pan);
    }

    private static CueRequest assistant(String id, long expires, java.util.function.BooleanSupplier guard) {
        return new CueRequest("session", id, id, "ASSISTANT",
                CueRequest.Category.ASSISTANT, 30, 0, expires,
                CueRequest.CHANNEL_SPEECH, 0, 0, 0, "回答", Float.NaN,
                Float.NaN, 0f, 0, guard);
    }

    private static CueRequest alert(String id, CueRequest.Category category, int priority,
                                    long expires) {
        return new CueRequest("session", id, id, "ALERT", category, priority,
                0, expires, CueRequest.CHANNEL_SPEECH, 0, 0, 0, "警报");
    }

    @Test public void cancelOneHintPreservesUnrelatedSpeechAndRejectsItsLateCompletion() {
        MutableClock clock = new MutableClock(); FakeRenderer renderer = new FakeRenderer();
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
        dispatcher.submit(request("old-hint", "hint1", 70, 10000));
        CueDispatcher.PlaybackCallback old = renderer.speechCallbacks.get("old-hint");
        clock.now = 2000;
        dispatcher.submit(request("status", "status", 70, 10000));
        dispatcher.cancelCue("old-hint", "BOARD_CHANGED");
        assertTrue(events.events.contains("old-hint:SPEECH:CANCELLED_BOARD_CHANGED"));
        assertTrue(renderer.started.contains("status"));
        clock.now = 4000;
        dispatcher.submit(request("next-hint", "hint2", 70, 10000));
        dispatcher.cancelCue("next-hint", "REPLACED");
        old.onFinished(4500, true);
        assertFalse(events.events.contains("old-hint:SPEECH:COMPLETED"));
        assertFalse(renderer.started.contains("next-hint"));
        assertFalse(events.events.contains("status:SPEECH:CANCELLED_REPLACED"));
    }

    @Test public void preparedNearToneAndSpeechUseOneSubmissionAndKeepHaptics() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        renderer.preparedSpeech = true;
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
        CueDispatcher.DispatchResult result = dispatcher.submit(new CueRequest("session", "paired",
                "paired", "NEAR_ZONE", CueRequest.Category.NEAR_ZONE,
                NearZoneRouting.NEAR_PRIORITY, 0, 1200, NearZoneRouting.nearChannels(true),
                NearZoneRouting.TONE_NEAR, 0, 0, NearZoneRouting.speech(4), -1f));
        assertEquals(7, result.acceptedChannels);
        assertEquals(1, renderer.synchronizedStarts);
        assertEquals(0, renderer.tones);
        assertEquals(1, renderer.haptics);
        assertEquals(List.of("paired"), renderer.started);
        assertTrue(events.events.contains("paired:TONE:STARTED"));
        assertTrue(events.events.contains("paired:SPEECH:STARTED"));
    }

    @Test public void cacheMissOrRejectedPairFallsBackToImmediateToneAndNormalSpeech() {
        for (boolean cached : new boolean[]{false, true}) {
            FakeRenderer renderer = new FakeRenderer();
            renderer.preparedSpeech = cached;
            renderer.acceptSynchronized = false;
            CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                    new Events(), new MutableClock());
            assertTrue(dispatcher.submit(near("fallback", 1, 1200, 0)).audioQueued());
            assertEquals(cached ? 1 : 0, renderer.synchronizedStarts);
            assertEquals(1, renderer.tones);
            assertEquals(List.of("fallback"), renderer.started);
        }
    }

    @Test public void preparedPairNeverAddsAChannelThatThePlayerDisabled() {
        for (int channel : new int[]{CueRequest.CHANNEL_TONE, CueRequest.CHANNEL_SPEECH}) {
            FakeRenderer renderer = new FakeRenderer();
            renderer.preparedSpeech = true;
            FakePolicy policy = new FakePolicy();
            policy.channels = channel;
            CueDispatcher dispatcher = new CueDispatcher(renderer, policy, new Events(), new MutableClock());
            assertEquals(channel, dispatcher.submit(near("single", 1, 1200, 0)).acceptedChannels);
            assertEquals(0, renderer.synchronizedStarts);
            assertEquals(channel == CueRequest.CHANNEL_TONE ? 1 : 0, renderer.tones);
            assertEquals(channel == CueRequest.CHANNEL_SPEECH ? 1 : 0, renderer.started.size());
        }
    }

    @Test public void busyPreparedNearCueStillPlaysImmediateTonesAndKeepsOnlyLatestWaitingVoice() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        renderer.preparedSpeech = true;
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), new Events(), clock);
        dispatcher.submit(near("first", 1, 1200, 0));
        clock.now = 100;
        dispatcher.submit(near("second", 2, 1300, 0));
        clock.now = 200;
        dispatcher.submit(near("third", 3, 1400, 0));
        assertEquals(1, renderer.synchronizedStarts);
        assertEquals(2, renderer.tones);
        assertEquals(List.of("third"), dispatcher.pendingCueIdsForTest());
        renderer.speechCallbacks.get("first").onFinished(300, true);
        assertEquals(List.of("first", "third"), renderer.started);
    }

    @Test public void pairedLateCallbacksCannotStartOrDrainAfterPauseClearOrClose() {
        for (int cancellation = 0; cancellation < 3; cancellation++) {
            FakeRenderer renderer = new FakeRenderer();
            renderer.preparedSpeech = true;
            renderer.autoStartTone = false;
            renderer.autoStartSpeech = false;
            Events events = new Events();
            CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, new MutableClock());
            dispatcher.submit(near("cancelled", 1, 1200, 0));
            CueDispatcher.PlaybackCallback tone = renderer.toneCallback, speech = renderer.callback;
            if (cancellation == 0) { dispatcher.pause(); dispatcher.resume(); }
            else if (cancellation == 1) dispatcher.clearCategory(CueRequest.Category.NEAR_ZONE);
            else dispatcher.close();
            tone.onStarted(100); speech.onStarted(100);
            tone.onFinished(200, true); speech.onFinished(200, true);
            assertFalse(events.events.contains("cancelled:TONE:STARTED"));
            assertFalse(events.events.contains("cancelled:SPEECH:STARTED"));
            assertFalse(dispatcher.isSpeaking());
            assertTrue(dispatcher.pendingCueIdsForTest().isEmpty());
        }
    }

    @Test public void preparedAlertExplicitlyPreemptsAssistantAndIgnoresItsLateResponse() {
        FakeRenderer renderer = new FakeRenderer();
        renderer.preparedSpeech = true;
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, new MutableClock());
        dispatcher.submit(assistant("reply", 5000, () -> true));
        CueDispatcher.PlaybackCallback old = renderer.callback;
        dispatcher.submit(near("urgent", 1, 1200, 0));
        assertEquals(1, renderer.assistantCancels);
        assertEquals(1, renderer.synchronizedStarts);
        old.onFinished(200, true);
        assertFalse(events.events.contains("reply:SPEECH:COMPLETED"));
        assertTrue(dispatcher.isSpeaking());
    }

    @Test public void nearZoneCuesAreNotReplayedAndDistinctEpisodesBothPlay() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
        clock.now = 1300;
        assertEquals(0, dispatcher.submit(near("late", 1, 1200, -0.7f)).acceptedChannels);
        assertEquals(0, renderer.tones);
        clock.now = 1000;
        CueRequest first = near("first", 2, 2200, -0.7f);
        assertTrue(first.hasPan());
        assertTrue(dispatcher.submit(first).audioQueued());
        // Episode keys, not a session cooldown, separate two approaches.
        clock.now = 1600;
        assertTrue(dispatcher.submit(near("second", 3, 2800, 0.7f)).audioQueued());
        assertEquals(2, renderer.tones);
        assertFalse(events.events.contains("late:TONE:STARTED"));
    }

    @Test public void expiryCategoryAndDedupeAreAppliedBeforePlayback() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        FakePolicy policy = new FakePolicy();
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, policy, events, clock);
        clock.now = 100;
        assertTrue(dispatcher.submit(request("one", "same", 60, 1000)).audioQueued());
        assertEquals("DROPPED", dispatcher.submit(request("two", "same", 60, 1000)).outcome);
        clock.now = 2000;
        assertEquals("expired", dispatcher.submit(request("old", "old", 60, 1000)).reason);
        policy.category = false;
        assertEquals("category_disabled",
                dispatcher.submit(request("off", "off", 60, 3000)).reason);
    }

    @Test public void compactPresetKeepsVisionVisualAndPlayerSpeechAndHaptics() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        int compactChannels = CueRequest.CHANNEL_SPEECH
                | CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL;
        CueDispatcher.Policy compactPolicy = new CueDispatcher.Policy() {
            @Override public boolean categoryEnabled(CueRequest.Category ignored) {
                return true;
            }
            @Override public int enabledChannels() { return compactChannels; }
            @Override public int enabledChannels(CueRequest.Category category) {
                return CueSettings.channelsForCategory(compactChannels,
                        CueSettings.PRESET_COMPACT, category);
            }
            @Override public long dedupeWindowMs(CueRequest.Category ignored) { return 500; }
        };
        CueDispatcher dispatcher = new CueDispatcher(renderer, compactPolicy,
                new Events(), clock);

        int allChannels = CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH
                | CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL;
        CueDispatcher.DispatchResult vision = dispatcher.submit(request(
                "vision", "vision:track:1", "VISION_DISAPPEAR",
                CueRequest.Category.VISION_MEMORY, allChannels, 1500, "敌人消失"));
        assertEquals(CueRequest.CHANNEL_VISUAL, vision.acceptedChannels);
        assertEquals(0, renderer.tones);
        assertEquals(0, renderer.haptics);
        assertTrue(renderer.started.isEmpty());

        CueDispatcher.DispatchResult player = dispatcher.submit(request(
                "dead", "player:dead", "PLAYER_DEAD",
                CueRequest.Category.PLAYER_STATE,
                CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC,
                2000, "你已阵亡"));
        assertEquals(CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC,
                player.acceptedChannels);
        assertEquals(1, renderer.haptics);
        assertEquals(List.of("dead"), renderer.started);
    }

    @Test public void criticalSpeechPreemptsLowerPrioritySpeech() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), new Events(), clock);
        dispatcher.submit(request("normal", "normal", 60, 1000));
        dispatcher.submit(request("critical", "critical", 100, 1000));
        assertTrue(renderer.stopped);
        assertFalse(dispatcher.pendingCueIdsForTest().contains("critical"));
    }

    @Test public void spokenAlertsYieldAndResumeAssistantRegardlessOfPriority() {
        for (CueRequest.Category category : List.of(CueRequest.Category.NEAR_ZONE,
                CueRequest.Category.PERIPHERAL_THREAT, CueRequest.Category.DANGER)) {
            MutableClock clock = new MutableClock();
            FakeRenderer renderer = new FakeRenderer();
            renderer.autoStartSpeech = false;
            Events events = new Events();
            CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
            dispatcher.submit(assistant("assistant", 5000, () -> true));
            CueDispatcher.PlaybackCallback old = renderer.callback;
            assertTrue(dispatcher.isSpeaking());

            dispatcher.submit(alert("alert", category, 1, 5000));

            assertEquals(1, renderer.assistantCancels);
            assertEquals(List.of("assistant", "alert"), renderer.started);
            assertTrue(dispatcher.isSpeaking());
            assertTrue(events.events.contains("assistant:SPEECH:YIELDED"));
            assertEquals(List.of("assistant"), dispatcher.pendingCueIdsForTest());
            old.onFinished(10, true);
            assertTrue("late assistant callback must not clear the alert", dispatcher.isSpeaking());
            renderer.speechCallbacks.get("alert").onFinished(11, true);
            assertEquals(List.of("assistant", "alert", "assistant"), renderer.started);
            old.onStarted(12);
            old.onFinished(13, true);
            assertTrue("old callbacks must not finish a resumed attempt", dispatcher.isSpeaking());
            renderer.speechCallbacks.get("assistant").onStarted(14);
            assertTrue(events.events.contains("assistant:SPEECH:RESUMED"));
            renderer.speechCallbacks.get("assistant").onFinished(15, true);
            assertFalse(dispatcher.isSpeaking());
            assertEquals(1, java.util.Collections.frequency(events.events, "assistant:SPEECH:COMPLETED"));
        }
    }

    @Test public void assistantFreshnessIsCheckedAtTheActualPlaybackStart() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        renderer.autoStartSpeech = false;
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
        boolean[] valid = {true};
        dispatcher.submit(assistant("stale", 5000, () -> valid[0]));
        valid[0] = false;

        renderer.speechCallbacks.get("stale").onStarted(100);

        assertFalse(dispatcher.isSpeaking());
        assertTrue(renderer.stopped);
        assertTrue(events.events.contains("stale:SPEECH:EXPIRED"));
        renderer.speechCallbacks.get("stale").onFinished(101, true);
        assertFalse(events.events.contains("stale:SPEECH:COMPLETED"));
    }

    @Test public void userBargeCancelsOnlyAssistantSpeechAndOldCallbacksStayDiscarded() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        renderer.autoStartSpeech = false;
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                new Events(), clock);
        dispatcher.submit(assistant("assistant", 5000, () -> true));
        CueDispatcher.PlaybackCallback old = renderer.speechCallbacks.get("assistant");

        dispatcher.cancelAssistantSpeech();

        assertFalse(dispatcher.isSpeaking());
        assertEquals(1, renderer.assistantCancels);
        old.onStarted(10);
        old.onFinished(11, true);
        assertFalse(dispatcher.isSpeaking());
    }

    @Test public void toneAndHapticAlertsDoNotDiscardAssistantSpeech() {
        for (int channels : new int[]{CueRequest.CHANNEL_TONE, CueRequest.CHANNEL_HAPTIC,
                CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC}) {
            FakeRenderer renderer = new FakeRenderer();
            CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                    new Events(), new MutableClock());
            dispatcher.submit(assistant("assistant", 5000, () -> true));
            CueDispatcher.PlaybackCallback original = renderer.callback;
            dispatcher.submit(new CueRequest("session", "near", "near", "NEAR_ZONE",
                    CueRequest.Category.NEAR_ZONE, 96, 0, 5000, channels,
                    NearZoneRouting.TONE_NEAR, 0, 0, null));
            assertEquals(0, renderer.assistantCancels);
            assertTrue(dispatcher.isSpeaking());
            assertTrue(dispatcher.pendingCueIdsForTest().isEmpty());
            original.onFinished(10, true);
            assertFalse(dispatcher.isSpeaking());
        }
    }

    @Test public void continuationPreservesOriginalExpiryAndOwnershipAndUsesUncompletedText() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        renderer.assistantRemainder = "只有敌方法伤较多时才补魔抗。";
        boolean[] valid = {true};
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), new Events(), clock);
        dispatcher.submit(assistant("reply", 5000, () -> valid[0]));
        clock.now = 100;
        dispatcher.submit(alert("near", CueRequest.Category.NEAR_ZONE, 96, 1000));
        clock.now = 500;
        renderer.speechCallbacks.get("near").onFinished(500, true);
        CueRequest continuation = renderer.speechRequests.get("reply");
        assertEquals(renderer.assistantRemainder, continuation.speech);
        assertEquals(5000, continuation.expiresAtMs);
        assertEquals(0, continuation.createdAtMs);
        assertTrue(continuation.playbackAllowedAt(500));
        valid[0] = false;
        assertFalse(continuation.playbackAllowedAt(501));
    }

    @Test public void expiredOrInvalidatedAnswerCannotResumeAfterAnAlert() {
        for (boolean invalidate : new boolean[]{false, true}) {
            MutableClock clock = new MutableClock();
            FakeRenderer renderer = new FakeRenderer();
            Events events = new Events();
            boolean[] valid = {true};
            CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
            dispatcher.submit(assistant("reply", 500, () -> valid[0]));
            dispatcher.submit(alert("near", CueRequest.Category.NEAR_ZONE, 96, 1500));
            clock.now = invalidate ? 200 : 600;
            if (invalidate) valid[0] = false;
            renderer.speechCallbacks.get("near").onFinished(clock.now, true);
            assertEquals(List.of("reply", "near"), renderer.started);
            assertFalse(dispatcher.isSpeaking());
            assertTrue(events.events.contains("reply:SPEECH:EXPIRED"));
        }
    }

    @Test public void userBargeAndLifecycleChangesCancelYieldedRepliesWithoutCancellingAlerts() {
        for (int cancellation = 0; cancellation < 4; cancellation++) {
            FakeRenderer renderer = new FakeRenderer();
            CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                    new Events(), new MutableClock());
            dispatcher.submit(assistant("reply", 5000, () -> true));
            dispatcher.submit(alert("near", CueRequest.Category.NEAR_ZONE, 96, 1500));
            if (cancellation == 0) dispatcher.cancelAssistantSpeech();
            else if (cancellation == 1) dispatcher.clearCategory(CueRequest.Category.ASSISTANT);
            else if (cancellation == 2) { dispatcher.pause(); dispatcher.resume(); }
            else dispatcher.close();
            assertTrue(dispatcher.pendingCueIdsForTest().isEmpty());
            renderer.speechCallbacks.get("near").onFinished(100, true);
            assertEquals(List.of("reply", "near"), renderer.started);
            assertFalse(dispatcher.isSpeaking());
        }
    }

    @Test public void repeatedAlertsKeepOneWaitingReplyAndDoNotDiscardUnstartedFeedback() {
        FakeRenderer renderer = new FakeRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                new Events(), new MutableClock());
        dispatcher.submit(alert("near-1", CueRequest.Category.NEAR_ZONE, 96, 5000));
        dispatcher.submit(assistant("feedback", 5000, () -> true));
        dispatcher.submit(alert("near-2", CueRequest.Category.NEAR_ZONE, 96, 5000));
        dispatcher.submit(alert("near-3", CueRequest.Category.NEAR_ZONE, 96, 5000));
        assertEquals(List.of("near-3", "feedback"), dispatcher.pendingCueIdsForTest());
        renderer.speechCallbacks.get("near-1").onFinished(100, true);
        renderer.speechCallbacks.get("near-3").onFinished(200, true);
        assertEquals(List.of("near-1", "near-3", "feedback"), renderer.started);
        assertEquals(0, renderer.assistantCancels);
    }

    @Test public void partiallyExpiredSpeechIsNeverReportedAsCompleted() {
        FakeRenderer renderer = new FakeRenderer();
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, new MutableClock());
        dispatcher.submit(assistant("reply", 5000, () -> true));
        CueDispatcher.PlaybackCallback callback = renderer.callback;
        callback.onIncomplete(6000, "PARTIAL_EXPIRED");
        callback.onFinished(6001, true);
        assertTrue(events.events.contains("reply:SPEECH:PARTIAL_EXPIRED"));
        assertFalse(events.events.contains("reply:SPEECH:COMPLETED"));
        assertFalse(dispatcher.isSpeaking());
    }

    @Test public void criticalHapticBypassesCooldown() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                new Events(), clock);
        CueRequest normal = new CueRequest("session", "normal-haptic", "normal-haptic",
                "NORMAL", CueRequest.Category.PLAYER_STATE, 60, 100, 1000,
                CueRequest.CHANNEL_HAPTIC, 0, 0, 1, null);
        assertEquals(CueRequest.CHANNEL_HAPTIC,
                dispatcher.submit(normal).acceptedChannels);
        clock.now = 200;
        CueRequest critical = new CueRequest("session", "critical-haptic", "critical-haptic",
                "CRITICAL", CueRequest.Category.PLAYER_STATE, 100, 200, 2000,
                CueRequest.CHANNEL_HAPTIC, 0, 0, 1, null);
        assertEquals(CueRequest.CHANNEL_HAPTIC,
                dispatcher.submit(critical).acceptedChannels);
        assertEquals(2, renderer.haptics);
    }

    @Test public void pauseStopsAndClearsSpeechUntilResume() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                new Events(), clock);
        dispatcher.submit(request("active", "active", 60, 10000));
        clock.now = 501;
        dispatcher.submit(request("queued", "queued", 40, 10000));
        dispatcher.pause();

        assertTrue(renderer.stopped);
        assertTrue(dispatcher.pendingCueIdsForTest().isEmpty());
        assertEquals("paused",
                dispatcher.submit(request("during", "during", 60, 10000)).reason);

        dispatcher.resume();
        clock.now = 1002;
        assertTrue(dispatcher.submit(request("after", "after", 60, 10000)).audioQueued());
        assertTrue(renderer.started.contains("after"));
    }

    @Test public void clearingVisionCategoryStopsItsPendingSpeech() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        renderer.autoStartSpeech = false;
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                new Events(), clock);
        dispatcher.submit(request("vision", "vision", "VISION_APPEAR",
                CueRequest.Category.VISION_MEMORY, CueRequest.CHANNEL_SPEECH,
                10000, "发现敌方头像"));
        assertFalse(renderer.stopped);
        dispatcher.clearCategory(CueRequest.Category.VISION_MEMORY);
        assertTrue(dispatcher.pendingCueIdsForTest().isEmpty());
        assertTrue(renderer.stopped);
    }

    @Test public void clearingSpeakingCategoryContinuesWithOtherSpeech() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        renderer.autoStartSpeech = false;
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                new Events(), clock);
        dispatcher.submit(request("vision", "vision", "VISION_APPEAR",
                CueRequest.Category.VISION_MEMORY, CueRequest.CHANNEL_SPEECH,
                10000, "发现敌方头像"));
        dispatcher.submit(request("danger", "danger", "DANGER_PING",
                CueRequest.Category.DANGER, CueRequest.CHANNEL_SPEECH,
                10000, "危险信号"));

        dispatcher.clearCategory(CueRequest.Category.VISION_MEMORY);

        assertEquals(List.of("vision", "danger"), renderer.started);
        assertTrue(dispatcher.pendingCueIdsForTest().isEmpty());
    }

    @Test public void clearAllDropsEveryCategoryAndResetsHapticWindow() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        renderer.autoStartSpeech = false;
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                new Events(), clock);
        dispatcher.submit(request("vision", "vision", "VISION_APPEAR",
                CueRequest.Category.VISION_MEMORY, CueRequest.CHANNEL_SPEECH,
                10000, "发现敌方头像"));
        dispatcher.submit(request("danger", "danger", "DANGER_PING",
                CueRequest.Category.DANGER, CueRequest.CHANNEL_SPEECH,
                10000, "危险信号"));
        CueRequest beforeReset = new CueRequest("session", "before-haptic", "before-haptic",
                "PLAYER_DEAD", CueRequest.Category.PLAYER_STATE, 100, 0, 10000,
                CueRequest.CHANNEL_HAPTIC, 0, 0, 1, null);
        assertEquals(CueRequest.CHANNEL_HAPTIC, dispatcher.submit(beforeReset).acceptedChannels);

        dispatcher.clearAll();

        assertTrue(dispatcher.pendingCueIdsForTest().isEmpty());
        assertTrue(renderer.stopped);
        CueRequest afterReset = new CueRequest("session", "after-haptic", "after-haptic",
                "PLAYER_DEAD", CueRequest.Category.PLAYER_STATE, 60, 0, 10000,
                CueRequest.CHANNEL_HAPTIC, 0, 0, 1, null);
        assertEquals(CueRequest.CHANNEL_HAPTIC, dispatcher.submit(afterReset).acceptedChannels);
        assertTrue(dispatcher.submit(request("after", "vision", "VISION_APPEAR",
                CueRequest.Category.VISION_MEMORY, CueRequest.CHANNEL_SPEECH,
                10000, "重新发现")).audioQueued());
    }

    @Test public void lateToneCallbackAfterPauseDoesNotPollutePlaybackLog() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
        CueRequest tone = new CueRequest("session", "tone", "tone", "TONE",
                CueRequest.Category.VISION_MEMORY, 40, 0, 10000,
                CueRequest.CHANNEL_TONE, 2, 1, 1, null);

        dispatcher.submit(tone);
        int beforePause = events.events.size();
        dispatcher.pause();
        renderer.toneCallback.onFinished(101, true);

        assertEquals(beforePause, events.events.size());
    }

    @Test public void lateToneCallbackAfterCategoryClearDoesNotPollutePlaybackLog() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
        CueRequest tone = new CueRequest("session", "tone-category", "tone-category", "TONE",
                CueRequest.Category.VISION_MEMORY, 40, 0, 10000,
                CueRequest.CHANNEL_TONE, 2, 1, 1, null);

        dispatcher.submit(tone);
        int beforeClear = events.events.size();
        dispatcher.clearCategory(CueRequest.Category.VISION_MEMORY);
        renderer.toneCallback.onFinished(101, true);

        assertEquals(beforeClear, events.events.size());
    }

    @Test public void toneOnlyAndHapticAlertsStartTheAssistantQuietPeriod() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                new Events(), clock);

        CueRequest toneOnlyNear = request("near-tone", "near-tone", "NEAR_ZONE",
                CueRequest.Category.NEAR_ZONE, CueRequest.CHANNEL_TONE, 1000, "");
        assertEquals(CueRequest.CHANNEL_TONE, dispatcher.submit(toneOnlyNear).acceptedChannels);
        assertEquals(0, dispatcher.recentAlertAtMs());

        clock.now = 100;
        CueRequest hapticOnlyPeripheral = request("edge-haptic", "edge-haptic",
                "PERIPHERAL_THREAT", CueRequest.Category.PERIPHERAL_THREAT,
                CueRequest.CHANNEL_HAPTIC, 1000, "");
        assertEquals(CueRequest.CHANNEL_HAPTIC,
                dispatcher.submit(hapticOnlyPeripheral).acceptedChannels);
        assertEquals(100, dispatcher.recentAlertAtMs());
    }

    @Test public void suppressedOrStaleToneDoesNotStartTheAssistantQuietPeriod() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        renderer.autoStartTone = false;
        FakePolicy policy = new FakePolicy();
        CueDispatcher dispatcher = new CueDispatcher(renderer, policy, new Events(), clock);
        CueRequest toneOnlyNear = request("near-tone", "near-tone", "NEAR_ZONE",
                CueRequest.Category.NEAR_ZONE, CueRequest.CHANNEL_TONE, 1000, "");

        policy.channels = 0;
        assertEquals(0, dispatcher.submit(toneOnlyNear).acceptedChannels);
        assertEquals(Long.MIN_VALUE, dispatcher.recentAlertAtMs());

        policy.channels = 15;
        assertEquals(CueRequest.CHANNEL_TONE, dispatcher.submit(toneOnlyNear).acceptedChannels);
        assertEquals(Long.MIN_VALUE, dispatcher.recentAlertAtMs());
        CueDispatcher.PlaybackCallback lateStart = renderer.toneCallback;
        dispatcher.clearCategory(CueRequest.Category.NEAR_ZONE);
        lateStart.onStarted(10);

        assertEquals(Long.MIN_VALUE, dispatcher.recentAlertAtMs());
    }

    @Test public void speechStartingAfterExpiryIsLoggedAsExpiredAndCannotComplete() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
        renderer.autoStartSpeech = false;
        dispatcher.submit(request("late", "late", 60, 100));

        renderer.callback.onStarted(101);
        int afterExpiry = events.events.size();
        renderer.callback.onFinished(102, true);

        assertTrue(renderer.stopped);
        assertEquals(afterExpiry, events.events.size());
        assertTrue(events.events.get(afterExpiry - 1).endsWith(":SPEECH:EXPIRED"));
    }

    @Test public void queueIsBoundedAndHigherPriorityEvictsLowestOldest() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), new Events(), clock);
        dispatcher.submit(request("active", "active", 60, 100000));
        for (int index = 0; index < CueDispatcher.MAX_PENDING; index++) {
            clock.now += 501;
            dispatcher.submit(request("low" + index, "low" + index, 40, 100000));
        }
        assertEquals(CueDispatcher.MAX_PENDING, dispatcher.pendingCueIdsForTest().size());
        clock.now += 501;
        dispatcher.submit(request("high", "high", 80, 100000));
        assertEquals(CueDispatcher.MAX_PENDING, dispatcher.pendingCueIdsForTest().size());
        assertTrue(dispatcher.pendingCueIdsForTest().contains("high"));
        assertFalse(dispatcher.pendingCueIdsForTest().contains("low0"));
    }

    @Test public void equalPrioritySpeechIsFifo() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), new Events(), clock);
        dispatcher.submit(request("active", "active", 60, 10000));
        clock.now = 501;
        dispatcher.submit(request("first", "first", 40, 10000));
        clock.now = 1002;
        dispatcher.submit(request("second", "second", 40, 10000));

        CueDispatcher.PlaybackCallback active = renderer.callback;
        active.onFinished(1100, true);
        CueDispatcher.PlaybackCallback first = renderer.callback;
        first.onFinished(1200, true);

        assertEquals(List.of("active", "first", "second"), renderer.started);
    }

    @Test public void latestNearSpeechReplacesOlderPendingNearSpeech() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        renderer.autoStartSpeech = false;
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);

        dispatcher.submit(near("active", 1, 10000, -0.7f));
        clock.now = 10;
        dispatcher.submit(near("old-pending", 2, 10000, 0f));
        clock.now = 20;
        dispatcher.submit(near("new-pending", 3, 10000, 0.7f));

        assertFalse(dispatcher.pendingCueIdsForTest().contains("old-pending"));
        assertTrue(dispatcher.pendingCueIdsForTest().contains("new-pending"));
        assertTrue(events.events.contains("old-pending:SPEECH:QUEUE_REPLACED"));
    }

    @Test public void nearSpeechDoesNotUseOldStartedCooldown() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(),
                new Events(), clock);
        assertTrue(dispatcher.submit(near("first", 1, 10000, 0f)).audioQueued());
        clock.now = 10;
        assertTrue(dispatcher.submit(near("second", 2, 10000, 0f)).audioQueued());
    }

    @Test public void lowerPriorityReturnWithinDedupeWindowStillSpeaksAfterCurrentCue() {
        MutableClock clock = new MutableClock();
        FakeRenderer renderer = new FakeRenderer();
        Events events = new Events();
        CueDispatcher dispatcher = new CueDispatcher(renderer, new FakePolicy(), events, clock);
        CueRequest first = new CueRequest("session", "first", CueEventKeys.nearZone(0, 1),
                "NEAR_ZONE", CueRequest.Category.NEAR_ZONE, 97, 0, 1200,
                NearZoneRouting.nearChannels(false), NearZoneRouting.TONE_NEAR, 0, 0,
                "右方有敌人", 1f);
        assertTrue(dispatcher.submit(first).audioQueued());
        CueDispatcher.PlaybackCallback firstPlayback = renderer.callback;

        clock.now = 200;
        CueRequest returned = new CueRequest("session", "returned", CueEventKeys.nearZone(0, 2),
                "NEAR_ZONE", CueRequest.Category.NEAR_ZONE, 85, 200, 1400,
                NearZoneRouting.nearChannels(false), NearZoneRouting.TONE_NEAR, 0, 0,
                "左上有敌人", -.7f);
        CueDispatcher.DispatchResult result = dispatcher.submit(returned);
        assertTrue((result.acceptedChannels & CueRequest.CHANNEL_SPEECH) != 0);
        assertEquals(2, renderer.tones);
        assertTrue(dispatcher.pendingCueIdsForTest().contains("returned"));
        assertFalse(renderer.stopped);

        clock.now = 800;
        firstPlayback.onFinished(800, true);
        assertEquals(List.of("first", "returned"), renderer.started);
        assertTrue(events.events.contains("returned:SPEECH:STARTED"));
        assertFalse(events.events.contains("returned:DROPPED:deduplicated"));
    }
}
