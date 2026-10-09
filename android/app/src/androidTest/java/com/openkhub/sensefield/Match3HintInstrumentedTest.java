package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native Bitmap, real window/input and controlled TTS callbacks; no acoustic claims. */
@RunWith(AndroidJUnit4.class)
public final class Match3HintInstrumentedTest {
    private static Context context() { return ApplicationProvider.getApplicationContext(); }
    private static Object get(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }

    @Test public void diagnosticBoardsAreSevenBySevenAndTheirCellCentersAgreeWithTouch() throws Exception {
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        for (String file : new String[]{"screen-13-89250125.jpg", "screen-52-89282114.jpg", "screen-169-89377034.jpg"}) {
            java.io.InputStream input;
            try { input = test.getAssets().open(file); }
            catch (java.io.IOException absent) { Assume.assumeNoException(absent); return; }
            Bitmap frame;
            try (input) { frame = BitmapFactory.decodeStream(input); }
            try {
                BoardGeometry g = Match3Sampler.autoDetectGeometry(frame);
                assertNotNull(file, g); assertEquals(7, g.rows); assertEquals(7, g.cols);
                assertTrue(g.left >= 65 && g.left <= 72);
                assertTrue(g.top >= 340 && g.top <= 350);
                assertTrue(g.right >= 400 && g.right <= 410);
                assertTrue(g.bottom >= 675 && g.bottom <= 685);
                Match3Sampler sampler = new Match3Sampler(context(), g);
                char[][] matrix = sampler.sample(frame);
                for (int row = 0; row < 7; row++) for (int col = 0; col < 7; col++) {
                    int[] hit = sampler.touchRead(frame, g.centerX(col), g.centerY(row));
                    assertArrayEquals(new int[]{row, col, matrix[row][col]}, hit);
                }
            } finally { frame.recycle(); }
        }
    }

    private static final class Engine extends TextToSpeech {
        UtteranceProgressListener progress;
        String id;
        int stops, submissions;
        String spoken;
        Engine() { super(context(), status -> { }); }
        @Override public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
            progress = listener; return SUCCESS;
        }
        @Override public int speak(CharSequence text, int queue, Bundle parameters, String utteranceId) {
            id = utteranceId; spoken = text.toString(); submissions++; return SUCCESS;
        }
        @Override public int stop() { stops++; return SUCCESS; }
    }
    @Test public void independentAxesCoverSixThroughNineAndRectangularBoards() {
        for (boolean landscape : new boolean[]{false, true})
        for (int rows = 6; rows <= 9; rows++) for (int cols = 6; cols <= 9; cols++) {
            Bitmap frame = Bitmap.createBitmap(landscape ? 1800 : 1024, landscape ? 1024 : 1800,
                    Bitmap.Config.ARGB_8888);
            try {
                Canvas c = new Canvas(frame); Paint p = new Paint(); c.drawColor(0xff33c9ef);
                p.setColor(0xff1e2a58);
                c.drawRect(150, 400, 150 + cols * 68, 400 + rows * 68, p);
                p.setColor(0xffe4a430);
                for (int row = 0; row < rows; row++) for (int col = 0; col < cols; col++)
                    c.drawCircle(150 + col * 68 + 34, 400 + row * 68 + 34, 29, p);
                BoardGeometry g = Match3Sampler.autoDetectGeometry(frame);
                assertNotNull(rows + "x" + cols, g); assertEquals(rows, g.rows); assertEquals(cols, g.cols);
            } finally { frame.recycle(); }
        }
    }
    @Test public void reusedTilePixelsGiveExactlyTheOriginalCenterAndCornerResults() throws Exception {
        Method original=Match3Sampler.class.getDeclaredMethod("classifyBoardCell",Bitmap.class,
                int.class,int.class,int.class,int.class,int.class,java.util.List.class);
        original.setAccessible(true);
        Method buffered=Match3Sampler.class.getDeclaredMethod("classifyBufferedCell",Bitmap.class,int[].class,
                int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class);
        buffered.setAccessible(true);
        Bitmap frame=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        java.util.Random random=new java.util.Random(1010);
        try {
            int[] pixels=new int[128*128];for(int i=0;i<pixels.length;i++)pixels[i]=0xff000000|random.nextInt(0x1000000);
            frame.setPixels(pixels,0,128,0,0,128,128);
            for(int width:new int[]{5,8,17,33,48,64})for(int offset:new int[]{0,1,11}) {
                int height=width+1,x=width/2,y=height/2,half=Math.max(3,width/8);
                int[] tile=new int[width*height];frame.getPixels(tile,0,width,offset,offset,width,height);
                assertEquals("width="+width+" origin="+offset,
                        original.invoke(null,frame,offset+x,offset+y,width,height,half,java.util.Collections.emptyList()),
                        buffered.invoke(null,frame,tile,width,height,x,y,width,height,half,offset+x,offset+y));
            }
        } finally {frame.recycle();}
    }
    private static final class Result implements CueDispatcher.PlaybackCallback {
        final CountDownLatch ended = new CountDownLatch(1);
        boolean success; String reason;
        public void onStarted(long at) { }
        public void onFinished(long at, boolean success) { this.success = success; ended.countDown(); }
        public void onFailed(long at, String reason) { this.reason = reason; onFinished(at, false); }
    }
    private static CuePlayer player(Engine engine) throws Exception {
        CuePlayer p = new CuePlayer(context());
        set(p, "tts", engine); set(p, "ttsReady", true); set(p, "offlineTtsReady", true);
        Method install = CuePlayer.class.getDeclaredMethod("installTtsListener", TextToSpeech.class);
        install.setAccessible(true); install.invoke(p, engine);
        return p;
    }
    private static CueRequest speech(String id, String text) {
        long now = SystemClock.elapsedRealtime();
        return new CueRequest("test", id, id, "消消乐交换提示", CueRequest.Category.SYSTEM, 70,
                now, now + 30000, CueRequest.CHANNEL_SPEECH, 0, 0, 0, text);
    }
    @Test public void longAndSlowSpeechCanFinishAfterTheOldFourSecondCutoff() throws Exception {
        Engine engine = new Engine(); CuePlayer p = player(engine); Result result = new Result();
        try {
            assertTrue(p.speak(speech("long", "右下区域，第五行，第四列和第五列交换。请先找到标出的两颗棋子。"), false, result));
            engine.progress.onStart(engine.id);
            assertFalse(result.ended.await(4400, TimeUnit.MILLISECONDS));
            assertEquals(0, engine.stops);
            engine.progress.onDone(engine.id);
            assertTrue(result.ended.await(1, TimeUnit.SECONDS)); assertTrue(result.success);
        } finally { p.close(); }
    }
    @Test public void noStartAndMissingCompletionHaveDifferentReasons() throws Exception {
        for (boolean started : new boolean[]{false, true}) {
            Engine engine = new Engine(); CuePlayer p = player(engine); Result result = new Result();
            try {
                assertTrue(p.speak(speech("deadline" + started, "格"), false, result));
                if (started) engine.progress.onStart(engine.id);
                assertTrue(result.ended.await(5, TimeUnit.SECONDS));
                assertFalse(result.success);
                assertEquals(started ? "PLAYBACK_TIMEOUT" : "START_TIMEOUT", result.reason);
                assertEquals(1, engine.stops);
            } finally { p.close(); }
        }
    }
    @Test public void oldCompletionAndDeadlineCannotStopAReplacementAndEngineErrorsAreNamed() throws Exception {
        Engine engine = new Engine(); CuePlayer p = player(engine);
        try {
            Result old = new Result(), next = new Result();
            assertTrue(p.speak(speech("old", "旧提示"), false, old));
            String oldId = engine.id;
            engine.progress.onStart(oldId); engine.progress.onDone(oldId);
            assertTrue(p.speak(speech("new", "右上区域，第二行和第三行，第六列交换。"), false, next));
            engine.progress.onStart(engine.id);
            Method timeout = CuePlayer.class.getDeclaredMethod("timeoutDynamicSpeech", String.class,
                    CueDispatcher.PlaybackCallback.class, TextToSpeech.class, String.class);
            timeout.setAccessible(true); timeout.invoke(p, oldId, old, engine, "START_TIMEOUT");
            engine.progress.onDone(oldId);
            assertEquals(0, engine.stops); assertEquals(1, next.ended.getCount());
            engine.progress.onError(engine.id);
            assertEquals("ENGINE_ERROR", next.reason);
        } finally { p.close(); }
    }

    @Test public void initializationRetainsOnlyLatestSpeechUntilTheEngineIsReady() throws Exception {
        Engine engine = new Engine(); CuePlayer p = player(engine);
        try {
            set(p, "ttsReady", false); set(p, "dynamicVoiceRequested", true);
            Result old = new Result(), next = new Result();
            assertTrue(p.speak(speech("pending-old", "旧提示"), false, old));
            assertTrue(p.speak(speech("pending-new", "右下区域，第五行，第四列和第五列交换。"), false, next));
            assertTrue(old.ended.await(1, TimeUnit.SECONDS)); assertFalse(old.success);
            assertEquals(0, engine.submissions);
            set(p, "ttsReady", true);
            Method finish = CuePlayer.class.getDeclaredMethod("finishPendingDynamic", boolean.class);
            finish.setAccessible(true); finish.invoke(p, true);
            assertEquals(1, engine.submissions); assertTrue(engine.id.contains("pending-new"));
            engine.progress.onStart(engine.id); engine.progress.onDone(engine.id);
            assertTrue(next.success);
        } finally { p.close(); }
    }

    @Test public void multiTtsReceivesPronunciationTextWhileTheHintKeepsItsSource() throws Exception {
        Engine engine = new Engine(); CuePlayer p = player(engine);
        try {
            long now = SystemClock.elapsedRealtime();
            String source = "右上区域，第 3 行和第 4 行，第 6 列交换。";
            CueRequest request = new CueRequest("test", "read-row", "m3live:hint:1", "消消乐交换提示",
                    CueRequest.Category.SYSTEM, 70, now, now + 30000, CueRequest.CHANNEL_SPEECH,
                    0, 0, 0, source);
            assertTrue(p.speak(request, false, new Result()));
            assertEquals("右上区域，第3航和第4航，第6列交换。", engine.spoken);
            assertEquals(source, request.speech);
        } finally { p.close(); }
    }

    @Test public void anOfflineEngineDefaultIsPreservedBeforeOtherEnumeratedVoices() {
        android.speech.tts.Voice selected = new android.speech.tts.Voice("zzz-selected", java.util.Locale.SIMPLIFIED_CHINESE,
                400, 100, false, java.util.Collections.emptySet());
        android.speech.tts.Voice other = new android.speech.tts.Voice("aaa-first", java.util.Locale.SIMPLIFIED_CHINESE,
                500, 100, false, java.util.Collections.emptySet());
        android.speech.tts.Voice online = new android.speech.tts.Voice("online", java.util.Locale.SIMPLIFIED_CHINESE,
                500, 100, true, java.util.Collections.emptySet());
        assertSame(selected, CuePlayer.selectedOfflineVoice(selected, new java.util.HashSet<>(java.util.Arrays.asList(other, selected))));
        assertSame(other, CuePlayer.selectedOfflineVoice(online, java.util.Collections.singleton(other)));
        assertNull(CuePlayer.selectedOfflineVoice(online, java.util.Collections.singleton(online)));
    }

    private static void clickNamed(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) { view.performClick(); return; }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            clickNamed(((ViewGroup) view).getChildAt(i), text);
    }
    private static boolean confirmCapture(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String text = String.valueOf(node.getText());
        if (text.equalsIgnoreCase("Start now") || text.equalsIgnoreCase("Start recording") || text.equals("立即开始"))
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        for (int i = 0; i < node.getChildCount(); i++) if (confirmCapture(node.getChild(i))) return true;
        return false;
    }
    private static final class SyntheticGame extends View {
        private final Paint p = new Paint();
        private int tick;
        int rows = 7, cols = 7;
        boolean heartbeat = true, singleCellChanged, mostlyUnknown, endpointChanged;
        final AtomicInteger touches = new AtomicInteger();
        SyntheticGame(Context context) {
            super(context); setOnTouchListener((v, e) -> { touches.incrementAndGet(); return true; });
        }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(Color.rgb(50, 200, 240));
            float side = Math.min(getWidth(), getHeight()) * .7f;
            float left = (getWidth() - side) / 2, top = getHeight() * (getHeight() > getWidth() ? .3f : .12f);
            float cell = side / Math.max(rows, cols);
            p.setColor(Color.rgb(30, 42, 88)); c.drawRect(left, top, left + cell * cols, top + cell * rows, p);
            int[] colors = {0xffefb630, 0xffeb5520, 0xff44d832, 0xff55a9ed, 0xffb15edb, 0xffc78030};
            for (int row = 0; row < rows; row++) for (int col = 0; col < cols; col++) {
                int color = (row * 3 + col) % 6;
                if (row == 0 && col == 0) color = 0;
                if (col == 0 && (row == 1 || row == 2) || row == 0 && col == 1) color = 1;
                if (singleCellChanged && row == rows - 1 && col == cols - 1) color = (color + 1) % colors.length;
                if(endpointChanged && row==0 && col==0)color=4;
                p.setColor(mostlyUnknown && (row>=3 || col>=3)?0xff909090:colors[color]);
                c.drawCircle(left + cell * (col + .5f), top + cell * (row + .5f), cell * .43f, p);
            }
            // MediaProjection emits changed surfaces; keep only an out-of-board pixel changing.
            p.setColor(Color.rgb(100 + tick++ % 100, 130, 200)); c.drawRect(0, 0, 8, 8, p);
            if (heartbeat) postInvalidateDelayed(200);
        }
    }

    private interface Condition { boolean ready() throws Exception; }
    private static void await(String message, long timeout, Condition condition) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.ready() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
        assertTrue(message + "; current=" + (DiagnosticRecorder.current == null ? "none"
                : DiagnosticRecorder.current.stateForDiagnostics()), condition.ready());
    }
    private static Object startProjection(ActivityScenario<Match3AssistActivity> scenario,
                                          SyntheticGame[] game) throws Exception {
        return startProjection(scenario,game,false);
    }
    private static Object startProjection(ActivityScenario<Match3AssistActivity> scenario,
                                          SyntheticGame[] game,boolean mostlyUnknown) throws Exception {
        GameProfile.settings(context()).edit().putBoolean("match3_overlay_permission_explained", true).commit();
        scenario.onActivity(a -> clickNamed(a.getWindow().getDecorView(), "开始辅助"));
        await("System consent starts the actual projection", 8000, () -> {
            confirmCapture(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
            return Match3LiveService.isRunning();
        });
        scenario.onActivity(a -> { game[0] = new SyntheticGame(a);game[0].mostlyUnknown=mostlyUnknown;a.setContentView(game[0]); });
        Match3LiveService service = Match3LiveService.testInstance();
        await("A stable board produces a hint", 12000, () -> {
            Object s = get(service, "active"); return s != null && get(s, "currentHint") != null;
        });
        return get(service, "active");
    }
    @Test public void actualProjectionOffersACertifiedLocalExchangeInsideAnUnfamiliarBoard() throws Exception {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        SyntheticGame[] game=new SyntheticGame[1];
        try(ActivityScenario<Match3AssistActivity> scenario=ActivityScenario.launch(Match3AssistActivity.class)) {
            Object session=startProjection(scenario,game,true);
            Match3Hint hint=(Match3Hint)get(session,"currentHint");assertNotNull(hint);
            assertEquals(0,hint.swap.fromRow);assertEquals(0,hint.swap.fromCol);
            assertEquals(0,hint.swap.toRow);assertEquals(1,hint.swap.toCol);
            org.json.JSONObject state=new org.json.JSONObject(DiagnosticRecorder.current.stateForDiagnostics());
            assertTrue("Unfamiliar cells do not discard a proven island",state.getInt("unknown_cells")>49*.4);
            SystemClock.sleep(3200);assertSame(hint,get(session,"currentHint"));
            scenario.onActivity(a->{game[0].endpointChanged=true;game[0].invalidate();});
            await("A real endpoint change retires the old exchange",5000,()->get(session,"currentHint")!=hint);
            java.io.File directory=context().getExternalFilesDir("match3-release-capture");assertNotNull(directory);directory.mkdirs();
            org.json.JSONObject evidence=new org.json.JSONObject().put("source","generated_unknown_artwork_actual_projection")
                    .put("state",state).put("retained_hint",true).put("endpoint_change_retired_hint",true)
                    .put("independent_accuracy_evidence",false).put("acoustic_evidence",false);
            java.nio.file.Files.write(new java.io.File(directory,"partial-board-projection.json").toPath(),
                    evidence.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {command(Match3LiveService.ACTION_STOP);await("Stopped",5000,()->!Match3LiveService.isRunning());}
    }
    private static void command(String action) {
        context().startService(new Intent(context(), Match3LiveService.class).setAction(action));
    }
    private static void overlayPermission(String mode) throws Exception {
        try (android.os.ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand("appops set com.openkhub.sensefield SYSTEM_ALERT_WINDOW " + mode);
             java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            while (in.read() != -1) { }
        }
    }

    /** Real projection/dispatcher cancellation check with a controlled renderer, not acoustic evidence. */
    @Test public void idlePhotosCannotCancelASevenSecondHintInTheActualProjection() throws Exception {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
        String[] names = {"screen-284-2285557292.jpg", "screen-297-2285567809.jpg", "screen-310-2285578323.jpg"};
        Bitmap[] frames = new Bitmap[names.length];
        for (int i = 0; i < names.length; i++) {
            java.io.InputStream input;
            try { input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(names[i]); }
            catch (java.io.IOException absent) { Assume.assumeNoException(absent); return; }
            try (input) { frames[i] = BitmapFactory.decodeStream(input); }
        }
        android.content.SharedPreferences prefs = GameProfile.settings(context());
        java.util.Map<String, ?> previous = prefs.getAll();
        String[] keys = {"match3_overlay_permission_explained", "match3_hint_highlight_enabled",
                "cue_channel_speech", "cue_category_system"};
        android.content.SharedPreferences.Editor edit = prefs.edit();
        for (String key : keys) edit.putBoolean(key, true);
        edit.commit();
        android.os.Handler callbacks = new android.os.Handler(android.os.Looper.getMainLooper());
        CountDownLatch completed = new CountDownLatch(1), installed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<CueRequest> spoken = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger stopped = new AtomicInteger();
        FeedbackPhoto[] replay = new FeedbackPhoto[1];
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(Match3AssistActivity.class)) {
            scenario.onActivity(a -> clickNamed(a.getWindow().getDecorView(), "开始辅助"));
            await("Actual projection authorized", 10000, () -> {
                confirmCapture(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
                return Match3LiveService.isRunning();
            });
            Match3LiveService service = Match3LiveService.testInstance();
            assertNotNull(service);
            ((android.os.Handler) get(service, "handler")).post(() -> {
                try {
                    Object session = get(service, "active");
                    Method prepare = Match3LiveService.class.getDeclaredMethod("ensureDispatcher", session.getClass());
                    prepare.setAccessible(true); prepare.invoke(service, session);
                    CueDispatcher original = (CueDispatcher) get(service, "dispatcher");
                    assertNotNull(original);
                    CueDispatcher.Listener listener = (CueDispatcher.Listener) get(original, "listener");
                    original.close();
                    CueDispatcher.Renderer delayed = new CueDispatcher.Renderer() {
                        public boolean playTone(CueRequest r, CueDispatcher.PlaybackCallback callback) { return false; }
                        public boolean vibrate(CueRequest r) { return false; }
                        public boolean speak(CueRequest r, boolean interrupt, CueDispatcher.PlaybackCallback callback) {
                            spoken.set(r); callback.onStarted(SystemClock.elapsedRealtime());
                            callbacks.postDelayed(() -> {
                                callback.onFinished(SystemClock.elapsedRealtime(), true); completed.countDown();
                            }, 7000);
                            return true;
                        }
                        public void stopSpeech() { stopped.incrementAndGet(); callbacks.removeCallbacksAndMessages(null); }
                    };
                    set(service, "dispatcher", new CueDispatcher(delayed,
                            new Match3LiveCuePolicy(new CueSettings(context())), listener, SystemClock::elapsedRealtime));
                } catch (Throwable error) { failure.set(error); }
                finally { installed.countDown(); }
            });
            assertTrue(installed.await(5, TimeUnit.SECONDS)); assertNull(failure.get());
            scenario.onActivity(a -> { replay[0] = new FeedbackPhoto(a, frames[0]); showFeedbackPhoto(a, replay[0]); });
            await("Stable real board starts the controlled long hint", 20000, () -> {
                Match3DiagnosticReplayInstrumentedTest.dismissFullscreenTutorial(InstrumentationRegistry
                        .getInstrumentation().getUiAutomation().getRootInActiveWindow());
                return spoken.get() != null;
            });
            Object session = get(service, "active");
            Match3Hint hint = (Match3Hint) get(session, "currentHint"); assertNotNull(hint);
            long boardRevision = (long) get(session, "boardRevision");
            long startedFrames = (long) get(session, "processedFrames");
            for (int i = 0; i < 5; i++) {
                Bitmap frame = frames[(i + 1) % frames.length];
                // Keep the same real surface: changing setContentView would add
                // layout/blank-frame transitions unrelated to sprite animation.
                scenario.onActivity(a -> { replay[0].frame = frame; replay[0].invalidate(); });
                SystemClock.sleep(1500);
                assertSame("Idle artwork must not retire the exchange; "
                        + DiagnosticRecorder.current.stateForDiagnostics(), hint, get(session, "currentHint"));
                assertEquals(boardRevision, (long) get(session, "boardRevision"));
            }
            assertTrue("A seven-second utterance reaches its normal completion", completed.await(2, TimeUnit.SECONDS));
            assertEquals("No software cancellation during idle animation", 0, stopped.get());
            assertTrue((long) get(session, "processedFrames") >= startedFrames + 6);
            assertNotNull(((Match3HintOverlay) get(session, "overlay")).renderedHint());
            java.io.File directory = context().getExternalFilesDir("match3-release-capture");
            assertNotNull(directory); directory.mkdirs();
            String events = new String(java.nio.file.Files.readAllBytes(
                    new java.io.File(DiagnosticRecorder.current.directory, "events.jsonl").toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean finished = false;
            for (String line : events.split("\n")) if (!line.isEmpty()) {
                org.json.JSONObject event = new org.json.JSONObject(line), data = event.optJSONObject("data");
                if ("CuePlayback".equals(event.optString("type")) && data != null
                        && spoken.get().cueId.equals(data.optString("cue_id"))
                        && "COMPLETED".equals(data.optString("result"))) finished = true;
            }
            assertTrue("Production listener records normal completion", finished);
            org.json.JSONObject evidence = new org.json.JSONObject().put("source", "actual_projection_supplied_idle_photos")
                    .put("renderer", "controlled_7000ms_callback").put("software_completion", true)
                    .put("cancel_count", stopped.get()).put("board_revision", boardRevision)
                    .put("acoustic_evidence", false).put("independent_accuracy_evidence", false);
            java.nio.file.Files.write(new java.io.File(directory, "feedback-idle-speech.json").toPath(),
                    evidence.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            if (DiagnosticRecorder.current != null) {
                java.io.File evidence = context().getExternalFilesDir("match3-release-capture");
                assertNotNull(evidence); evidence.mkdirs();
                java.nio.file.Files.copy(new java.io.File(DiagnosticRecorder.current.directory, "events.jsonl").toPath(),
                        new java.io.File(evidence, "feedback-idle-events.jsonl").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            command(Match3LiveService.ACTION_STOP);
            await("Projection stopped", 5000, () -> !Match3LiveService.isRunning());
            callbacks.removeCallbacksAndMessages(null);
            for (Bitmap frame : frames) if (frame != null) frame.recycle();
            android.content.SharedPreferences.Editor restore = prefs.edit();
            for (String key : keys) if (previous.containsKey(key)) restore.putBoolean(key, (Boolean) previous.get(key));
            else restore.remove(key);
            restore.commit();
        }
    }

    private static void showFeedbackPhoto(Match3AssistActivity activity, View replay) {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        activity.setContentView(replay);
    }

    private static final class FeedbackPhoto extends View {
        Bitmap frame;
        final Paint paint = new Paint();
        int tick;
        FeedbackPhoto(Context context, Bitmap frame) { super(context); this.frame = frame; }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawBitmap(frame, null, new android.graphics.Rect(0, 0, getWidth(), getHeight()), paint);
            paint.setColor(0xff000000 | (100 + tick++ % 100));
            canvas.drawRect(0, 0, 8, 8, paint);
            postInvalidateDelayed(200);
        }
    }
    @Test public void fullProjectionKeepsRecognitionAndTouchesWorkingUnderTheHint() throws Exception {
        Assume.assumeTrue(android.provider.Settings.canDrawOverlays(context()));
        GameProfile.settings(context()).edit().putBoolean("match3_hint_highlight_enabled", true).apply();
        SyntheticGame[] game = new SyntheticGame[1];
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(Match3AssistActivity.class)) {
            scenario.onActivity(a -> clickNamed(a.getWindow().getDecorView(), "开始辅助"));
            long until = SystemClock.elapsedRealtime() + 8000;
            while (!Match3LiveService.isRunning() && SystemClock.elapsedRealtime() < until) {
                confirmCapture(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
                SystemClock.sleep(150);
            }
            assertTrue("System consent starts the actual projection", Match3LiveService.isRunning());
            scenario.onActivity(a -> { game[0] = new SyntheticGame(a); a.setContentView(game[0]); });
            Field active = Match3LiveService.class.getDeclaredField("active"); active.setAccessible(true);
            // Obtain the real service via a weak diagnostic test reference, not a fake projection.
            until = SystemClock.elapsedRealtime() + 12000;
            Match3LiveService service = Match3LiveService.testInstance();
            Object session = null; Match3Hint hint = null;
            while (SystemClock.elapsedRealtime() < until) {
                session = active.get(service); hint = session == null ? null : (Match3Hint) get(session, "currentHint");
                if (hint != null && get(session, "overlay") != null
                        && ((Match3HintOverlay) get(session, "overlay")).renderedHint() != null) break;
                SystemClock.sleep(150);
            }
            assertNotNull("A stable board produces a visible pair", hint);
            assertEquals(7, hint.geometry.rows); assertEquals(7, hint.geometry.cols);
            Match3HintOverlay overlay = (Match3HintOverlay) get(session, "overlay"); assertNotNull(overlay);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) get(overlay, "params");
            assertTrue(params.alpha <= .8f);
            assertEquals("The hint must not black out the captured board", 0,
                    params.flags & WindowManager.LayoutParams.FLAG_SECURE);
            assertTrue((params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
            long at = SystemClock.uptimeMillis();
            float x = hint.geometry.centerX(hint.swap.fromCol), y = hint.geometry.centerY(hint.swap.fromRow);
            android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            automation.injectInputEvent(android.view.MotionEvent.obtain(at, at, 0, x, y, 0), true);
            automation.injectInputEvent(android.view.MotionEvent.obtain(at, at + 40, 1, x, y, 0), true);
            SystemClock.sleep(400);
            assertTrue("Touch passes to the game", game[0].touches.get() >= 2);
            assertFalse("The window must not black out recognition", (boolean) get(session, "visualSuppressed"));
            assertSame("Unchanged board keeps the same hint", hint, get(session, "currentHint"));

            // Export the same renderer composition separately from the actual capture check below.
            Bitmap composite = Bitmap.createBitmap(hint.geometry.frameWidth, hint.geometry.frameHeight,
                    Bitmap.Config.ARGB_8888);
            Match3Hint shownHint = hint;
            scenario.onActivity(a -> {
                Canvas c = new Canvas(composite);
                int[] location = new int[2]; game[0].getLocationOnScreen(location);
                c.save(); c.translate(location[0], location[1]); game[0].draw(c); c.restore();
                Match3HintOverlay.HintView v = new Match3HintOverlay.HintView(a);
                v.hint = shownHint;
                v.layout(0, 0, params.width, params.height);
                int saved = c.saveLayerAlpha(params.x, params.y, params.x + params.width,
                        params.y + params.height, (int) (params.alpha * 255));
                c.translate(params.x, params.y); v.draw(c); c.restoreToCount(saved);
            });
            java.io.File rendered = new java.io.File(context().getExternalFilesDir(null), "match3-0.4.5-renderer.png");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(rendered)) {
                composite.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally { composite.recycle(); }

            GameProfile.settings(context()).edit().putBoolean("match3_hint_highlight_enabled", false).commit();
            command(Match3LiveService.ACTION_REFRESH_VISUAL);
            Object stableSession = session;
            await("Setting only removes visuals", 2500, () -> get(stableSession, "overlay") == null);
            assertSame(hint, get(session, "currentHint"));
            GameProfile.settings(context()).edit().putBoolean("match3_hint_highlight_enabled", true).commit();
            command(Match3LiveService.ACTION_REFRESH_VISUAL);
            await("The same hint returns when enabled", 2500, () -> {
                Match3HintOverlay v = (Match3HintOverlay) get(stableSession, "overlay");
                return v != null && v.renderedHint() == shownHint;
            });

            overlayPermission("deny");
            await("Revocation retires only the visual channel", 3000, () -> (boolean) get(stableSession, "visualSuppressed"));
            assertSame("Voice still owns the current recommendation", hint, get(session, "currentHint"));
            scenario.onActivity(a -> a.setContentView(new View(a)));
            await("Leaving the board or losing frames removes the pair", 7000,
                    () -> get(stableSession, "currentHint") == null);
            assertNull("Leaving the board removes the pair", get(session, "currentHint"));
        } finally {
            context().stopService(new Intent(context(), Match3LiveService.class));
            overlayPermission("allow");
        }
    }

    @Test public void permissionDeniedStillAllowsVoiceAndShowsARecoveryEntry() throws Exception {
        overlayPermission("deny");
        GameProfile.settings(context()).edit().putBoolean("match3_hint_highlight_enabled", true).commit();
        SyntheticGame[] game = new SyntheticGame[1];
        try (ActivityScenario<Match3SettingsActivity> settings = ActivityScenario.launch(Match3SettingsActivity.class)) {
            settings.onActivity(a -> {
                View permission = a.findViewById(android.R.id.content).findViewWithTag("ui_nav:允许显示高亮");
                assertNotNull(permission);
                assertEquals(View.VISIBLE, permission.getVisibility()); assertTrue(permission.isClickable());
            });
        }
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(Match3AssistActivity.class)) {
            Object s = startProjection(scenario, game);
            assertNull(get(s, "overlay"));
            assertNotNull(get(s, "currentHint"));
            assertTrue((boolean) get(s, "boardValid"));
        } finally {
            context().stopService(new Intent(context(), Match3LiveService.class)); overlayPermission("allow");
        }
    }
    private static Object readUnchecked(Object target, String name) {
        try { return get(target, name); } catch (Exception error) { throw new AssertionError(error); }
    }

    @Test public void realCaptureKeepsTheHighlightAndRemovesItsPixelsBeforeRecognition() throws Exception {
        overlayPermission("allow");
        GameProfile.settings(context()).edit().putBoolean("match3_hint_highlight_enabled", true).commit();
        SyntheticGame[] game = new SyntheticGame[1];
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(Match3AssistActivity.class)) {
            Object s = startProjection(scenario, game);
            long startedFrames = (long) get(s, "processedFrames");
            Match3Hint firstHint = (Match3Hint) get(s, "currentHint");
            // Ten 800ms samples plus emulator scheduling; this is a correctness
            // check, not a production latency benchmark. The frame count is unchanged.
            await("Capture keeps producing frames under the persistent window", 20000,
                    () -> (long) get(s, "processedFrames") >= startedFrames + 10);
            await("The unchanged board remains valid", 3500, () -> (boolean) get(s, "boardValid"));
            assertNotNull(get(s, "currentHint"));
            assertSame("Our drawing must not create another board revision", firstHint, get(s, "currentHint"));
            assertFalse("Continuous visuals are required, rather than voice-only fallback", (boolean) get(s, "visualSuppressed"));
            Match3HintOverlay overlay = (Match3HintOverlay) get(s, "overlay");
            assertNotNull("Capture retains the hint window", overlay.renderedHint());
            assertTrue("The real compositor returned the overlay, and its pixels were filtered",
                    (long) get(s, "filteredOverlayFrames") > 0);
            java.io.File record = new java.io.File(context().getExternalFilesDir(null), "match3-0.4.5-capture-mode.txt");
            String mode = "non-secure hint stayed visible for 10 capture samples; current-frame overlay removal preserved recognition\n";
            java.nio.file.Files.write(record.toPath(), mode.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally { context().stopService(new Intent(context(), Match3LiveService.class)); }
    }

    @Test public void windowPixelsAreRemovedWithoutReplacingCurrentGamePixels() {
        Bitmap game = Bitmap.createBitmap(700, 1100, Bitmap.Config.ARGB_8888);
        Bitmap captured = null;
        try {
            Canvas base = new Canvas(game); base.drawColor(0xff28bce4);
            Paint tile = new Paint(); tile.setColor(0xff273358);
            BoardGeometry geometry = new BoardGeometry(700, 1100, 70, 250, 630, 810, 7, 7);
            base.drawRect(70, 250, 630, 810, tile);
            int[] palette = {0xffe9232f, 0xfff6d531, 0xff2caf60, 0xff7f40c0};
            for (int r = 0; r < 7; r++) for (int c = 0; c < 7; c++) {
                tile.setColor(palette[(r + c) % palette.length]);
                base.drawCircle(geometry.centerX(c), geometry.centerY(r), 32, tile);
            }
            Match3Board.Swap swap = new Match3Board.Swap(2, 2, 2, 3, 3);
            Match3Hint hint = new Match3Hint("filter", 1, 1, geometry, swap);
            captured = game.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(captured);
            Match3HintOverlay.HintView drawing = new Match3HintOverlay.HintView(context()); drawing.hint = hint;
            int layer = canvas.saveLayerAlpha(70, 250, 630, 810, 191);
            canvas.translate(70, 250); drawing.drawHint(canvas); canvas.restoreToCount(layer);
            Match3OverlayCaptureFilter filter = new Match3OverlayCaptureFilter(context());
            assertTrue(filter.clean(captured, hint, .75f));
            assertArrayEquals(Match3Sampler.sample(game, geometry, null), Match3Sampler.sample(captured, geometry, null));
            for (int r = 0; r < 7; r++) for (int c = 0; c < 7; c++)
                assertEquals("Cell centers must never be replaced with old content", game.getPixel(geometry.centerX(c), geometry.centerY(r)),
                        captured.getPixel(geometry.centerX(c), geometry.centerY(r)));
            assertFalse("A frame without our probe is never modified", filter.clean(game, hint, .75f));
        } finally { game.recycle(); if (captured != null) captured.recycle(); }
    }

    @Test public void stoppingCaptureCannotInvalidateAnImageStillOwnedByTheWorker() throws Exception {
        overlayPermission("allow");
        SyntheticGame[] game = new SyntheticGame[1];
        CountDownLatch acquired = new CountDownLatch(1), continueCopy = new CountDownLatch(1), copied = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(Match3AssistActivity.class)) {
            startProjection(scenario, game);
            Match3LiveService service = Match3LiveService.testInstance();
            android.media.ImageReader source = (android.media.ImageReader) get(service, "reader");
            ((android.os.Handler) get(service, "handler")).post(() -> {
                android.media.Image image = null; Bitmap bitmap = null;
                try {
                    // Let the synthetic screen deliver a fresh image while callbacks queue behind us.
                    SystemClock.sleep(200);
                    image = source.acquireLatestImage();
                    assertNotNull("A real captured image is held across stop", image);
                    acquired.countDown();
                    assertTrue(continueCopy.await(5, TimeUnit.SECONDS));
                    android.media.Image.Plane plane = image.getPlanes()[0];
                    bitmap = Bitmap.createBitmap(plane.getRowStride() / plane.getPixelStride(),
                            image.getHeight(), Bitmap.Config.ARGB_8888);
                    java.nio.ByteBuffer bytes = plane.getBuffer().duplicate(); bytes.rewind();
                    bitmap.copyPixelsFromBuffer(bytes);
                } catch (Throwable error) { failure.set(error); }
                finally {
                    acquired.countDown();
                    if (bitmap != null) bitmap.recycle();
                    if (image != null) image.close();
                    copied.countDown();
                }
            });
            assertTrue(acquired.await(3, TimeUnit.SECONDS));
            assertNull(failure.get());
            command(Match3LiveService.ACTION_STOP);
            await("Stop invalidates the session without closing the in-flight image", 3000,
                    () -> !Match3LiveService.isRunning());
            continueCopy.countDown();
            assertTrue(copied.await(3, TimeUnit.SECONDS));
            assertNull("The held native buffer remains valid until its worker finishes", failure.get());
        } finally {
            continueCopy.countDown();
            context().stopService(new Intent(context(), Match3LiveService.class));
        }
    }

    @Test public void levelChangePointReadRotationAndFiveSecondExpiryCannotKeepOldPairs() throws Exception {
        overlayPermission("allow");
        GameProfile.settings(context()).edit().putBoolean("match3_hint_highlight_enabled", true).commit();
        SyntheticGame[] game = new SyntheticGame[1];
        try (ActivityScenario<Match3AssistActivity> scenario = ActivityScenario.launch(Match3AssistActivity.class)) {
            Object s = startProjection(scenario, game);
            Match3LiveService service = Match3LiveService.testInstance();
            Match3Hint old = (Match3Hint) get(s, "currentHint");
            scenario.onActivity(a -> { game[0].rows = 8; game[0].cols = 8; game[0].invalidate(); });
            await("New level replaces geometry and revision", 12000, () -> {
                Match3Hint h = (Match3Hint) get(s, "currentHint");
                return h != null && h != old && h.geometry.rows == 8 && h.geometry.cols == 8;
            });
            assertTrue(((Match3Hint) get(s, "currentHint")).revision > old.revision);
            long levelRevision = ((Match3Hint) get(s, "currentHint")).revision;
            scenario.onActivity(a -> { game[0].singleCellChanged = true; game[0].invalidate(); });
            await("A stable single-cell change is also a new board revision", 10000, () -> {
                Match3Hint h = (Match3Hint) get(s, "currentHint"); return h != null && h.revision > levelRevision;
            });
            command(Match3LiveService.ACTION_EXPLORE_ON);
            await("Point reading removes swap highlights", 2000, () -> (boolean) get(s, "exploreMode"));
            assertNull(get(s, "currentHint"));
            command(Match3LiveService.ACTION_EXPLORE_OFF);
            await("Normal recommendations recover", 12000, () -> get(s, "currentHint") != null);

            CountDownLatch ruleObserved = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> ruleFailure = new java.util.concurrent.atomic.AtomicReference<>();
            ((android.os.Handler) get(service, "handler")).post(() -> {
                try {
                    Match3GoalOutcome tracker = (Match3GoalOutcome) get(s, "outcomes");
                    Match3Position.Cell[][] a = new Match3Position.Cell[2][3], b = new Match3Position.Cell[2][3];
                    String[] before = {"YRY", "HYH"}, after = {"YYY", "HRH"};
                    for (int row = 0; row < 2; row++) for (int col = 0; col < 3; col++) {
                        char aa = before[row].charAt(col), bb = after[row].charAt(col);
                        a[row][col] = aa == 'H' ? Match3Position.Cell.obstacle(Match3Position.Kind.EMPTY, 0) : Match3Position.Cell.animal(aa);
                        b[row][col] = bb == 'H' ? Match3Position.Cell.obstacle(Match3Position.Kind.EMPTY, 0) : Match3Position.Cell.animal(bb);
                    }
                    Match3Position initial = new Match3Position(a);
                    Match3Goals baseline = new Match3Goals(42, 8, java.util.Collections.singletonList(
                            new Match3Goals.Target(0, Match3Goals.Kind.CHICK, 6, false)), true, 100);
                    tracker.begin(100, initial, baseline, Match3MoveRanker.rankedMoves(initial, baseline).get(0), 100);
                    tracker.observeFrame(new Match3Position(b), baseline, 200);
                    tracker.confirmed(new Match3Goals(42, 7, java.util.Collections.singletonList(
                            new Match3Goals.Target(0, Match3Goals.Kind.CHICK, 5, false)), true, 900), 900);
                    assertTrue(tracker.abstainedRules().contains(Match3Goals.Kind.CHICK));
                } catch (Throwable error) { ruleFailure.set(error); }
                finally { ruleObserved.countDown(); }
            });
            assertTrue(ruleObserved.await(3, TimeUnit.SECONDS));assertNull(ruleFailure.get());

            Activity[] beforeRotation = new Activity[1];
            scenario.onActivity(a -> {
                beforeRotation[0] = a;
                a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            });
            await("The landscape activity has finished recreation", 8000, () -> {
                boolean[] ready = {false};
                scenario.onActivity(a -> ready[0] = a != beforeRotation[0]
                        && a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
                return ready[0];
            });
            await("Rotation replaces capture session without reusing the old hint", 8000, () -> get(service, "active") != s);
            Object rotated = get(service, "active"); assertNotNull(rotated);
            assertNull(get(rotated, "currentHint"));
            assertTrue("Rotation cannot revive a contradicted task rule within the same game",
                    ((Match3GoalOutcome) get(rotated, "outcomes")).abstainedRules().contains(Match3Goals.Kind.CHICK));
            android.media.ImageReader r = (android.media.ImageReader) get(rotated, "reader");
            assertTrue("Capture really resized", r.getWidth() > r.getHeight());
            scenario.onActivity(a -> { game[0] = new SyntheticGame(a); a.setContentView(game[0]); });
            try {
                await("Landscape geometry reacquires", 12000, () -> get(rotated, "currentHint") != null);
            } catch (AssertionError failure) {
                Bitmap actual = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                java.io.File out = new java.io.File(context().getExternalFilesDir(null), "match3-landscape-failure.png");
                if (actual != null) try (java.io.FileOutputStream file = new java.io.FileOutputStream(out)) {
                    actual.compress(Bitmap.CompressFormat.PNG, 100, file);
                } finally { if (actual != null) actual.recycle(); }
                CountDownLatch written = new CountDownLatch(1);
                ((android.os.Handler) get(service, "handler")).post(() -> {
                    try {
                        Bitmap frame = (Bitmap) get(rotated, "frame");
                        if (frame != null) {
                            java.io.File image = new java.io.File(context().getExternalFilesDir(null), "match3-landscape-frame.png");
                            try (java.io.FileOutputStream file = new java.io.FileOutputStream(image)) {
                                frame.compress(Bitmap.CompressFormat.PNG, 100, file);
                            }
                            android.os.Bundle status = new android.os.Bundle();
                            status.putString("landscape_geometry", String.valueOf(Match3Sampler.autoDetectGeometry(frame)));
                            status.putString("landscape_popup", String.valueOf(Match3Coach.isPopupShowing(frame)));
                            status.putString("landscape_unconfirmed_cells", String.valueOf(get(rotated, "unconfirmedCells")));
                            InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
                        }
                    } catch (Exception error) {
                        android.os.Bundle status = new android.os.Bundle();
                        status.putString("landscape_dump_error", error.getClass().getSimpleName());
                        InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
                    }
                    finally { written.countDown(); }
                });
                written.await(3, TimeUnit.SECONDS);
                throw failure;
            }
            scenario.onActivity(a -> game[0].heartbeat = false);
            // System bars and other windows may still repaint an otherwise still
            // game. Stop delivery on the real reader to exercise actual no-frame
            // expiry, rather than assuming that no game draw means no OS frame.
            CountDownLatch noFrames = new CountDownLatch(1);
            ((android.os.Handler) get(service, "handler")).post(() -> {
                r.setOnImageAvailableListener(null, null);
                noFrames.countDown();
            });
            assertTrue("Capture delivery is stopped on its owning worker", noFrames.await(3, TimeUnit.SECONDS));
            await("More than five seconds with no frame retires the hint", 8000, () -> get(rotated, "currentHint") == null);
            Match3HintOverlay v = (Match3HintOverlay) get(rotated, "overlay");
            assertTrue(v == null || v.renderedHint() == null);
        } finally {
            context().stopService(new Intent(context(), Match3LiveService.class));
            try (android.os.ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("settings put system user_rotation 0")) { }
        }
    }
}
