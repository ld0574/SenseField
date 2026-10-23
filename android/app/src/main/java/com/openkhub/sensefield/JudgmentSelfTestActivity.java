package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 判定自测台：多场景排查入口（L2 判定层联调）+ 演示播报（真实 TTS/音调/震动链路）。
 * 场景卡在设置卡上方：填完设置点「保存设置」即自动滚到结果区，不会被软键盘挡住。
 * 本页只在用户显式开启判定层并配置渠道后才会发起网络请求；默认关闭。
 */
public class JudgmentSelfTestActivity extends UiActivity {
    private Switch enabledSwitch;
    private Spinner channelSpinner;
    private Spinner langSpinner;
    private EditText apiKeyInput;
    private EditText localBaseUrlInput;
    private TextView output;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);
        LinearLayout page = UiKit.page(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        UiKit.pageHeader(this, page, "判定自测", "消消乐 · 实验功能");
        UiKit.add(page, note("问题语言默认简体中文（可切 English 对照）。判定经所选渠道真发请求，低置信按铁律出声播报「没看清」。"), 20);

        /* ---------- 多场景测试（放在最上，避免被软键盘挡住） ---------- */
        LinearLayout scenarios = card(page, "多场景测试（先在下方保存设置，再点场景）");
        Button match3 = UiKit.button(this, "场景 1：消消乐五问", true);
        match3.setOnClickListener(v -> runScenario("match3", false));
        scenarios.addView(match3);
        Button axtree = UiKit.button(this, "场景 2：中文无障碍树五问", false);
        axtree.setOnClickListener(v -> runScenario("menu-axtree", false));
        scenarios.addView(axtree);
        Button errorPath = UiKit.button(this, "场景 3：错误路径（choice 缺必填 criteria）", false);
        errorPath.setOnClickListener(v -> runScenario("match3", true));
        scenarios.addView(errorPath);
        Button demoCue = UiKit.button(this, "演示播报（真实 TTS／音调／震动，不需要游戏画面）", false);
        demoCue.setOnClickListener(v -> runDemoAnnouncements());
        scenarios.addView(demoCue);

        /* ---------- 设置卡片 ---------- */
        LinearLayout settings = card(page, "启用判定层（默认关闭；开启后按所选渠道发起网络请求）");
        enabledSwitch = new Switch(this);
        enabledSwitch.setText("启用 Jev 判定层");
        enabledSwitch.setTextSize(UiKit.TEXT_BODY);
        enabledSwitch.setTextColor(UiKit.INK);
        enabledSwitch.setChecked(JevSettings.enabled(this));
        UiKit.styleCheckable(enabledSwitch, this);
        settings.addView(enabledSwitch);
        channelSpinner = new Spinner(this);
        UiKit.styleSpinner(channelSpinner, new String[] {
                "openrouter（OpenRouter typesafe/jev-1.13）",
                "edgeone（腾讯 EdgeOne Makers @makers/jev）",
                "local（本机薄适配服务，模拟器访问宿主机用 http://10.0.2.2:8080）"});
        int savedIndex = channelIndex(JevSettings.channel(this));
        channelSpinner.setSelection(savedIndex < 0 ? 0 : savedIndex);
        settings.addView(UiKit.field(this, "渠道", channelSpinner));
        langSpinner = new Spinner(this);
        UiKit.styleSpinner(langSpinner, new String[] {"简体中文（默认）", "English"});
        langSpinner.setSelection(JevSettings.LANG_EN.equals(JevSettings.lang(this)) ? 1 : 0);
        settings.addView(UiKit.field(this,
                "问题语言（默认简体中文；English 用于与英文训练语言对照实测）", langSpinner));
        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("sk-or-v1-… 或 EdgeOne 的 key");
        apiKeyInput.setText(JevSettings.apiKey(this));
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        UiKit.styleInput(apiKeyInput);
        settings.addView(UiKit.field(this, "API Key（仅存本机 SharedPreferences）", apiKeyInput));
        localBaseUrlInput = new EditText(this);
        localBaseUrlInput.setHint("http://10.0.2.2:8080");
        localBaseUrlInput.setText(JevSettings.localBaseUrl(this));
        localBaseUrlInput.setSingleLine(true);
        localBaseUrlInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        UiKit.styleInput(localBaseUrlInput);
        settings.addView(UiKit.field(this, "本地服务 baseUrl（local 渠道用）", localBaseUrlInput));
        Button save = UiKit.button(this, "保存设置", true);
        save.setOnClickListener(v -> saveSettings());
        settings.addView(save);

        /* ---------- 输出卡片 ---------- */
        LinearLayout out = card(page, "运行结果");
        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(18);
        output.setTextColor(UiKit.INK);
        output.setLineSpacing(UiKit.dp(this, 2), 1f);
        output.setTextIsSelectable(true);
        output.setFocusable(true);
        output.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        output.setText("等待运行…");
        out.addView(output);
    }

    /** Adds a titled card; every child is separated by the standard control gap. */
    private LinearLayout card(LinearLayout page, String title) {
        LinearLayout card = UiKit.card(this);
        UiKit.add(card, UiKit.heading(this, title), 12);
        LinearLayout content = UiKit.vertical(this);
        UiKit.spaceChildren(content, UiKit.GAP_CONTROL);
        UiKit.add(card, content, 0);
        UiKit.add(page, card, UiKit.GAP_SECTION);
        return content;
    }

    private void saveSettings() {
        JevSettings.setEnabled(this, enabledSwitch.isChecked());
        String channel = channelSpinner.getSelectedItemPosition() == 2 ? JevSettings.CHANNEL_LOCAL
                : channelSpinner.getSelectedItemPosition() == 1 ? JevSettings.CHANNEL_EDGEONE
                : JevSettings.CHANNEL_OPENROUTER;
        JevSettings.setChannel(this, channel);
        JevSettings.setLang(this, langSpinner.getSelectedItemPosition() == 1 ? JevSettings.LANG_EN : JevSettings.LANG_ZH);
        JevSettings.setApiKey(this, apiKeyInput.getText().toString());
        JevSettings.setLocalBaseUrl(this, localBaseUrlInput.getText().toString());
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus();
        if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        Toast.makeText(this, "判定层设置已保存（语言："
                + (JevSettings.LANG_EN.equals(JevSettings.lang(this)) ? "English" : "简体中文") + "）",
                Toast.LENGTH_SHORT).show();
        output.requestFocus();
    }

    private void runScenario(String assetName, boolean breakContract) {
        final JevClient client = JevSettings.clientOrNull(this);
        StringBuilder header = new StringBuilder();
        if (client == null) {
            output.setText("【未运行】判定层未启用或配置不完整：请先打开「启用 Jev 判定层」并填写 Key（local 渠道填 baseUrl），保存后再点场景。\n低置信绝不静默——同理，配置不齐也不能悄悄失败。");
            return;
        }
        final String lang = JevSettings.lang(this);
        header.append("渠道: ").append(JevSettings.channel(this))
                .append(" · 问题语言: ").append("en".equals(lang) ? "English" : "简体中文")
                .append("\n场景: ").append(assetName)
                .append(breakContract ? "（错误路径：choice 缺 criteria）" : "").append("\n运行中…\n");
        output.setText(header.toString());

        Thread thread = new Thread(() -> {
            String result;
            try {
                JSONObject asset = readAsset("jev/" + assetName + ".json");
                Object state = asset.has("state") ? asset.getJSONObject("state") : asset;
                Map<String, JevQuestion> questions = JevSettings.defaultQuestions(lang);
                if (breakContract) {
                    // 故意去掉 choice 的必填 criteria，预期渠道返回 400/422 校验错误
                    questions.clear();
                    questions.put("next_action", new JevQuestion("next_action", JevQuestion.TYPE_CHOICE,
                            "What should the assistant do next?", null));
                }
                JevResult r = client.judge(state, questions);
                result = header + formatResult(r);
            } catch (JevException e) {
                result = header + "【判定失败 · " + e.kind + "】HTTP " + e.status + "\n网关原文：\n" + e.bodyText;
            } catch (Exception e) {
                result = header + "【自测异常】" + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final String text = result;
            runOnUiThread(() -> output.setText(text));
        }, "jev-selftest");
        thread.start();
    }

    /** 演示播报：绕过识别层，直接走 CueDispatcher→CuePlayer 的真实 TTS/音调/震动链路。
     *  用途：模拟器上没有游戏画面与识别模型时，验证「播报」半条链路是否正常。
     *  策略用宽容版（全类目全通道、零去重）——演示是测试工具，不继承产品默认的类目开关。 */
    private void runDemoAnnouncements() {
        output.setText("演示播报：依次播 3 条提示（危险→近身→系统），注意听语音/音调、感受震动。\n");
        CuePlayer player = new CuePlayer(this);
        CueDispatcher.Policy permissive = new CueDispatcher.Policy() {
            @Override public boolean categoryEnabled(CueRequest.Category category) { return true; }
            @Override public int enabledChannels() {
                return CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC;
            }
            @Override public long dedupeWindowMs(CueRequest.Category category) { return 0; }
        };
        final CueDispatcher dispatcher = new CueDispatcher(player, permissive,
                new CueDispatcher.Listener() {
                    @Override public void onDispatch(CueRequest request, CueDispatcher.DispatchResult result) {
                        appendOutput("已分发: " + request.kind + " → 受理通道位 " + result.acceptedChannels
                                + " · " + result.outcome + (result.reason == null ? "" : "（" + result.reason + "）"));
                    }
                    @Override public void onPlayback(CueRequest request, String channel, long atMs, String result) {
                        appendOutput("  播放[" + channel + "] " + request.kind + " → " + result);
                    }
                }, SystemClock::elapsedRealtime);
        long now = SystemClock.elapsedRealtime();
        String session = "demo-" + now;
        /* 节奏：第一条延迟 1.2s，避开 TTS 引擎绑定窗口（实测绑定+中文声源就绪约 0.1–0.5s）；
         * 第三条文本必须能在 4s 播报超时内说完（CuePlayer 语音超时策略）。 */
        handler.postDelayed(() -> {
            long t = SystemClock.elapsedRealtime();
            dispatcher.submit(new CueRequest(session, session + ":1", "demo:danger", "演示·危险",
                    CueRequest.Category.DANGER, 95, t, t + 8000,
                    CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC,
                    0, 0, 0, "左前方发现敌方英雄，注意规避"));
        }, 1200);
        handler.postDelayed(() -> {
            long t = SystemClock.elapsedRealtime();
            dispatcher.submit(new CueRequest(session, session + ":2", "demo:near", "演示·近身",
                    CueRequest.Category.NEAR_ZONE, 80, t, t + 8000,
                    CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC,
                    0, 0, 0, "敌人进入近身范围，向后撤退"));
        }, 4500);
        handler.postDelayed(() -> {
            long t = SystemClock.elapsedRealtime();
            dispatcher.submit(new CueRequest(session, session + ":3", "demo:done", "演示·系统",
                    CueRequest.Category.SYSTEM, 60, t, t + 8000,
                    CueRequest.CHANNEL_SPEECH, 0, 0, 0,
                    "演示播报结束"));
        }, 8000);
        handler.postDelayed(() -> {
            dispatcher.close();
            player.close();
            appendOutput("演示播报完毕（播放器已释放）。若语音无声：本机缺少中文 TTS 引擎，安装后重试；"
                    + "音调通道不可用会标注 tone_unavailable（不影响语音与震动）。");
        }, 13500);
    }

    private void appendOutput(String line) {
        runOnUiThread(() -> output.setText(output.getText() + line + "\n"));
    }

    private String formatResult(JevResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP 200 · ").append(r.elapsedMs).append("ms · model=").append(r.model)
                .append(" · tokens 输入 ").append(r.inputTokens).append(" / 输出 ").append(r.outputTokens).append("\n");
        JevThresholds thresholds = JevThresholds.DEFAULT;
        for (Map.Entry<String, JevAnswer> e : r.answers.entrySet()) {
            JevAnswer a = e.getValue();
            sb.append("\n[").append(a.type).append("] ").append(e.getKey()).append(": ");
            if (JevQuestion.TYPE_CHOICE.equals(a.type)) {
                sb.append(a.choice).append(" (conf=").append(fmt(a.confidence)).append(")");
            } else if (JevQuestion.TYPE_SCORE.equals(a.type)) {
                sb.append("score=").append(String.format(java.util.Locale.US, "%.2f", a.score))
                        .append(" (conf=").append(fmt(a.confidence)).append(")");
            } else {
                sb.append("p=").append(String.format(java.util.Locale.US, "%.2f", a.noul))
                        .append(" (|2p-1|=").append(String.format(java.util.Locale.US, "%.2f", a.surety())).append(")");
            }
            JevThresholds.Decision d = thresholds.decide(a);
            sb.append("\n   → ").append(decisionLabel(d))
                    .append("：").append(JevThresholds.speechFor(d, a)).append("\n");
        }
        return sb.toString();
    }

    private static String decisionLabel(JevThresholds.Decision d) {
        switch (d) {
            case AUTO_EXECUTE: return "高置信·自动执行（仍需过白名单）";
            case CONFIRM: return "中置信·先确认";
            default: return "低置信·绝不静默";
        }
    }

    private static String fmt(Double v) {
        return v == null ? "—" : String.format(java.util.Locale.US, "%.2f", v);
    }

    private JSONObject readAsset(String path) throws Exception {
        try (InputStream in = getAssets().open(path)) {
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private TextView note(String text) {
        return UiKit.body(this, text);
    }

    private static int channelIndex(String channel) {
        if (JevSettings.CHANNEL_EDGEONE.equals(channel)) return 1;
        if (JevSettings.CHANNEL_LOCAL.equals(channel)) return 2;
        return JevSettings.CHANNEL_OPENROUTER.equals(channel) ? 0 : -1;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
