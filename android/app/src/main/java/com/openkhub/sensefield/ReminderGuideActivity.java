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
import java.util.ArrayList;
import java.util.List;

/** First-start narration, plus a silent learning directory for later manual review. */
public final class ReminderGuideActivity extends UiActivity {
    static final String EXTRA_START = "start_after_reminder_guide";
    static final String EXTRA_ITEM = "reminder_guide_item";
    static final String EXTRA_FULL = "reminder_guide_full";
    static final String EXTRA_AUTO_READ = "reminder_guide_auto_read";
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
                    if (progress != null) progress.setText("进度 " + (playback.currentIndex() + 1)
                            + " / " + playback.stepCount());
                }
                @Override public void onEnded(boolean success) {
                    if (success && fullMode && steps == fullSteps && selectedSectionTitle == null)
                        markFullGuideCompleted();
                    stopExplanation();
                    clearBookmark();
                    status.setText(success ? hapticUnavailable
                            ? "手机未能请求振动，请检查设备及系统振动设置。"
                            : selectedSectionTitle != null
                                    ? "这一段已播放完。可以重听本段，或选择其他段落。"
                                    : "已播放完。可以再次播放，也可以返回列表。"
                            : "未能播放完。请检查音量，再重试。");
                    if (!success) offerManualReading();
                }
            });
    private List<ReminderGuide.Step> steps;
    private List<ReminderGuide.Step> fullSteps;
    private final List<Button> sectionButtons = new ArrayList<>();
    private TextView status;
    private TextView progress;
    private Button repeat;
    private Button stop;
    private Button proceed;
    private Button footerProceed;
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
    private boolean fullMode;
    private boolean preparing;
    private boolean pausedBeforeStart;
    private String selectedSectionTitle;
    private String selectedSectionId;
    private int resumeIndex = -1;
    private String resumeSectionId;
    private String resumeSignature;
    private int startIndex;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.configureWindow(this);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        startAfterGuide = getIntent().getBooleanExtra(EXTRA_START, false);
        autoPending = state == null && (getIntent().hasExtra(EXTRA_ITEM)
                || getIntent().getBooleanExtra(EXTRA_AUTO_READ, false));
        authorizationInFlight = state != null && state.getBoolean("authorization_in_flight", false);
        if (state != null) {
            resumeIndex = state.getInt("guide_resume_index", -1);
            resumeSectionId = state.getString("guide_resume_section");
            resumeSignature = state.getString("guide_resume_signature");
        }
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);
        boolean full = getIntent().getBooleanExtra(EXTRA_FULL, false);
        fullMode = full;
        String itemId = getIntent().getStringExtra(EXTRA_ITEM);
        boolean detail = full || itemId != null;
        TextView title = UiKit.pageHeader(this, content,
                full ? "完整提醒说明" : "提醒试听", "王者荣耀辅助");
        if (!detail) UiKit.add(content, UiKit.navigationRow(this, "完整说明", "按段收听，支持暂停与继续",
                () -> startActivity(new Intent(this, ReminderGuideActivity.class).putExtra(EXTRA_FULL, true))), 16);
        status = full ? UiKit.text(this, "尚未播放", 18, UiKit.MUTED, false)
                : detail ? body("可单独播放和重听。这里都是示例。")
                : UiKit.body(this, "点选试听，可反复播放。");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout fullControls = null;
        progress = null;
        if (full) {
            fullControls = playbackBar();
            fullControls.setTag("reminder_guide_playback_controls");
            fullControls.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 14),
                    UiKit.dp(this, 18), UiKit.dp(this, 14));
            // A top rule and shadow keep the fixed bar visually separate from the scrolling text.
            android.graphics.drawable.GradientDrawable rule =
                    new android.graphics.drawable.GradientDrawable();
            rule.setColor(UiKit.OUTLINE);
            android.graphics.drawable.LayerDrawable bar = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[] {rule,
                            new android.graphics.drawable.ColorDrawable(UiKit.SURFACE)});
            bar.setLayerInset(1, 0, UiKit.dp(this, 1), 0, 0);
            fullControls.setBackground(bar);
            fullControls.setElevation(UiKit.dp(this, 8));
            LinearLayout summary = UiKit.vertical(this);
            progress = UiKit.hint(this, "选择一段收听，或播放全文");
            UiKit.add(summary, progress, 6);
            ScrollView statusScroll = new ScrollView(this) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    // A long engine/error message remains readable by scrolling while
                    // the guide text and playback actions keep their own usable space.
                    int cap = UiKit.dp(getContext(), getResources().getConfiguration().screenHeightDp < 500 ? 84 : 132);
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
                }
            };
            statusScroll.addView(status);
            UiKit.add(summary, statusScroll, 0);
            UiKit.add(fullControls, summary, 12);
        } else UiKit.add(content, status, 18);
        repeat = null;
        stop = null;
        proceed = null;
        footerProceed = null;
        steps = null;
        fullSteps = null;
        selectedSectionTitle = null;
        selectedSectionId = null;
        sectionButtons.clear();
        if (startAfterGuide) {
            proceed = button(content, "跳过并开始", true);
            proceed.setTag("reminder_guide_start_top");
            proceed.setOnClickListener(view -> continueStart());
            UiKit.add(content, UiKit.hint(this, "说明可随时跳过，以后在设置里按段重听。"), 12);
        }
        try {
            ReminderGuide.Outputs outputs = currentOutputs();
            PresentationAudioPolicy policy = PresentationAudioPolicy.from(GameProfile.settings(this));
            List<ReminderGuideCatalog.Item> items = ReminderGuideCatalog.build(outputs, policy.nearTwoWord, policy.distanceHaptic);
            if (!detail) {
                if (items.isEmpty()) UiKit.add(content, body("当前没有可试听的提示，请到提示偏好中检查开启的通道与事件。"), 12);
                for (ReminderGuideCatalog.Section section : ReminderGuideCatalog.Section.values())
                    sampleSection(content, items, section);
            } else if (full) {
                fullSteps = ReminderGuideCatalog.fullGuide(outputs, policy.nearTwoWord);
                steps = fullSteps;
                fullPlaybackControls(fullControls);
                for (ReminderGuideSections.Section section : ReminderGuideSections.build(fullSteps)) {
                    LinearLayout card = UiKit.card(this);
                    TextView heading = UiKit.heading(this, section.title);
                    Button playSection = compactButton("听本段");
                    playSection.setTag(section.id);
                    playSection.setContentDescription("朗读这一段：" + section.title);
                    playSection.setOnClickListener(view -> {
                        steps = section.steps;
                        selectedSectionTitle = section.title;
                        selectedSectionId = section.id;
                        clearBookmark();
                        startExplanation();
                    });
                    sectionButtons.add(playSection);
                    UiKit.add(card, UiKit.headingActionRow(this, heading, playSection), 12);
                    UiKit.add(card, UiKit.readingSections(this, section.text), 0);
                    UiKit.add(content, card, UiKit.GAP_SECTION);
                }
            } else {
                ReminderGuideCatalog.Item item = ReminderGuideCatalog.find(items, itemId);
                if (item == null) {
                    autoPending = false;
                    status.setText("这项提醒当前未开启，请返回列表或检查提示偏好。");
                } else {
                    title.setText(item.title);
                    UiKit.add(content, UiKit.readingSections(this, item.explanation), 18);
                    steps = item.samples;
                    playbackControls(content, "播放示例／再次播放");
                    if (steps.get(0).channel == CueRequest.CHANNEL_HAPTIC)
                        button(content, "震感与节奏设置").setOnClickListener(view ->
                                startActivity(new Intent(this, HapticSettingsActivity.class)));
                }
            }
        } catch (IOException | JSONException failure) {
            autoPending = false;
            status.setText("无法读取当前提醒配置，请到提示配置中检查。");
            Log.w("ReminderGuide", "Could not resolve guide outputs", failure);
        }
        if (detail) {
            button(content, "声音与提示配置").setOnClickListener(view ->
                    startActivity(new Intent(this, GameTuningActivity.class)));
            UiKit.add(content, UiKit.navigationRow(this, "返回试听列表", "", this::finish), 0);
        } else {
            UiKit.gap(content, 8);
            ReminderSampleGrid footer = UiKit.actionRow(this);
            Button settings = compactButton("提示设置");
            settings.setContentDescription("声音与提示配置");
            settings.setOnClickListener(view -> startActivity(new Intent(this, GameTuningActivity.class)));
            footer.addView(settings);
            UiKit.add(content, footer, 0);
        }
        if (full) {
            LinearLayout page = UiKit.vertical(this);
            page.setFitsSystemWindows(true);
            page.setBackgroundColor(UiKit.PAGE);
            scroll.setFitsSystemWindows(false);
            page.addView(scroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            page.addView(fullControls, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            setContentView(page);
        } else setContentView(scroll);
        refreshAvailability();
        restoreBookmark();
    }

    private Button button(LinearLayout parent, String text) { return button(parent, text, false); }

    private Button compactButton(String label) {
        return compactButton(label, false);
    }

    private Button compactButton(String label, boolean primary) {
        Button button = UiKit.button(this, label, primary);
        button.setTextSize(primary ? 20 : 18);
        button.setMinHeight(UiKit.dp(this, primary ? 64 : 56));
        button.setMinimumHeight(UiKit.dp(this, primary ? 64 : 56));
        button.setMinWidth(UiKit.dp(this, 56));
        button.setMinimumWidth(UiKit.dp(this, 56));
        button.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6), UiKit.dp(this, 8), UiKit.dp(this, 6));
        return button;
    }

    private void sampleSection(LinearLayout content, List<ReminderGuideCatalog.Item> items,
                               ReminderGuideCatalog.Section section) {
        ReminderSampleGrid grid = new ReminderSampleGrid(this, 2, true);
        for (ReminderGuideCatalog.Item item : items) {
            if (ReminderGuideCatalog.section(item) != section) continue;
            Button entry = compactButton(ReminderGuideCatalog.compactLabel(item));
            entry.setTag(item.id);
            UiKit.styleControl(entry, UiKit.ButtonStyle.TONAL, 12);
            entry.setContentDescription(section.title + "，" + item.title + "，打开说明与单项试听");
            entry.setOnClickListener(view -> startActivity(new Intent(this, ReminderGuideActivity.class)
                    .putExtra(EXTRA_ITEM, item.id)));
            grid.addView(entry);
        }
        if (grid.getChildCount() == 0) return;
        LinearLayout group = UiKit.vertical(this);
        TextView heading = UiKit.text(this, section.title, 20, UiKit.INK, true);
        heading.setAccessibilityHeading(true);
        UiKit.add(group, heading, 10);
        UiKit.add(group, grid, 0);
        UiKit.add(content, group, 24);
    }

    private void playbackControls(LinearLayout content, String label) {
        repeat = button(content, label, true);
        repeat.setOnClickListener(view -> startExplanation());
        stop = button(content, "停止播放", false);
        stop.setEnabled(false);
        stop.setOnClickListener(view -> {
            stopExplanation();
            clearBookmark();
            status.setText("已停止。可以再次播放当前示例。");
        });
    }

    private void fullPlaybackControls(LinearLayout content) {
        ReminderSampleGrid controls = new ReminderSampleGrid(this, 2);
        repeat = compactButton("播放全文", !startAfterGuide);
        repeat.setContentDescription("播放完整提醒说明，可暂停后继续");
        repeat.setOnClickListener(view -> {
            if (pausedBeforeStart || playback.isPaused()) resumeExplanation();
            else if (preparing || playback.isRunning()) pauseExplanation();
            else if (resumeIndex >= 0) startExplanation(true);
            else {
                steps = fullSteps;
                selectedSectionTitle = null;
                selectedSectionId = null;
                startExplanation();
            }
        });
        controls.addView(repeat);
        if (startAfterGuide) {
            // Keep starting available even after scrolling through a long guide.
            // Pause already stops narration; replace the redundant stop action.
            footerProceed = compactButton("跳过并开始", true);
            footerProceed.setTag("reminder_guide_start_footer");
            footerProceed.setOnClickListener(view -> continueStart());
            controls.addView(footerProceed);
            UiKit.add(content, controls, 0);
            return;
        }
        stop = compactButton("停止播放");
        stop.setEnabled(false);
        stop.setOnClickListener(view -> {
            stopExplanation();
            clearBookmark();
            status.setText("已停止。可选择任意一段重听，或播放全文。");
        });
        controls.addView(stop);
        UiKit.add(content, controls, 0);
    }

    private LinearLayout playbackBar() {
        LinearLayout bar = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                boolean side = MeasureSpec.getSize(widthSpec) >= UiKit.dp(getContext(), 600)
                        && getResources().getConfiguration().screenHeightDp < 500;
                int orientation = side ? HORIZONTAL : VERTICAL;
                if (getChildCount() == 2) {
                    LinearLayout.LayoutParams summary = (LinearLayout.LayoutParams) getChildAt(0).getLayoutParams();
                    if (getOrientation() != orientation || summary.weight != (side ? 1 : 0)) {
                        setOrientation(orientation);
                        for (int i = 0; i < 2; i++) {
                            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) getChildAt(i).getLayoutParams();
                            params.width = side ? 0 : LayoutParams.MATCH_PARENT;
                            params.weight = side ? 1 : 0;
                            params.setMargins(0, 0, 0, !side && i == 0 ? UiKit.dp(getContext(), 12) : 0);
                            params.setMarginEnd(side && i == 0 ? UiKit.dp(getContext(), 20) : 0);
                            getChildAt(i).setLayoutParams(params);
                        }
                    }
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return bar;
    }

    private ReminderGuide.Outputs currentOutputs() throws IOException, JSONException {
        GameProfile profile = GameProfile.load(this);
        CueSettings settings = new CueSettings(this);
        int near = profile.relation != null && settings.categoryEnabled(CueRequest.Category.NEAR_ZONE)
                ? settings.nearRequestedChannels() : 0;
        boolean appearances = (profile.minimapYolox || profile.flags[1] != 0 || profile.flags[2] != 0)
                && CueSettings.visionMemoryDispatchEnabled(GameProfile.settings(this).getBoolean(
                        GameProfile.PREF_VISION_MEMORY, GameProfile.DEFAULT_VISION_MEMORY),
                        settings.categoryEnabled(CueRequest.Category.VISION_MEMORY))
                && NearZoneRouting.farAppearAudible(profile.relation != null,
                        settings.categoryEnabled(CueRequest.Category.NEAR_ZONE), settings.farAppearPreference());
        int appearance = appearances ? settings.enabledChannels(CueRequest.Category.VISION_MEMORY)
                & (CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC
                    | (settings.speakAppear() ? CueRequest.CHANNEL_SPEECH : 0)) : 0;
        int playerChannels = profile.playerLife != null && settings.categoryEnabled(CueRequest.Category.PLAYER_STATE)
                ? settings.enabledChannels(CueRequest.Category.PLAYER_STATE)
                        & (CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC) : 0;
        int danger = profile.flags[3] != 0 && settings.categoryEnabled(CueRequest.Category.DANGER)
                ? settings.enabledChannels(CueRequest.Category.DANGER) & CueRouting.directCueRequestedChannels(3, 0) : 0;
        boolean peripheral = profile.flags[0] != 0 && settings.categoryEnabled(CueRequest.Category.PERIPHERAL_THREAT)
                && (settings.enabledChannels(CueRequest.Category.PERIPHERAL_THREAT) & CueRequest.CHANNEL_TONE) != 0;
        boolean systemSpeech = settings.categoryEnabled(CueRequest.Category.SYSTEM)
                && (settings.enabledChannels(CueRequest.Category.SYSTEM) & CueRequest.CHANNEL_SPEECH) != 0;
        return new ReminderGuide.Outputs(near, appearance, playerChannels, danger, peripheral, systemSpeech);
    }

    private boolean liveRunning() { return CaptureService.isRunning() || Match3LiveService.isRunning(); }

    private TextView body(String text) { return UiKit.body(this, text); }

    private Button button(LinearLayout parent, String text, boolean primary) {
        Button button = UiKit.button(this, text, primary);
        UiKit.add(parent, button, 16);
        return button;
    }

    private void refreshAvailability() {
        boolean running = liveRunning();
        if (repeat != null) repeat.setEnabled(!running && steps != null);
        for (Button section : sectionButtons) section.setEnabled(!running);
        refreshProceed();
        if (running) status.setText("辅助正在运行。请先在辅助首页停止，再听说明，避免错过游戏提醒。");
    }

    private boolean fullGuideCompleted() {
        return GameProfile.settings(this).getBoolean(ReminderGuide.PREF_FULL_GUIDE_COMPLETED, false);
    }

    private void markFullGuideCompleted() {
        GameProfile.settings(this).edit()
                .putBoolean(ReminderGuide.PREF_FULL_GUIDE_COMPLETED, true)
                .remove(ReminderGuide.PREF_FULL_GUIDE_SKIPPED).apply();
        refreshProceed();
    }

    private void refreshProceed() {
        String label = fullGuideCompleted() ? "开始辅助" : "跳过并开始";
        boolean enabled = !liveRunning() && !authorizationInFlight;
        for (Button action : new Button[] {proceed, footerProceed}) {
            if (action == null) continue;
            action.setText(label);
            action.setContentDescription(label + "，进入必要授权并启动王者荣耀辅助");
            action.setEnabled(enabled);
        }
    }

    private void offerManualReading() {
        if (!startAfterGuide || !fullMode || fullGuideCompleted()) return;
        status.append(" 可先阅读本页，或点击“跳过并开始”。以后也能在设置里重听。");
        refreshProceed();
    }

    private void startExplanation() {
        startExplanation(false);
    }

    private void startExplanation(boolean restoring) {
        int position = restoring ? Math.max(0, resumeIndex) : 0;
        clearBookmark();
        stopExplanation();
        startIndex = position;
        if (steps == null || liveRunning()) { refreshAvailability(); return; }
        refreshProceed();
        boolean audioNeeded = steps.stream().anyMatch(step -> step.channel != CueRequest.CHANNEL_HAPTIC);
        AudioManager audio = getSystemService(AudioManager.class);
        if (audioNeeded && (audio == null || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                || audio.isStreamMute(AudioManager.STREAM_MUSIC))) {
            status.setText("媒体音量为 0 或已静音。请按音量＋调高，再重听说明。");
            offerManualReading();
            return;
        }
        if (audioNeeded && GameProfile.settings(this).getInt("volume", 45) == 0) {
            status.setText("应用提示音量为 0。请到声音与语音设置中调高，再重听说明。");
            offerManualReading();
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
        repeat.setEnabled(fullMode);
        if (fullMode) {
            repeat.setText("暂停");
            repeat.setContentDescription("暂停当前说明");
        }
        if (stop != null) stop.setEnabled(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        boolean voiceNeeded = steps.stream().anyMatch(step -> step.channel == CueRequest.CHANNEL_SPEECH);
        if (voiceNeeded) {
            preparing = true;
            status.setText("正在准备内置离线语音……");
            waitForVoice(generation, SystemClock.elapsedRealtime() + 3000);
        } else playback.startAt(ReminderGuideSections.sentenceSteps(steps), startIndex);
    }

    private void waitForVoice(int run, long until) {
        if (run != generation || player == null) return;
        if (!player.speechReady()) {
            if (SystemClock.elapsedRealtime() < until) {
                handler.postDelayed(() -> waitForVoice(run, until), 100);
            } else {
                stopExplanation();
                status.setText("内置语音未准备好。可以阅读说明，或跳过并开始辅助。");
                offerManualReading();
            }
            return;
        }
        preparing = false;
        playback.startAt(ReminderGuideSections.sentenceSteps(steps), startIndex);
    }

    private void pauseExplanation() {
        if (liveRunning()) { stopExplanation(); refreshAvailability(); return; }
        if (!preparing && !playback.isRunning()) return;
        boolean waitingForVoice = preparing;
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (waitingForVoice) {
            pending = null;
            pendingId = null;
            if (dispatcher != null) dispatcher.clearAll();
        } else playback.pause();
        if (player != null) player.cancelHaptics();
        preparing = false;
        pausedBeforeStart = waitingForVoice;
        repeat.setText("继续播放");
        repeat.setContentDescription("继续当前说明，从未听完的句子开始");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText("已暂停。继续时从未听完的句子开始。");
    }

    private void resumeExplanation() {
        if (liveRunning()) { stopExplanation(); refreshAvailability(); return; }
        if (pausedBeforeStart) {
            resumeIndex = startIndex;
            startExplanation(true);
            return;
        }
        if (!playback.isPaused() || player == null || dispatcher == null) return;
        repeat.setText("暂停");
        repeat.setContentDescription("暂停当前说明");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        playback.resume();
    }

    private void playStep(ReminderGuide.Step step, ReminderGuidePlayback.Completion completion) {
        if (liveRunning() || dispatcher == null) { completion.finish(false); return; }
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
            }, player.hapticDurationMs(step.request("duration", SystemClock.elapsedRealtime())) + 200);
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
        preparing = false;
        pausedBeforeStart = false;
        handler.removeCallbacksAndMessages(null);
        playback.stop();
        if (dispatcher != null) dispatcher.close();
        if (player != null) { player.cancelHaptics(); player.close(); }
        dispatcher = null;
        player = null;
        if (repeat != null) repeat.setEnabled(steps != null && !liveRunning());
        if (fullMode && repeat != null) {
            repeat.setText("播放全文");
            repeat.setContentDescription("播放完整提醒说明，可暂停后继续");
        }
        if (stop != null) stop.setEnabled(false);
        if (status != null) status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    private void continueStart() {
        if (liveRunning() || authorizationInFlight) return;
        if (!fullGuideCompleted()) {
            GameProfile.settings(this).edit()
                    .putBoolean(ReminderGuide.PREF_FULL_GUIDE_SKIPPED, true).apply();
        }
        autoPending = false;
        stopExplanation();
        clearBookmark();
        authorizationInFlight = true;
        refreshProceed();
        startActivityForResult(new Intent(this, CapturePermissionsActivity.class)
                .putExtra(CapturePermissionsActivity.EXTRA_START, true), REQUEST_START);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        if (!autoPending || steps == null || liveRunning() || authorizationInFlight) return;
        autoPending = false;
        AccessibilityManager accessibility = getSystemService(AccessibilityManager.class);
        if (accessibility != null && accessibility.isTouchExplorationEnabled()) {
            status.setText(fullMode
                    ? "正在使用屏幕阅读器。可以阅读完整说明，或点击“播放全文”收听。"
                    : "正在使用屏幕阅读器，请点击“播放示例／再次播放”试听当前这一项。");
            offerManualReading();
        } else startExplanation();
    }

    @Override protected void onPause() {
        bookmarkPlayback();
        stopExplanation();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        bookmarkPlayback();
        state.putBoolean("authorization_in_flight", authorizationInFlight);
        state.putInt("guide_resume_index", resumeIndex);
        state.putString("guide_resume_section", resumeSectionId);
        state.putString("guide_resume_signature", resumeSignature);
        super.onSaveInstanceState(state);
    }

    private void bookmarkPlayback() {
        if (!fullMode || steps == null || (!preparing && !pausedBeforeStart && !playback.isRunning())) return;
        resumeIndex = preparing || pausedBeforeStart ? startIndex : playback.currentIndex();
        resumeSectionId = selectedSectionId;
        resumeSignature = stepSignature(steps);
    }

    private void restoreBookmark() {
        if (!fullMode || fullSteps == null || resumeIndex < 0) return;
        List<ReminderGuide.Step> candidate = fullSteps;
        if (resumeSectionId != null) {
            candidate = null;
            for (ReminderGuideSections.Section section : ReminderGuideSections.build(fullSteps)) {
                if (resumeSectionId.equals(section.id)) {
                    candidate = section.steps;
                    selectedSectionTitle = section.title;
                    selectedSectionId = section.id;
                    break;
                }
            }
        }
        if (candidate == null || !stepSignature(candidate).equals(resumeSignature)
                || resumeIndex >= ReminderGuideSections.sentenceSteps(candidate).size()) {
            clearBookmark();
            return;
        }
        steps = candidate;
        repeat.setText("继续播放");
        repeat.setContentDescription("继续当前说明，从未听完的句子开始");
        status.setText("已暂停。点击继续，从未听完的句子开始。");
        if (progress != null) progress.setText("进度 " + (resumeIndex + 1) + " / "
                + ReminderGuideSections.sentenceSteps(candidate).size());
        if (stop != null) stop.setEnabled(true);
    }

    private void clearBookmark() {
        resumeIndex = -1;
        resumeSectionId = null;
        resumeSignature = null;
    }

    private static String stepSignature(List<ReminderGuide.Step> values) {
        StringBuilder result = new StringBuilder();
        for (ReminderGuide.Step step : values) result.append(step.title).append('|').append(step.text)
                .append('|').append(step.channel).append('|').append(step.tone).append('|').append(step.pan)
                .append('|').append(step.distance).append('|').append(step.urgency).append('\n');
        return result.toString();
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_START) return;
        authorizationInFlight = false;
        if (result == RESULT_OK) { setResult(RESULT_OK); finish(); }
        else refreshProceed();
    }
}
