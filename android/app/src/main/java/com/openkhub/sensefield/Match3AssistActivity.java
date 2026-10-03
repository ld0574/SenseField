package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 开心消消乐（北京乐元素）辅助 · 体验版。
 * v1 识别路线：玩家截一张游戏棋盘截图 → 选图 → 按标定网格采样颜色 → 颜色矩阵
 * → 本地三连/交换检测 → 语音播报 → 可选发 Jev 判定。连续截屏实时识别在路线图。
 * 无障碍优先：所有结果都走 TTS 出声，界面上同步显示文字。
 */
public class Match3AssistActivity extends Activity {
    private static final String[] ANIPOP_PACKAGES = {
            "com.happyelements.AndroidAnimal",
            "com.happyelements.AndroidAnimal.qq",
            "com.happyelements.AndroidAnimal.mi",
            "com.happyelements.AndroidAnimal.wdj"
    };
    private static final int REQ_PICK_IMAGE = 2001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Bitmap screenshot;
    private ImageView preview;
    private Spinner rowsSpin;
    private Spinner colsSpin;
    private EditText leftIn;
    private EditText topIn;
    private EditText rightIn;
    private EditText bottomIn;
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
        title.setText("开心消消乐辅助 · 体验版");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);
        page.addView(note("v1 识别路线：在游戏里截一张棋盘图 → 回来选图 → 标定棋盘范围 → 采样颜色 → 播报可消除位置，并可发 Jev 判定。实时连续识别在路线图。"));

        LinearLayout actions = UiKit.card(this);
        Button launch = UiKit.button(this, "启动开心消消乐", true);
        launch.setOnClickListener(v -> launchGame());
        actions.addView(launch);
        Button pick = UiKit.button(this, "选择游戏截图（相册）", false);
        pick.setOnClickListener(v -> pickScreenshot());
        actions.addView(pick);
        Button demo = UiKit.button(this, "判定示例局面（无需截图，走 Jev 真请求）", false);
        demo.setOnClickListener(v -> judgeSample());
        actions.addView(demo);
        page.addView(actions);

        LinearLayout calib = UiKit.card(this);
        calib.addView(sectionLabel("棋盘标定（按截图百分比填，一次标定后自动记住）"));
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
        Button sample = UiKit.button(this, "采样棋盘并播报可消除位置", false);
        sample.setOnClickListener(v -> sampleAndAnnounce());
        calib.addView(sample);
        page.addView(calib);

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

    /* ---------- 启动游戏 ---------- */

    private void launchGame() {
        for (String pkg : ANIPOP_PACKAGES) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                startActivity(launch);
                return;
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + ANIPOP_PACKAGES[0])));
            toast("未检测到已安装的开心消消乐，已打开应用商店");
        } catch (Exception e) {
            toast("未检测到开心消消乐，请先安装（乐元素官方版或各渠道版）");
        }
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
            toast("截图已载入，检查标定后点「采样棋盘并播报」");
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

    private static int intToIndex(int value, int min, int max) {
        return Math.max(0, Math.min(max - min, value - min));
    }

    private void saveCalibration() {
        GameProfile.settings(this).edit()
                .putInt("match3_rows", indexToInt(rowsSpin))
                .putInt("match3_cols", indexToInt(colsSpin))
                .putInt("match3_l", parseInt(leftIn, 4))
                .putInt("match3_t", parseInt(topIn, 18))
                .putInt("match3_r", parseInt(rightIn, 96))
                .putInt("match3_b", parseInt(bottomIn, 82))
                .apply();
    }

    private static int indexToInt(Spinner spinner) {
        return 6 + spinner.getSelectedItemPosition();
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
        int rows = indexToInt(rowsSpin), cols = indexToInt(colsSpin);
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

    /* ---------- 采样 + 检测 + 播报 ---------- */

    private void sampleAndAnnounce() {
        if (screenshot == null) {
            toast("先选择一张游戏截图");
            return;
        }
        saveCalibration();
        drawPreview();
        char[][] board = sampleBoard(screenshot);
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
        sb.append("\n提示：错格多半是标定不准或特殊棋子，微调百分比后重新采样。");
        output.setText(sb.toString());
    }

    /** 按标定切格，取每格中心 16×16 平均色 → HSV → 六色字母（. 表示未识别）。 */
    private char[][] sampleBoard(Bitmap bitmap) {
        int rows = indexToInt(rowsSpin), cols = indexToInt(colsSpin);
        int l = bitmap.getWidth() * parseInt(leftIn, 4) / 100;
        int t = bitmap.getHeight() * parseInt(topIn, 18) / 100;
        int r = bitmap.getWidth() * parseInt(rightIn, 96) / 100;
        int b = bitmap.getHeight() * parseInt(bottomIn, 82) / 100;
        char[][] board = new char[rows][cols];
        int cellW = (r - l) / cols, cellH = (b - t) / rows;
        int half = Math.max(3, Math.min(cellW, cellH) / 8);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int cx = l + cellW * col + cellW / 2;
                int cy = t + cellH * row + cellH / 2;
                board[row][col] = classifyCell(bitmap, cx, cy, half);
            }
        }
        return board;
    }

    private static char classifyCell(Bitmap bitmap, int cx, int cy, int half) {
        long sumR = 0, sumG = 0, sumB = 0, n = 0;
        for (int y = Math.max(0, cy - half); y <= Math.min(bitmap.getHeight() - 1, cy + half); y++) {
            for (int x = Math.max(0, cx - half); x <= Math.min(bitmap.getWidth() - 1, cx + half); x++) {
                int px = bitmap.getPixel(x, y);
                sumR += Color.red(px);
                sumG += Color.green(px);
                sumB += Color.blue(px);
                n++;
            }
        }
        if (n == 0) return '.';
        float[] hsv = new float[3];
        Color.colorToHSV(Color.rgb((int) (sumR / n), (int) (sumG / n), (int) (sumB / n)), hsv);
        if (hsv[1] < 0.18f || hsv[2] < 0.15f) return '.';   // 低饱和/过暗 → 棋盘底或空格
        float h = hsv[0];
        if (h >= 345 || h < 14) return 'R';
        if (h < 38) return 'O';
        if (h < 68) return 'Y';
        if (h < 165) return 'G';
        if (h < 262) return 'B';
        return 'P';
    }

    /* ---------- Jev 判定 ---------- */

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

    /* ---------- 播报（复用演示播报的宽容策略真链路） ---------- */

    private void announce(String speech) {
        long t = SystemClock.elapsedRealtime();
        if (session == null) {
            player = new CuePlayer(this);
            session = "m3-" + t;
            dispatcher = new CueDispatcher(player, permissivePolicy(), listener(), SystemClock::elapsedRealtime);
        }
        dispatcher.submit(new CueRequest(session, session + ":a" + (seq++), "m3:announce", "消消乐播报",
                CueRequest.Category.SYSTEM, 70, t, t + 10000,
                CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC,
                0, 0, 0, speech));
    }

    private CuePlayer player;
    private CueDispatcher dispatcher;
    private String session;
    private int seq;

    private CueDispatcher.Policy permissivePolicy() {
        return new CueDispatcher.Policy() {
            @Override public boolean categoryEnabled(CueRequest.Category category) { return true; }
            @Override public int enabledChannels() {
                return CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC;
            }
            @Override public long dedupeWindowMs(CueRequest.Category category) { return 0; }
        };
    }

    private CueDispatcher.Listener listener() {
        return new CueDispatcher.Listener() {
            @Override public void onDispatch(CueRequest request, CueDispatcher.DispatchResult result) {
                // 播报结果以语音为准，分发详情不打扰界面
            }
            @Override public void onPlayback(CueRequest request, String channel, long atMs, String result) {
                // 同上
            }
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
