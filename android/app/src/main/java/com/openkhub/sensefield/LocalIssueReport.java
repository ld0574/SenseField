package com.openkhub.sensefield;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 未识别界面的本地记录：认不出的格子成批出现时，在既有诊断会话目录里落一个附属文本文件，
 * 字段是白名单制，取值全部沿用现成的读数出口。<b>只落本地</b>——本类不 import 任何
 * {@code android.*}、不联网、不外发、不弹窗、不新增写盘位置（落盘走
 * {@link DiagnosticArchive#writeJson}，与 checkpoint／summary 同一个原子改名出口）。
 *
 * <p>触发源只有 {@link Match3UnknownElements} 给出的样本；本类不再实现第二套「认不出」判据，
 * 也不改动它的采样常量与判据语义。脱敏只有一处事实源：{@link JevAnnotationExporter#desensitize}，
 * 沿用它的规则表与占位符，逐字段一次＋整篇文本再一次（与导出侧 {@code emit} 的双趟纪律一致）。
 * 身份串「剔除」这件事不在这里重写第二份；这里只做<b>字段准入</b>——白名单之外的形状一律整值作废，
 * 这是任务要求的字段纪律，不是第二套脱敏规则。</p>
 *
 * <p>上限与节流：认不出是常态，报告不能一帧一条。节流间隔直接复用采样器的
 * {@link Match3UnknownElements#SAMPLE_GAP_MS}；单会话文件数与单文件字节数各有一条硬上限
 * （{@link #MAX_REPORT_FILES}、{@link #MAX_REPORT_BYTES}），超限即不再落盘、只更新读数。</p>
 *
 * <p>失败不响：写盘异常一律吞掉，只把 {@link #STATUS_NOT_WRITTEN} 记进读数，由调用方递到既有
 * 诊断记录行里；播报链路不因为一份报告写坏而中断。</p>
 */
final class LocalIssueReport {

    /** 单份记录的字节硬上限：字段数固定、每值有长度门槛，超上限说明取值被喂脏了，宁可不写。 */
    static final int MAX_REPORT_BYTES = 2048;
    /** 单会话记录文件数硬上限：与采样器 24 家族 × 每家族 2 样本的总量相除可解释（约每 8 个样本一条）。 */
    static final int MAX_REPORT_FILES = 6;
    /** 节流：复用采样器已有的间隔常量，不在这里另立一个时间口径。 */
    static final long MIN_GAP_MS = Match3UnknownElements.SAMPLE_GAP_MS;
    /** 唯一自由文本取值（应用版本）的准入形状与长度上限。 */
    static final int MAX_VERSION_CHARS = 32;
    /** 记录标签，读数行与文件内容用同一个字面量对得上。 */
    static final String RECORD_TAG = "unrecognized_screen_local_note";
    /** 取值不合规时的整值替身：宁可少一个字段，也不把可疑形状写进文件。 */
    static final String UNAVAILABLE = "unavailable";

    static final String STATUS_IDLE = "idle";
    static final String STATUS_EMPTY = "no_unrecognized_family";
    static final String STATUS_THROTTLED = "throttled";
    static final String STATUS_FILE_LIMIT = "file_limit";
    static final String STATUS_OVERSIZED = "oversized";
    static final String STATUS_QUEUED = "queued";
    static final String STATUS_WRITTEN = "written";
    static final String STATUS_NOT_WRITTEN = "not_written";

    static final String FILE_PREFIX = "unrecognized-screen-";
    static final String FILE_SUFFIX = ".txt";

    private static final Pattern ADMITTED_VERSION = Pattern.compile("[A-Za-z0-9._+-]{1," + MAX_VERSION_CHARS + "}");

    private LocalIssueReport() {
    }

    /** 白名单字段袋。除构造它需要的取值外，别的内容进不来。 */
    static final class Snapshot {
        final String familyId;
        final String packageName;
        final String gameName;
        final int rows;
        final int cols;
        final String reason;
        final String cellKind;
        final String appVersion;

        private Snapshot(String familyId, String packageName, String gameName, int rows, int cols,
                         String reason, String cellKind, String appVersion) {
            this.familyId = familyId;
            this.packageName = packageName;
            this.gameName = gameName;
            this.rows = rows;
            this.cols = cols;
            this.reason = reason;
            this.cellKind = cellKind;
            this.appVersion = appVersion;
        }

        /** 家族标识缺失＝这一批没有「认不出」的家族可报，报告不许凭空白造家族数。 */
        boolean usable() {
            return familyId != null && !familyId.isEmpty() && rows > 0 && cols > 0;
        }
    }

    /** 待落盘载荷：名字、正文、计数与脱敏次数。 */
    static final class Pending {
        final String fileName;
        final String text;
        final int unknownFamilies;
        final int unknownSamples;
        final int desensitizations;
        final int bytes;

        private Pending(String fileName, String text, int unknownFamilies, int unknownSamples,
                        int desensitizations) {
            this.fileName = fileName;
            this.text = text;
            this.unknownFamilies = unknownFamilies;
            this.unknownSamples = unknownSamples;
            this.desensitizations = desensitizations;
            this.bytes = text.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /**
     * 从现成取值摊出白名单字段：包名与游戏显示名取自既有常量与注册表，盘面形状取自当轮几何，
     * 判据读数取自采样器与外观判定既有常量。<b>逐格行列坐标不进报告</b>，样本自带的像素与
     * row/col 一个也不取。
     */
    static Snapshot read(DiagnosticGame game, BoardGeometry geometry, Match3UnknownElements.Sample sample) {
        if (game == null || geometry == null || sample == null) return null;
        return new Snapshot(sample.familyId, packageFor(game), game.label, geometry.rows, geometry.cols,
                sample.reason(), sample.observation.cell.kind.name(), UNAVAILABLE);
    }

    /** 带应用版本取值的入口：版本是唯一可能带进脏形状的自由文本，先过字段准入再过脱敏。 */
    static Snapshot read(DiagnosticGame game, BoardGeometry geometry, Match3UnknownElements.Sample sample,
                         String suppliedVersion) {
        Snapshot base = read(game, geometry, sample);
        if (base == null) return null;
        return new Snapshot(base.familyId, base.packageName, base.gameName, base.rows, base.cols,
                base.reason, base.cellKind, admitVersion(suppliedVersion));
    }

    /** 包名一律取既有常量，不在这里拼字符串，也不凭空造一个新包名。 */
    static String packageFor(DiagnosticGame game) {
        if (game == DiagnosticGame.HONOR) return GameLauncher.HONOR_OF_KINGS_PACKAGE;
        if (game == DiagnosticGame.MATCH3) return GameLauncher.HAPPY_ANIPOP_PACKAGE;
        return UNAVAILABLE;
    }

    /** 字段准入：形状不合规就整值换成替身，绝不留下半截。 */
    static String admitVersion(String supplied) {
        if (supplied == null) return UNAVAILABLE;
        String stripped = JevAnnotationExporter.desensitize(supplied.trim());
        return ADMITTED_VERSION.matcher(stripped).matches() ? stripped : UNAVAILABLE;
    }

    /** 记录正文：只写白名单字段，值与整篇各过一遍既有脱敏规则；超字节上限时返回 null（宁可不写）。 */
    static Pending render(Snapshot snapshot, int unknownFamilies, int unknownSamples, String fileName) {
        if (snapshot == null || unknownFamilies <= 0) return null;
        Map<String, Integer> sink = new LinkedHashMap<String, Integer>();
        StringBuilder sb = new StringBuilder();
        append(sb, sink, "record", RECORD_TAG);
        append(sb, sink, "schema", "local-unrecognized-screen-v1");
        append(sb, sink, "package", snapshot.packageName);
        append(sb, sink, "game", snapshot.gameName);
        append(sb, sink, "board_rows", Integer.toString(snapshot.rows));
        append(sb, sink, "board_cols", Integer.toString(snapshot.cols));
        append(sb, sink, "unknown_families", Integer.toString(unknownFamilies));
        append(sb, sink, "unknown_samples", Integer.toString(unknownSamples));
        append(sb, sink, "family_limit", Integer.toString(Match3UnknownElements.MAX_FAMILIES));
        append(sb, sink, "samples_per_family_limit", Integer.toString(Match3UnknownElements.MAX_SAMPLES_PER_FAMILY));
        append(sb, sink, "stable_samples_required", Integer.toString(Match3UnknownElements.STABLE_SAMPLES));
        append(sb, sink, "sample_gap_ms", Long.toString(Match3UnknownElements.SAMPLE_GAP_MS));
        append(sb, sink, "review_threshold", Float.toString(Match3AnimalAppearance.MAXIMUM));
        append(sb, sink, "review_confidence_band", Float.toString(Match3AnimalAppearance.MARGIN));
        append(sb, sink, "unresolved_reason", snapshot.reason);
        append(sb, sink, "unresolved_cell_kind", snapshot.cellKind);
        append(sb, sink, "app_version", snapshot.appVersion);
        append(sb, sink, "delivery", "local_file_only_no_upload_no_share_no_prompt");
        String text = JevAnnotationExporter.desensitize(sb.toString(), sink);
        int replacements = 0;
        for (Integer hits : sink.values()) replacements += hits.intValue();
        Pending job = new Pending(fileName, text, unknownFamilies, unknownSamples, replacements);
        return job.bytes > MAX_REPORT_BYTES ? null : job;
    }

    private static void append(StringBuilder sb, Map<String, Integer> sink, String key, String value) {
        sb.append(key).append('=').append(JevAnnotationExporter.desensitize(value == null ? "" : value, sink))
                .append('\n');
    }

    /** 家族数与样本数的记账，加上节流与上限判定；不落盘，只决定要不要投递一份载荷。 */
    static final class Writer {
        private final File directory;
        private final Set<String> families = new LinkedHashSet<>();
        private int samples;
        private int files;
        private int notWritten;
        private int desensitizations;
        private long lastAtMs = -1;
        private String status = STATUS_IDLE;

        Writer(File directory) {
            this.directory = directory;
        }

        /**
         * 每个样本一次判定：家族数为 0 不生成；间隔没到不生成；文件数到顶不生成；
         * 正文超字节上限不生成（也不消耗节流窗口）。生成的那份交给 {@code out}，由调用方投到既有 IO 工作线程。
         */
        synchronized void observe(Snapshot snapshot, long atMs, Consumer<Pending> out) {
            if (snapshot != null && snapshot.usable()) families.add(snapshot.familyId);
            if (snapshot != null) samples++;
            if (families.isEmpty()) {
                status = STATUS_EMPTY;
                return;
            }
            if (files >= MAX_REPORT_FILES) {
                status = STATUS_FILE_LIMIT;
                return;
            }
            if (lastAtMs >= 0 && atMs >= lastAtMs && atMs - lastAtMs < MIN_GAP_MS) {
                status = STATUS_THROTTLED;
                return;
            }
            Pending job = render(snapshot, families.size(), samples, FILE_PREFIX + (files + 1) + FILE_SUFFIX);
            if (job == null) {
                status = STATUS_OVERSIZED;
                return;
            }
            files++;
            lastAtMs = atMs;
            desensitizations += job.desensitizations;
            status = STATUS_QUEUED;
            out.accept(job);
        }

        /** 只在既有诊断 IO 工作线程上调用；失败一律吞掉，只把「这次没写成」留在读数里。 */
        synchronized String settle(Pending job) {
            if (job == null) return STATUS_NOT_WRITTEN;
            String name = safeFileName(job.fileName);
            if (name == null) {
                notWritten++;
                return STATUS_NOT_WRITTEN;
            }
            try {
                DiagnosticArchive.writeJson(directory, name, job.text);
                return STATUS_WRITTEN;
            } catch (Exception failure) {
                notWritten++;
                return STATUS_NOT_WRITTEN;
            }
        }

        synchronized String status() {
            return status;
        }

        synchronized int familyCount() {
            return families.size();
        }

        synchronized int sampleCount() {
            return samples;
        }

        synchronized int fileCount() {
            return files;
        }

        synchronized int notWrittenCount() {
            return notWritten;
        }

        synchronized int desensitizationCount() {
            return desensitizations;
        }
    }

    /** 字段准入后的形状自检：只认本类生成的文件名，别的路径形状一律作废。 */
    static String safeFileName(String name) {
        return name != null && name.matches(FILE_PREFIX + "[1-9][0-9]*" + Pattern.quote(FILE_SUFFIX)) ? name : null;
    }
}
