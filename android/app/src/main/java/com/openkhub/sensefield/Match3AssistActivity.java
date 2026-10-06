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
    private Spinner markNameSpin;
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
        Button exploreBtn = UiKit.button(this, "触屏点读模式 开/关（需先开实时识别；Android 14+）", false);
        exploreBtn.setOnClickListener(v -> {
            if (!Match3LiveService.isRunning()) { toast("先点「开始实时识别」再开触屏点读"); return; }
            exploreOn = !exploreOn;
            startService(new Intent(this, Match3LiveService.class)
                    .setAction(exploreOn ? Match3LiveService.ACTION_EXPLORE_ON : Match3LiveService.ACTION_EXPLORE_OFF));
            toast(exploreOn ? "触屏点读已开启：直接在游戏里点棋子，每次点击播报该格" : "触屏点读已关闭");
        });
        actions.addView(exploreBtn);
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
        loadGating();
        Button autoFit = UiKit.button(this, "自动适配棋盘（对当前截图自动找棋盘范围）", false);
        autoFit.setOnClickListener(v -> autoFitBoard());
        calib.addView(autoFit);
        Button sample = UiKit.button(this, "采样截图并播报可消除位置", false);
        sample.setOnClickListener(v -> sampleAndAnnounce());
        calib.addView(sample);
        page.addView(calib);

        /* ---------- 特殊棋子模板库 ---------- */
        LinearLayout special = UiKit.card(this);
        special.addView(sectionLabel("棋子学习库（先各学一次基础动物，识别会用学习的画面而非颜色猜测；特殊棋子同样标注）"));
        LinearLayout mark = UiKit.horizontal(this);
        briefLabel(mark, "行");
        markRowSpin = spinner(mark, "行", new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9"}, 0);
        briefLabel(mark, "列");
        markColSpin = spinner(mark, "列", new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9"}, 0);
        special.addView(mark);
        LinearLayout nameRow = UiKit.horizontal(this);
        briefLabel(nameRow, "棋子");
        markNameSpin = spinner(nameRow, "棋子", new String[]{
                "红狐狸", "小鸡", "青蛙", "河马", "棕熊", "紫猫", "自定义…"}, 0);
        special.addView(nameRow);
        markNameIn = new EditText(this);
        markNameIn.setHint("选「自定义…」时填名称，如：炸弹");
        markNameIn.setSingleLine(true);
        special.addView(markNameIn);
        Button markSave = UiKit.button(this, "从当前截图裁剪该格，保存为模板", false);
        markSave.setOnClickListener(v -> saveSpecialTemplate());
        special.addView(markSave);
        page.addView(special);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
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
        page.addView(preview);

        /* ---------- 说明书与点读 ---------- */
        LinearLayout coach = UiKit.card(this);
        coach.addView(sectionLabel("本关说明书与点读（开心消消乐通用规则内置）"));
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
        page.addView(coach);

        /* ---------- 读屏判定（无障碍树→Jev，领导方案 P0） ---------- */
        LinearLayout readerCard = UiKit.card(this);
        readerCard.addView(sectionLabel("读屏判定（无障碍树→Jev 判断式）：先在系统设置开启「听野读屏状态服务」"));
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
        page.addView(readerCard);

        /* ---------- 诊断记录（实时识别时自动保存帧，支持导出分享） ---------- */
        LinearLayout diagCard = UiKit.card(this);
        diagCard.addView(sectionLabel("诊断记录"));
        Button diagBtn = UiKit.button(this, "查看诊断记录与导出（需先停止识别）", false);
        diagBtn.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        diagCard.addView(diagBtn);
        page.addView(diagCard);

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
            if (exploreOn) startService(new Intent(this, Match3LiveService.class)
                    .setAction(Match3LiveService.ACTION_EXPLORE_ON));
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
                /* 标记「玩家亲手标定过」：实时服务在自动检测失败时只信这份标定，
                 * 没有此标志一律 ABSTAIN 播报，绝不拿默认 8×8 硬读（防满屏河马式乱播） */
                .putBoolean("match3_calibrated", true)
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
        String preset = (String) markNameSpin.getSelectedItem();
        String name = ("自定义…".equals(preset) || markNameIn.getText().toString().trim().isEmpty())
                ? preset : markNameIn.getText().toString().trim();
        if (name == null || name.isEmpty() || "自定义…".equals(name)) {
            toast("先选或填棋子名称");
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
            announce("棋子模板已保存：" + name + "，当前共 " + count + " 个。学全五种基础动物后识别最准。");
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

    /** 自动适配：对当前截图检测深色棋盘格包围盒，写回标定（实时识别启动时也会自动做一次）。 */
    private void autoFitBoard() {
        if (screenshot == null) {
            toast("先选择一张游戏截图");
            return;
        }
        int[] box = Match3Sampler.autoDetectBoard(screenshot);
        if (box == null) {
            toast("自动适配未命中（棋盘底色不是深色？），请手动微调");
            return;
        }
        leftIn.setText(String.valueOf(box[0]));
        topIn.setText(String.valueOf(box[1]));
        rightIn.setText(String.valueOf(box[2]));
        bottomIn.setText(String.valueOf(box[3]));
        saveCalibration();
        drawPreview();
        announce("棋盘自动适配完成，范围是横向百分之 " + box[0] + " 到 " + box[2]
                + "，纵向百分之 " + box[1] + " 到 " + box[3] + "。请点采样确认。");
        output.setText("自动适配：左上 (" + box[0] + "%, " + box[1] + "%) 右下 ("
                + box[2] + "%, " + box[3] + "%)。已写回标定并保存，点「采样」验证。");
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

    /* ---------- 预览点读 / 框选 / 道具栏 ---------- */

    private boolean exploreOn;
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
        saveCalibration();
        int rows = 6 + rowsSpin.getSelectedItemPosition();
        int cols = 6 + colsSpin.getSelectedItemPosition();
        int l = screenshot.getWidth() * parseInt(leftIn, 4) / 100;
        int t = screenshot.getHeight() * parseInt(topIn, 18) / 100;
        int r = screenshot.getWidth() * parseInt(rightIn, 96) / 100;
        int b = screenshot.getHeight() * parseInt(bottomIn, 82) / 100;
        int cellW = (r - l) / cols, cellH = (b - t) / rows;
        int col = (px - l) / cellW, row = (py - t) / cellH;
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
            char[][] board = sampler().sample(screenshot);
            Match3Coach.RegionSummary s = Match3Coach.summarizeRegion(
                    board, regionFirst[0], regionFirst[1], row, col);
            String speech = Match3Coach.regionSpeech(s);
            announce(speech);
            output.setText(speech);
            regionPick = false;
            regionFirst = null;
            return;
        }
        char piece = Match3Sampler.classifyPoint(screenshot, px, py);
        String speech = "第 " + (row + 1) + " 行，第 " + (col + 1) + " 列："
                + Match3Coach.pieceName(piece) + "，"
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
