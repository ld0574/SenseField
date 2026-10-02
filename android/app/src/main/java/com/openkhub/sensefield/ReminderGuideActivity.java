package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;

/** A skippable spoken introduction before consent, separate from live capture output. */
public final class ReminderGuideActivity extends Activity {
    static final String EXTRA_START = "start_after_reminder_guide";
    private static final int REQUEST_START = 1201;
    private static final CueDispatcher.Policy GUIDE_POLICY = new CueDispatcher.Policy() {
        @Override public boolean categoryEnabled(CueRequest.Category category) { return true; }
        @Override public int enabledChannels() {
            return CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC;
        }
        @Override public long dedupeWindowMs(CueRequest.Category category) { return 0; }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ReminderGuidePlayback playback = new ReminderGuidePlayback(
            new ReminderGuidePlayback.Output() {
                @Override public void play(ReminderGuide.Step step,
                        ReminderGuidePlayback.Completion completion) {
                    playStep(step, completion);
                }
                @Override public void stop() {
                    pending = null;
                    pendingId = null;
                    if (dispatcher != null) dispatcher.clearAll();
                }
            }, new ReminderGuidePlayback.Listener() {
                @Override public void onStep(ReminderGuide.Step step) {
                    status.setText(step.sample ? "正在试听：" + step.title : "正在说明：" + step.title);
                }
                @Override public void onEnded(boolean success) {
                    stopExplanation();
                    status.setText(success ? hapticUnavailable
                            ? "说明已播完。手机未能请求震动，可使用语音和短音，并到设置中检查震动。"
                            : "说明已播完。可以重听，也可以继续。"
                            : "说明未能播完。请检查媒体音量和中文语音引擎，再重听。");
                }
            });
    private List<ReminderGuide.Step> steps;
    private TextView status;
    private Button repeat;
    private Button stop;
    private Button proceed;
    private CuePlayer player;
    private CueDispatcher dispatcher;
    private ReminderGuidePlayback.Completion pending;
    private String pendingId;
    private int generation;
    private long nextId;
    private boolean autoPending;
    private boolean startAfterGuide;
    private boolean authorizationInFlight;
    private boolean hapticUnavailable;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.configureWindow(this);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        startAfterGuide = getIntent().getBooleanExtra(EXTRA_START, false);
        autoPending = state == null;
        authorizationInFlight = state != null && state.getBoolean("authorization_in_flight", false);
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);
        UiKit.addBrandHeader(content, "王者荣耀");
        TextView title = UiKit.text(this, "提醒说明与试听", 32, UiKit.INK, true);
        title.setAccessibilityHeading(true);
        UiKit.add(content, title, 12);
        UiKit.add(content, body("说明对应当前开启的提示。这里的声音和震动都是示例。"), 18);
        status = body("可以先听说明，也可以直接开始。");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(content, status, 18);
        if (startAfterGuide) {
            proceed = button(content, "继续开始（可跳过说明）", true);
            proceed.setOnClickListener(view -> continueStart());
        }
        repeat = button(content, "重听说明", false);
        repeat.setOnClickListener(view -> startExplanation());
        stop = button(content, "停止朗读", false);
        stop.setEnabled(false);
        stop.setOnClickListener(view -> {
            stopExplanation();
            status.setText("已停止朗读。可以重听，也可以继续。");
        });
        String error = null;
        try {
            steps = currentSteps();
            for (ReminderGuide.Step step : steps) {
                if (step.sample) continue;
                LinearLayout card = UiKit.card(this);
                TextView heading = UiKit.text(this, step.title, 26, UiKit.INK, true);
                heading.setAccessibilityHeading(true);
                UiKit.add(card, heading, 8);
                UiKit.add(card, body(step.text), 0);
                UiKit.add(content, card, 14);
            }
        } catch (IOException | JSONException failure) {
            steps = null;
            error = "无法读取当前提醒配置，请到声音与语音设置中检查。";
            Log.w("ReminderGuide", "Could not resolve guide outputs", failure);
        }
        button(content, "声音与语音设置", false).setOnClickListener(view ->
                startActivity(new Intent(this, GameTuningActivity.class)));
        button(content, "返回", false).setOnClickListener(view -> finish());
        setContentView(scroll);
        if (error != null) status.setText(error);
        refreshAvailability();
    }

    private List<ReminderGuide.Step> currentSteps() throws IOException, JSONException {
        GameProfile profile = GameProfile.load(this);
        CueSettings settings = new CueSettings(this);
        int near = profile.relation != null && settings.categoryEnabled(CueRequest.Category.NEAR_ZONE)
                ? settings.nearRequestedChannels() : 0;
        boolean appearances = (profile.minimapYolox || profile.flags[1] != 0 || profile.flags[2] != 0)
                && CueSettings.visionMemoryDispatchEnabled(GameProfile.settings(this).getBoolean(
                        GameProfile.PREF_VISION_MEMORY, GameProfile.DEFAULT_VISION_MEMORY),
                        settings.categoryEnabled(CueRequest.Category.VISION_MEMORY))
                && NearZoneRouting.farAppearAudible(profile.relation != null,
                        settings.categoryEnabled(CueRequest.Category.NEAR_ZONE),
                        settings.farAppearPreference());
        int appearance = appearances ? settings.enabledChannels(CueRequest.Category.VISION_MEMORY)
                & (CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC
                    | (settings.speakAppear() ? CueRequest.CHANNEL_SPEECH : 0)) : 0;
        int playerChannels = profile.playerLife != null
                && settings.categoryEnabled(CueRequest.Category.PLAYER_STATE)
                ? settings.enabledChannels(CueRequest.Category.PLAYER_STATE)
                        & (CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC) : 0;
        int danger = profile.flags[3] != 0 && settings.categoryEnabled(CueRequest.Category.DANGER)
                ? settings.enabledChannels(CueRequest.Category.DANGER)
                        & CueRouting.directCueRequestedChannels(3, 0) : 0;
        boolean peripheral = profile.flags[0] != 0
                && settings.categoryEnabled(CueRequest.Category.PERIPHERAL_THREAT)
                && (settings.enabledChannels(CueRequest.Category.PERIPHERAL_THREAT)
                        & CueRequest.CHANNEL_TONE) != 0;
        boolean systemSpeech = settings.categoryEnabled(CueRequest.Category.SYSTEM)
                && (settings.enabledChannels(CueRequest.Category.SYSTEM)
                        & CueRequest.CHANNEL_SPEECH) != 0;
        return ReminderGuide.build(new ReminderGuide.Outputs(near, appearance, playerChannels,
                danger, peripheral, systemSpeech));
    }

    private TextView body(String text) { return UiKit.text(this, text, 24, UiKit.INK, false); }

    private Button button(LinearLayout parent, String text, boolean primary) {
        Button button = UiKit.button(this, text, primary);
        button.setTextSize(24);
        button.setMinHeight(UiKit.dp(this, 84));
        button.setMinimumHeight(UiKit.dp(this, 84));
        UiKit.add(parent, button, 14);
        return button;
    }

    private void refreshAvailability() {
        boolean running = CaptureService.isRunning();
        repeat.setEnabled(!running && steps != null);
        if (proceed != null) proceed.setEnabled(!running && !authorizationInFlight);
        if (running) status.setText("辅助正在运行。请先在辅助首页停止，再听说明，避免错过游戏提醒。");
    }

    private void startExplanation() {
        stopExplanation();
        if (steps == null || CaptureService.isRunning()) { refreshAvailability(); return; }
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                || audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            status.setText("媒体音量为 0 或已静音。请按音量＋调高，再重听说明。");
            return;
        }
        if (GameProfile.settings(this).getInt("volume", 45) == 0) {
            status.setText("应用提示音量为 0。请到声音与语音设置中调高，再重听说明。");
            return;
        }
        player = new CuePlayer(this);
        hapticUnavailable = false;
        dispatcher = new CueDispatcher(player, GUIDE_POLICY, new CueDispatcher.Listener() {
            @Override public void onDispatch(CueRequest request, CueDispatcher.DispatchResult result) {
                Log.i("ReminderGuide", "Example dispatch=" + result.outcome + " kind=" + request.kind);
            }
            @Override public void onPlayback(CueRequest request, String channel, long at, String result) {
                if ("STARTED".equals(result)) return;
                int run = generation;
                handler.post(() -> {
                    if (run == generation && request.cueId.equals(pendingId))
                        finishStep("COMPLETED".equals(result));
                });
            }
        }, SystemClock::elapsedRealtime);
        repeat.setEnabled(false);
        stop.setEnabled(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        status.setText("正在准备中文语音……");
        waitForVoice(generation, SystemClock.elapsedRealtime() + 3000);
    }

    private void waitForVoice(int run, long until) {
        if (run != generation || player == null) return;
        if (!player.speechReady()) {
            if (SystemClock.elapsedRealtime() < until) {
                handler.postDelayed(() -> waitForVoice(run, until), 100);
            } else {
                stopExplanation();
                status.setText("中文语音未准备好。请到声音与语音设置中选择可用的引擎，再重听说明。");
            }
            return;
        }
        playback.start(steps);
    }

    private void playStep(ReminderGuide.Step step, ReminderGuidePlayback.Completion completion) {
        if (CaptureService.isRunning() || dispatcher == null) { completion.finish(false); return; }
        int run = generation;
        pending = completion;
        pendingId = "guide:" + nextId++;
        CueDispatcher.DispatchResult result = dispatcher.submit(step.request(
                pendingId, SystemClock.elapsedRealtime()));
        if ((result.acceptedChannels & step.channel) == 0) {
            if (step.channel == CueRequest.CHANNEL_HAPTIC) {
                hapticUnavailable = true;
                finishStep(true);
            } else finishStep(false);
        } else if (step.channel == CueRequest.CHANNEL_HAPTIC) {
            // This only sequences the sample; it does not certify perception.
            String id = pendingId;
            handler.postDelayed(() -> {
                if (run == generation && id.equals(pendingId)) finishStep(true);
            }, 2 * CuePlayer.NEAR_HAPTIC_ON_MS + CuePlayer.NEAR_HAPTIC_GAP_MS + 200);
        }
    }

    private void finishStep(boolean success) {
        ReminderGuidePlayback.Completion completion = pending;
        pending = null;
        pendingId = null;
        if (completion != null) completion.finish(success);
    }

    private void stopExplanation() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        playback.stop();
        if (dispatcher != null) dispatcher.close();
        if (player != null) player.close();
        dispatcher = null;
        player = null;
        if (repeat != null) repeat.setEnabled(steps != null && !CaptureService.isRunning());
        if (stop != null) stop.setEnabled(false);
        if (status != null) status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    private void continueStart() {
        if (CaptureService.isRunning() || authorizationInFlight) return;
        stopExplanation();
        authorizationInFlight = true;
        proceed.setEnabled(false);
        startActivityForResult(new Intent(this, CapturePermissionsActivity.class)
                .putExtra(CapturePermissionsActivity.EXTRA_START, true), REQUEST_START);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        if (!autoPending || CaptureService.isRunning() || authorizationInFlight) return;
        autoPending = false;
        AccessibilityManager accessibility = getSystemService(AccessibilityManager.class);
        if (accessibility != null && accessibility.isTouchExplorationEnabled()) {
            status.setText("正在使用屏幕阅读器。可点击“重听说明”，播放声音与震动示例。");
        } else startExplanation();
    }

    @Override protected void onPause() {
        stopExplanation();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("authorization_in_flight", authorizationInFlight);
        super.onSaveInstanceState(state);
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_START) return;
        authorizationInFlight = false;
        if (result == RESULT_OK) { setResult(RESULT_OK); finish(); }
    }
}
