package com.openkhub.sensefield;

import android.app.Notification;
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
    private char[][] lastRawMatrix;          // 上一采样帧的矩阵（稳定窗用）
    private int sameRawCount;                // 连续一致的帧数
    private char[][] lastAnnouncedMatrix;    // 上次已播报的局面（签名去重用）
    private List<Match3Board.Swap> lastSwaps;
    private long lastChangeAt;
    private long lastAnnounceAt;
    private boolean popupAnnounced;
    private int liveRows = 8;
    private int liveCols = 8;
    private volatile boolean running;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_EXPLORE_ON.equals(intent.getAction())) { exploreMode = true; Log.i(TAG, "触屏点读模式开启（背景播报已抑制）"); return START_STICKY; }
        if (intent != null && ACTION_EXPLORE_OFF.equals(intent.getAction())) { exploreMode = false; Log.i(TAG, "触屏点读模式关闭"); return START_STICKY; }
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
        /* 自适应：服务启动后的第一帧自动检测棋盘包围盒（深色棋盘格区域），
         * 覆盖标定并持久化——玩家不用手调百分比。检测不到沿用现有标定。 */
        if (sampler == null) {
            var prefs = GameProfile.settings(this);
            int l = prefs.getInt("match3_l", 4), t = prefs.getInt("match3_t", 18);
            int r = prefs.getInt("match3_r", 96), b = prefs.getInt("match3_b", 82);
            int[] auto = Match3Sampler.autoDetectBoard(frame);
            if (auto != null) {
                l = auto[0]; t = auto[1]; r = auto[2]; b = auto[3];
                prefs.edit().putInt("match3_l", l).putInt("match3_t", t)
                        .putInt("match3_r", r).putInt("match3_b", b).apply();
                Log.i(TAG, "棋盘自动适配: l=" + l + "% t=" + t + "% r=" + r + "% b=" + b + "%");
            } else {
                Log.i(TAG, "棋盘自动适配未命中，沿用手动标定");
            }
            sampler = new Match3Sampler(this,
                    Math.max(6, Math.min(9, prefs.getInt("match3_rows", 8))),
                    Math.max(6, Math.min(9, prefs.getInt("match3_cols", 8))),
                    l, t, r, b);
            liveRows = Math.max(6, Math.min(9, prefs.getInt("match3_rows", 8)));
            liveCols = Math.max(6, Math.min(9, prefs.getInt("match3_cols", 8)));
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

        /* 稳定窗：连续 STABLE_FRAMES 帧一致才认账（吸收消除动画/棋子摇摆中间态） */
        if (lastRawMatrix != null && matrixEquals(lastRawMatrix, matrix)) {
            sameRawCount++;
        } else {
            lastRawMatrix = matrix;
            sameRawCount = 1;
        }
        if (sameRawCount < STABLE_FRAMES) return;

        /* 播报签名去重：与上次已播报局面相同 → 完全静默 */
        if (matrixEquals(lastAnnouncedMatrix, matrix)) {
            return;
        }
        boolean isFirst = lastAnnouncedMatrix == null;
        lastAnnouncedMatrix = matrix;
        lastChangeAt = SystemClock.elapsedRealtime();
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(matrix);
        lastSwaps = swaps;
        long now = SystemClock.elapsedRealtime();
        if (!isFirst && now - lastAnnounceAt < MIN_ANNOUNCE_GAP_MS) {
            return;   // 间隔内：局面已被记住，间隔过后有变化再播
        }
        lastAnnounceAt = now;
        StringBuilder sb = new StringBuilder(isFirst ? "棋盘识别完成。" : "局面更新。");
        if (!swaps.isEmpty()) {
            sb.append(Match3Coach.swapSpeechWithQuadrant(swaps.get(0), liveRows, liveCols));
            if (swaps.size() > 1) sb.append("，共 ").append(swaps.size()).append(" 处");
        } else {
            sb.append("暂无可消除交换。");
        }
        Log.i(TAG, sb.toString());
        announce(sb.toString());
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
                && now - lastChangeAt >= IDLE_HINT_MS
                && now - lastAnnounceAt >= MIN_ANNOUNCE_GAP_MS) {
            lastAnnounceAt = now;
            lastChangeAt = now;
            String speech = "还在犹豫的话，" + Match3Board.swapSpeech(lastSwaps.get(0)) + "。";
            Log.i(TAG, "犹豫提示: " + speech);
            announce(speech);
        }
    }

    static final String ACTION_EXPLORE_ON = "com.openkhub.sensefield.m3live.EXPLORE_ON";
    static final String ACTION_EXPLORE_OFF = "com.openkhub.sensefield.m3live.EXPLORE_OFF";

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

    private Notification buildNotification() {
        android.app.NotificationChannel channel = new android.app.NotificationChannel("m3live",
                "消消乐实时识别", android.app.NotificationManager.IMPORTANCE_LOW);
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
        return new android.app.Notification.Builder(this, "m3live")
                .setContentTitle("听野 · 消消乐实时识别中")
                .setContentText("正在识别棋盘并语音播报")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
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
        super.onDestroy();
    }
}
