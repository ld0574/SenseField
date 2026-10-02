package com.openkhub.sensefield;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 判定自测台：多场景排查入口（L2 判定层联调）。
 * 场景：消消乐五问 / 中文无障碍树 / 错误路径（缺必填 criteria）。
 * 本页只在用户显式开启判定层并配置渠道后才会发起网络请求；默认关闭。
 */
public class JudgmentSelfTestActivity extends Activity {
    private Switch enabledSwitch;
    private Spinner channelSpinner;
    private EditText apiKeyInput;
    private EditText localBaseUrlInput;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = UiKit.page(this);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("判定自测台 · Jev L2");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);
        page.addView(note("测试 state 与五问来自创作工具模板；判定经所选渠道真发请求，低置信按铁律出声播报「没看清」。"));

        // —— 设置卡片 ——
        LinearLayout settings = UiKit.card(this);
        settings.addView(sectionLabel("启用判定层（默认关闭；开启后按所选渠道发起网络请求）"));
        enabledSwitch = new Switch(this);
        enabledSwitch.setText("启用 Jev 判定层");
        enabledSwitch.setChecked(JevSettings.enabled(this));
        settings.addView(enabledSwitch);
        settings.addView(sectionLabel("渠道"));
        channelSpinner = new Spinner(this);
        List<String> channels = new ArrayList<>();
        channels.add("openrouter（OpenRouter typesafe/jev-1.13）");
        channels.add("edgeone（腾讯 EdgeOne Makers @makers/jev）");
        channels.add("local（本机薄适配服务，如 http://10.0.2.2:8080）");
        channelSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, channels));
        int savedIndex = channelIndex(JevSettings.channel(this));
        channelSpinner.setSelection(savedIndex < 0 ? 0 : savedIndex);
        settings.addView(channelSpinner);
        settings.addView(sectionLabel("API Key（仅存本机 SharedPreferences）"));
        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("sk-or-v1-… 或 EdgeOne 的 key");
        apiKeyInput.setText(JevSettings.apiKey(this));
        settings.addView(apiKeyInput);
        settings.addView(sectionLabel("本地服务 baseUrl（local 渠道用；模拟器访问宿主机用 10.0.2.2）"));
        localBaseUrlInput = new EditText(this);
        localBaseUrlInput.setHint("http://10.0.2.2:8080");
        localBaseUrlInput.setText(JevSettings.localBaseUrl(this));
        settings.addView(localBaseUrlInput);
        Button save = UiKit.button(this, "保存设置", false);
        save.setOnClickListener(v -> {
            JevSettings.setEnabled(this, enabledSwitch.isChecked());
            String channel = channelSpinner.getSelectedItemPosition() == 2 ? JevSettings.CHANNEL_LOCAL
                    : channelSpinner.getSelectedItemPosition() == 1 ? JevSettings.CHANNEL_EDGEONE
                    : JevSettings.CHANNEL_OPENROUTER;
            JevSettings.setChannel(this, channel);
            JevSettings.setApiKey(this, apiKeyInput.getText().toString());
            JevSettings.setLocalBaseUrl(this, localBaseUrlInput.getText().toString());
            Toast.makeText(this, "判定层设置已保存", Toast.LENGTH_SHORT).show();
        });
        settings.addView(save);
        page.addView(settings);

        // —— 场景卡片 ——
        LinearLayout scenarios = UiKit.card(this);
        scenarios.addView(sectionLabel("多场景测试"));
        Button match3 = UiKit.button(this, "场景 1：消消乐五问（英文 criteria）", true);
        match3.setOnClickListener(v -> runScenario("match3", false));
        scenarios.addView(match3);
        Button axtree = UiKit.button(this, "场景 2：中文无障碍树五问", false);
        axtree.setOnClickListener(v -> runScenario("menu-axtree", false));
        scenarios.addView(axtree);
        Button errorPath = UiKit.button(this, "场景 3：错误路径（choice 缺必填 criteria）", false);
        errorPath.setOnClickListener(v -> runScenario("match3", true));
        scenarios.addView(errorPath);
        page.addView(scenarios);

        // —— 输出卡片 ——
        LinearLayout out = UiKit.card(this);
        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        output.setText("等待运行…");
        out.addView(output);
        page.addView(out);
    }

    private void runScenario(String assetName, boolean breakContract) {
        final JevClient client = JevSettings.clientOrNull(this);
        StringBuilder header = new StringBuilder();
        if (client == null) {
            output.setText("【未运行】判定层未启用或配置不完整：请先打开「启用 Jev 判定层」并填写 Key（local 渠道填 baseUrl）。\n低置信绝不静默——同理，配置不齐也不能悄悄失败。");
            return;
        }
        header.append("渠道: ").append(JevSettings.channel(this)).append("\n场景: ").append(assetName)
                .append(breakContract ? "（错误路径：choice 缺 criteria）" : "").append("\n运行中…\n");
        output.setText(header.toString());

        Thread thread = new Thread(() -> {
            String result;
            try {
                JSONObject asset = readAsset("jev/" + assetName + ".json");
                Object state = asset.has("state") ? asset.getJSONObject("state") : asset;
                Map<String, JevQuestion> questions = JevSettings.defaultQuestions();
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

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 4));
        return t;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        return t;
    }

    private static int channelIndex(String channel) {
        if (JevSettings.CHANNEL_EDGEONE.equals(channel)) return 1;
        if (JevSettings.CHANNEL_LOCAL.equals(channel)) return 2;
        return JevSettings.CHANNEL_OPENROUTER.equals(channel) ? 0 : -1;
    }
}
