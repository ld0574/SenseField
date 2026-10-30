package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Optional screenshot calibration, learning and inspection; live geometry is confirmed automatically. */
public final class Match3ToolsActivity extends UiActivity {
    private static final int REQ_PICK_IMAGE = 2001;
    private static final int REQ_SAVE_ROUND_DIGEST = 2002;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Bitmap screenshot;
    private Uri screenshotUri;
    /** 玩家按下「保存这一盘战报」后落到缓存里、等他亲手在创建文档界面里选位置的那份文本。 */
    private File preparedDigest;
    private BoardGeometry screenshotGeometry;
    private Match3BoardPreview preview;
    private Button selectBoard, saveBoard;
    private TextView calibrationStatus;
    private boolean updatingCalibrationFields;
    private BoardGeometry restoredGeometry;
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

        UiKit.pageHeader(this, page, "截图校准与棋子学习", "开心消消乐");
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
        calib.addView(note("自动定位不准时，框出整个棋盘并确认行列。绿色网格应对齐每一格；不规则棋盘要包含边缘格和中间空位。确认后点击保存，实时识别仍会核验当前棋盘。"));
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
        selectBoard = UiKit.button(this, "框选棋盘范围", false);
        selectBoard.setTag("match3_select_board");
        selectBoard.setEnabled(false);
        selectBoard.setOnClickListener(v -> beginBoardSelection());
        calib.addView(selectBoard);
        saveBoard = UiKit.button(this, "确认网格并保存校准", true);
        saveBoard.setTag("match3_save_calibration");
        saveBoard.setEnabled(false);
        saveBoard.setOnClickListener(v -> saveCalibration());
        calib.addView(saveBoard);
        Button resetBoard = UiKit.button(this, "清除校准，恢复自动定位", false);
        resetBoard.setTag("match3_reset_calibration");
        resetBoard.setOnClickListener(v -> resetCalibration());
        calib.addView(resetBoard);
        calibrationStatus = note("截图试读不会保存设置，只有确认保存才会用于实时识别。");
        calibrationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        calib.addView(calibrationStatus);
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

        preview = new Match3BoardPreview(this);
        preview.setTag("match3_board_preview");
        preview.setListener(new Match3BoardPreview.Listener() {
            @Override public void onImageTap(int x, int y) { handlePreviewTap(x, y); }
            @Override public void onFirstCorner() {
                calibrationStatus.setText("第一个角已选，再点另一个对角。框中应包含整个棋盘。");
                preview.announceForAccessibility(calibrationStatus.getText());
            }
            @Override public boolean onBoardSelected(int left, int top, int right, int bottom) {
                return acceptBoardSelection(left, top, right, bottom);
            }
        });
        UiKit.add(page, preview, UiKit.GAP_SECTION);
        watchCalibrationFields();

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
        coach.addView(note("区域试读只读取已定位棋盘中的部分格子。调整整个棋盘范围，请使用手动棋盘校准。"));
        Button region = UiKit.button(this, "选取区域并试读（点两个对角格）", false);
        region.setOnClickListener(v -> {
            if (screenshot == null) { toast("先选择一张游戏截图"); return; }
            preview.cancelSelection();
            selectBoard.setText("框选棋盘范围");
            regionPick = !regionPick;
            regionFirst = null;
            toast(regionPick ? "框选模式：先点棋盘区域的左上角" : "框选模式已取消");
        });
        coach.addView(region);
        Button props = UiKit.button(this, "播报屏幕下方道具栏", false);
        props.setOnClickListener(v -> announcePropBar());
        coach.addView(props);
        // 兑现 Match3Gate.gatedSpeech 低置信那句「要我从上往下逐条读吗」：给玩家一颗真能把那一条读出来的按钮。
        // 放在本卡片（不挂在 JevSettings.enabled 门后）是因为逐条读全程本地，判定层未启用也要能用。
        Button readAloud = UiKit.button(this, "逐条读这一屏", false);
        readAloud.setOnClickListener(v -> readAloudThisScreen());
        coach.addView(readAloud);
        // 战报的两个玩家出口。战报原先唯一的消费者是 Match3LiveService 里那句 audit（只进诊断日志），
        // 这里只做「接到手」这一件事：文本逐字取自只读取数出口，本卡片一个字也不拼，
        // 念走 announce 既有通道、存走第 15 份书验收过的那条创建文档路。
        Button digestSpeak = UiKit.button(this, "念这一盘战报", false);
        digestSpeak.setOnClickListener(v -> announceRoundDigest());
        coach.addView(digestSpeak);
        Button digestSave = UiKit.button(this, "保存这一盘战报", false);
        digestSave.setOnClickListener(v -> saveRoundDigest());
        coach.addView(digestSave);

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
        if (savedInstanceState != null && screenshot != null) {
            int[] grid = savedInstanceState.getIntArray("screenshot_geometry");
            if (grid != null && grid.length == 8) try {
                restoredGeometry = new BoardGeometry(grid[0], grid[1], grid[2], grid[3], grid[4], grid[5], grid[6], grid[7]);
            } catch (IllegalArgumentException ignored) { }
        }
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQ_PICK_IMAGE) {
            loadScreenshot(data.getData(), true);
            return;
        }
        if (requestCode == REQ_SAVE_ROUND_DIGEST) {
            writeDigestToChosenDocument(data.getData());
        }
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
            Bitmap decoded;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                decoded = BitmapFactory.decodeStream(in, null, opts);
            }
            if (decoded == null) {
                toast("图片读取失败，换一张试试");
                return;
            }
            preview.cancelSelection(); regionPick = false; regionFirst = null;
            selectBoard.setText("框选棋盘范围");
            preview.setImageBitmap(decoded);
            preview.setGeometry(null);
            if (screenshot != null && screenshot != decoded) screenshot.recycle();
            screenshotGeometry = null; screenshot = decoded; screenshotUri = uri;
            selectBoard.setEnabled(true); saveBoard.setEnabled(true);
            if (!autoFitBoard(false)) {
                output.setText("尚未确认截图中的棋盘。可换一张稳定截图，或展开手动棋盘校准，框出整个棋盘。");
            }
            if (tellUser) toast("截图已载入，可采样或点击棋子试读");
        } catch (Exception e) {
            toast("图片读取异常: " + e.getMessage());
        }
    }

    /* ---------- 标定 ---------- */

    private void loadCalibration() {
        var prefs = GameProfile.settings(this);
        BoardGeometry manual = Match3Calibration.manual(prefs);
        if (manual != null) { applyGeometryFields(manual); return; }
        rowsSpin.setSelection(intToIndex(prefs.getInt("match3_rows", 8), 6, 9));
        colsSpin.setSelection(intToIndex(prefs.getInt("match3_cols", 8), 6, 9));
        leftIn.setText(String.valueOf(prefs.getInt("match3_l", 4)));
        topIn.setText(String.valueOf(prefs.getInt("match3_t", 18)));
        rightIn.setText(String.valueOf(prefs.getInt("match3_r", 96)));
        bottomIn.setText(String.valueOf(prefs.getInt("match3_b", 82)));
    }

    private boolean saveCalibration() {
        BoardGeometry geometry = checkedScreenshotGeometry();
        if (geometry == null) return false;
        Match3Calibration.saveManual(GameProfile.settings(this), geometry);
        drawPreview();
        calibrationStatus.setText("校准已保存。实时识别会先自动定位，仅在需要时核验此范围；下次棋盘变化仍会重新确认。");
        toast("校准已保存");
        return true;
    }

    private void resetCalibration() {
        Match3Calibration.clear(GameProfile.settings(this));
        preview.cancelSelection(); regionPick = false; regionFirst = null;
        selectBoard.setText("框选棋盘范围"); screenshotGeometry = null;
        loadCalibration(); preview.setGeometry(null);
        if (screenshot != null) autoFitBoard(false);
        calibrationStatus.setText("已清除保存的校准，恢复自动定位。当前截图可继续试读。");
        toast("已恢复自动定位");
    }

    private void beginBoardSelection() {
        if (screenshot == null) { toast("先选择一张游戏截图"); return; }
        if (preview.isSelecting()) {
            preview.cancelSelection(); selectBoard.setText("框选棋盘范围");
            calibrationStatus.setText("框选已取消，保存的校准未改变。"); return;
        }
        regionPick = false; regionFirst = null;
        preview.beginSelection(); selectBoard.setText("取消棋盘框选"); hideKeyboard();
        calibrationStatus.setText("在下方截图中拖出整个棋盘，或依次点两个对角；然后确认行列与网格，再保存。");
        preview.post(() -> preview.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
                preview.getWidth(), Math.min(preview.getHeight(), UiKit.dp(this, 320))), false));
        preview.announceForAccessibility(calibrationStatus.getText());
    }

    private boolean acceptBoardSelection(int left, int top, int right, int bottom) {
        BoardGeometry geometry;
        try { geometry = new BoardGeometry(screenshot.getWidth(), screenshot.getHeight(), left, top, right, bottom,
                6 + rowsSpin.getSelectedItemPosition(), 6 + colsSpin.getSelectedItemPosition()); }
        catch (IllegalArgumentException invalid) { toast("范围无效，请重新框出整个棋盘"); return false; }
        if (!Match3Calibration.plausible(geometry)) { toast("范围太小，请框出整个棋盘后再试"); return false; }
        applyGeometryFields(geometry); screenshotGeometry = geometry; drawPreview();
        selectBoard.setText("框选棋盘范围");
        calibrationStatus.setText("范围已框选，尚未保存。请确认行列数和绿色网格对齐，再点击「确认网格并保存校准」。");
        output.setText(calibrationStatus.getText());
        preview.announceForAccessibility(calibrationStatus.getText());
        return true;
    }

    private void applyGeometryFields(BoardGeometry geometry) {
        boolean wasUpdating = updatingCalibrationFields; updatingCalibrationFields = true;
        int[] pct = geometry.percentages();
        rowsSpin.setSelection(geometry.rows - 6); colsSpin.setSelection(geometry.cols - 6);
        leftIn.setText(String.valueOf(pct[0])); topIn.setText(String.valueOf(pct[1]));
        rightIn.setText(String.valueOf(pct[2])); bottomIn.setText(String.valueOf(pct[3]));
        updatingCalibrationFields = wasUpdating;
    }

    private void watchCalibrationFields() {
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { calibrationFieldsChanged(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        };
        for (EditText input : new EditText[]{leftIn, topIn, rightIn, bottomIn}) input.addTextChangedListener(watcher);
        android.widget.AdapterView.OnItemSelectedListener listener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!updatingCalibrationFields && screenshot != null && screenshotGeometry != null) {
                    if (screenshotGeometry != null && screenshotGeometry.rows == 6 + rowsSpin.getSelectedItemPosition()
                            && screenshotGeometry.cols == 6 + colsSpin.getSelectedItemPosition()) return;
                    calibrationFieldsChanged();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        };
        rowsSpin.setOnItemSelectedListener(listener); colsSpin.setOnItemSelectedListener(listener);
    }

    private void calibrationFieldsChanged() {
        if (updatingCalibrationFields || screenshot == null) return;
        drawPreview();
        calibrationStatus.setText("当前截图的范围尚未保存。确认网格后再保存，实时设置才会改变。");
    }

    private BoardGeometry checkedScreenshotGeometry() {
        if (screenshot == null) { toast("先选择一张游戏截图"); return null; }
        if (preview.isSelecting()) { toast("先完成棋盘框选，或取消框选后再操作"); return null; }
        if (screenshotGeometry == null) { toast("先框选整个棋盘或调整校准，确认绿色网格后再操作"); return null; }
        try {
            BoardGeometry geometry = screenshotGeometry();
            if (Match3Calibration.plausible(geometry)) return geometry;
            toast("范围太小，请框出整个棋盘并确认行列数");
        } catch (IllegalArgumentException invalid) { toast("请填写 0 到 100 的整数百分比，并保证左上角在右下角之前"); }
        return null;
    }

    private static int intToIndex(int value, int min, int max) {
        return Math.max(0, Math.min(max - min, value - min));
    }

    private static int parsePercent(EditText input) {
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value < 0 || value > 100) throw new IllegalArgumentException("Invalid percentage");
            return value;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid percentage", invalid); }
    }

    private void drawPreview() {
        if (screenshot == null) return;
        try { preview.setGeometry(screenshotGeometry()); }
        catch (IllegalArgumentException invalid) { preview.setGeometry(null); }
    }

    /* ---------- 截图式识别 ---------- */

    private void sampleAndAnnounce() {
        if (screenshot == null) {
            toast("先选择一张游戏截图");
            return;
        }
        if (checkedScreenshotGeometry() == null) return;
        drawPreview();
        char[][] board; List<String> scan; Match3Position position; Match3Goals goals;
        try (Match3Sampler sampler = sampler()) {
            position = sampler.samplePosition(screenshot);board=position.matrix();
            scan = Match3Board.scanSpeech(board, sampler::pieceName);
        }
        Match3HudReader reader=new Match3HudReader(this);
        try { goals=reader.read(screenshot,screenshotGeometry(),android.os.SystemClock.elapsedRealtime()); }
        finally { reader.close(); }
        StringBuilder sb = new StringBuilder("识别矩阵（. 表示未识别，空白表示空格）：\n");
        for (char[] row : board) sb.append(String.valueOf(row)).append('\n');
        List<Match3MoveValue> moves=Match3MoveRanker.rankedMoves(position,goals);
        sb.append("\n");
        if(goals.hudVerified) {
            if(goals.level>=0)sb.append("第 ").append(goals.level).append(" 关，");
            sb.append(goals.steps>=0?"剩余 "+goals.steps+" 步":"剩余步数未看清").append("\n");
            for(Match3Goals.Target target:goals.targets)sb.append(target.kind.name).append("：")
                    .append(target.completed?"已完成":target.remaining>=0?"剩余 "+target.remaining:"数量未看清").append("\n");
        } else sb.append("这张截图没有确认目标，先提供基础交换。\n");
        sb.append("\n可用动作：").append(moves.size()).append(" 处\n");
        int spoken = 0;
        for (Match3MoveValue value : moves) {
            Match3Board.Swap s=value.swap;
            if (spoken++ >= 5) break;
            String speech=Match3Coach.swapSpeechWithDirection(s,position.rows,position.cols,position)
                    +(value.reason.isEmpty()?"":"，"+value.reason)+"。";
            sb.append("  ").append(speech).append('\n');
            // A single complete suggestion replaces the old burst of five QUEUE_FLUSH requests.
            // 结果语义接进这同一条请求的同一个字符串、跟在解说之后，不再 announce 第二次：同类别、
            // 非 100 优先级的语音有 speech_cooldown（下方 isEmpty 支的注释写明了这一点），紧跟着的
            // 第二条会被整条吞掉。OutcomePhrase 无读数时出空串，整条文案与改前逐字相同。
            if(spoken==1)announce(speech + OutcomePhrase.forSwap(s));
        }
        if (moves.isEmpty()) {
            // 任务书六：整盘解说只挂「没找到可推荐」这一支，追加在既有那句之后；
            // 既有三句文案逐字不动，正常推荐路径（上面 spoken==1 那一支）不受影响。
            // 解说合进同一条播报请求而不是再 announce 一次：CueDispatcher 对同类别、
            // 非 100 优先级的语音有 speech_cooldown（Match3LiveCuePolicy 强制 ≥1500ms），
            // 紧接着的第二条会被整条吞掉，等于没播。通道仍是 announce() 里既有的
            // Match3LiveCuePolicy.REQUESTED_CHANNELS（只有 CHANNEL_SPEECH），未新增触觉或视觉。
            announce((goals.finished()?"截图中的目标已经完成。":goals.hudVerified && goals.steps==0
                    ?"截图中的剩余步数已经用完。":"没有找到可直接消除的交换。")
                    + Match3Narrator.narrate(board, position.rows, position.cols));
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
        int[] pct = {parsePercent(leftIn), parsePercent(topIn), parsePercent(rightIn), parsePercent(bottomIn)};
        if (screenshotGeometry != null) {
            int[] previous = screenshotGeometry.percentages();
            // Editing one edge or the grid count must not round the other selected pixel edges.
            screenshotGeometry = new BoardGeometry(screenshot.getWidth(), screenshot.getHeight(),
                    pct[0] == previous[0] ? screenshotGeometry.left : screenshot.getWidth() * pct[0] / 100,
                    pct[1] == previous[1] ? screenshotGeometry.top : screenshot.getHeight() * pct[1] / 100,
                    pct[2] == previous[2] ? screenshotGeometry.right : screenshot.getWidth() * pct[2] / 100,
                    pct[3] == previous[3] ? screenshotGeometry.bottom : screenshot.getHeight() * pct[3] / 100, rows, cols);
            return screenshotGeometry;
        }
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
        if (checkedScreenshotGeometry() == null) return;
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

    /** Automatic screenshot positioning updates a draft; only the explicit save button persists it. */
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
        preview.cancelSelection(); selectBoard.setText("框选棋盘范围");
        regionPick = false; regionFirst = null;
        applyGeometryFields(geometry);
        screenshotGeometry = geometry;
        drawPreview();
        calibrationStatus.setText("当前截图已自动定位，尚未保存。可直接试读；只有确认保存才会用于实时识别。");
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
                        // 白名单闸门第一次真的被查询：名单外的 next_action 不许念成一句指令。
                        announce(gatedAnnouncement(e.getKey(), d, a));
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
            case DIRECT_ANNOUNCE: return "高置信·直接播，不用确认";
            case CONFIRM: return "中置信·先确认";
            default: return "低置信·绝不静默";
        }
    }

    /* ---------- 预览点读 / 框选 / 道具栏 ---------- */

    private int kbIndex;
    private float gateHigh = 0.85f;
    private float gateMid = 0.60f;
    /** 本次生效的辅助强度档位名，只供读数面展示；默认值与上面两条默认阈值同档（标准）。 */
    private String gatePresetLabel = "标准";

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
        // 档位接线（任务书八）：把设置面板已有的四档翻成生效的两条置信线，播报疏密才真的跟着档位走。
        // 读法沿用现成的 cue_preset，标准档与自定义档等值回传 ⇒ 没调过档位的用户零变化；不新增偏好键。
        gatePresetLabel = CueSettings.presetLabel(GameProfile.settings(this));
        float[] intensityLines = Match3Intensity.apply(GameProfile.settings(this)
                .getString("cue_preset", CueSettings.PRESET_STANDARD), gateHigh, gateMid);
        gateHigh = intensityLines[0];
        gateMid = intensityLines[1];
    }
    private boolean regionPick;
    private int[] regionFirst;

    /** 预览点击：普通模式报单格；框选模式两次点击定区域后播报内容摘要。 */
    private void handlePreviewTap(int px, int py) {
        if (checkedScreenshotGeometry() == null) return;
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

    /* ---------- 「逐条读这一屏」：把弃权问句兑现成一条真能听的路 ---------- */

    /** 只读展示行（不进播报通道）：空列表时屏幕上总得有一句交代，读数本身一个字也不补。 */
    private static final String EMPTY_READ_NOTE = "这一屏没有可逐条读的内容。";

    /**
     * 状态 → 待播句子列表的纯函数：按钮回调与单测走的是同一条链，所以「回调真调到链」这件事可被 JVM 证伪。
     *
     * <p>四条口径：① 句子一律原样取自 {@link LocalReadAloud#sentences(String, int)}（两参版传现值
     * {@link LocalReadAloud#DEFAULT_LIMIT}，三参版由档位映射传入），本方法一个字也不拼、
     * 一个字符也不加工；② 状态不新鲜时一条明细也不播，只给既有的「未就绪」固定话术
     * （{@link DecisionWhy#explain} 的 NOT_READY 分支，与 {@code judgeScreen()} 第二条提前返回同源）；
     * ③ {@link LocalReadAloud} 返回空列表且本地敏感闸门命中时，只给既有拦截句
     * {@link Match3LineReader#GATE_BLOCK}，其余空列表情形返回空列表——一条也不播，绝不编造读数；
     * ④ 全程本地：不新截图、不上云、不注入任何输入事件。
     */
    static List<String> readAloudLines(String state, boolean freshState) {
        return readAloudLines(state, freshState, LocalReadAloud.DEFAULT_LIMIT);
    }

    /**
     * 与两参版同一条链，只是把「一次念几条」的上限从外面传进来（疏密档位的落点）。
     * {@code limit <= 0} 由 {@link LocalReadAloud#sentences(String, int)} 兜底回默认上限，语义一字不变。
     */
    static List<String> readAloudLines(String state, boolean freshState, int limit) {
        if (!freshState) {
            return java.util.Collections.singletonList(
                    DecisionWhy.explain(DecisionWhy.Kind.NOT_READY, 0.0d, 0.0d, 0.0d, null));
        }
        List<String> lines = LocalReadAloud.sentences(state, limit);
        if (!lines.isEmpty()) {
            return lines;
        }
        if (Match3Gate.sensitiveHit(state) != null) {
            return java.util.Collections.singletonList(Match3LineReader.GATE_BLOCK);
        }
        return java.util.Collections.emptyList();
    }

    /** 按钮回调：读已缓存的压缩状态，把 {@link #readAloudLines} 的句子逐句交给既有 announce 通道。 */
    private void readAloudThisScreen() {
        final List<String> lines = readAloudLines(SenseFieldReaderService.latestState(),
                SenseFieldReaderService.hasFreshState(), aloudLimitForPreset());
        for (String line : lines) {
            announce(line);
        }
        output.setText(lines.isEmpty() ? EMPTY_READ_NOTE : String.join("\n", lines));
    }

    /**
     * 玩家已经选过的疏密档位 → 本次逐条读的条数上限。只读既有的 {@code cue_preset} 键，
     * 读法与默认值和 {@link #loadGating()} 的档位接线同源（不新增偏好键、不新增控件、不改面板）。
     */
    private int aloudLimitForPreset() {
        String preset = GameProfile.settings(this)
                .getString("cue_preset", CueSettings.PRESET_STANDARD);
        return ReadAloudDensity.limitFor(preset);
    }

    /* ---------- 战报的两个玩家出口：听（既有 announce 通道）与存（既有创建文档路） ---------- */

    /**
     * 战报出口的决议：一份战报文本能不能念、念哪句、该落成什么文件名。两个出口共用这一个纯函数，
     * 界面侧只做动作，所以「念的是不是原文」「文件名里藏没藏身份」这两件事在 JVM 单测里可证伪。
     *
     * <p>四条口径，逐条对着任务书 2 的报警器：① {@code speech} 就是入参 {@code digest} 本身，
     * 逐字取自 {@link Match3RoundDigest#digest}（经 {@link Match3LiveService#roundDigestSnapshot()}），
     * 本方法一个字符也不加、不拼、不改标点；② 空盘那句「盘面上没看到连线」是生成器自带的，
     * 这里不另造话术、也不替玩家补任何数字；③ 文件名只由「消毒过的会话 id ＋调用方给的时刻」拼成，
     * 形状与第 15 份书验收过的那条保存路一致，会话 id 先过 {@link #safeSessionIdForFile} 消毒
     * （掺进冒号／目录分隔符／点／空格／非 ASCII 就整串换占位符），于是盘符、目录形状、机型串、
     * 聊天软件名这些进不到文件名里；id 本身为空也用占位符；
     * ④ 战报为空（null 或全空白）时 {@code speak} 为假、{@code exportFileName} 为 {@code null}，
     * 既不念也不创建文档，更不抛异常。</p>
     *
     * <p>纯函数：不碰界面、不起线程、不读写文件、不发意图。时刻由调用方传入，本方法不读时钟。</p>
     */
    static DigestOutlet digestOutlet(String digest, String sessionId, long exportedAtMillis) {
        if (digest == null || digest.trim().isEmpty()) {
            return new DigestOutlet(false, "", null);
        }
        return new DigestOutlet(true, digest,
                "sensefield-" + safeSessionIdForFile(sessionId) + "-" + exportedAtMillis + ".txt");
    }

    /**
     * 会话 id 消毒：合法形状只有「字母／数字／{@code -}／{@code _}，最长 36（UUID 的原长）」。
     * 一旦掺进别的字符——冒号、目录分隔符、点、空格、非 ASCII，盘符／用户目录／机型串／聊天软件名
     * 这些形状全靠它们落地——<b>整串作废</b>换成占位符：宁可文件名只留时刻，也不留下可疑形状的一半。
     */
    static String safeSessionIdForFile(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return NO_SESSION_PLACEHOLDER;
        if (sessionId.length() > MAX_SESSION_ID_LENGTH) return NO_SESSION_PLACEHOLDER;
        for (int i = 0; i < sessionId.length(); i++) {
            char c = sessionId.charAt(i);
            boolean legal = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!legal) return NO_SESSION_PLACEHOLDER;
        }
        return sessionId;
    }

    private static final String NO_SESSION_PLACEHOLDER = "nosession";
    private static final int MAX_SESSION_ID_LENGTH = 36;

    /** 决议的形状：要不要念、念哪句、落成哪个文件名（{@code null} ＝ 不创建文档）。 */
    static final class DigestOutlet {
        final boolean speak;
        final String speech;
        final String exportFileName;

        private DigestOutlet(boolean speak, String speech, String exportFileName) {
            this.speak = speak;
            this.speech = speech;
            this.exportFileName = exportFileName;
        }
    }

    /** 只读展示行（不进播报通道）：一局都没落定过时屏幕上得有一句交代，但绝不替玩家编一份战报。 */
    private static final String NO_DIGEST_NOTE = "这一盘还没有战报：开实时辅助打到回合落定，战报会自动生成。";

    /** 「念这一盘战报」的按钮回调：只读取数出口 → {@link #digestOutlet} → 既有 announce 通道。 */
    private void announceRoundDigest() {
        DigestOutlet outlet = digestOutlet(Match3LiveService.roundDigestSnapshot(),
                diagnosticsSessionId(), System.currentTimeMillis());
        output.setText(outlet.speak ? outlet.speech : NO_DIGEST_NOTE);
        if (outlet.speak) {
            announce(outlet.speech);
        }
    }

    /**
     * 「保存这一盘战报」的按钮回调：复用第 15 份书已经验收过的那条路——先把逐字文本落到与诊断包
     * 同一个 {@code diagnostic-exports} 缓存目录（同一套 {@code System.currentTimeMillis()} 命名），
     * 再交给既有的 {@code ACTION_CREATE_DOCUMENT} 由玩家亲手选位置。跑在诊断包那条路同一个
     * {@code DiagnosticRecorder.IO} 上（不起新线程、不新增清单条目、不发网络、不自动分享）。
     */
    private void saveRoundDigest() {
        DigestOutlet outlet = digestOutlet(Match3LiveService.roundDigestSnapshot(),
                diagnosticsSessionId(), System.currentTimeMillis());
        if (outlet.exportFileName == null) {
            output.setText(NO_DIGEST_NOTE);
            return;
        }
        final String text = outlet.speech;
        final String name = outlet.exportFileName;
        DiagnosticRecorder.IO.execute(() -> {
            try {
                File staged = new File(new File(getCacheDir(), "diagnostic-exports"), name);
                Files.createDirectories(staged.getParentFile().toPath());
                Files.write(staged.toPath(), text.getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    preparedDigest = staged;
                    try {
                        Intent target = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain");
                        target.addCategory(Intent.CATEGORY_OPENABLE);
                        target.putExtra(Intent.EXTRA_TITLE, staged.getName());
                        startActivityForResult(target, REQ_SAVE_ROUND_DIGEST);
                        output.setText(text + "\n（请在下一步选择保存位置）");
                    } catch (RuntimeException error) {
                        toast("无法打开保存位置，可再按一次保存");
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> output.setText("战报落盘失败：" + error));
            }
        });
    }

    /** 玩家亲手选好位置之后才把缓存里那份逐字文本搬过去：这一步由创建文档的返回结果触发，不是自动分享。 */
    private void writeDigestToChosenDocument(Uri destination) {
        final File source = preparedDigest;
        if (source == null || !source.isFile()) {
            output.setText(NO_DIGEST_NOTE);
            return;
        }
        DiagnosticRecorder.IO.execute(() -> {
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) {
                    runOnUiThread(() -> output.setText("无法写入所选文件，可再按一次保存"));
                    return;
                }
                byte[] buffer = new byte[32768];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                runOnUiThread(() -> toast("战报已保存"));
            } catch (Exception error) {
                runOnUiThread(() -> output.setText("战报保存失败：" + error));
            }
        });
    }

    /** 会话 id 只读取自既有诊断记录器的公开字段（只读复用，它一个字也没改）；还没有会话时返回 null。 */
    private static String diagnosticsSessionId() {
        DiagnosticRecorder recorder = DiagnosticRecorder.current;
        return recorder == null ? null : recorder.sessionId;
    }

    /**
     * 白名单闸门：把「判定答案 → 允许出口的话术」抽成纯函数，供 {@code judgeSample()} 的播报出口与单测共用。
     *
     * <p>不在白名单里的 next_action 答案<b>不许念成一句指令</b>，只回落既有兜底话术
     * {@link JevThresholds#UNCERTAIN_SPEECH}（不新造句子）。白名单问的是「哪些答案可以出口」，
     * 不是「可以替玩家点哪一格」——本方法只返回一句话，不产生任何操作，也不碰 DIRECT_ANNOUNCE 的语义。
     * {@code JevThresholds.inWhitelist}／{@code speechFor} 与名单内容一字未改，这里只是第一次有人查它。
     */
    static String gatedAnnouncement(String key, JevThresholds.Decision decision, JevAnswer answer) {
        if ("next_action".equals(key)
                && !JevThresholds.inWhitelist(answer, JevThresholds.defaultWhitelist())) {
            return JevThresholds.UNCERTAIN_SPEECH;
        }
        return key + "：" + JevThresholds.speechFor(decision, answer);
    }

    /* ---------- 读屏判定（无障碍树→Jev，P0 基线：屏幕类型 Choice＋模态 Noul 扇出） ---------- */

    private void judgeScreen() {
        final JevClient client = JevSettings.clientOrNull(this);
        if (client == null) {
            output.setText("【未运行】判定层未启用或配置不完整（判定自测页上方保存设置）。\n"
                    + DecisionWhy.explain(DecisionWhy.Kind.NOT_RUNNING, 0.0d, gateHigh, gateMid, null));
            return;
        }
        if (!SenseFieldReaderService.hasFreshState()) {
            output.setText("【未就绪】读屏状态服务没有可用状态：请点「打开系统无障碍设置」，"
                    + "找到「听野读屏状态服务」并开启，然后切回本页再试。\n开启后切几次屏幕，服务会自动缓存最新压缩状态。\n"
                    + DecisionWhy.explain(DecisionWhy.Kind.NOT_READY, 0.0d, gateHigh, gateMid, null));
            return;
        }
        final String state = SenseFieldReaderService.latestState();
        final String sensitive = Match3Gate.sensitiveHit(state);
        if (sensitive != null) {
            String block = "本地敏感闸门命中：当前为「" + sensitive + "」相关页面，内容不上云、不播报细节，建议手动处理。";
            // 只读展示：说明行是固定话术，命中的原文片段（state）一个字也不带出；播报实参仍是 block。
            output.setText(block + "\n"
                    + DecisionWhy.explain(DecisionWhy.Kind.SENSITIVE, 0.0d, gateHigh, gateMid, sensitive));
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
                boolean abstained = (st == null);
                if (st != null) {
                    double c = st.surety();
                    String speech;
                    DecisionWhy.Kind why;
                    if ("ABSTAIN".equals(st.choice)) {
                        speech = AnnounceWhy.speech(true, c, gateHigh, gateMid);
                        why = DecisionWhy.Kind.ABSTAIN;
                    } else if (c >= gateHigh) {
                        speech = "当前是「" + st.choice + "」。";
                        why = DecisionWhy.Kind.HIGH;
                    } else if (c >= gateMid) {
                        speech = "可能是「" + st.choice + "」，这个我不太确定。";
                        why = DecisionWhy.Kind.MID;
                    } else {
                        speech = AnnounceWhy.speech(false, c, gateHigh, gateMid);
                        why = DecisionWhy.Kind.LOW;
                    }
                    abstained = why == DecisionWhy.Kind.ABSTAIN || why == DecisionWhy.Kind.LOW;
                    sb.append("[screen_type] ").append(st.choice)
                      .append(" (conf=").append(String.format(java.util.Locale.US, "%.2f", c))
                      .append(" · 档位 ").append(gatePresetLabel)
                      .append(" 生效线 ").append(String.format(java.util.Locale.US, "%.2f/%.2f",
                              gateHigh, gateMid)).append(")\n")
                      .append("→ ").append(speech).append("\n")
                      .append("· ").append(DecisionWhy.explain(why, c, gateHigh, gateMid, st.choice)).append("\n\n");
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
                // 兑现「从上往下逐条读」这句问句：只有本轮真的弃权才念。
                // 只读本地已缓存的压缩状态（不新截图、不上云），生成器内部先过 Match3Gate.sensitiveHit 闸门：
                // 命中敏感时不逐条念内容，改为念一句固定的闸门话术（Match3LineReader.GATE_BLOCK），
                // 所以这里永远不会整段静默——玩家至少听得到「这一屏我不逐条读」。句子不含 @[ 坐标，
                // 走的仍是 announce 的既有 CHANNEL_SPEECH 通道。
                if (abstained) {
                    for (String line : Match3LineReader.readGuarded(state)) {
                        announce(line);
                    }
                }
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
                String text = nodeLine + "\n→ " + speech + " (conf=" + a.confidence + ")\n"
                        + "· " + DecisionWhy.explain(DecisionWhy.Kind.ICON_DISAMBIG, a.confidence,
                                gateHigh, gateMid, nodeLine) + "\n";
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
        preview.setImageDrawable(null);
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
        if (screenshot != null && screenshotGeometry != null) try {
            BoardGeometry g = screenshotGeometry();
            state.putIntArray("screenshot_geometry", new int[]{g.frameWidth, g.frameHeight, g.left, g.top, g.right, g.bottom, g.rows, g.cols});
        } catch (IllegalArgumentException ignored) { }
        for (LinearLayout section : optionalSections) state.putBoolean(section.getTag().toString(),
                section.findViewWithTag("ui_details_body").getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(state);
    }

    @Override protected void onPostResume() {
        updatingCalibrationFields = true;
        super.onPostResume();
        updatingCalibrationFields = false;
        if (restoredGeometry != null && screenshot != null) {
            BoardGeometry candidate = restoredGeometry.scaledTo(screenshot.getWidth(), screenshot.getHeight());
            if (candidate != null && candidate.rows == 6 + rowsSpin.getSelectedItemPosition()
                    && candidate.cols == 6 + colsSpin.getSelectedItemPosition()) {
                int[] pct;
                try { pct = new int[]{parsePercent(leftIn), parsePercent(topIn), parsePercent(rightIn), parsePercent(bottomIn)}; }
                catch (IllegalArgumentException invalid) { pct = null; }
                if (java.util.Arrays.equals(candidate.percentages(), pct)) {
                    screenshotGeometry = candidate;
                    calibrationStatus.setText("当前截图的网格已恢复。可继续试读；调整后需确认保存，实时设置才会改变。");
                }
            }
            restoredGeometry = null;
        }
        if (screenshot != null && screenshotGeometry != null) drawPreview();
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
