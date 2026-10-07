package com.openkhub.sensefield;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;

/** Per-event local earcons, without changing recognition or output-channel preferences. */
public final class CueSoundSettingsActivity extends UiActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, TextView> selections = new HashMap<>();
    private SharedPreferences preferences;
    private TextView previewStatus;
    private CuePlayer previewPlayer;
    private AlertDialog picker;
    private int generation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.configureWindow(this);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        preferences = GameProfile.settings(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);
        UiKit.pageHeader(this, content, "提醒音效", "王者荣耀辅助");
        UiKit.add(content, UiKit.body(this,
                "分别选择音效，重新开始辅助后生效。"), UiKit.GAP_SECTION);
        previewStatus = UiKit.hint(this, "");
        previewStatus.setVisibility(View.GONE);
        previewStatus.setTag("cue_sound_preview_status");
        previewStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(content, previewStatus, UiKit.GAP_SECTION);
        for (int index = 0; index < CueSoundLibrary.KINDS.length; index++) {
            int kind = CueSoundLibrary.KINDS[index];
            String event = CueSoundLibrary.EVENTS[index];
            LinearLayout card = UiKit.card(this);
            TextView title = UiKit.heading(this, event);
            UiKit.add(card, title, 8);
            TextView selected = UiKit.body(this, getString(R.string.cue_sound_current, label(kind)));
            selected.setTag("cue_sound_selected:" + kind);
            selections.put(kind, selected);
            UiKit.add(card, selected, 12);
            ReminderSampleGrid actions = new ReminderSampleGrid(this, 2).fillRow();
            Button choose = UiKit.button(this, "选择音效", false);
            choose.setTag("cue_sound_choose:" + kind);
            choose.setContentDescription("为" + event + "选择音效");
            choose.setOnClickListener(view -> choose(kind, event));
            actions.addView(choose);
            Button preview = UiKit.button(this, "试听", false);
            preview.setTag("cue_sound_preview:" + kind);
            preview.setContentDescription("试听" + event + "的当前音效");
            preview.setOnClickListener(view -> preview(kind, event));
            actions.addView(preview);
            UiKit.add(card, actions, 0);
            UiKit.add(content, card, UiKit.GAP_SECTION);
        }
        setContentView(scroll);
    }

    private String label(int kind) {
        return CueSoundLibrary.label(preferences.getString(CueSoundLibrary.preferenceKey(kind), "classic"));
    }

    private void choose(int kind, String event) {
        stopPreview();
        ArrayAdapter<String> choices = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_single_choice, CueSoundLibrary.LABELS) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView item = (TextView) super.getView(position, recycled, parent);
                item.setTextSize(18);
                item.setTextColor(UiKit.INK);
                item.setSingleLine(false);
                item.setMinimumHeight(UiKit.dp(CueSoundSettingsActivity.this, 64));
                return item;
            }
        };
        picker = new AlertDialog.Builder(this).setTitle(event + "音效")
                .setSingleChoiceItems(choices, CueSoundLibrary.index(preferences.getString(
                        CueSoundLibrary.preferenceKey(kind), "classic")), (dialog, which) -> {
                    preferences.edit().putString(CueSoundLibrary.preferenceKey(kind),
                            CueSoundLibrary.IDS[which]).apply();
                    selections.get(kind).setText(getString(R.string.cue_sound_current, label(kind)));
                    showStatus(getString(R.string.cue_sound_saved, event, label(kind)));
                    dialog.dismiss();
                    preview(kind, event);
                }).setNegativeButton("取消", null).show();
        UiKit.styleDialog(picker, UiKit.ButtonStyle.OUTLINED);
        picker.setOnDismissListener(dialog -> picker = null);
    }

    private void preview(int kind, String event) {
        stopPreview();
        if (CaptureService.isRunning() || Match3LiveService.isRunning()
                || preferences.getBoolean("capture_active", false)) {
            showStatus("请先停止游戏辅助，再试听音效。");
            return;
        }
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null || !NearCueTestPolicy.audioAvailable(
                audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                audio.isStreamMute(AudioManager.STREAM_MUSIC), preferences.getInt("volume", 45))) {
            showStatus("媒体静音或音量为 0，请调整手机媒体音量和听野提示音量后重试。");
            return;
        }
        int current = generation;
        long now = SystemClock.elapsedRealtime();
        previewPlayer = CuePlayer.tonePreview(this);
        CueRequest request = new CueRequest("sound-preview", "sound-preview:" + now,
                "sound-preview:" + now, "SOUND_PREVIEW", CueRequest.Category.SYSTEM, 80,
                now, now + 2000, CueRequest.CHANNEL_TONE, kind, 0, 0, null);
        showStatus(getString(R.string.cue_sound_preparing, event, label(kind)));
        boolean accepted = previewPlayer.playTone(request, new CueDispatcher.PlaybackCallback() {
            @Override public void onStarted(long atMs) {
                handler.post(() -> {
                    if (current == generation)
                        showStatus(getString(R.string.cue_sound_playing, event, label(kind)));
                });
            }
            @Override public void onFinished(long atMs, boolean success) {
                handler.post(() -> {
                    if (current != generation) return;
                    stopPreview();
                    showStatus(success ? getString(R.string.cue_sound_done, event, label(kind))
                            : "未能播放这次音效，请检查音量后重试。");
                });
            }
        });
        if (!accepted) {
            stopPreview();
            showStatus("未能准备这次音效，请重试。");
            return;
        }
        handler.postDelayed(() -> {
            if (current != generation) return;
            stopPreview();
            showStatus("试听未完成，请重试。");
        }, 2500);
    }

    private void stopPreview() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (previewPlayer != null) {
            previewPlayer.close();
            previewPlayer = null;
        }
    }

    private void showStatus(CharSequence value) {
        previewStatus.setText(value);
        previewStatus.setVisibility(View.VISIBLE);
    }

    @Override protected void onPause() {
        boolean wasPlaying = previewPlayer != null;
        stopPreview();
        if (picker != null) picker.dismiss();
        if (wasPlaying) showStatus("试听已停止，可选择音效并重新试听。");
        super.onPause();
    }
}
