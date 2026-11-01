package com.openkhub.sensefield;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Complete game resources before requesting the one-use screen projection permission. */
final class GameModelPreparation {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    static boolean ready(Activity activity, String id) {
        try { return GameModelStore.get(activity, id).ready(); } catch (Exception ignored) { return false; }
    }
    static void prepare(Activity activity, String id, Runnable complete) {
        AtomicBoolean cancelled = new AtomicBoolean();
        LinearLayout content = UiKit.vertical(activity);
        int padding = UiKit.dp(activity, 24); content.setPadding(padding, padding, padding, padding);
        TextView status = UiKit.body(activity, "首次使用需要下载此游戏的识别资源，完成后可离线使用。");
        content.addView(status);
        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); content.addView(progress);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("准备游戏识别资源").setView(content)
                .setNegativeButton("取消", (d, which) -> cancelled.set(true)).create();
        dialog.setOnDismissListener(d -> cancelled.set(true));
        dialog.show();
        WORKER.execute(() -> {
            try {
                GameModelStore.get(activity.getApplicationContext(), id).ensure(percent -> activity.runOnUiThread(() -> {
                    if (!cancelled.get() && !activity.isDestroyed()) {
                        progress.setProgress(percent);
                        UiKit.setTextIfChanged(status, "正在准备识别资源：" + percent + "%");
                    }
                }), () -> cancelled.get() || activity.isDestroyed());
                activity.runOnUiThread(() -> {
                    if (cancelled.get() || activity.isFinishing() || activity.isDestroyed()) return;
                    dialog.dismiss();
                    if ("match3".equals(id)) Match3VisualCatalog.resourcesPrepared();
                    complete.run();
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (cancelled.get() || activity.isFinishing() || activity.isDestroyed()) return;
                    dialog.dismiss();
                    String code = error.getMessage() == null ? "" : error.getMessage();
                    String message = code.equals("game_model_space") ? "手机存储空间不足，请清理后重试。"
                            : code.endsWith("_401") || code.endsWith("_403") ? "此设备暂未获得资源下载权限，请检查服务配置。"
                            : code.endsWith("_404") ? "此版本游戏识别资源尚未发布，请联系维护者。"
                            : code.endsWith("_503") ? "游戏识别资源服务尚未准备好，请稍后重试。"
                            : code.endsWith("_429") ? "下载请求过于频繁，请稍后重试。"
                            : code.equals("game_model_checksum") ? "识别资源校验未通过，请重新下载。"
                            : "识别资源暂未下载完成，请检查网络后重试；已下载部分会保留。";
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
