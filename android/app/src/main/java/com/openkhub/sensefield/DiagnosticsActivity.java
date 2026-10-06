package com.openkhub.sensefield;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Bottom settings entry with large controls; no computer or second recorder. */
public final class DiagnosticsActivity extends UiActivity {
    private static final int SAVE_REPORT = 6101;
    private TextView status;
    private RadioGroup sessions;
    private EditText feedback;
    private Button share, save, clear;
    private String selected;
    private File prepared;
    private boolean busy;
    private String recoveryError = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshState();
            status.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.configureWindow(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(UiKit.PAGE);
        LinearLayout page = UiKit.page(this);
        scroll.addView(page);
        UiKit.pageHeader(this, page, "测试记录与反馈", "王者荣耀辅助");
        UiKit.add(page, UiKit.body(this, "每局自动记录，无需另外录屏。"), 20);

        // How to report a problem, as numbered steps rather than a paragraph.
        LinearLayout how = UiKit.card(this);
        UiKit.add(how, UiKit.heading(this, "怎么反馈问题"), 12);
        UiKit.add(how, UiKit.steps(this,
                "正常开始一局辅助",
                "遇到漏报或误报，下拉通知栏，在听野通知里点“标记问题”",
                "结束后停止辅助",
                "回到本页，选一局导出给队友"), 0);
        UiKit.add(page, how, UiKit.GAP_SECTION);

        LinearLayout recording = UiKit.card(this);
        CheckBox images = new CheckBox(this);
        images.setText("保存游戏画面");
        images.setTextSize(18);
        images.setTextColor(UiKit.INK);
        UiKit.styleCheckable(images, this);
        images.setChecked(DiagnosticRecorder.imagesEnabledPreference(this));
        images.setOnCheckedChangeListener((button, enabled) -> {
            GameProfile.settings(this).edit().putBoolean(DiagnosticRecorder.PREF_IMAGES, enabled).apply();
            DiagnosticRecorder recorder = DiagnosticRecorder.current;
            if (recorder != null && !recorder.finished) recorder.setImagesEnabled(enabled);
        });
        UiKit.add(recording, UiKit.withHint(images, "帮助查漏报／误报；只存在手机里，不自动上传"), 12);
        // The complete recording and privacy terms stay available, folded until requested.
        UiKit.add(recording, UiKit.details(this, "记录范围与隐私",
                "首次使用默认保存画面。每约 10 秒保存背景截图；提醒时另存当时画面，并有上限地保存前后短时采样。",
                "漏报等问题请及时下拉通知栏，展开听野通知后点“标记问题”（部分手机要点右侧箭头）。",
                "最多 20 分钟或 60 MB，仅留最近 3 局。采样可能不完整，不等于录像。",
                "画面可能包含昵称、聊天等信息。只保存在手机内，不自动上传；分享前请确认愿意提供这些内容。"), 0);
        UiKit.add(page, recording, UiKit.GAP_SECTION);

        LinearLayout export = UiKit.card(this);
        UiKit.add(export, UiKit.heading(this, "导出记录"), 8);
        status = UiKit.text(this, "正在读取记录…", 20, UiKit.INK, true);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(export, status, 8);
        sessions = new RadioGroup(this);
        UiKit.add(export, sessions, 16);
        feedback = new EditText(this);
        UiKit.styleInput(feedback);
        feedback.setTextSize(18);
        feedback.setMinLines(3);
        feedback.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        feedback.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        feedback.setHint("例如：第二局 2:30 附近没人却提醒");
        LinearLayout feedbackField = UiKit.field(this, "问题说明（可选）", feedback);
        ((TextView) feedbackField.getChildAt(0)).setTextSize(UiKit.TEXT_BODY);
        ((TextView) feedbackField.getChildAt(0)).setAccessibilityHeading(true);
        UiKit.add(export, feedbackField, 20);
        share = button(export, "导出并分享诊断包", UiKit.ButtonStyle.FILLED);
        share.setOnClickListener(v -> prepare(true));
        save = button(export, "保存诊断包到文件", UiKit.ButtonStyle.OUTLINED);
        ((LinearLayout.LayoutParams) save.getLayoutParams()).bottomMargin = 0;
        save.setOnClickListener(v -> prepare(false));
        UiKit.add(page, export, 24);
        // Deleting is irreversible: keep it apart from export and mark it as destructive.
        clear = button(page, "删除本地测试记录", UiKit.ButtonStyle.DANGER);
        clear.setOnClickListener(v -> UiKit.styleDialog(new AlertDialog.Builder(this)
                .setTitle("删除本地测试记录？")
                .setMessage("手机里的测试记录和导出包将被删除，已分享的文件不会受影响。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, which) -> clearRecords()).show(),
                UiKit.ButtonStyle.DANGER));
        setContentView(scroll);
        if (state != null) {
            String pendingPath = state.getString("prepared");
            if (pendingPath != null) prepared = new File(pendingPath);
            selected = state.getString("diagnostic_selected");
        }
        loadSessions();
    }

    private Button button(LinearLayout page, String text, UiKit.ButtonStyle style) {
        Button b = UiKit.button(this, text, style);
        UiKit.add(page, b, UiKit.GAP_CONTROL);
        return b;
    }

    private boolean recording() {
        DiagnosticRecorder recorder = DiagnosticRecorder.current;
        return CaptureService.isRunning()
                || (recorder != null && !recorder.finished);
    }

    private void refreshState() {
        boolean active = recording();
        share.setEnabled(!busy && !active && selected != null);
        save.setEnabled(!busy && !active && selected != null);
        clear.setEnabled(!busy && !active && selected != null);
        if (busy) return;
        DiagnosticRecorder recorder = DiagnosticRecorder.current;
        if (recorder != null && !recorder.failure.isEmpty()) UiKit.setTextIfChanged(status, recorder.failure);
        else if (!active && !recoveryError.isEmpty()) UiKit.setTextIfChanged(status, recoveryError);
        else UiKit.setTextIfChanged(status, active ? "正在记录，请结束后停止辅助再导出"
                : selected == null ? "还没有记录，请先开始一局辅助" : "选择一局，导出诊断包");
    }

    private void loadSessions() {
        DiagnosticRecorder.IO.execute(() -> {
            String recoveryFailure = "";
            String activeDirectory = DiagnosticRecorder.activeDirectory();
            try { DiagnosticRecovery.recover(DiagnosticRecorder.root(this), activeDirectory,
                    System.currentTimeMillis()); }
            catch (IOException error) { recoveryFailure = "部分记录未能恢复，请检查手机存储空间。"; }
            File[] found = DiagnosticArchive.sessions(DiagnosticRecorder.root(this));
            String[] suffixes = new String[found.length];
            for (int i = 0; i < found.length; i++) {
                switch (DiagnosticRecovery.status(found[i], activeDirectory)) {
                    case ACTIVE: suffixes[i] = " · 正在记录"; break;
                    case FINISHED: suffixes[i] = " · 已结束"; break;
                    case INTERRUPTED: suffixes[i] = " · 记录中断（已恢复）"; break;
                    default: suffixes[i] = " · 记录未完整保存";
                }
            }
            final String loadError = recoveryFailure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                sessions.removeAllViews();
                String preferred = selected;
                selected = null;
                recoveryError = loadError;
                for (int i = 0; i < found.length; i++) {
                    File f = found[i];
                    RadioButton b = new RadioButton(this);
                    b.setId(android.view.View.generateViewId());
                    b.setTextSize(18);
                    b.setTextColor(UiKit.INK);
                    UiKit.styleCheckable(b, this);
                    long timestamp;
                    try { timestamp = Long.parseLong(f.getName().split("-")[1]); }
                    catch (RuntimeException ignored) { timestamp = f.lastModified(); }
                    String date = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(timestamp));
                    b.setText(getString(R.string.diagnostic_session_label, date, suffixes[i]));
                    b.setTag(f.getName());
                    sessions.addView(b);
                    if (f.getName().equals(preferred)) { selected = f.getName(); b.setChecked(true); }
                }
                if (selected == null && sessions.getChildCount() > 0) {
                    RadioButton first = (RadioButton) sessions.getChildAt(0);
                    selected = String.valueOf(first.getTag());
                    first.setChecked(true);
                }
                sessions.setOnCheckedChangeListener((g, id) -> {
                    RadioButton b = g.findViewById(id);
                    if (b != null) selected = String.valueOf(b.getTag());
                    refreshState();
                });
                refreshState();
            });
        });
    }

    private void prepare(boolean sharing) {
        if (recording() || selected == null || busy) return;
        busy = true;
        UiKit.setTextIfChanged(status, "正在整理诊断包…");
        refreshState();
        String id = selected;
        String note = feedback.getText().toString();
        DiagnosticRecorder.IO.execute(() -> {
            try {
                if (recording()) throw new IOException("请先停止辅助再导出");
                File directory = DiagnosticArchive.session(DiagnosticRecorder.root(this), id);
                File output = new File(new File(getCacheDir(), "diagnostic-exports"),
                        "sensefield-" + id + "-" + System.currentTimeMillis() + ".zip");
                DiagnosticArchive.export(directory, output, note);
                runOnUiThread(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    prepared = output;
                    refreshState();
                    try {
                        if (sharing) {
                            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".diagnostics", output);
                            Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip");
                            send.putExtra(Intent.EXTRA_STREAM, uri);
                            send.setClipData(ClipData.newRawUri("听野诊断包", uri));
                            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(send, "选择分享方式"));
                        } else {
                            Intent saveIntent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip");
                            saveIntent.addCategory(Intent.CATEGORY_OPENABLE);
                            saveIntent.putExtra(Intent.EXTRA_TITLE, output.getName());
                            startActivityForResult(saveIntent, SAVE_REPORT);
                        }
                    } catch (RuntimeException error) { toast("无法打开分享方式，可尝试保存到文件"); }
                });
            } catch (Exception error) { showError("导出失败：" + error.getMessage()); }
        });
    }

    private void clearRecords() {
        if (recording() || busy) return;
        busy = true;
        refreshState();
        DiagnosticRecorder.IO.execute(() -> {
            try {
                if (recording()) throw new IOException("请先停止辅助");
                for (File f : DiagnosticArchive.sessions(DiagnosticRecorder.root(this))) DiagnosticArchive.delete(f);
                DiagnosticArchive.delete(new File(getCacheDir(), "diagnostic-exports"));
                runOnUiThread(() -> { busy = false; loadSessions(); toast("本地测试记录已删除"); });
            } catch (Exception error) { showError("删除失败：" + error.getMessage()); }
        });
    }

    private void showError(String message) {
        runOnUiThread(() -> { busy = false; if (!isDestroyed()) { UiKit.setTextIfChanged(status, message); toast(message); } });
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_REPORT || result != RESULT_OK || data == null || prepared == null) return;
        Uri destination = data.getData();
        File source = prepared;
        busy = true;
        refreshState();
        DiagnosticRecorder.IO.execute(() -> {
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException("无法写入所选文件");
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                runOnUiThread(() -> { busy = false; refreshState(); toast("诊断包已保存"); });
            } catch (Exception error) { showError("保存失败：" + error.getMessage()); }
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (prepared != null) state.putString("prepared", prepared.getAbsolutePath());
        state.putString("diagnostic_selected", selected);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() { super.onResume(); status.post(refresh); }
    @Override protected void onPause() { status.removeCallbacks(refresh); super.onPause(); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
