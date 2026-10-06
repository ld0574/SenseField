package com.openkhub.sensefield;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 消消乐实时识别服务 v2：MediaProjection 连续截屏 → 稳定窗防抖 → 变化播报。
 * 三层防重复（修复「重复错误播报」）：
 *   1) 800ms 定时取最新帧（静态画面也有节拍；不依赖 ImageReader 回调）；
 *   2) 稳定窗：矩阵连续 STABLE_FRAMES 帧一致才认账——吸收消除动画/棋子摇摆的中间态；
 *   3) 播报签名去重：与「上次已播报局面」相同就完全静默——只有真正换了局面才播。
 * 犹豫提示：局面 15 秒无变化且有可消除交换 → 定时器重报最优解（独立于帧回调）。
 */
public class Match3LiveService extends Service {
    private static final String TAG = "Match3Live";
    static final String ACTION_START = "com.openkhub.sensefield.m3live.START";
    static final String ACTION_STOP = "com.openkhub.sensefield.m3live.STOP";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_DATA = "data";
    private static final int NOTIFICATION_ID = 3002;
    private static final long CAPTURE_INTERVAL_MS = 800;
    private static final int STABLE_FRAMES = 3;          // 连续 3 帧一致才算稳定（≈2.4s）
    private static final long MIN_ANNOUNCE_GAP_MS = 6000;
    private static final long IDLE_HINT_MS = 15000;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private CuePlayer player;
    private CueDispatcher dispatcher;
    private Match3Sampler sampler;

    private Bitmap latestFrame;              // onImageAvailable 只负责存最新帧
    private char[][][] rawWindow = new char[STABLE_FRAMES][][];   // 最近 STABLE_FRAMES 帧原始矩阵
    private int rawIdx;                      // 环形写入位置
    private int rawFill;                     // 已入窗帧数
    private char[][] lastAnnouncedMatrix;    // 上次已播报的局面（签名去重用）
    private char[][] pendingMatrix;          // 首播双重确认：上一稳定窗的候选局面
    private int pendingStable;               // 候选局面已连续出现的稳定窗数
    private boolean awaitingConfirm;         // 会话开始/关卡过渡后待双重确认
    private List<Match3Board.Swap> lastSwaps;
    private long lastChangeAt;
    private int hintCount;   // 同一局面的犹豫提示次数（上限 2 次，防无限重复）
    private long lastAnnounceAt;
    private boolean popupAnnounced;
    private boolean abstainAnnounced;   // ABSTAIN 防线提示每轮服务只播一次
    private int liveRows = 8;
    private int liveCols = 8;
    private volatile boolean running;
    private DiagnosticRecorder diagnostics;
    private NotificationManager notificationManager;
    private long sessionStartMs;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_EXPLORE_ON.equals(intent.getAction())) { exploreMode = true; Log.i(TAG, "触屏点读模式开启（背景播报已抑制）"); return START_STICKY; }
        if (intent != null && ACTION_EXPLORE_OFF.equals(intent.getAction())) { exploreMode = false; Log.i(TAG, "触屏点读模式关闭"); return START_STICKY; }
        if (intent != null && ACTION_MARK_ISSUE.equals(intent.getAction())) {
            if (diagnostics == null || diagnostics.finished) return START_NOT_STICKY;
            diagnostics.markIssue();
            refreshNotification();
            return START_NOT_STICKY;
        }
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        teardownMedia();   // 重复 START 时先拆旧投影，否则 ContentRecordingSession 冲突
        sessionStartMs = SystemClock.elapsedRealtime();
        awaitingConfirm = true;   // 新会话首播走双重确认，杜绝开场动画误报
        pendingMatrix = null;
        pendingStable = 0;
        diagnostics = DiagnosticRecorder.start(this, java.util.UUID.randomUUID().toString(), sessionStartMs);
        notificationManager = getSystemService(NotificationManager.class);
        android.content.res.Resources res = getResources();
        android.util.DisplayMetrics dm = res.getDisplayMetrics();
        width = dm.widthPixels;
        height = dm.heightPixels;
        dpi = dm.densityDpi;
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            Log.w(TAG, "获取投影失败: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                stopSelf();
            }
        }, handler());
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("sensefield-m3live",
                width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler());
        reader.setOnImageAvailableListener(this::onImageAvailable, handler());
        Log.i(TAG, "实时识别已启动 " + width + "x" + height);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, CAPTURE_INTERVAL_MS);
        return START_NOT_STICKY;
    }

    /* ---------- 帧采集：只存最新帧，处理交给定时 tick ---------- */

    private Handler handler() {
        if (handler == null) {
            thread = new HandlerThread("m3live-capture");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }

    private void onImageAvailable(ImageReader r) {
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null) return;
        } catch (Exception e) {
            return;
        }
        try {
            Bitmap bitmap = imageToBitmap(image);
            synchronized (frameLock) {
                if (latestFrame != null && !latestFrame.isRecycled()) latestFrame.recycle();
                latestFrame = bitmap;
            }
        } catch (Exception e) {
            Log.w(TAG, "帧转换失败: " + e.getMessage());
        } finally {
            if (image != null) image.close();
        }
    }

    private final Object frameLock = new Object();

    /* ---------- 定时 tick：取最新帧走稳定窗判定 ---------- */

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Bitmap frame;
            synchronized (frameLock) {
                frame = latestFrame;
                latestFrame = null;
            }
            if (frame != null && !frame.isRecycled()) {
                try {
                    processFrame(frame);
                } catch (Exception e) {
                    Log.w(TAG, "帧处理失败: " + e.getMessage());
                }
            }
            idleCheck();
            handler.postDelayed(this, CAPTURE_INTERVAL_MS);
        }
    };

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int rowPadding = rowStride - pixelStride * width;
        int w = image.getWidth(), h = image.getHeight();
        Bitmap full = Bitmap.createBitmap(w + rowPadding / pixelStride, h,
                Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(buffer);
        if (rowPadding > 0) {
            return Bitmap.createBitmap(full, 0, 0, w, h);
        }
        return full;
    }

    private int width;
    private int height;
    private int dpi;

    private void processFrame(Bitmap frame) {
        /* 连续触屏点读模式：只处理触摸报点，完全抑制局面变化播报（优先级=触屏识别准确率） */
        if (exploreMode) {
            handleExploreTouch();
            return;
        }
        /* 自适应：服务启动后的第一帧自动检测棋盘包围盒＋格数（深色棋盘格区域＋列剖面自相关），
         * 覆盖标定并持久化——玩家不用手调百分比。真机视频对拍（REAL_VIDEO_FINDINGS.md）实锤：
         * 检测不到时绝不能拿默认 8×8 标定硬读——那会把蓝天读成满屏河马再反复播报。 */
        if (sampler == null) {
            var prefs = GameProfile.settings(this);
            boolean calibrated = prefs.getBoolean("match3_calibrated", false);
            int[] auto = Match3Sampler.autoDetectBoard(frame);
            Match3Sampler candidate = null;
            int candRows = 0, candCols = 0;
            if (auto != null) {
                int n = Match3Sampler.detectGridCount(frame, auto);
                candRows = n > 0 ? n : Math.max(6, Math.min(9, prefs.getInt("match3_rows", 8)));
                candCols = n > 0 ? n : Math.max(6, Math.min(9, prefs.getInt("match3_cols", 8)));
                candidate = new Match3Sampler(this, candRows, candCols, auto[0], auto[1], auto[2], auto[3]);
                /* 采纳前先试采：弹窗/切屏/动画等坏帧也可能「检出」一块假棋盘，
                 * 采出来一片未知就丢弃——绝不拿坏标定占坑（真机乱播第二成因）。
                 * 40% 这条线在 1235 帧真机抽帧上留了很大余量：格数正确的 1140 帧未知率
                 * 0–10%（均值 0.09%）全部采纳，语音助手遮屏造成的 68 帧错格 55–75% 全部拦下。
                 * 数据见 research/board-recognition/REAL_VIDEO_FINDINGS.md */
                char[][] probe = candidate.sample(frame);
                if (countUnknown(probe) * 100 > probe.length * probe[0].length * 40) {
                    Log.i(TAG, "自动适配命中但采样验证失败（未知过多），本帧不采纳");
                    candidate = null;
                }
            }
            if (candidate != null) {
                prefs.edit().putInt("match3_l", auto[0]).putInt("match3_t", auto[1])
                        .putInt("match3_r", auto[2]).putInt("match3_b", auto[3])
                        .putInt("match3_rows", candRows).putInt("match3_cols", candCols)
                        .putBoolean("match3_calibrated", true).apply();
                liveRows = candRows;
                liveCols = candCols;
                sampler = candidate;
                abstainAnnounced = false;
                Log.i(TAG, "棋盘自动适配: l=" + auto[0] + "% t=" + auto[1] + "% r=" + auto[2]
                        + "% b=" + auto[3] + "% 格数=" + candRows + "x" + candCols
                        + "（试采验证通过）");
            } else if (calibrated) {
                /* 检测不到但玩家框选过：沿用手动标定；格数仍尝试自检
                 * （7×7 的局按 8×8 读会整盘错位，这正是真机乱播的另一半成因） */
                int l = prefs.getInt("match3_l", 4), t = prefs.getInt("match3_t", 18);
                int r = prefs.getInt("match3_r", 96), b = prefs.getInt("match3_b", 82);
                int n = Match3Sampler.detectGridCount(frame, new int[]{l, t, r, b});
                int rows = n > 0 ? n : Math.max(6, Math.min(9, prefs.getInt("match3_rows", 8)));
                int cols = n > 0 ? n : Math.max(6, Math.min(9, prefs.getInt("match3_cols", 8)));
                if (n > 0) {
                    prefs.edit().putInt("match3_rows", rows).putInt("match3_cols", cols).apply();
                }
                liveRows = rows;
                liveCols = cols;
                sampler = new Match3Sampler(this, rows, cols, l, t, r, b);
                abstainAnnounced = false;
                Log.i(TAG, "沿用手动标定 " + rows + "x" + cols
                        + (n > 0 ? "（格数自检=" + n + "）" : ""));
            } else {
                /* ABSTAIN 防线：检测不到且从没框选过 → 明确播报并等待，绝不静默硬读 */
                if (!abstainAnnounced) {
                    announce("还没找到棋盘位置。请先框选标定棋盘区域，或者多等几秒我再试。");
                    abstainAnnounced = true;
                    Log.i(TAG, "棋盘自动适配未命中且无手动标定，ABSTAIN 等待框选或后续帧重试");
                }
                return;
            }
        }
        char[][] matrix = sampler.sample(frame);

        /* 教程/说明弹窗：出现→暂停局面播报并告知；消失→提醒可以开始游戏 */
        boolean popup = Match3Coach.isPopupShowing(frame);
        if (popup && !popupAnnounced) {
            popupAnnounced = true;
            Log.i(TAG, "检测到说明弹窗，暂停局面播报");
            announce("检测到游戏说明弹窗。读完或跳过后就可以开始了。");
            return;
        }
        if (!popup && popupAnnounced) {
            popupAnnounced = false;
            Log.i(TAG, "弹窗结束，可以开始游戏");
            announce("说明已结束，可以开始游戏了。");
            return;
        }
        if (popup) return;   // 弹窗期间的棋盘是暗的，不参与识别

        /* 稳定窗改为三帧逐格多数票：棋子 idle 摇摆会让个别格的逐帧读数来回抖，
         * 严格相等的三帧几乎凑不齐 → 播报时断时续且重复；逐格多数票把摇摆抹平
         *（三帧各不相同算未定，留 '.' 交给未知格逻辑），再走后面的去重/门槛。 */
        rawWindow[rawIdx] = matrix;
        rawIdx = (rawIdx + 1) % STABLE_FRAMES;
        if (rawFill < STABLE_FRAMES) rawFill++;
        if (rawFill < STABLE_FRAMES) return;
        matrix = majorityMatrix(rawWindow);

        /* 云端 VLM 兜底：真机截图对拍证明本地采样 49/49 全对（REAL_VIDEO_FINDINGS.md），
         * 所以本地读数优先播报；只有本地不确定（未知格 >25%）或玩家显式开启
         * match3_cloud_escalate 时才走云端。此前默认每次稳定帧都打 VLM 并用其结果
         * 覆盖本地——VLM 读矩阵会错位，免费档还限速，正是「对两次后一直错」的元凶。 */
        var prefsNow = GameProfile.settings(this);
        boolean autoCloud = prefsNow.getBoolean("match3_cloud_escalate", false);
        int cloudUnknown = 0, cloudTotal = 0;
        for (char[] row : matrix) {
            for (char c : row) {
                cloudTotal++;
                if (c == '.') cloudUnknown++;
            }
        }
        boolean suspicious = cloudUnknown * 100 > cloudTotal * 25;
        if ((suspicious || autoCloud) && "openrouter".equals(prefsNow.getString("jev_channel", "openrouter"))) {
            int l = frame.getWidth() * prefsNow.getInt("match3_l", 4) / 100;
            int t = frame.getHeight() * prefsNow.getInt("match3_t", 18) / 100;
            int r = frame.getWidth() * prefsNow.getInt("match3_r", 96) / 100;
            int b = frame.getHeight() * prefsNow.getInt("match3_b", 82) / 100;
            if (r - l > 40 && b - t > 40) {
                Bitmap crop = Bitmap.createBitmap(frame, l, t, r - l, b - t);
                /* 自托管模型服务（腾讯云）优先：无配额无限速；未配置或失败回退 OpenRouter */
                String selfUrl = prefsNow.getString("match3_cloud_url", "");
                char[][] cloud = null;
                if (selfUrl != null && !selfUrl.trim().isEmpty()) {
                    cloud = CloudVision.readBoardFromServer(crop, selfUrl,
                            matrix.length, matrix[0].length);
                }
                if (cloud == null) {
                    cloud = CloudVision.readBoard(crop,
                            prefsNow.getString("jev_api_key", ""),
                            prefsNow.getString("jev_vlm_model", "z-ai/glm-4.5v"),
                            matrix.length, matrix[0].length);
                }
                if (cloud != null && cloud.length == matrix.length
                        && cloud[0].length == matrix[0].length) {
                    Log.i(TAG, "云端识别接管: " + cloud.length + "x" + cloud[0].length);
                    matrix = cloud;
                } else if (cloud != null) {
                    Log.i(TAG, "云端行列 " + cloud.length + "x" + cloud[0].length
                            + " 与本地 " + matrix.length + "x" + matrix[0].length + " 不符，丢弃");
                }
            }
        }

        /* 播报签名去重：与上次已播报局面相同 → 完全静默 */
        if (matrixEquals(lastAnnouncedMatrix, matrix)) {
            return;
        }
        /* 首播双重确认：会话开始或关卡过渡后的第一次播报，要求连续两个稳定窗
         * 多数票结果一致才开嗓——关卡开场动画棋子还在掉落，多数票逐窗漂移，
         * 旧逻辑 2.5 秒就把掉落中的棋盘播出去（诊断包 diag3/diag4 实锤「还没开始就连续误报」） */
        if (lastAnnouncedMatrix == null || awaitingConfirm) {
            if (matrixEquals(pendingMatrix, matrix)) {
                pendingStable++;
            } else {
                pendingMatrix = matrix;
                pendingStable = 1;
            }
            if (pendingStable < 2) {
                return;
            }
            awaitingConfirm = false;
            pendingMatrix = null;
            pendingStable = 0;
        }
        /* 变化幅度门槛：只差 1 格多半是选中高亮/动画残影，不算新局面（防重复虚报） */
        int diffCells = countDiffCells(lastAnnouncedMatrix, matrix);
        if (lastAnnouncedMatrix != null && diffCells < 2) {
            return;
        }
        /* 自我修复检测：未知格占比 >40% 说明采样坏了（弹窗/切屏/标定漂移）→
         * 重新自动适配棋盘并重采样一次；仍坏则静默（宁可不说，不播垃圾） */
        int unknown = 0, total = 0;
        for (char[] row : matrix) {
            for (char c : row) {
                total++;
                if (Match3Sampler.isUnreadable(c)) unknown++;
            }
        }
        if (unknown * 100 > total * 40) {
            Log.i(TAG, "自我修复：未知格 " + unknown + "/" + total + "，重新自动适配");
            int[] auto = Match3Sampler.autoDetectBoard(frame);
            Match3Sampler candidate = null;
            int candRows = rows(), candCols = cols();
            if (auto != null) {
                int n = Match3Sampler.detectGridCount(frame, auto);
                candRows = n > 0 ? n : rows();
                candCols = n > 0 ? n : cols();
                candidate = new Match3Sampler(this, candRows, candCols, auto[0], auto[1], auto[2], auto[3]);
            }
            /* 先验证再采纳：修复采样仍一片未知 → 保留原标定静默，绝不把坏边界持久化
             * （旧逻辑先持久化后验证，动画帧能把好标定永久改坏——真机「对两次后一直错」主嫌疑）。
             * 分母必须用 fixed 自己的格数：新旧格数不同时沿用旧 total 会把阈值算错
             * （旧 8×8=64 → 新 6×6=36 时阈值变成 71%，几乎全未知的标定也能被采纳）。 */
            char[][] fixed = (candidate != null ? candidate : sampler).sample(frame);
            int fixedCells = fixed.length * fixed[0].length;
            if (countUnknown(fixed) * 100 > fixedCells * 40) {
                Log.i(TAG, "自我修复后仍未识别，本轮静默且不采纳新标定");
                awaitingConfirm = true;   // 过渡期（切屏/弹窗/结算）后重走双重确认
                if (!abstainAnnounced
                        && !GameProfile.settings(this).getBoolean("match3_calibrated", false)) {
                    announce("棋盘位置变了但认不出来。请框选标定棋盘区域。");
                    abstainAnnounced = true;
                }
                return;
            }
            if (candidate != null) {
                var prefs = GameProfile.settings(this);
                prefs.edit().putInt("match3_l", auto[0]).putInt("match3_t", auto[1])
                        .putInt("match3_r", auto[2]).putInt("match3_b", auto[3])
                        .putInt("match3_rows", candRows).putInt("match3_cols", candCols)
                        .putBoolean("match3_calibrated", true).apply();
                liveRows = candRows;
                liveCols = candCols;
                sampler = candidate;
            }
            matrix = fixed;
        }
        boolean isFirst = lastAnnouncedMatrix == null;
        lastChangeAt = SystemClock.elapsedRealtime();
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(matrix);
        lastSwaps = swaps;
        long now = SystemClock.elapsedRealtime();
        if (!isFirst && now - lastAnnounceAt < MIN_ANNOUNCE_GAP_MS) {
            /* 间隔内绝不改 lastAnnouncedMatrix：改了下一帧就会在上面「同局面静默」处判成已播过，
             * 这一步棋永久没了播报（真机视频实锤：21s 走子被吞，30.8s 播的是过时犹豫提示）。
             * 只更新 lastChangeAt/lastSwaps，等间隔过后同一局面再播一次。 */
            return;
        }
        lastAnnouncedMatrix = matrix;
        hintCount = 0;
        lastAnnounceAt = now;
        StringBuilder sb = new StringBuilder(isFirst ? "棋盘识别完成。" : "局面更新。");
        if (!swaps.isEmpty()) {
            sb.append(Match3Coach.swapSpeechWithQuadrant(swaps.get(0), liveRows, liveCols));
            if (swaps.size() > 1) sb.append("，共 ").append(swaps.size()).append(" 处");
        } else {
            sb.append("暂无可消除交换。");
        }
        Log.i(TAG, sb.toString());
        if (diagnostics != null) {
            diagnostics.audit("BoardRecognized rows=" + matrix.length + " cols=" + matrix[0].length
                    + " unknown=" + unknown + "/" + total + " swaps=" + swaps.size());
            saveDiagnosticFrame(frame, "board_recognized");
        }
        announce(sb.toString());
    }

    /** 逐格多数票：三帧里 ≥2 帧相同的字母胜出；三帧各不相同 → '.'（未定）。 */
    static char[][] majorityMatrix(char[][][] window) {
        int rows = window[0].length, cols = window[0][0].length;
        char[][] out = new char[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                char a = window[0][r][c], b = window[1][r][c], d = window[2][r][c];
                out[r][c] = (a == b || a == d) ? a : (b == d ? b : '.');
            }
        }
        return out;
    }

    private static int countUnknown(char[][] m) {
        int n = 0;
        for (char[] row : m) for (char c : row) if (Match3Sampler.isUnreadable(c)) n++;
        return n;
    }

    private static int countDiffCells(char[][] a, char[][] b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        int diff = 0;
        for (int r = 0; r < Math.min(a.length, b.length); r++) {
            for (int c = 0; c < Math.min(a[r].length, b[r].length); c++) {
                if (a[r][c] != b[r][c]) diff++;
            }
        }
        return diff;
    }

    private static boolean matrixEquals(char[][] a, char[][] b) {
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        for (int r = 0; r < a.length; r++) {
            if (a[r].length != b[r].length) return false;
            for (int c = 0; c < a[r].length; c++) {
                if (a[r][c] != b[r][c]) return false;
            }
        }
        return true;
    }

    /** 犹豫提示：独立定时检查（静态画面不产生新帧，不能依赖 ImageReader 回调）。
     *  已播报局面 15 秒无变化且有可消除交换 → 重报最优解，之后每 15 秒一次。 */
    private void idleCheck() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (lastAnnouncedMatrix != null && lastSwaps != null && !lastSwaps.isEmpty()
                && hintCount < 2
                && now - lastChangeAt >= IDLE_HINT_MS
                && now - lastAnnounceAt >= MIN_ANNOUNCE_GAP_MS) {
            lastAnnounceAt = now;
            lastChangeAt = now;
            hintCount++;
            String speech = hintCount == 1
                    ? "还在犹豫的话，" + Match3Board.swapSpeech(lastSwaps.get(0)) + "。"
                    : "仍然可以：" + Match3Board.swapSpeech(lastSwaps.get(0)) + "。不需要时忽略即可。";
            Log.i(TAG, "犹豫提示#" + hintCount + ": " + speech);
            announce(speech);
        }
    }

    static final String ACTION_EXPLORE_ON = "com.openkhub.sensefield.m3live.EXPLORE_ON";
    static final String ACTION_EXPLORE_OFF = "com.openkhub.sensefield.m3live.EXPLORE_OFF";
    static final String ACTION_MARK_ISSUE = "com.openkhub.sensefield.m3live.MARK_ISSUE";

    /* 连续触屏点读模式：开启后完全抑制背景局面播报（消灭胡乱虚报），
     * 只按玩家每一次触摸播报对应格子（模板优先识别），触摸坐标来自读屏服务的观察模式。 */
    private volatile boolean exploreMode;
    private static volatile boolean serviceRunning;

    static boolean isRunning() { return serviceRunning; }
    private long lastTouchHandledAt;

    private void handleExploreTouch() {
        if (!exploreMode || sampler == null || latestFrame == null) return;
        int tx = SenseFieldReaderService.latestTouchX();
        int ty = SenseFieldReaderService.latestTouchY();
        long ta = SenseFieldReaderService.latestTouchAt();
        if (ta == 0 || ta == lastTouchHandledAt) return;
        lastTouchHandledAt = ta;
        int[] hit = sampler.touchRead(latestFrame, tx, ty);
        if (hit == null) {
            announce("点在棋盘范围外了。");
            return;
        }
        String name = Match3Coach.pieceName((char) hit[2]);
        String speech = "第 " + (hit[0] + 1) + " 行，第 " + (hit[1] + 1) + " 列："
                + name + "，" + Match3Coach.quadrantOf(hit[0], hit[1], rows(), cols()) + "区域。";
        Log.i(TAG, "Match3Touch: row=" + (hit[0] + 1) + " col=" + (hit[1] + 1) + " piece=" + (char) hit[2]);
        announce(speech);
    }

    private int rows() {
        return sampler == null ? 8 : sampler.rowCount();
    }

    private int cols() {
        return sampler == null ? 8 : sampler.colCount();
    }

    private void announce(String speech) {
        if (dispatcher == null) {
            player = new CuePlayer(this);
            dispatcher = new CueDispatcher(player, permissivePolicy(), listener(),
                    SystemClock::elapsedRealtime);
        }
        long t = SystemClock.elapsedRealtime();
        String session = "m3live";
        dispatcher.submit(new CueRequest(session, session + ":" + t, "m3live:announce",
                "消消乐实时播报", CueRequest.Category.SYSTEM, 70, t, t + 10000,
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

    private CueDispatcher.Listener listener() {
        return new CueDispatcher.Listener() {
            @Override public void onDispatch(CueRequest request, CueDispatcher.DispatchResult result) { }
            @Override public void onPlayback(CueRequest request, String channel, long atMs, String result) { }
        };
    }

    private void saveDiagnosticFrame(Bitmap frame, String reason) {
        if (diagnostics == null || diagnostics.finished) return;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            frame.compress(Bitmap.CompressFormat.JPEG, 70, bos);
            byte[] bytes = bos.toByteArray();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
            DiagnosticSnapshot snap = DiagnosticSnapshot.parse(null);
            diagnostics.frame(NativeFrameResult.empty(), snap, buf,
                    frame.getWidth(), frame.getHeight(), frame.getWidth() * 4,
                    SystemClock.elapsedRealtime(), SystemClock.elapsedRealtime(), 120000);
        } catch (Exception e) {
            Log.w(TAG, "保存诊断帧失败: " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        android.app.NotificationChannel channel = new android.app.NotificationChannel("m3live",
                "消消乐实时识别", android.app.NotificationManager.IMPORTANCE_LOW);
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
        PendingIntent markIssue = PendingIntent.getService(this, 4,
                new Intent(this, Match3LiveService.class).setAction(ACTION_MARK_ISSUE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 5,
                new Intent(this, Match3LiveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String diagText = diagnostics == null || diagnostics.finished
                ? "" : " · 诊断会话 active";
        return new android.app.Notification.Builder(this, "m3live")
                .setContentTitle("听野 · 消消乐实时识别中" + diagText)
                .setContentText("正在识别棋盘并语音播报")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .addAction(android.R.drawable.ic_menu_edit, "标记问题", markIssue)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
                .build();
    }

    private void teardownMedia() {
        if (reader != null) { try { reader.close(); } catch (Exception ignored) { } reader = null; }
        if (display != null) { try { display.release(); } catch (Exception ignored) { } display = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) { } projection = null; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        android.app.NotificationChannel channel = new android.app.NotificationChannel("m3live",
                "消消乐实时识别", android.app.NotificationManager.IMPORTANCE_LOW);
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
        running = true;
        serviceRunning = true;
    }

    @Override
    public void onDestroy() {
        running = false;
        serviceRunning = false;
        teardownMedia();
        if (dispatcher != null) dispatcher.close();
        if (player != null) player.close();
        if (thread != null) thread.quitSafely();
        if (diagnostics != null) {
            diagnostics.finish("m3live_session_finished");
            Log.i(TAG, "诊断会话已保存至 " + DiagnosticRecorder.activeDirectory());
        }
        super.onDestroy();
    }

    private void refreshNotification() {
        if (notificationManager == null) return;
        notificationManager.notify(NOTIFICATION_ID, buildNotification());
    }
}
