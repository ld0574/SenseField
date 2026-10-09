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
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
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

/** Optional screenshot calibration, learning and inspection; live geometry is confirmed automatically. */
public final class Match3ToolsActivity extends UiActivity {
    private static final int REQ_PICK_IMAGE = 2001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Bitmap screenshot;
    private Uri screenshotUri;
    private BoardGeometry screenshotGeometry;
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
    private Spinner markNameSpin;
    private TextView output;
    private final List<LinearLayout> optionalSections = new ArrayList<>();
    private final CueDispatcher.Listener silentListener = new CueDispatcher.Listener() {
        @Override public void onDispatch(CueRequest request, CueDispatcher.DispatchResult result) { }
        @Override public void onPlayback(CueRequest request, String channel, long atMs, String result) { }
    };

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

        UiKit.pageHeader(this, page, "高级识别工具", "开心消消乐");
        UiKit.add(page, note("实时识别会自动确认棋盘。这些工具只在识别不准或需要截图点读时使用。"), 20);
        LinearLayout screenshots = card(page, "截图试读");
        Button pick = UiKit.button(this, "选择游戏截图", true);
        pick.setOnClickListener(v -> pickScreenshot());
        screenshots.addView(pick);
        screenshots.addView(note("载入截图后自动寻找棋盘，再按需采样或点读。"));
        Button sample = UiKit.button(this, "采样截图并播报可消除位置", false);
        sample.setOnClickListener(v -> sampleAndAnnounce());
        screenshots.addView(sample);
        Button autoFit = UiKit.button(this, "重新自动定位截图棋盘", false);
        autoFit.setOnClickListener(v -> autoFitBoard(true));
        screenshots.addView(autoFit);

        /* ---------- 标定 ---------- */
        LinearLayout calib = foldedCard(page, "手动棋盘校准");
        calib.addView(note("通常无需手动设置。只有自动定位不准时，才调整截图范围；实时识别仍会核验当前棋盘。"));
        rowsSpin = spinner(new String[]{"6", "7", "8", "9"}, 2);
        colsSpin = spinner(new String[]{"6", "7", "8", "9"}, 2);
        calib.addView(UiKit.fieldRow(this, UiKit.field(this, "行数", rowsSpin),
                UiKit.field(this, "列数", colsSpin)));
        leftIn = pctInput("左上X%", 4);
        topIn = pctInput("左上Y%", 18);
        calib.addView(UiKit.fieldRow(this, UiKit.field(this, "左上 X（%）", leftIn),
                UiKit.field(this, "左上 Y（%）", topIn)));
        rightIn = pctInput("右下X%", 96);
        bottomIn = pctInput("右下Y%", 82);
        calib.addView(UiKit.fieldRow(this, UiKit.field(this, "右下 X（%）", rightIn),
                UiKit.field(this, "右下 Y（%）", bottomIn)));
        loadCalibration();
        loadGating();
        /* ---------- 特殊棋子模板库 ---------- */
        LinearLayout special = foldedCard(page, "棋子学习库");
        special.addView(note("从截图保存棋子示例，帮助识别基础动物和特殊棋子。"));
        markRowSpin = spinner(new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9"}, 0);
        markColSpin = spinner(new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9"}, 0);
        special.addView(UiKit.fieldRow(this, UiKit.field(this, "行", markRowSpin),
                UiKit.field(this, "列", markColSpin)));
        markNameSpin = spinner(new String[]{
                "红狐狸", "小鸡", "青蛙", "河马", "棕熊", "紫猫", "自定义…"}, 0);
        special.addView(UiKit.field(this, "棋子", markNameSpin));
        markNameIn = new EditText(this);
        markNameIn.setHint("选「自定义…」时填名称，如：炸弹");
        markNameIn.setSingleLine(true);
        UiKit.styleInput(markNameIn);
        special.addView(UiKit.field(this, "自定义名称", markNameIn));
        Button markSave = UiKit.button(this, "从当前截图裁剪该格，保存为模板", false);
        markSave.setOnClickListener(v -> saveSpecialTemplate());
        special.addView(markSave);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setContentDescription("棋盘截图预览：点击格子播报该格");
        preview.setOnTouchListener((v, event) -> {
            if (event.getAction() != android.view.MotionEvent.ACTION_UP) return false;
            if (screenshot == null) return false;
            int bw = screenshot.getWidth(), bh = screenshot.getHeight();
            if (preview.getWidth() == 0 || preview.getHeight() == 0) return false;
            int px = (int) (event.getX() * bw / preview.getWidth());
            int py = (int) (event.getY() * bh / preview.getHeight());
            handlePreviewTap(px, py);
            return true;
        });
        UiKit.add(page, preview, UiKit.GAP_SECTION);

        /* ---------- 说明书与点读 ---------- */
        LinearLayout coach = foldedCard(page, "说明书与截图点读");
        coach.addView(note("已内置开心消消乐通用规则。"));
        Button kb = UiKit.button(this, "朗读说明书（每按一次读一条）", false);
        kb.setOnClickListener(v -> {
            String[] names = Match3Coach.knowledgeNames();
            String name = names[kbIndex % names.length];
            announce(name + "。" + Match3Coach.KNOWLEDGE.get(name));
            output.setText("说明书 [" + name + "]：" + Match3Coach.KNOWLEDGE.get(name));
            kbIndex++;
        });
        coach.addView(kb);
        Button region = UiKit.button(this, "框选识别（点我后在预览上点左上角和右下角）", false);
        region.setOnClickListener(v -> {
            regionPick = !regionPick;
            regionFirst = null;
            toast(regionPick ? "框选模式：先点棋盘区域的左上角" : "框选模式已取消");
        });
        coach.addView(region);
        Button props = UiKit.button(this, "播报屏幕下方道具栏", false);
        props.setOnClickListener(v -> announcePropBar());
        coach.addView(props);

        if (JevSettings.enabled(this)) {
        /* ---------- 读屏判定（无障碍树→Jev，领导方案 P0） ---------- */
        LinearLayout readerCard = foldedCard(page, "读屏判定工具");
        readerCard.addView(note("先在系统设置开启听野读屏状态服务。"));
        Button a11yGo = UiKit.button(this, "读屏→Jev 屏幕判定（类型＋弹窗门控）", true);
        a11yGo.setOnClickListener(v -> judgeScreen());
        readerCard.addView(a11yGo);
        Button iconGo = UiKit.button(this, "图标消歧判定", false);
        iconGo.setOnClickListener(v -> judgeIcon());
        readerCard.addView(iconGo);
        Button priGo = UiKit.button(this, "播报优先级排序（前 5 候选）", false);
        priGo.setOnClickListener(v -> judgePriority());
        readerCard.addView(priGo);
        Button markBtn = UiKit.button(this, "标记操作前状态（结果确认用）", false);
        markBtn.setOnClickListener(v -> { SenseFieldReaderService.markBefore(); toast("已标记操作前状态"); });
        readerCard.addView(markBtn);
        Button a11ySet = UiKit.button(this, "打开系统无障碍设置", false);
        a11ySet.setOnClickListener(v -> startActivity(
                new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        readerCard.addView(a11ySet);


        }
        LinearLayout judgmentCard = foldedCard(page, "实验判定（可选）");
        judgmentCard.addView(note("默认关闭；本地棋盘识别无需启用此服务。"));
        Button judgmentTest = UiKit.button(this, "实验判定设置与自测", false);
        judgmentTest.setOnClickListener(v -> startActivity(new Intent(this, JudgmentSelfTestActivity.class)));
        judgmentCard.addView(judgmentTest);
        Button demo = UiKit.button(this, "测试示例棋盘（需配置服务）", false);
        demo.setOnClickListener(v -> judgeSample());
        if (JevSettings.enabled(this)) judgmentCard.addView(demo);

        LinearLayout outCard = card(page, "识别结果");
        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(18);
        output.setTextColor(UiKit.INK);
        output.setLineSpacing(UiKit.dp(this, 2), 1f);
        output.setTextIsSelectable(true);
        output.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        output.setText("等待操作…");
        outCard.addView(output);
        if (savedInstanceState != null && savedInstanceState.getString("screenshot_uri") != null)
            loadScreenshot(Uri.parse(savedInstanceState.getString("screenshot_uri")), false);
        if (savedInstanceState != null) for (LinearLayout section : optionalSections) {
            String key = section.getTag().toString();
            section.findViewWithTag("ui_details_body").setVisibility(
                    savedInstanceState.getBoolean(key) ? View.VISIBLE : View.GONE);
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
        loadScreenshot(data.getData(), true);
    }

    private void loadScreenshot(Uri uri, boolean tellUser) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, bounds.outWidth / 1080);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (screenshot != null) screenshot.recycle();
                screenshotGeometry = null;
                screenshot = BitmapFactory.decodeStream(in, null, opts);
            }
            screenshotUri = uri;
            if (screenshot == null) {
                toast("图片读取失败，换一张试试");
                return;
            }
            if (!autoFitBoard(false)) {
                preview.setImageBitmap(screenshot);
                output.setText("尚未确认截图中的棋盘。可换一张稳定截图，或展开手动棋盘校准。");
            }
            if (tellUser) toast("截图已载入，可采样或点击棋子试读");
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

    private boolean saveCalibration() {
        int rows = 6 + rowsSpin.getSelectedItemPosition();
        int cols = 6 + colsSpin.getSelectedItemPosition();
        int l = parseInt(leftIn, 4), t = parseInt(topIn, 18);
        int r = parseInt(rightIn, 96), b = parseInt(bottomIn, 82);
        /* 写入侧同用一把闸门（BUGFIX_PLAN 修复三）：只堵实时服务的使用侧，
         * 设置页照样还能把坏值塞回 SharedPreferences。 */
        int[] cell = new int[2];
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = screenshot == null ? dm.widthPixels : screenshot.getWidth();
        int height = screenshot == null ? dm.heightPixels : screenshot.getHeight();
        if (!Match3Sampler.plausibleCalibration(width, height,
                l, t, r, b, rows, cols, cell)) {
            toast("标定区域太小：单格只有 " + cell[0] + "x" + cell[1] + " 像素，"
                    + "至少需要 " + Match3Sampler.minPlausibleCell(width)
                    + " 像素。请把整个棋盘框进来（画面 " + width
                    + "x" + height + "）。");
            return false;
        }
        GameProfile.settings(this).edit()
                .putInt("match3_rows", rows)
                .putInt("match3_cols", cols)
                .putInt("match3_l", l)
                .putInt("match3_t", t)
                .putInt("match3_r", r)
                .putInt("match3_b", b)
                /* 标记「玩家亲手标定过」：实时服务在自动检测失败时只信这份标定，
                 * 没有此标志一律 ABSTAIN 播报，绝不拿默认 8×8 硬读（防满屏河马式乱播） */
                .putBoolean("match3_calibrated", true)
                .apply();
        return true;
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
        BoardGeometry geometry;
        try { geometry = screenshotGeometry(); }
        catch (IllegalArgumentException invalid) { return; }
        Bitmap marked = screenshot.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(marked);
        Paint paint = new Paint();
        paint.setColor(Color.rgb(0, 200, 120));
        paint.setStrokeWidth(Math.max(2, marked.getWidth() / 300));
        for (int i = 0; i <= geometry.cols; i++) {
            float x = geometry.cellLeft(i);
            canvas.drawLine(x, geometry.top, x, geometry.bottom, paint);
        }
        for (int i = 0; i <= geometry.rows; i++) {
            float y = geometry.cellTop(i);
            canvas.drawLine(geometry.left, y, geometry.right, y, paint);
        }
        preview.setImageBitmap(marked);
    }

    /* ---------- 截图式识别 ---------- */

    private void sampleAndAnnounce() {
        if (screenshot == null) {
            toast("先选择一张游戏截图");
            return;
        }
        if (!saveCalibration()) return;
        drawPreview();
        char[][] board; List<String> scan;
        try (Match3Sampler sampler = sampler()) {
            board = sampler.sample(screenshot); scan = Match3Board.scanSpeech(board, sampler::pieceName);
        }
        StringBuilder sb = new StringBuilder("识别矩阵（. 表示未识别，空白表示空格）：\n");
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
            announce("没有找到可直接消除的交换，开始逐行扫描局面。");
            for (String line : scan) {
                sb.append("  ").append(line).append('\n');
            }
        }
        sb.append("\n提示：错格多半是标定不准或特殊棋子，微调百分比或标注模板后重新采样。");
        output.setText(sb.toString());
    }

    private Match3Sampler sampler() {
        return new Match3Sampler(this, screenshotGeometry());
    }

    private BoardGeometry screenshotGeometry() {
        int rows = 6 + rowsSpin.getSelectedItemPosition(), cols = 6 + colsSpin.getSelectedItemPosition();
        int[] pct = {parseInt(leftIn, 4), parseInt(topIn, 18), parseInt(rightIn, 96), parseInt(bottomIn, 82)};
        if (screenshotGeometry != null && screenshotGeometry.rows == rows && screenshotGeometry.cols == cols
                && java.util.Arrays.equals(pct, screenshotGeometry.percentages())) return screenshotGeometry;
        screenshotGeometry = BoardGeometry.fromPercent(screenshot.getWidth(), screenshot.getHeight(), rows, cols, pct);
        return screenshotGeometry;
    }

    /* ---------- 特殊棋子模板 ---------- */

    private void saveSpecialTemplate() {
        if (screenshot == null) {
            toast("先选一张截图再标注");
            return;
        }
        String preset = (String) markNameSpin.getSelectedItem();
        String name = "自定义…".equals(preset)
                ? markNameIn.getText().toString().trim() : preset;
        if (name == null || name.isEmpty() || "自定义…".equals(name)) {
            toast("先选或填棋子名称");
            return;
        }
        if (!saveCalibration()) return;
        int row = markRowSpin.getSelectedItemPosition();
        int col = markColSpin.getSelectedItemPosition();
        BoardGeometry geometry = screenshotGeometry();
        if (row >= geometry.rows || col >= geometry.cols) { toast("该位置不在当前棋盘内"); return; }
        int cx = geometry.centerX(col), cy = geometry.centerY(row);
        int side = Math.max(8, Math.min(geometry.cellWidth(), geometry.cellHeight()) / 2);
        int cl = Math.max(0, cx - side), ct = Math.max(0, cy - side);
        int cr = Math.min(screenshot.getWidth(), cx + side), cb = Math.min(screenshot.getHeight(), cy + side);
        if (cr - cl < 8 || cb - ct < 8) {
            toast("裁剪区域无效");
            return;
        }
        Bitmap crop = Bitmap.createBitmap(screenshot, cl, ct, cr - cl, cb - ct);
        try {
            Match3Sampler.saveTemplate(this, name, crop);
            List<Match3Sampler.SpecialTemplate> loaded = Match3Sampler.loadTemplates(this);
            int count;
            try { count = loaded.size(); }
            finally { Match3Sampler.recycleTemplates(loaded); }
            announce("棋子模板已保存：" + name + "，当前共 " + count + " 个。可继续添加其他棋子的清晰示例。");
            output.setText("已保存模板「" + name + "」，共 " + count + " 个。下次采样时颜色判不出的格子会自动与模板比对。");
            hideKeyboard();
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
        } finally {
            if (crop != screenshot) crop.recycle();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus();
        if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    /** 自动适配：对当前截图检测深色棋盘格包围盒，写回标定（实时识别启动时也会自动做一次）。 */
    private boolean autoFitBoard(boolean readStatus) {
        if (screenshot == null) {
            toast("先选择一张游戏截图");
            return false;
        }
        BoardGeometry geometry = Match3Sampler.autoDetectGeometry(screenshot);
        if (geometry == null) {
            toast("棋盘位置或格数还没确认，请换一张稳定的截图或重新校准");
            return false;
        }
        int[] box = geometry.percentages();
        rowsSpin.setSelection(geometry.rows - 6);
        colsSpin.setSelection(geometry.cols - 6);
        leftIn.setText(String.valueOf(box[0]));
        topIn.setText(String.valueOf(box[1]));
        rightIn.setText(String.valueOf(box[2]));
        bottomIn.setText(String.valueOf(box[3]));
        if (!saveCalibration()) return false;
        screenshotGeometry = geometry;
        drawPreview();
        String result = "截图已自动定位为 " + geometry.rows + " 行 " + geometry.cols + " 列。可采样验证或点击棋子试读。";
        if (readStatus) announce(result);
        output.setText(result);
        return true;
    }

    /* ---------- Jev 示例判定 ---------- */

    private void judgeSample() {
        final JevClient client = JevSettings.clientOrNull(this);
        if (client == null) {
            output.setText("示例测试尚未配置。请在「判定自测」页配置并启用服务；截图识别与实时辅助仍可在本机使用。");
            return;
        }
        output.setText("正在读取示例棋盘…\n");
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

    /* ---------- 预览点读 / 框选 / 道具栏 ---------- */

    private int kbIndex;
    private float gateHigh = 0.85f;
    private float gateMid = 0.60f;

    private void loadGating() {
        try (InputStream in = getAssets().open("jev/gating.json")) {
            byte[] buf = new byte[4096];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            org.json.JSONObject cfg = new org.json.JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            gateHigh = (float) cfg.getJSONObject("screen_type").optDouble("high", 0.85);
            gateMid = (float) cfg.getJSONObject("screen_type").optDouble("mid", 0.60);
        } catch (Exception e) {
            // 配置缺失用默认阈值，不阻断
        }
    }
    private boolean regionPick;
    private int[] regionFirst;

    /** 预览点击：普通模式报单格；框选模式两次点击定区域后播报内容摘要。 */
    private void handlePreviewTap(int px, int py) {
        if (!saveCalibration()) return;
        BoardGeometry geometry = screenshotGeometry();
        int[] cell = geometry.cellAt(px, py);
        int rows = geometry.rows, cols = geometry.cols;
        if (cell == null) {
            toast("请检查棋盘范围，点击棋盘内的格子");
            return;
        }
        int row = cell[0], col = cell[1];
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            toast("点在棋盘范围外了");
            return;
        }
        if (regionPick) {
            int[] corner = {row, col};
            if (regionFirst == null) {
                regionFirst = corner;
                toast("左上角已定（第 " + (row + 1) + " 行第 " + (col + 1) + " 列），再点右下角");
                return;
            }
            Match3Coach.RegionSummary s;
            try (Match3Sampler sampler = sampler()) {
                char[][] board = sampler.sample(screenshot);
                s = Match3Coach.summarizeRegion(board, regionFirst[0], regionFirst[1], row, col, sampler::pieceName);
            }
            String speech = Match3Coach.regionSpeech(s);
            announce(speech);
            output.setText(speech);
            regionPick = false;
            regionFirst = null;
            return;
        }
        int[] hit; String name;
        try (Match3Sampler sampler = sampler()) {
            hit = sampler.touchRead(screenshot, px, py);
            if (hit == null) return;
            name = sampler.pieceName((char) hit[2]);
        }
        if (hit == null) return;
        String speech = "第 " + (row + 1) + " 行，第 " + (col + 1) + " 列："
                + name + "，"
                + Match3Coach.quadrantOf(row, col, rows, cols) + "区域。";
        announce(speech);
        output.setText(speech);
    }

    /** 道具栏播报：棋盘盒下方横向采 6 格，逐格报颜色/模板名。 */
    private void announcePropBar() {
        if (screenshot == null) {
            toast("本功能配合截图使用：先选一张含道具栏的截图");
            return;
        }
        char[] strip = Match3Sampler.classifyStrip(screenshot, 84, 96, 6);
        StringBuilder sb = new StringBuilder("道具栏从左到右：");
        for (int i = 0; i < strip.length; i++) {
            sb.append("第 ").append(i + 1).append(" 个，")
              .append(Match3Coach.pieceName(strip[i])).append("色道具；");
        }
        String speech = sb.toString();
        announce(speech);
        output.setText(speech);
    }

    /* ---------- 读屏判定（无障碍树→Jev，P0 基线：屏幕类型 Choice＋模态 Noul 扇出） ---------- */

    private void judgeScreen() {
        final JevClient client = JevSettings.clientOrNull(this);
        if (client == null) {
            output.setText("【未运行】判定层未启用或配置不完整（判定自测页上方保存设置）。");
            return;
        }
        if (!SenseFieldReaderService.hasFreshState()) {
            output.setText("【未就绪】读屏状态服务没有可用状态：请点「打开系统无障碍设置」，"
                    + "找到「听野读屏状态服务」并开启，然后切回本页再试。\n开启后切几次屏幕，服务会自动缓存最新压缩状态。");
            return;
        }
        final String state = SenseFieldReaderService.latestState();
        final String sensitive = Match3Gate.sensitiveHit(state);
        if (sensitive != null) {
            String block = "本地敏感闸门命中：当前为「" + sensitive + "」相关页面，内容不上云、不播报细节，建议手动处理。";
            output.setText(block);
            announce(block);
            return;
        }
        final String pkg = SenseFieldReaderService.latestPackage();
        output.setText("读屏判定中（来源 " + pkg + "，状态 " + state.length() + " 字符）…\n");
        new Thread(() -> {
            try {
                Map<String, JevQuestion> qs = new java.util.LinkedHashMap<>();
                Map<String, String> types = new java.util.LinkedHashMap<>();
                types.put("主内容列表", "以可滚动列表或卡片流为主");
                types.put("详情/阅读", "以大段阅读内容为主");
                types.put("表单填写", "存在多个输入框待填写");
                types.put("登录/验证", "登录或验证页面");
                types.put("支付/金额确认", "涉及付款或金额确认");
                types.put("弹窗/对话框", "存在覆盖型对话框");
                types.put("系统设置", "系统或应用设置页");
                types.put("错误/异常", "存在错误提示");
                types.put("加载中", "正在加载或骨架屏");
                types.put("广告/推广", "以广告或推广内容为主");
                types.put("其他", "以上都不是");
                types.put("ABSTAIN", "信息不足以判断");
                qs.put("screen_type", new JevQuestion("screen_type", JevQuestion.TYPE_CHOICE,
                        "当前屏幕属于下列哪一类？只选一个。", types));
                qs.put("has_modal", new JevQuestion("has_modal", JevQuestion.TYPE_NOUL,
                        "当前存在模态层（对话框/底部弹窗/权限申请/广告浮层），导致下方主内容不完整或不可操作。",
                        mapOf("true", "存在模态遮挡", "false", "无遮挡")));
                JevResult r = client.judge(state, qs);
                StringBuilder sb = new StringBuilder("HTTP 200 · ").append(r.elapsedMs).append("ms · ")
                        .append(r.model).append(" · tokens ").append(r.inputTokens).append("\n\n");
                List<String> speeches = new ArrayList<>();
                JevAnswer st = r.answers.get("screen_type");
                if (st != null) {
                    double c = st.surety();
                    String speech;
                    if ("ABSTAIN".equals(st.choice)) speech = "这一屏我没看清楚，要我从上往下逐条读吗？";
                    else if (c >= gateHigh) speech = "当前是「" + st.choice + "」。";
                    else if (c >= gateMid) speech = "可能是「" + st.choice + "」，这个我不太确定。";
                    else speech = "这一屏我没看清楚，要我从上往下逐条读吗？";
                    sb.append("[screen_type] ").append(st.choice)
                      .append(" (conf=").append(String.format(java.util.Locale.US, "%.2f", c)).append(")\n")
                      .append("→ ").append(speech).append("\n\n");
                    speeches.add(speech);
                }
                JevAnswer modal = r.answers.get("has_modal");
                if (modal != null && modal.noul >= 0.7) {
                    String m = "屏幕上有弹窗遮挡，下方内容暂时无法操作。";
                    sb.append("[has_modal] p=").append(String.format(java.util.Locale.US, "%.2f", modal.noul))
                      .append("\n→ ").append(m).append("\n");
                    speeches.add(0, m);
                }
                for (String s : speeches) announce(s);
                String text = sb.toString();
                runOnUiThread(() -> output.setText(text));
            } catch (Exception e) {
                String msg = "【读屏判定失败】" + (e instanceof JevException
                        ? ((JevException) e).kind + " HTTP " + ((JevException) e).status + "\n原文: " + ((JevException) e).bodyText
                        : e.getMessage());
                runOnUiThread(() -> output.setText(msg));
            }
        }, "screen-jev").start();
    }

    private static Map<String, String> mapOf(String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** 图标消歧：对状态中第一个无文本可点图标发起 Choice 判定（候选固定＋ABSTAIN）。 */
    private void judgeIcon() {
        final JevClient client = JevSettings.clientOrNull(this);
        if (client == null) { output.setText("判定层未启用"); return; }
        final String nodeLine = Match3Gate.firstIconOnlyNode(SenseFieldReaderService.latestState());
        if (nodeLine == null) { output.setText("当前状态里没有「无文本可点图标」可消歧。"); return; }
        output.setText("图标消歧中…\n对象：" + nodeLine);
        new Thread(() -> {
            try {
                Map<String, JevQuestion> qs = new java.util.LinkedHashMap<>();
                Map<String, String> crit = new java.util.LinkedHashMap<>();
                for (String c : Match3Gate.ICON_CANDIDATES) {
                    crit.put(c, "ABSTAIN".equals(c) ? "信息不足，不要猜" : c + "功能");
                }
                qs.put("icon_meaning", new JevQuestion("icon_meaning", JevQuestion.TYPE_CHOICE,
                        "这个无文字图标最可能是什么功能？位置与上下文见状态。", crit));
                JevResult r = client.judge(SenseFieldReaderService.latestState(), qs);
                JevAnswer a = r.answers.get("icon_meaning");
                boolean abstain = "ABSTAIN".equals(a.choice);
                String speech = abstain ? "这个图标我看不出来是什么。" : "这个图标可能是「" + a.choice + "」。";
                String text = nodeLine + "\n→ " + speech + " (conf=" + a.confidence + ")\n";
                runOnUiThread(() -> output.setText(text));
                announce(speech);
            } catch (Exception e) {
                runOnUiThread(() -> output.setText("【消歧失败】" + e.getMessage()));
            }
        }, "icon-jev").start();
    }

    /** 播报优先级：对前 5 个有文本候选各打一票 Score（0-3 档量规），按分排序播报。 */
    private void judgePriority() {
        final JevClient client = JevSettings.clientOrNull(this);
        if (client == null) { output.setText("判定层未启用"); return; }
        new Thread(() -> {
            try {
                String st = SenseFieldReaderService.latestState();
                List<String> cands = new ArrayList<>();
                for (String line : st.split("\n")) {
                    if (line.contains("\"") && cands.size() < 5) cands.add(line);
                }
                if (cands.isEmpty()) { runOnUiThread(() -> output.setText("无候选")); return; }
                Map<String, JevQuestion> qs = new java.util.LinkedHashMap<>();
                for (int i = 0; i < cands.size(); i++) {
                    qs.put("pri_" + i, new JevQuestion("pri_" + i, JevQuestion.TYPE_SCORE,
                            "这条信息现在应该优先播报给视障用户的程度（0=不必播，3=必须立即播）： " + cands.get(i),
                            new String[]{"不必播报", "可播报", "应播报", "必须立即播报"}));
                }
                JevResult r = client.judge(st, qs);
                List<int[]> ranked = new ArrayList<>();
                for (int i = 0; i < cands.size(); i++) {
                    JevAnswer a = r.answers.get("pri_" + i);
                    ranked.add(new int[]{i, a == null ? 0 : (int) Math.round(a.score)});
                }
                java.util.Collections.sort(ranked, (x, y) -> y[1] - x[1]);
                StringBuilder sb = new StringBuilder("播报优先级（高→低）：\n");
                List<String> order = new ArrayList<>();
                for (int[] e : ranked) {
                    if (e[1] < 3) continue;
                    sb.append("  ").append(cands.get(e[0])).append(" → ").append(e[1]).append("\n");
                    order.add(cands.get(e[0]));
                }
                for (String line : order) announce(line);
                String text = sb.toString();
                runOnUiThread(() -> output.setText(text));
            } catch (Exception e) {
                runOnUiThread(() -> output.setText("【优先级失败】" + e.getMessage()));
            }
        }, "pri-jev").start();
    }

    /* ---------- 播报（宽容策略真链路，与演示播报一致） ---------- */

    private CuePlayer player;
    private CueDispatcher dispatcher;
    private String session;
    private int seq;

    private void announce(String speech) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> announce(speech));
            return;
        }
        if (isFinishing() || isDestroyed()) return;
        if (CaptureService.isRunning() || Match3LiveService.isRunning()) {
            toast("请先停止实时辅助，再试听截图或示例");
            return;
        }
        long t = SystemClock.elapsedRealtime();
        if (dispatcher == null) {
            player = new CuePlayer(this);
            session = "m3-" + t;
            dispatcher = new CueDispatcher(player, new Match3LiveCuePolicy(new CueSettings(this)), silentListener,
                    SystemClock::elapsedRealtime);
        }
        dispatcher.submit(new CueRequest(session, session + ":a" + (seq++), "m3:announce", "消消乐播报",
                CueRequest.Category.SYSTEM, 70, t, t + 10000,
                Match3LiveCuePolicy.REQUESTED_CHANNELS,
                0, 0, 0, speech));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (dispatcher != null) dispatcher.close();
        if (player != null) player.close();
        if (screenshot != null && !screenshot.isRecycled()) screenshot.recycle();
        screenshot = null;
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

    private Spinner spinner(String[] values, int defaultIndex) {
        Spinner spinner = new Spinner(this);
        UiKit.styleSpinner(spinner, values);
        spinner.setSelection(defaultIndex);
        return spinner;
    }

    private EditText pctInput(String hint, int value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(String.valueOf(value));
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        UiKit.styleInput(input);
        return input;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (screenshotUri != null) state.putString("screenshot_uri", screenshotUri.toString());
        for (LinearLayout section : optionalSections) state.putBoolean(section.getTag().toString(),
                section.findViewWithTag("ui_details_body").getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(state);
    }

    private LinearLayout foldedCard(LinearLayout page, String title) {
        LinearLayout section = UiKit.details(this, title);
        optionalSections.add(section);
        UiKit.add(page, section, UiKit.GAP_SECTION);
        LinearLayout body = section.findViewWithTag("ui_details_body");
        UiKit.spaceChildren(body, UiKit.GAP_CONTROL);
        return body;
    }

    /** Adds a titled card to the page; every child is separated by the standard gap. */
    private LinearLayout card(LinearLayout page, String title) {
        LinearLayout content = cardContent(title);
        UiKit.add(page, (View) content.getParent(), UiKit.GAP_SECTION);
        return content;
    }

    /** A titled card that is not yet attached; returns the stack to add controls to. */
    private LinearLayout cardContent(String title) {
        LinearLayout card = UiKit.card(this);
        UiKit.add(card, UiKit.heading(this, title), 12);
        LinearLayout content = UiKit.vertical(this);
        UiKit.spaceChildren(content, UiKit.GAP_CONTROL);
        UiKit.add(card, content, 0);
        return content;
    }

    private TextView note(String text) {
        return UiKit.body(this, text);
    }
}
