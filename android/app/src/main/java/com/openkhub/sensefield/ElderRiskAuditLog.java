package com.openkhub.sensefield;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 养老风险标签审计日志：把 {@link ElderRiskMonitor#flushAuditQueue()} 返回的 JSON 行追加写
 * 到外部存储下的一个固定 JSONL 文件，供家属/护工端或诊断导出消费。
 *
 * 单例、进程内共享（同一 Context），线程安全——写入侧是读屏服务的 main 线程，但文件是追加
 * 模式，多窗口变化并发时也不会丢行。不锁整个会话，不在内存里积累。
 */
public final class ElderRiskAuditLog {

    private static final String TAG = "ElderRiskAudit";
    private static final String FILE_NAME = "elder-risk-events.jsonl";

    private final File file;
    private final OutputStreamWriter writer;

    /** 工厂：给一个上下文，把 JSONL 写到 ExternalFilesDir 下；目录不存在则尝试创建。 */
    public static ElderRiskAuditLog open(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            // fallback：用 cache dir，但对外部消费者可能不可见
            dir = ctx.getCacheDir();
        }
        return openAt(new File(dir, FILE_NAME));
    }

    /** 供单元测试直接指定路径。 */
    public static ElderRiskAuditLog openAt(File file) {
        try {
            if (file.getParentFile() != null && !file.getParentFile().exists()
                    && !file.getParentFile().mkdirs()) {
                Log.w(TAG, "无法创建审计目录: " + file.getParentFile());
            }
            OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8);
            return new ElderRiskAuditLog(file, w);
        } catch (IOException e) {
            Log.w(TAG, "打开审计日志失败: " + e.getMessage());
            return null;
        }
    }

    private ElderRiskAuditLog(File file, OutputStreamWriter writer) {
        this.file = file;
        this.writer = writer;
    }

    /** 把一批 JSON 行追加写进 JSONL。空行/纯空格行自动跳过。 */
    public void appendLines(java.util.List<String> lines) {
        if (lines == null || lines.isEmpty() || writer == null) return;
        try {
            for (String line : lines) {
                if (line == null) continue;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                writer.write(trimmed);
                writer.write('\n');
            }
            writer.flush();
        } catch (IOException e) {
            Log.w(TAG, "写入审计日志失败: " + e.getMessage());
        }
    }

    /** 日志文件大小（字节）；便于测试断言。 */
    public long fileSize() {
        return file.exists() ? file.length() : 0;
    }

    public void close() {
        try { if (writer != null) writer.close(); } catch (IOException ignored) {}
    }
}
