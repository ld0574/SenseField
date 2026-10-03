package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 开心消消乐（北京乐元素）辅助 · 实时版。
 * 三条识别链路：①截图式（选图→标定采样→播报）②实时式（MediaProjection 每秒采样，局面变化即播报）
 * ③示例判定（Jev 真请求）。特殊棋子：颜色判不出的格子可与标注模板比对，不再一律显示「.」。
 * 启动游戏：扫描全机 happyelements 系应用（覆盖各渠道服），不再依赖固定包名列表。
 */
public class Match3AssistActivity extends Activity {
    private static final String[] ANIPOP_PACKAGES = {
            "com.happyelements.AndroidAnimal",
            "com.happyelements.AndroidAnimal.qq",
            "com.happyelements.AndroidAnimal.mi",
            "com.happyelements.AndroidAnimal.wdj"
    };
    private static final int REQ_PICK_IMAGE = 2001;
    private static final int REQ_PROJECTION = 2002;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Bitmap screenshot;
    private ImageView preview;
    private Spinner rowsSpin;
    private Spinner colsSpin;
    private EditText leftIn;
    private EditText topIn;
    private EditText rightIn;
    private EditText bottomIn;
    private Spinner markRowSpin;
    private Spinner markColSpin;
    private EditText markNameIn;
    private TextView output;
    private final CueDispatcher.Listener silentListener = new CueDispatcher.Listener() {
        @Override public void onDispatch(CueRequest request, CueDispatcher.DispatchResult result) { }
        @Override public void onPlayback(CueRequest request, String channel, long atMs, String result) { }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = UiKit.page(this);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("开心消消乐辅助 · 实时版");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);
        page.addView(note("三条链路：实时识别（录屏每秒采样，局面变化即播报）｜截图识别（选图→标定→采样）｜示例判定（Jev 真请求）。识别与标定共用一套参数。"));

        /* ---------- 启动 + 实时识别 ---------- */
        LinearLayout actions = UiKit.card(this);
        Button launch = UiKit.button(this, "启动开心消消乐", true);
        launch.setOnClickListener(v -> launchGame());
        actions.addView(launch);
        Button liveStart = UiKit.button(this, "开始实时识别（录屏授权）", true);
        liveStart.setOnClickListener(v -> startLive());
        actions.addView(liveStart);
        Button liveStop = UiKit.button(this, "停止实时识别", false);
        liveStop.setOnClickListener(v -> {
            stopService(new Intent(this, Match3LiveService.class));
            toast("实时识别已停止");
        });
        actions.addView(liveStop);
        Button pick = UiKit.button(this, "选择游戏截图（相册，截图式识别）", false);
        pick.setOnClickListener(v -> pickScreenshot());
        actions.addView(pick);
        Button demo = UiKit.button(this, "判定示例局面（Jev 真请求）", false);
        demo.setOnClickListener(v -> judgeSample());
        actions.addView(demo);
        page.addView(actions);

        /* ---------- 标定 ---------- */
        LinearLayout calib = UiKit.card(this);
        calib.addView(sectionLabel("棋盘标定（按屏幕百分比，实时与截图共用；一次标定自动记住）"));
        LinearLayout grid = UiKit.horizontal(this);
        briefLabel(grid, "行数");
        rowsSpin = spinner(grid, "行数", new String[]{"6", "7", "8", "9"}, 2);
        briefLabel(grid, "列数");
        colsSpin = spinner(grid, "列数", new String[]{"6", "7", "8", "9"}, 2);
        calib.addView(grid);
        LinearLayout pct = UiKit.horizontal(this);
        leftIn = pctInput(pct, "左上X%", 4);
        topIn = pctInput(pct, "左上Y%", 18);
        calib.addView(pct);
        LinearLayout pct2 = UiKit.horizontal(this);
        rightIn = pctInput(pct2, "右下X%", 96);
        bottomIn = pctInput(pct2, "右下Y%", 82);
        calib.addView(pct2);
        loadCalibration();
        Button sample = UiKit.button(this, "采样截图并播报可消除位置", false);
        sample.setOnClickListener(v -> sampleAndAnnounce());
        calib.addView(sample);
        page.addView(calib);

        /* ---------- 特殊棋子模板库 ---------- */
        LinearLayout special = UiKit.card(this);
        special.addView(sectionLabel("特殊棋子模板库（颜色判不出的格子与模板比对，不再显示「.」）"));
        LinearLayout mark = UiKit.horizontal(this);
        briefLabel(mark, "行");
        markRowSpin = spinner(mark, "行", new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9"}, 0);
        briefLabel(mark, "列");
        markColSpin = spinner(mark, "列", new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9"}, 0);
        special.addView(mark);
        markNameIn = new EditText(this);
        markNameIn.setHint("棋子名称，如：炸弹 / 魔法石");
        markNameIn.setSingleLine(true);
        special.addView(markNameIn);
        Button markSave = UiKit.button(this, "从当前截图裁剪该格，保存为模板", false);
        markSave.setOnClickListener(v -> saveSpecialTemplate());
        special.addView(markSave);
        page.addView(special);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        page.addView(preview);

        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        output.setText("等待操作…");
        page.addView(output);
    }

    /* ---------- 启动游戏：扫描全机 happyelements 系（覆盖各渠道服），不再依赖固定列表 ---------- */

    private void launchGame() {
        List<android.content.pm.ResolveInfo> launchables = getPackageManager()
                .queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER), 0);
        String picked = null;
        List<String> found = new ArrayList<>();
        for (android.content.pm.ResolveInfo info : launchables) {
            String pkg = info.activityInfo == null ? null : info.activityInfo.packageName;
            if (pkg == null || !pkg.toLowerCase().contains("happyelements")) continue;
            String version;
            try {
                version = getPackageManager().getPackageInfo(pkg, 0).versionName;
            } catch (Exception e) {
                version = "?";
            }
            found.add(pkg + " v" + version);
            if (picked == null || pkg.equals(ANIPOP_PACKAGES[0])) picked = pkg;
        }
        if (picked != null) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(picked);
            if (launch != null) {
                startActivity(launch);
                announce("已启动开心消消乐。");
                output.setText("检测到已安装：\n  " + join(found) + "\n已启动：" + picked);
                return;
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + ANIPOP_PACKAGES[0])));
            announce("未检测到开心消消乐，请先安装。");
            output.setText("未检测到 happyelements 系已安装应用，已尝试打开应用商店。");
        } catch (Exception e) {
            announce("未检测到开心消消乐，请先安装。");
            output.setText("未检测到 happyelements 系已安装应用，且本机没有可用应用商店。");
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(list.get(i));
            if (i < list.size() - 1) sb.append('\n').append("  ");
        }
        return sb.toString();
    }

    /* ---------- 实时识别：录屏授权 → 前台服务 ---------- */

    private void startLive() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            toast("本机不支持录屏");
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    /* ---------- 选截图 ---------- */

    private void pickScreenshot() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(Intent.createChooser(intent, "选择游戏棋盘截图"), REQ_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                announce("录屏授权被取消，实时识别未开启。");
                return;
            }
            Intent service = new Intent(this, Match3LiveService.class)
                    .setAction(Match3LiveService.ACTION_START)
                    .putExtra(Match3LiveService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(Match3LiveService.EXTRA_DATA, data);
            startForegroundService(service);
            output.setText("实时识别已启动：约每秒采样一次，局面变化时自动播报。切到游戏全屏即可。\n停止：回本页点「停止实时识别」。");
            return;
        }
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            Uri uri = data.getData();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, bounds.outWidth / 1080);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                screenshot = BitmapFactory.decodeStream(in, null, opts);
            }
            if (screenshot == null) {
                toast("图片读取失败，换一张试试");
                return;
            }
            drawPreview();
            toast("截图已载入，检查标定后点「采样截图并播报」");
        } catch (Exception e) {
            toast("图片读取异常: " + e.getMessage());
        }
    }

    /* ---------- 标定 ---------- */

    private void loadCalibration() {
        var prefs = GameProfile.settings(this);
        rowsSpin.setSelection(intToIndex(prefs.getInt("match3_rows", 8), 6, 9));
        colsSpin.setSelection(intToIndex(prefs.getInt("match3_cols", 8), 6, 9));
        leftIn.setText(String.valueOf(prefs.getInt("match3_l", 4)));
        topIn.setText(String.valueOf(prefs.getInt("match3_t", 18)));
        rightIn.setText(String.valueOf(prefs.getInt("match3_r", 96)));
        bottomIn.setText(String.valueOf(prefs.getInt("match3_b", 82)));
    }

    private void saveCalibration() {
        GameProfile.settings(this).edit()
                .putInt("match3_rows", 6 + rowsSpin.getSelectedItemPosition())
                .putInt("match3_cols", 6 + colsSpin.getSelectedItemPosition())
                .putInt("match3_l", parseInt(leftIn, 4))
                .putInt("match3_t", parseInt(topIn, 18))
                .putInt("match3_r", parseInt(rightIn, 96))
                .putInt("match3_b", parseInt(bottomIn, 82))
                .apply();
    }

    private static int intToIndex(int value, int min, int max) {
        return Math.max(0, Math.min(max - min, value - min));
    }

    private static int parseInt(EditText input, int fallback) {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(input.getText().toString().trim())));
        } catch (Exception e) {
            return fallback;
        }
    }

    private void drawPreview() {
        if (screenshot == null) return;
        Bitmap marked = screenshot.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(marked);
        Paint paint = new Paint();
        paint.setColor(Color.rgb(0, 200, 120));
        paint.setStrokeWidth(Math.max(2, marked.getWidth() / 300));
        int rows = 6 + rowsSpin.getSelectedItemPosition();
        int cols = 6 + colsSpin.getSelectedItemPosition();
        int l = marked.getWidth() * parseInt(leftIn, 4) / 100;
        int t = marked.getHeight() * parseInt(topIn, 18) / 100;
        int r = marked.getWidth() * parseInt(rightIn, 96) / 100;
        int b = marked.getHeight() * parseInt(bottomIn, 82) / 100;
        for (int i = 0; i <= cols; i++) {
            int x = l + (r - l) * i / cols;
            canvas.drawLine(x, t, x, b, paint);
        }
        for (int i = 0; i <= rows; i++) {
            int y = t + (b - t) * i / rows;
            canvas.drawLine(l, y, r, y, paint);
        }
        preview.setImageBitmap(marked);
    }

    /* ---------- 截图式识别 ---------- */

    private void sampleAndAnnounce() {
        if (screenshot == null) {
            toast("先选择一张游戏截图");
            return;
        }
        saveCalibration();
        drawPreview();
        char[][] board = sampler().sample(screenshot);
        StringBuilder sb = new StringBuilder("识别矩阵（. 表示未识别/空格）：\n");
        for (char[] row : board) sb.append(String.valueOf(row)).append('\n');
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(board);
        sb.append("\n可消除交换：").append(swaps.size()).append(" 处\n");
        announce("棋盘识别完成，共找到 " + swaps.size() + " 处可消除交换。");
        int spoken = 0;
        for (Match3Board.Swap s : swaps) {
            if (spoken++ >= 5) break;
            sb.append("  ").append(Match3Board.swapSpeech(s)).append('\n');
            announce(Match3Board.swapSpeech(s));
        }
        if (swaps.isEmpty()) {
            List<String> scan = Match3Board.scanSpeech(board);
            announce("没有找到可直接消除的交换，开始逐行扫描局面。");
            for (String line : scan) {
                sb.append("  ").append(line).append('\n');
            }
        }
        sb.append("\n提示：错格多半是标定不准或特殊棋子，微调百分比或标注模板后重新采样。");
        output.setText(sb.toString());
    }

    private Match3Sampler sampler() {
        var prefs = GameProfile.settings(this);
        return new Match3Sampler(this,
                6 + rowsSpin.getSelectedItemPosition(),
                6 + colsSpin.getSelectedItemPosition(),
                parseInt(leftIn, 4), parseInt(topIn, 18),
                parseInt(rightIn, 96), parseInt(bottomIn, 82));
    }

    /* ---------- 特殊棋子模板 ---------- */

    private void saveSpecialTemplate() {
        if (screenshot == null) {
            toast("先选一张截图再标注");
            return;
        }
        String name = markNameIn.getText().toString().trim();
        if (name.isEmpty()) {
            toast("先填棋子名称");
            return;
        }
        saveCalibration();
        int row = markRowSpin.getSelectedItemPosition();
        int col = markColSpin.getSelectedItemPosition();
        int rows = 6 + rowsSpin.getSelectedItemPosition();
        int cols = 6 + colsSpin.getSelectedItemPosition();
        int l = screenshot.getWidth() * parseInt(leftIn, 4) / 100;
        int t = screenshot.getHeight() * parseInt(topIn, 18) / 100;
        int r = screenshot.getWidth() * parseInt(rightIn, 96) / 100;
        int b = screenshot.getHeight() * parseInt(bottomIn, 82) / 100;
        int cellW = (r - l) / cols, cellH = (b - t) / rows;
        int cx = l + cellW * col + cellW / 2, cy = t + cellH * row + cellH / 2;
        int side = Math.max(8, Math.min(cellW, cellH) / 2);
        int cl = Math.max(0, cx - side), ct = Math.max(0, cy - side);
        int cr = Math.min(screenshot.getWidth(), cx + side), cb = Math.min(screenshot.getHeight(), cy + side);
        if (cr - cl < 8 || cb - ct < 8) {
            toast("裁剪区域无效");
            return;
        }
        try {
            Match3Sampler.saveTemplate(this, name,
                    Bitmap.createBitmap(screenshot, cl, ct, cr - cl, cb - ct));
            int count = Match3Sampler.loadTemplates(this).size();
            announce("特殊棋子模板已保存：" + name + "，当前共 " + count + " 个模板。");
            output.setText("已保存模板「" + name + "」，共 " + count + " 个。下次采样时颜色判不出的格子会自动与模板比对。");
            hideKeyboard();
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus();
        if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    /* ---------- Jev 示例判定 ---------- */

    private void judgeSample() {
        final JevClient client = JevSettings.clientOrNull(this);
        if (client == null) {
            output.setText("【未运行】判定层未启用或配置不完整：先在「判定自测」页启用并保存渠道与 Key。\n低置信绝不静默——配置不齐也不悄悄失败。");
            return;
        }
        output.setText("示例判定：载入创作工具消消乐模板，发 Jev 真请求…\n");
        new Thread(() -> {
            try {
                JSONObject asset = readAsset("jev/match3.json");
                Map<String, JevQuestion> questions = JevSettings.defaultQuestions(this);
                JevResult r = client.judge(asset.getJSONObject("state"), questions);
                StringBuilder sb = new StringBuilder("HTTP 200 · " + r.elapsedMs + "ms · " + r.model + "\n");
                JevThresholds thresholds = JevThresholds.DEFAULT;
                for (Map.Entry<String, JevAnswer> e : r.answers.entrySet()) {
                    JevAnswer a = e.getValue();
                    JevThresholds.Decision d = thresholds.decide(a);
                    sb.append("[").append(a.type).append("] ").append(e.getKey())
                            .append(" → ").append(JevThresholds.speechFor(d, a))
                            .append("（").append(decisionLabel(d)).append("）\n");
                    if ("next_action".equals(e.getKey()) || "danger_level".equals(e.getKey())
                            || "should_interrupt".equals(e.getKey())) {
                        announce(e.getKey() + "：" + JevThresholds.speechFor(d, a));
                    }
                }
                String text = sb.toString();
                runOnUiThread(() -> output.setText(text));
            } catch (Exception e) {
                String msg = "【判定失败】" + (e instanceof JevException
                        ? ((JevException) e).kind + " HTTP " + ((JevException) e).status + "\n原文: " + ((JevException) e).bodyText
                        : e.getMessage());
                runOnUiThread(() -> output.setText(msg));
            }
        }, "match3-jev").start();
    }

    private static String decisionLabel(JevThresholds.Decision d) {
        switch (d) {
            case AUTO_EXECUTE: return "高置信·自动执行";
            case CONFIRM: return "中置信·先确认";
            default: return "低置信·绝不静默";
        }
    }

    /* ---------- 播报（宽容策略真链路，与演示播报一致） ---------- */

    private CuePlayer player;
    private CueDispatcher dispatcher;
    private String session;
    private int seq;

    private void announce(String speech) {
        long t = SystemClock.elapsedRealtime();
        if (dispatcher == null) {
            player = new CuePlayer(this);
            session = "m3-" + t;
            dispatcher = new CueDispatcher(player, permissivePolicy(), silentListener,
                    SystemClock::elapsedRealtime);
        }
        dispatcher.submit(new CueRequest(session, session + ":a" + (seq++), "m3:announce", "消消乐播报",
                CueRequest.Category.SYSTEM, 70, t, t + 10000,
                CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC,
                0, 0, 0, speech));
    }

    private CueDispatcher.Policy permissivePolicy() {
        return new CueDispatcher.Policy() {
            @Override public boolean categoryEnabled(CueRequest.Category category) { return true; }
            @Override public int enabledChannels() {
                return CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC;
            }
            @Override public long dedupeWindowMs(CueRequest.Category category) { return 0; }
        };
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (dispatcher != null) dispatcher.close();
        if (player != null) player.close();
    }

    /* ---------- 小工具 ---------- */

    private JSONObject readAsset(String path) throws Exception {
        try (InputStream in = getAssets().open(path)) {
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private Spinner spinner(LinearLayout parent, String label, String[] values, int defaultIndex) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(defaultIndex);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        parent.addView(spinner, lp);
        return spinner;
    }

    private EditText pctInput(LinearLayout parent, String hint, int value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(String.valueOf(value));
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        parent.addView(input, lp);
        return input;
    }

    private void briefLabel(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setPadding(0, 0, UiKit.dp(this, 6), 0);
        parent.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
}
