package com.openkhub.sensefield;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 社区：看帖子、发帖子。
 *
 * <p>帖子按板块过滤，配图在帖子里直接显示。服务地址在这里配置，
 * 发帖页与本页读的是同一份设置。
 */
public final class CommunityActivity extends UiActivity {

    /** 传给发帖页的板块 key；只认 {@link CommunitySection#fromKey(String)} 认得出的值。 */
    public static final String EXTRA_SECTION = "community_section";

    private static final String PREF_ENDPOINT = "community_endpoint";
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8765";
    private static final int LIST_LIMIT = 20;
    private static final int THUMB_PX = 640;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;

    private EditText endpointInput;
    private TextView endpointStatus;
    private TextView listStatus;
    private LinearLayout sectionTabs;
    private LinearLayout postList;
    private String activeSection = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.configureWindow(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout page = UiKit.page(this);
        scroll.addView(page);

        UiKit.pageHeader(this, page, "社区", "听野");

        LinearLayout compose = UiKit.card(this);
        Button newPost = UiKit.button(this, "发一条", UiKit.ButtonStyle.FILLED);
        newPost.setContentDescription("发一条帖子，可以带配图");
        newPost.setOnClickListener(view -> {
            Intent intent = new Intent(this, ComposePostActivity.class);
            intent.putExtra(EXTRA_SECTION, activeSection);
            startActivity(intent);
        });
        ((LinearLayout.LayoutParams) newPost.getLayoutParams()).bottomMargin = 0;
        UiKit.add(compose, newPost, 0);
        UiKit.add(page, compose, UiKit.GAP_SECTION);

        LinearLayout browse = UiKit.card(this);
        UiKit.add(browse, UiKit.heading(this, "最新帖子"), 8);

        sectionTabs = new LinearLayout(this);
        sectionTabs.setOrientation(LinearLayout.HORIZONTAL);
        ScrollView tabsScroll = new ScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsScroll.addView(sectionTabs);
        UiKit.add(browse, tabsScroll, UiKit.GAP_CONTROL);

        listStatus = UiKit.body(this, "");
        listStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(browse, listStatus, UiKit.GAP_CONTROL);

        Button reload = UiKit.button(this, "刷新帖子", UiKit.ButtonStyle.OUTLINED);
        reload.setOnClickListener(view -> loadPosts());
        UiKit.add(browse, reload, UiKit.GAP_CONTROL);

        postList = new LinearLayout(this);
        postList.setOrientation(LinearLayout.VERTICAL);
        UiKit.spaceChildren(postList, 12);
        UiKit.add(browse, postList, UiKit.GAP_CONTROL);
        UiKit.add(page, browse, UiKit.GAP_SECTION);

        LinearLayout service = UiKit.card(this);
        UiKit.add(service, UiKit.heading(this, "社区服务地址"), 8);
        endpointInput = new EditText(this);
        UiKit.styleInput(endpointInput);
        endpointInput.setSingleLine(true);
        endpointInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpointInput.setHint(DEFAULT_ENDPOINT);
        endpointInput.setText(GameProfile.settings(this).getString(PREF_ENDPOINT, ""));
        UiKit.add(service, UiKit.field(this, "服务地址", endpointInput), UiKit.GAP_CONTROL);
        UiKit.add(service, UiKit.hint(this,
                "留空则发往手机本机 " + DEFAULT_ENDPOINT + "；填电脑地址时手机要和电脑在同一网络。"),
                UiKit.GAP_CONTROL);

        endpointStatus = UiKit.body(this, "");
        endpointStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(service, endpointStatus, UiKit.GAP_CONTROL);

        Button save = UiKit.button(this, "保存地址", UiKit.ButtonStyle.FILLED);
        ((LinearLayout.LayoutParams) save.getLayoutParams()).bottomMargin = 0;
        save.setOnClickListener(view -> saveEndpoint());
        UiKit.add(service, save, 0);
        UiKit.add(page, service, UiKit.GAP_SECTION);

        setContentView(scroll);
        buildTabs();
        loadPosts();
    }

    @Override protected void onResume() {
        super.onResume();
        // 从发帖页回来时刷新一次，好马上看见自己刚发出去的帖子。
        loadPosts();
    }

    // ---------- 板块筛选 ----------

    private void buildTabs() {
        sectionTabs.removeAllViews();
        String[] labels = new String[CommunitySection.values().length + 1];
        labels[0] = "全部";
        System.arraycopy(CommunitySection.labels(), 0, labels, 1, CommunitySection.values().length);

        for (int i = 0; i < labels.length; i++) {
            final String key = i == 0 ? "" : CommunitySection.values()[i - 1].key();
            Button tab = UiKit.button(this, labels[i], UiKit.ButtonStyle.OUTLINED);
            tab.setContentDescription(labels[i] + "板块");
            tab.setStateListAnimator(null);
            if (key.equals(activeSection)) {
                tab.setTextColor(UiKit.PAGE);
                tab.setBackgroundColor(UiKit.PRIMARY);
            }
            ((LinearLayout.LayoutParams) tab.getLayoutParams()).rightMargin = UiKit.dp(this, 8);
            tab.setOnClickListener(view -> {
                activeSection = key;
                buildTabs();
                loadPosts();
            });
            sectionTabs.addView(tab);
        }
    }

    private void loadPosts() {
        listStatus.setTextColor(UiKit.MUTED);
        listStatus.setText("正在读取帖子…");
        final String endpoint = endpoint();
        final String query = activeSection.isEmpty() ? "" : "?section=" + activeSection;
        new Thread(() -> {
            JSONArray posts = new JSONArray();
            String failure = null;
            try {
                posts = fetch(endpoint + "/posts" + query + "&limit=" + LIST_LIMIT);
            } catch (IOException | JSONException problem) {
                failure = problem.getMessage() == null ? "读取失败" : problem.getMessage();
            }
            final JSONArray loaded = posts;
            final String reason = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (reason != null) {
                    listStatus.setTextColor(UiKit.DANGER);
                    listStatus.setText("没能读到帖子：" + reason);
                    return;
                }
                renderPosts(loaded);
            });
        }, "sensefield-community-list").start();
    }

    private void renderPosts(JSONArray posts) {
        postList.removeAllViews();
        if (posts.length() == 0) {
            listStatus.setTextColor(UiKit.MUTED);
            listStatus.setText(activeSection.isEmpty() ? "还没有帖子，来发第一条吧。" : "这个板块还没有帖子。");
            return;
        }
        listStatus.setTextColor(UiKit.MUTED);
        listStatus.setText("共 " + posts.length() + " 条，按发布时间倒序。");
        for (int i = 0; i < posts.length(); i++) {
            try {
                postList.addView(buildPostCard(posts.getJSONObject(i)));
            } catch (JSONException ignored) {
                // 单条读不出来就跳过，不让一坏条毁掉整页。
            }
        }
    }

    private View buildPostCard(JSONObject post) throws JSONException {
        LinearLayout card = UiKit.card(this);

        String section = post.optString("section", "");
        CommunitySection known = CommunitySection.fromKey(section);
        TextView meta = UiKit.text(this,
                (known == null ? section : known.label()) + " · " + post.optString("author", ""),
                15, UiKit.MUTED, false);
        UiKit.add(card, meta, 4);

        TextView title = UiKit.text(this, post.optString("title", ""), 20, UiKit.INK, true);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        UiKit.add(card, title, 4);

        TextView body = UiKit.text(this, post.optString("body", ""), UiKit.TEXT_BODY, UiKit.INK, false);
        UiKit.add(card, body, 0);

        JSONArray images = post.optJSONArray("images");
        if (images != null && images.length() > 0) {
            LinearLayout gallery = new LinearLayout(this);
            gallery.setOrientation(LinearLayout.HORIZONTAL);
            ScrollView galleryScroll = new ScrollView(this);
            galleryScroll.setHorizontalScrollBarEnabled(false);
            galleryScroll.addView(gallery);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = UiKit.dp(this, 10);
            galleryScroll.setLayoutParams(params);
            UiKit.add(card, galleryScroll, 0);

            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                if (image == null) continue;
                String url = endpoint() + image.optString("url", "");
                ImageView thumb = new ImageView(this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackgroundColor(UiKit.DISABLED_BG);
                thumb.setContentDescription("配图 " + (i + 1) + "，共 " + images.length() + " 张");
                LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(
                        UiKit.dp(this, 150), UiKit.dp(this, 150));
                thumbParams.rightMargin = UiKit.dp(this, 8);
                thumb.setLayoutParams(thumbParams);
                gallery.addView(thumb);
                CommunityImages.loadInto(thumb, url, THUMB_PX);
            }
        }
        return card;
    }

    private JSONArray fetch(String url) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            int code = connection.getResponseCode();
            InputStream stream = code / 100 == 2 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (stream != null) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = stream.read(chunk)) > 0) buffer.write(chunk, 0, count);
                stream.close();
            }
            String body = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            if (code / 100 != 2) throw new IOException("服务端返回 " + code);
            return new JSONArray(body);
        } finally {
            connection.disconnect();
        }
    }

    // ---------- 服务地址 ----------

    private String endpoint() {
        SharedPreferences preferences = GameProfile.settings(this);
        String configured = preferences.getString(PREF_ENDPOINT, "");
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) value = DEFAULT_ENDPOINT;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /** 只在地址真的能用时改设置；写坏的值留在输入框里让用户改，不静默丢弃。 */
    private void saveEndpoint() {
        String value = endpointInput.getText() == null
                ? "" : endpointInput.getText().toString().trim();
        SharedPreferences preferences = GameProfile.settings(this);
        if (value.isEmpty()) {
            preferences.edit().remove(PREF_ENDPOINT).apply();
            endpointStatus.setTextColor(UiKit.MUTED);
            endpointStatus.setText("已恢复默认：" + DEFAULT_ENDPOINT);
            loadPosts();
            return;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            endpointStatus.setTextColor(UiKit.DANGER);
            endpointStatus.setText("地址要以 http:// 或 https:// 开头。已填的内容保留着，改完再保存。");
            return;
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        preferences.edit().putString(PREF_ENDPOINT, value).apply();
        endpointStatus.setTextColor(UiKit.MUTED);
        endpointStatus.setText("已保存。帖子会发往 " + value);
        loadPosts();
    }
}