package com.openkhub.sensefield;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Detailed output channel and event controls, linked from game tuning. */
public final class AlertSettingsActivity extends UiActivity {
    private SharedPreferences preferences;
    private LinearLayout competitiveNoticeCard;
    private boolean revertingCompetitiveToggle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);
        preferences = GameProfile.settings(this);
        new CueSettings(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout content = UiKit.page(this);
        scroll.addView(content);

        UiKit.pageHeader(this, content, "提示偏好", "王者荣耀辅助");

        LinearLayout channels = UiKit.card(this);
        SettingHelp.addGroup(this, channels, "channel_group", 8);
        addToggle(channels, "视觉提示", "cue_channel_visual", true);
        addToggle(channels, "提示音", "cue_channel_tone", true);
        addToggle(channels, "语音", "cue_channel_speech", true);
        addToggle(channels, "触觉", "cue_channel_haptic", true);
        UiKit.add(content, channels, UiKit.GAP_SECTION);

        LinearLayout categories = UiKit.card(this);
        SettingHelp.addGroup(this, categories, "events_group", 8);
        addToggle(categories, "附近敌人提醒（小地图近区）", CueSettings.PREF_CATEGORY_NEAR,
                CompetitiveDefaults.CATEGORY_NEAR_DEFAULT);
        addToggle(categories, "附近敌人震动", CueSettings.PREF_NEAR_HAPTIC,
                CompetitiveDefaults.NEAR_HAPTIC_DEFAULT);
        addToggle(categories, "远处新敌人提示音", CueSettings.PREF_FAR_APPEAR,
                CompetitiveDefaults.FAR_APPEAR_DEFAULT);
        addToggle(categories, "小地图新目标", "cue_category_vision", true);
        addToggle(categories, "屏幕边缘威胁", CueSettings.PREF_CATEGORY_PERIPHERAL,
                CompetitiveDefaults.CATEGORY_PERIPHERAL_DEFAULT);
        addToggle(categories, "危险接近", CueSettings.PREF_CATEGORY_DANGER,
                CompetitiveDefaults.CATEGORY_DANGER_DEFAULT);
        addToggle(categories, "玩家死亡／复活", "cue_category_player", true);
        addToggle(categories, "系统状态", "cue_category_system", true);
        UiKit.add(content, categories, 24);

        setContentView(scroll);
    }

    private void addToggle(LinearLayout content, String label, String key, boolean fallback) {
        CheckBox toggle = new CheckBox(this);
        toggle.setText(label);
        toggle.setChecked(preferences.getBoolean(key, fallback));
        toggle.setTextSize(18);
        toggle.setTextColor(UiKit.INK);
        UiKit.styleCheckable(toggle, this);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (revertingCompetitiveToggle) return;
            boolean stored = preferences.getBoolean(key, fallback);
            CompetitiveNoticeGate.Outcome outcome = CompetitiveDefaults.isCompetitiveKey(key)
                    ? CompetitiveNoticeGate.decide(stored, noticeSeen(), checked)
                    : CompetitiveNoticeGate.Outcome.NONE;
            if (!CompetitiveNoticeGate.writesThrough(outcome)) {
                // 闸门拦下时一个字节都不写：勾选框回到本次点击之前的状态，这一路仍是关。
                revertingCompetitiveToggle = true;
                ((CheckBox) button).setChecked(stored);
                revertingCompetitiveToggle = false;
                showCompetitiveNotice(content);
                return;
            }
            preferences.edit().putBoolean(key, checked).apply();
            CueSettings.markCustom(preferences);
        });
        UiKit.add(content, toggle, 6);
    }

    /** 告知是否已经显示过一次；这条键不是提示偏好，写它不改档位。 */
    private boolean noticeSeen() {
        return preferences.getBoolean(CompetitiveNoticeGate.NOTICE_SEEN_KEY, false);
    }

    /**
     * 一次性风险告知：按既有卡片形态就地追加，不开新窗口类型、不加通知渠道。真正显示出来的
     * 这一次才算「看过」，所以下一次主动开启才放行。
     */
    private void showCompetitiveNotice(LinearLayout container) {
        if (competitiveNoticeCard != null) return;
        LinearLayout notice = UiKit.accentCard(this);
        UiKit.add(notice, UiKit.heading(this, CompetitiveNoticeGate.NOTICE_TITLE), 6);
        for (String line : CompetitiveNoticeGate.NOTICE_LINES) {
            UiKit.add(notice, UiKit.body(this, line), 6);
        }
        competitiveNoticeCard = notice;
        UiKit.add(container, notice, 6);
        preferences.edit()
                .putBoolean(CompetitiveNoticeGate.NOTICE_SEEN_KEY, true)
                .apply();
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (!SettingHelp.close(this)) super.onBackPressed();
    }
}
