package com.openkhub.sensefield;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 在某个板块下写一条帖子。
 *
 * <p>两条不能让的规矩：校验不通过时只说明哪一项不合规，绝不把已经写好的内容清掉（视障用户
 * 重打一遍的成本极高）；发布失败时同样原样保留，让用户改完接着试。
 */
public final class ComposePostActivity extends UiActivity {
    private static final String PREF_ENDPOINT = "community_endpoint";
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8765";
    private static final int TITLE_MAX = 80;
    private static final int BODY_MAX = 2000;
    private static final int AUTHOR_MAX = 40;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int DETAIL_MAX = 200;

    private CommunitySection section;
    private EditText titleInput;
    private EditText bodyInput;
    private EditText authorInput;
    private TextView titleError;
    private TextView bodyError;
    private TextView authorError;
    private TextView status;
    private Button submit;
    private boolean sending;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);
        section = CommunitySection.fromKey(
                getIntent() == null ? null : getIntent().getStringExtra(CommunityActivity.EXTRA_SECTION));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout page = UiKit.page(this);
        scroll.addView(page);
        UiKit.pageHeader(this, page, section == null ? "发帖" : section.label(), "社区");

        if (section == null) {
            // 认不出的板块宁可什么都不做，也不能猜一个板块把帖子放进去。
            TextView unknown = UiKit.body(this, "没认出这个板块，请回到社区重新选一个。");
            unknown.setTextColor(UiKit.DANGER);
            UiKit.add(page, unknown, 0);
            setContentView(scroll);
            return;
        }

        LinearLayout form = UiKit.card(this);
        UiKit.add(form, UiKit.heading(this, "在「" + section.label() + "」发帖"), 8);

        titleInput = singleLineInput("一句话说清你要发什么");
        titleError = errorSlot();
        UiKit.add(form, UiKit.field(this, "标题", titleInput), UiKit.GAP_CONTROL);
        UiKit.add(form, titleError, UiKit.GAP_CONTROL);

        bodyInput = multiLineInput();
        bodyError = errorSlot();
        UiKit.add(form, UiKit.field(this, "正文", bodyInput), UiKit.GAP_CONTROL);
        UiKit.add(form, bodyError, UiKit.GAP_CONTROL);

        authorInput = singleLineInput("你希望别人怎么称呼你");
        authorError = errorSlot();
        UiKit.add(form, UiKit.field(this, "署名", authorInput), UiKit.GAP_CONTROL);
        UiKit.add(form, authorError, UiKit.GAP_CONTROL);

        status = UiKit.body(this, "");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(form, status, UiKit.GAP_CONTROL);

        submit = UiKit.button(this, "发布", UiKit.ButtonStyle.FILLED);
        submit.setOnClickListener(view -> submitPost());
        ((LinearLayout.LayoutParams) submit.getLayoutParams()).bottomMargin = 0;
        UiKit.add(form, submit, 0);

        UiKit.add(page, form, UiKit.GAP_SECTION);
        setContentView(scroll);
    }

    private void submitPost() {
        if (section == null || sending) return;
        String title = textOf(titleInput);
        String body = textOf(bodyInput);
        String author = textOf(authorInput);
        setError(titleError, problem("标题", title, TITLE_MAX));
        setError(bodyError, problem("正文", body, BODY_MAX));
        setError(authorError, problem("作者", author, AUTHOR_MAX));
        if (titleError.getVisibility() == View.VISIBLE
                || bodyError.getVisibility() == View.VISIBLE
                || authorError.getVisibility() == View.VISIBLE) {
            status.setTextColor(UiKit.DANGER);
            status.setText("还有没填好的地方。已经写的内容都还在，改完可以接着发布。");
            return;
        }

        String payload;
        try {
            payload = new JSONObject()
                    .put("section", section.key())
                    .put("title", title.trim())
                    .put("body", body)
                    .put("author", author.trim())
                    .toString();
        } catch (JSONException ignored) {
            // 四个字段都是本地字符串，拼不出非法 JSON；真到了这里也只能如实说出来。
            status.setTextColor(UiKit.DANGER);
            status.setText("内容没能整理成可发送的格式。已经写的内容都还在。");
            return;
        }

        sending = true;
        submit.setEnabled(false);
        status.setTextColor(UiKit.MUTED);
        status.setText("正在发布…");
        String endpoint = endpoint();
        new Thread(() -> post(endpoint, payload), "sensefield-community-post").start();
    }

    private void post(String endpoint, String payload) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint + "/posts").openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_CREATED) {
                settle(null);
                return;
            }
            settle("服务端没接受（" + code + "）" + detail(read(connection.getErrorStream())));
        } catch (IOException e) {
            settle("连不上社区服务，检查地址是否能访问");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void settle(String failure) {
        runOnUiThread(() -> {
            sending = false;
            submit.setEnabled(true);
            if (isFinishing() || isDestroyed()) return;
            if (failure == null) {
                finish();
                return;
            }
            status.setTextColor(UiKit.DANGER);
            status.setText("没能发出去：" + failure + "。已经写的内容都还在，改完可以再试。");
        });
    }

    private String endpoint() {
        SharedPreferences preferences = GameProfile.settings(this);
        String configured = preferences.getString(PREF_ENDPOINT, "");
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) value = DEFAULT_ENDPOINT;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private EditText singleLineInput(String hint) {
        EditText input = new EditText(this);
        UiKit.styleInput(input);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(hint);
        return input;
    }

    private EditText multiLineInput() {
        EditText input = new EditText(this);
        UiKit.styleInput(input);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint("写详细一点，别人更好接话");
        return input;
    }

    private TextView errorSlot() {
        TextView slot = UiKit.text(this, "", 16, UiKit.DANGER, false);
        slot.setVisibility(View.GONE);
        slot.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return slot;
    }

    private static void setError(TextView slot, String problem) {
        if (problem == null) {
            slot.setText("");
            slot.setVisibility(View.GONE);
            return;
        }
        slot.setText(problem);
        slot.setVisibility(View.VISIBLE);
    }

    /** 字数按去空白后计算；越界只报出来，绝不替用户截断。 */
    private static String problem(String field, String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return field + "不能为空";
        if (trimmed.length() > max) return field + "最多 " + max + " 字，现在 " + trimmed.length() + " 字";
        return null;
    }

    private static String textOf(EditText input) {
        return input.getText() == null ? "" : input.getText().toString();
    }

    private static String detail(String message) {
        if (message.isEmpty()) return "";
        String flat = message.replace('\n', ' ').trim();
        if (flat.length() > DETAIL_MAX) flat = flat.substring(0, DETAIL_MAX) + "…";
        return "：" + flat;
    }

    private static String read(InputStream stream) {
        if (stream == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        try {
            int count;
            while ((count = stream.read(chunk)) > 0) buffer.write(chunk, 0, count);
        } catch (IOException ignored) {
            return "";
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 读不到错误详情就用空串，反正只是给用户多一句提示。
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
