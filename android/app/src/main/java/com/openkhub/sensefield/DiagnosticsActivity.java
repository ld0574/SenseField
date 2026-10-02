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
public final class DiagnosticsActivity extends Activity {
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
        UiKit.addBrandHeader(page, "测试记录与反馈");
        UiKit.add(page, UiKit.text(this, "测试记录与反馈", 32, UiKit.INK, true), 16);
        UiKit.add(page, UiKit.body(this, "每局自动保存诊断记录。结束后停止辅助，再导出给队友。无需另外录屏。"), 16);
        CheckBox images = new CheckBox(this);
        images.setText("保存游戏画面，帮助查漏报／误报");
        images.setTextSize(24);
        UiKit.styleCheckable(images, this);
        images.setChecked(DiagnosticRecorder.imagesEnabledPreference(this));
        images.setOnCheckedChangeListener((button, enabled) -> {
            GameProfile.settings(this).edit().putBoolean(DiagnosticRecorder.PREF_IMAGES, enabled).apply();
            DiagnosticRecorder recorder = DiagnosticRecorder.current;
            if (recorder != null && !recorder.finished) recorder.setImagesEnabled(enabled);
        });
        UiKit.add(page, images, 8);
        UiKit.add(page, UiKit.body(this, "首次使用默认保存画面。每约 10 秒保存背景截图；提醒时另存当时画面，并有上限地保存前后短时采样。漏报等问题请及时点通知里的“标记问题”。最多 20 分钟或 60 MB，仅留最近 3 局。采样可能不完整，不等于录像。"), 10);
        UiKit.add(page, UiKit.body(this, "画面可能包含昵称、聊天等信息。只保存在手机内，不自动上传；分享前请确认愿意提供这些内容。"), 16);
        status = UiKit.text(this, "正在读取记录…", 24, UiKit.INK, true);
        UiKit.add(page, status, 12);
        sessions = new RadioGroup(this);
        UiKit.add(page, sessions, 16);
        UiKit.add(page, UiKit.heading(this, "问题说明（可选）"), 8);
        feedback = new EditText(this);
        feedback.setTextSize(24);
        feedback.setMinLines(3);
        feedback.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        feedback.setHint("例如：第二局 2:30 附近没人却提醒");
        UiKit.add(page, feedback, 18);
        share = button(page, "导出并分享诊断包", true);
        share.setOnClickListener(v -> prepare(true));
        save = button(page, "保存诊断包到文件", false);
        save.setOnClickListener(v -> prepare(false));
        clear = button(page, "删除本地测试记录", false);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("删除本地测试记录？")
                .setMessage("手机里的测试记录和导出包将被删除，已分享的文件不会受影响。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, which) -> clearRecords()).show());
        UiKit.gap(page, 28);
        button(page, "返回设置", false).setOnClickListener(v -> finish());
        setContentView(scroll);
        if (state != null) {
            String pendingPath = state.getString("prepared");
            if (pendingPath != null) prepared = new File(pendingPath);
        }
        loadSessions();
    }

    private Button button(LinearLayout page, String text, boolean primary) {
        Button b = UiKit.button(this, text, primary);
        b.setTextSize(24);
        b.setMinHeight(UiKit.dp(this, 80));
        b.setMinimumHeight(UiKit.dp(this, 80));
        UiKit.add(page, b, 18);
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
        if (recorder != null && !recorder.failure.isEmpty()) status.setText(recorder.failure);
        else if (!active && !recoveryError.isEmpty()) status.setText(recoveryError);
        else status.setText(active ? "正在记录，请结束后停止辅助再导出"
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
                selected = null;
                recoveryError = loadError;
                for (int i = 0; i < found.length; i++) {
                    File f = found[i];
                    RadioButton b = new RadioButton(this);
                    b.setId(android.view.View.generateViewId());
                    b.setTextSize(24);
                    UiKit.styleCheckable(b, this);
                    long timestamp;
                    try { timestamp = Long.parseLong(f.getName().split("-")[1]); }
                    catch (RuntimeException ignored) { timestamp = f.lastModified(); }
                    String date = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(timestamp));
                    b.setText(getString(R.string.diagnostic_session_label, date, suffixes[i]));
                    b.setTag(f.getName());
                    sessions.addView(b);
                    if (selected == null) { selected = f.getName(); b.setChecked(true); }
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
        status.setText("正在整理诊断包…");
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
        runOnUiThread(() -> { busy = false; if (!isDestroyed()) { status.setText(message); toast(message); } });
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
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() { super.onResume(); status.post(refresh); }
    @Override protected void onPause() { status.removeCallbacks(refresh); super.onPause(); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
