package com.openkhub.sensefield;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 在某个板块下写一条帖子，可以带配图。
 *
 * <p>三条不能让的规矩：校验不通过时只说明哪一项不合规，绝不把已经写好的内容清掉（视障用户
 * 重打一遍的成本极高）；发布失败时同样原样保留，**包括已经选好的图片**；
 * 已经上传成功但帖子没发出去的图片，用户可以直接重试发布，不必再选一遍。
 */
public final class ComposePostActivity extends UiActivity {
    private static final String PREF_ENDPOINT = "community_endpoint";
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8765";
    private static final int TITLE_MAX = 80;
    private static final int BODY_MAX = 2000;
    private static final int AUTHOR_MAX = 40;
    private static final int IMAGE_MAX_BYTES = 5 * 1024 * 1024;
    private static final int IMAGES_MAX = 6;
    private static final int PREVIEW_PX = 220;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 30000;

    private static final int REQUEST_PICK_IMAGES = 41;

    private final List<Uri> images = new ArrayList<>();
    private Spinner sectionPicker;
    private EditText titleInput;
    private EditText bodyInput;
    private EditText authorInput;
    private TextView titleError;
    private TextView bodyError;
    private TextView authorError;
    private TextView imageError;
    private TextView status;
    private LinearLayout previewRow;
    private Button submit;
    private boolean sending;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout page = UiKit.page(this);
        scroll.addView(page);

        // 认不出的 section key 就当没传：不猜一个板块，也不报错，让人在下面自己选。
        CommunitySection presetSection = CommunitySection.fromKey(
                getIntent() == null
                        ? null : getIntent().getStringExtra(CommunityActivity.EXTRA_SECTION));
        UiKit.pageHeader(this, page,
                presetSection == null ? "发一条" : presetSection.label(), "社区");

        LinearLayout form = UiKit.card(this);
        UiKit.add(form, UiKit.heading(this, "帖子内容"), 8);

        sectionPicker = new Spinner(this);
        CommunitySection[] sections = CommunitySection.values();
        UiKit.styleSpinner(sectionPicker, CommunitySection.labels());
        for (int i = 0; i < sections.length; i++) {
            if (presetSection != null && sections[i].key().equals(presetSection.key())) {
                sectionPicker.setSelection(i);
            }
        }
        UiKit.add(form, UiKit.field(this, "板块", sectionPicker), UiKit.GAP_CONTROL);

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

        Button pick = UiKit.button(this, "选择配图", UiKit.ButtonStyle.OUTLINED);
        pick.setContentDescription("选择配图，最多 " + IMAGES_MAX + " 张，每张不超过 5 MB");
        pick.setOnClickListener(view -> pickImages());
        UiKit.add(form, pick, UiKit.GAP_CONTROL);

        previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView previewScroll = new HorizontalScrollView(this);
        previewScroll.setFitsSystemWindows(false);
        previewScroll.addView(previewRow);
        UiKit.add(form, previewScroll, UiKit.GAP_CONTROL);

        imageError = errorSlot();
        UiKit.add(form, imageError, UiKit.GAP_CONTROL);

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

    // ---------- 选图 ----------

    private void pickImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_PICK_IMAGES);
        } catch (android.content.ActivityNotFoundException notFound) {
            setError(imageError, "这台手机上没有可用的相册选择器。");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_IMAGES || resultCode != RESULT_OK || data == null) return;
        List<Uri> picked = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                picked.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (picked.isEmpty()) return;

        // 超出上限的部分直接说清楚，不静默丢弃用户刚选的照片。
        List<Uri> accepted = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (Uri uri : picked) {
            if (images.size() + accepted.size() >= IMAGES_MAX) {
                refused.add("超出 " + IMAGES_MAX + " 张上限");
                continue;
            }
            accepted.add(uri);
        }
        images.addAll(accepted);
        renderPreviews();
        setError(imageError, refused.isEmpty() ? null : "有 " + refused.size() + " 张没加入：" + refused.get(0));
    }

    private void renderPreviews() {
        previewRow.removeAllViews();
        final Handler main = new Handler(Looper.getMainLooper());
        for (int i = 0; i < images.size(); i++) {
            final int index = i;
            final Uri uri = images.get(i);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            int gap = UiKit.dp(this, 10);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            itemParams.rightMargin = gap;
            item.setLayoutParams(itemParams);

            ImageView preview = new ImageView(this);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setContentDescription("第 " + (index + 1) + " 张配图");
            preview.setBackgroundColor(UiKit.DISABLED_BG);
            item.addView(preview, new LinearLayout.LayoutParams(
                    UiKit.dp(this, 120), UiKit.dp(this, 120)));

            Button remove = UiKit.button(this, "移除", UiKit.ButtonStyle.OUTLINED);
            remove.setContentDescription("移除第 " + (index + 1) + " 张配图");
            ((LinearLayout.LayoutParams) remove.getLayoutParams()).topMargin = UiKit.dp(this, 6);
            remove.setOnClickListener(view -> {
                images.remove(index);
                renderPreviews();
            });
            item.addView(remove, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            previewRow.addView(item);

            new Thread(() -> {
                Bitmap bitmap = null;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in != null) bitmap = CommunityImages.decode(in, PREVIEW_PX);
                } catch (IOException | SecurityException ignored) {
                    // 取不到就留空框，不假装有图。
                }
                final Bitmap loaded = bitmap;
                main.post(() -> {
                    if (loaded != null && index < images.size() && images.get(index).equals(uri)) {
                        preview.setImageBitmap(loaded);
                    }
                });
            }, "sensefield-community-preview").start();
        }
        previewRow.setContentDescription(images.isEmpty() ? "还没有配图" : "已选 " + images.size() + " 张配图");
    }

    // ---------- 发布 ----------

    private void submitPost() {
        if (sending) return;
        String title = textOf(titleInput);
        String body = textOf(bodyInput);
        String author = textOf(authorInput);
        setError(titleError, problem("标题", title, TITLE_MAX));
        setError(bodyError, problem("正文", body, BODY_MAX));
        setError(authorError, problem("署名", author, AUTHOR_MAX));
        if (titleError.getVisibility() == View.VISIBLE
                || bodyError.getVisibility() == View.VISIBLE
                || authorError.getVisibility() == View.VISIBLE) {
            status.setTextColor(UiKit.DANGER);
            status.setText("还有没填好的地方。已经写的内容和选好的图都还在，改完可以接着发布。");
            return;
        }

        CommunitySection section = CommunitySection.values()[sectionPicker.getSelectedItemPosition()];
        String endpoint = endpoint();
        sending = true;
        submit.setEnabled(false);
        status.setTextColor(UiKit.MUTED);
        status.setText(images.isEmpty() ? "正在发布…" : "正在上传 " + images.size() + " 张配图…");
        new Thread(() -> publish(endpoint, section.key(), title.trim(), body, author.trim()),
                "sensefield-community-post").start();
    }

    private void publish(String endpoint, String section, String title, String body, String author) {
        List<String> imageIds = new ArrayList<>();
        try {
            for (Uri uri : images) {
                imageIds.add(uploadImage(endpoint, uri));
            }
            String payload = new JSONObject()
                    .put("section", section)
                    .put("title", title)
                    .put("body", body)
                    .put("author", author)
                    .put("image_ids", imageIds)
                    .toString();
            String code = postJson(endpoint + "/posts", payload);
            settle("201".equals(code) ? null : "服务端没接受（" + code + "）");
        } catch (ImageTooLargeException tooLarge) {
            // 图片已经在服务端传上去了，只是帖子没发出去；图片留在列表里，重试即可。
            settle(tooLarge.getMessage());
        } catch (JSONException impossible) {
            settle("内容没能整理成可发送的格式。已经写的内容和选好的图都还在。");
        } catch (IOException offline) {
            settle("没能发出去，图片和文字都还在，可以再试一次");
        }
    }

    private static final class ImageTooLargeException extends IOException {
        ImageTooLargeException(String message) {
            super(message);
        }
    }

    private String uploadImage(String endpoint, Uri uri) throws IOException, JSONException {
        byte[] payload;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("读不到这张图片");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
                if (buffer.size() > IMAGE_MAX_BYTES) throw new ImageTooLargeException("有一张配图超过 5 MB");
            }
            payload = buffer.toByteArray();
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + "/images").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", contentTypeOf(uri));
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
            } finally {
                out.close();
            }
            String body = read(connection);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_CREATED) {
                throw new IOException("有一张配图没被接受（" + code + "）" + detail(body));
            }
            return new JSONObject(body).getString("id");
        } finally {
            connection.disconnect();
        }
    }

    private String postJson(String url, String payload) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
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
            String body = read(connection);
            String code = String.valueOf(connection.getResponseCode());
            if (!"201".equals(code)) throw new IOException("服务端没接受（" + code + "）" + detail(body));
            return code;
        } finally {
            connection.disconnect();
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
            status.setText(failure + "。已经写的内容和选好的图都还在。");
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

    // ---------- 输入控件 ----------

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

    /** 图片类型问 ContentResolver，拿不到就按二进制发；服务端反正按文件头判定。 */
    private String contentTypeOf(Uri uri) {
        String type = null;
        try {
            type = getContentResolver().getType(uri);
        } catch (Exception ignored) {
            // 某些提供方会在这里抛，交给服务端按文件头判定即可。
        }
        if (type == null || !type.startsWith("image/")) return "application/octet-stream";
        return type;
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

    private static String read(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getResponseCode() / 100 == 2
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int count;
        while ((count = stream.read(chunk)) > 0) buffer.write(chunk, 0, count);
        stream.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String detail(String message) {
        if (message == null || message.isEmpty()) return "";
        String flat = message.replace('\n', ' ').trim();
        if (flat.length() > 200) flat = flat.substring(0, 200) + "…";
        return "：" + flat;
    }
}