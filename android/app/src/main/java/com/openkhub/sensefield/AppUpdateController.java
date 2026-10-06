package com.openkhub.sensefield;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Launcher-owned upgrade flow; no update prompts are shown over a game. */
final class AppUpdateController implements AutoCloseable {
    private static final String AUTO_CHECK = "app_update_auto_check";
    private final Context context;
    private final SharedPreferences preferences;
    private final AppUpdateClient client;
    private final ExecutorService verifier = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String versionName;
    private final long versionCode;
    private Activity activity;
    private TextView status;
    private Button checkButton, actionButton;
    private ProgressBar progress;
    private AlertDialog prompt;
    private AppUpdateRelease offered, downloadedRelease;
    private File downloaded;
    private boolean resumed, checking, downloading, closed, promptShown;
    private boolean automaticCheckAttempted;
    private boolean waitingInstallPermission;
    private long verificationGeneration;
    private int percent;
    private String message;

    AppUpdateController(Context context) {
        this.context = context.getApplicationContext();
        preferences = GameProfile.settings(this.context);
        client = new AppUpdateClient(this.context, BuildConfig.APP_UPDATE_METADATA_URL);
        String name = BuildConfig.VERSION_NAME;
        long code = BuildConfig.VERSION_CODE;
        try {
            PackageInfo info = this.context.getPackageManager().getPackageInfo(this.context.getPackageName(), 0);
            name = info.versionName;
            code = info.getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        versionName = name;
        versionCode = code;
        message = "当前版本 " + versionName + (client.isConfigured()
                ? "。可检查新版。" : "。更新服务尚未配置。");
    }

    void attach(Activity activity, LinearLayout page) {
        this.activity = activity;
        LinearLayout card = UiKit.card(activity);
        checkButton = UiKit.button(activity, "检查更新", false);
        UiKit.add(card, UiKit.headingActionRow(activity, UiKit.heading(activity, "软件更新"), checkButton), 12);
        status = UiKit.hint(activity, message);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        UiKit.add(card, status, 12);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        UiKit.add(card, progress, 12);
        actionButton = UiKit.button(activity, "下载并安装", true);
        UiKit.add(card, actionButton, 12);
        checkButton.setOnClickListener(v -> {
            if (downloading) cancelDownload(); else check(true);
        });
        actionButton.setOnClickListener(v -> beginDownloadOrInstall());
        CheckBox auto = new CheckBox(activity);
        auto.setText("启动时检查更新");
        auto.setTextSize(UiKit.TEXT_BODY);
        UiKit.styleCheckable(auto, activity);
        auto.setChecked(preferences.getBoolean(AUTO_CHECK, true));
        auto.setEnabled(client.isConfigured());
        auto.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(AUTO_CHECK, checked).apply();
            if (checked) {
                automaticCheckAttempted = false;
                check(false);
            }
        });
        LinearLayout details = UiKit.details(activity, "更新设置");
        ((LinearLayout) details.getChildAt(1)).addView(auto);
        UiKit.add(card, details, 0);
        UiKit.add(page, card, UiKit.GAP_SECTION);
        render();
    }

    void resume() {
        if (closed) return;
        resumed = true;
        if (waitingInstallPermission && activity != null
                && activity.getPackageManager().canRequestPackageInstalls()) {
            waitingInstallPermission = false;
            install();
            return;
        }
        if (offered != null) maybePrompt();
        else check(false);
    }

    void pause() {
        resumed = false;
        if (prompt != null) { prompt.dismiss(); prompt = null; }
    }

    void detach() {
        pause();
        activity = null;
        status = null;
        checkButton = actionButton = null;
        progress = null;
    }

    private void check(boolean manual) {
        if (closed || checking || downloading || !client.isConfigured()) return;
        if (!manual && !preferences.getBoolean(AUTO_CHECK, true)) return;
        // Check once per cold launch, preserving the attempt across rotation and permission return.
        if (!manual && automaticCheckAttempted) return;
        automaticCheckAttempted = true;
        checking = true;
        setMessage("正在检查更新…");
        client.check(versionName, versionCode, new AppUpdateClient.CheckCallback() {
            @Override public void onUpdate(AppUpdateRelease release) {
                if (closed) return;
                checking = false;
                offered = release;
                promptShown = false;
                setMessage(sameVersion(release)
                        ? "发现 " + versionName + " 的修订更新。"
                        : "发现新版 " + release.getVersionName() + "，当前 " + versionName + "。");
                maybePrompt();
            }
            @Override public void onNoUpdate() {
                if (closed) return;
                checking = false;
                offered = downloaded == null ? null : downloadedRelease;
                setMessage("当前版本 " + versionName + " 无需更新。");
            }
            @Override public void onError(String reason) {
                if (closed) return;
                checking = false;
                setMessage("暂时无法检查更新。请稍后点击“检查更新”重试。");
            }
        });
    }

    private void maybePrompt() {
        if (!visible() || offered == null || promptShown || downloading || downloaded != null || running()) return;
        promptShown = true;
        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = UiKit.page(activity);
        scroll.addView(body);
        String notes = offered.getNotes();
        if (notes != null && notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
        String detail = (sameVersion(offered)
                ? "当前版本 " + versionName + "，本次为该版本的修订安装包"
                : "当前版本 " + versionName + "，新版 " + offered.getVersionName())
                + "。下载约 " + String.format(Locale.CHINA, "%.1f", offered.getBytes() / (1024.0 * 1024.0))
                + " MB，移动网络会消耗流量。下载完成后，请在系统安装界面确认。";
        if (notes != null && !notes.trim().isEmpty()) detail += "\n\n更新内容：\n" + notes;
        UiKit.add(body, UiKit.body(activity, detail), 0);
        prompt = new AlertDialog.Builder(activity).setTitle("听野有更新")
                .setView(scroll).setPositiveButton("下载并安装", (dialog, which) -> beginDownloadOrInstall())
                .setNegativeButton("稍后", (dialog, which) -> { }).create();
        prompt.setOnDismissListener(dialog -> prompt = null);
        prompt.show();
        UiKit.styleDialog(prompt, UiKit.ButtonStyle.FILLED);
    }

    private void beginDownloadOrInstall() {
        if (!visible() || downloading || checking) return;
        if (running()) { setMessage("请先停止当前游戏辅助，再下载安装更新。"); return; }
        if (downloaded != null) { install(); return; }
        if (offered == null) { check(true); return; }
        AppUpdateRelease target = offered;
        downloading = true;
        percent = 0;
        setMessage("正在下载 " + target.getVersionName() + "：0%");
        client.download(target, new AppUpdateClient.DownloadCallback() {
            @Override public void onProgress(long bytes, long total) {
                if (closed) return;
                percent = total > 0 ? (int) Math.min(100, bytes * 100 / total) : 0;
                setMessage("正在下载 " + target.getVersionName() + "：" + percent + "%");
            }
            @Override public void onDownloaded(File file) {
                if (closed) return;
                setMessage("下载完成，正在核验安装包…");
                verify(file, target, () -> {
                    downloading = false;
                    downloaded = file;
                    downloadedRelease = target;
                    setMessage("新版已下载，可继续安装。");
                    if (visible()) install();
                });
            }
            @Override public void onError(String reason) {
                if (closed) return;
                downloading = false;
                setMessage("更新下载失败，请稍后重试。" + reason);
            }
        });
    }

    private void verify(File file, AppUpdateRelease release, Runnable ready) {
        long generation = ++verificationGeneration;
        verifier.execute(() -> {
            AppUpdatePackageVerifier.Result result;
            try { result = AppUpdatePackageVerifier.verify(context, file, release); }
            catch (RuntimeException failure) { result = null; }
            AppUpdatePackageVerifier.Result checked = result;
            main.post(() -> {
                if (closed || generation != verificationGeneration) return;
                if (checked == null || !checked.valid) {
                    downloading = false;
                    downloaded = null;
                    downloadedRelease = null;
                    // This file was created in the updater's private directory.
                    file.delete();
                    setMessage("安装包核验失败，请重新检查更新或联系负责人。");
                } else ready.run();
            });
        });
    }

    private void install() {
        if (!visible() || downloaded == null || downloadedRelease == null) return;
        if (running()) { setMessage("更新已下载。请先停止游戏辅助，再点击安装。"); return; }
        File file = downloaded;
        verify(file, downloadedRelease, () -> {
            if (!visible() || running()) return;
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                waitingInstallPermission = true;
                setMessage("请在系统设置中允许听野安装更新，然后返回继续。");
                try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()))); }
                catch (RuntimeException unavailable) {
                    waitingInstallPermission = false;
                    setMessage("请在系统设置中允许听野安装应用，再点击继续安装。");
                }
                return;
            }
            waitingInstallPermission = false;
            try {
                Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", file);
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("听野安装包", uri));
                activity.startActivity(intent);
                setMessage("已打开系统安装界面，请确认更新。未完成时可再次点击安装。");
            } catch (RuntimeException unavailable) {
                setMessage("无法打开系统安装器，请联系负责人协助安装。");
            }
        });
    }

    private boolean visible() { return !closed && resumed && activity != null && !activity.isFinishing() && !activity.isDestroyed(); }
    private boolean sameVersion(AppUpdateRelease release) {
        return release.getVersionCode() == versionCode && versionName.equals(release.getVersionName());
    }
    private static boolean running() { return CaptureService.isRunning() || Match3LiveService.isRunning(); }
    private void setMessage(String value) { message = value; render(); }
    private void render() {
        if (status == null) return;
        UiKit.setTextIfChanged(status, message);
        UiKit.setTextIfChanged(checkButton, downloading ? "取消下载" : "检查更新");
        checkButton.setEnabled(client.isConfigured() && !checking);
        actionButton.setVisibility(offered == null ? View.GONE : View.VISIBLE);
        actionButton.setText(downloaded == null ? "下载并安装" : "继续安装");
        actionButton.setEnabled(!checking && !downloading);
        progress.setVisibility(downloading ? View.VISIBLE : View.GONE);
        progress.setProgress(percent);
    }

    void beforeGame() {
        if (downloading) cancelDownload();
        ++verificationGeneration;
    }

    private void cancelDownload() {
        client.cancel();
        ++verificationGeneration;
        downloading = false;
        percent = 0;
        setMessage("下载已取消，可稍后重试。");
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ++verificationGeneration;
        detach();
        client.close();
        verifier.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
