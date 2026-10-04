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
 * 消消乐实时识别服务：MediaProjection 连续截屏（约 1 次/秒）→ 标定采样 → 颜色矩阵
 * → 与上一帧比对 → 局面有实质变化时 TTS 播报可消除交换（6 秒最小播报间隔防刷屏）。
 * 需要 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION（API 34+ 硬性要求，先启服务后取投影）。
 */
public class Match3LiveService extends Service {
    private static final String TAG = "Match3Live";
    static final String ACTION_START = "com.openkhub.sensefield.m3live.START";
    static final String ACTION_STOP = "com.openkhub.sensefield.m3live.STOP";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_DATA = "data";
    private static final int NOTIFICATION_ID = 3002;
    private static final long CAPTURE_INTERVAL_MS = 800;
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
    private char[][] lastMatrix;
    private List<Match3Board.Swap> lastSwaps;
    private long lastChangeAt;
    private long lastAnnounceAt;
    private int width;
    private int height;
    private int dpi;
    private volatile boolean running;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        handler.postDelayed(this::idleCheck, 5000);
        return START_NOT_STICKY;
    }

    private Handler handler() {
        if (handler == null) {
            thread = new HandlerThread("m3live-capture");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }

    private long lastProcessedAt;

    private void onImageAvailable(ImageReader r) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastProcessedAt < CAPTURE_INTERVAL_MS) {
            return;
        }
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null) return;
        } catch (Exception e) {
            return;
        }
        lastProcessedAt = now;
        try {
            Bitmap bitmap = imageToBitmap(image);
            processFrame(bitmap);
        } catch (Exception e) {
            Log.w(TAG, "帧处理失败: " + e.getMessage());
        } finally {
            if (image != null) image.close();
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap full = Bitmap.createBitmap(width + rowPadding / pixelStride, height,
                Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(buffer);
        if (rowPadding > 0) {
            return Bitmap.createBitmap(full, 0, 0, width, height);
        }
        return full;
    }

    private void processFrame(Bitmap frame) {
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
        }
        char[][] matrix = sampler.sample(frame);
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(matrix);
        if (lastMatrix != null && !matrixChanged(lastMatrix, matrix)) {
            return;
        }
        lastMatrix = matrix;
        lastSwaps = swaps;
        lastChangeAt = SystemClock.elapsedRealtime();
        long now = SystemClock.elapsedRealtime();
        if (now - lastAnnounceAt < MIN_ANNOUNCE_GAP_MS) {
            return;
        }
        lastAnnounceAt = now;
        StringBuilder sb = new StringBuilder("局面更新。");
        if (!swaps.isEmpty()) {
            sb.append(Match3Board.swapSpeech(swaps.get(0)));
            if (swaps.size() > 1) sb.append("，共 ").append(swaps.size()).append(" 处");
        } else {
            sb.append("棋面上已有可消除组合。");
        }
        Log.i(TAG, sb.toString());
        announce(sb.toString());
    }

    private static boolean matrixChanged(char[][] a, char[][] b) {
        for (int r = 0; r < a.length; r++) {
            for (int c = 0; c < a[r].length; c++) {
                if (a[r][c] != b[r][c]) return true;
            }
        }
        return false;
    }

    /** 犹豫提示：独立定时器（静态画面不产生新帧，不能依赖 ImageReader 回调）。
     *  局面 15 秒无变化且有可消除交换 → 重报最优解，之后每 15 秒一次。 */
    private void idleCheck() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (lastMatrix != null && lastSwaps != null && !lastSwaps.isEmpty()
                && now - lastChangeAt >= IDLE_HINT_MS
                && now - lastAnnounceAt >= MIN_ANNOUNCE_GAP_MS) {
            lastAnnounceAt = now;
            lastChangeAt = now;
            String speech = "还在犹豫的话，" + Match3Board.swapSpeech(lastSwaps.get(0)) + "。";
            Log.i(TAG, "犹豫提示: " + speech);
            announce(speech);
        }
        handler.postDelayed(this::idleCheck, 5000);
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
        android.app.Notification.Builder builder = new android.app.Notification.Builder(this, "m3live")
                .setContentTitle("听野 · 消消乐实时识别中")
                .setContentText("正在识别棋盘并语音播报")
                .setSmallIcon(android.R.drawable.ic_menu_camera);
        return builder.build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        android.app.NotificationChannel channel = new android.app.NotificationChannel("m3live",
                "消消乐实时识别", android.app.NotificationManager.IMPORTANCE_LOW);
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
        running = true;
    }

    private void teardownMedia() {
        if (reader != null) { try { reader.close(); } catch (Exception ignored) { } reader = null; }
        if (display != null) { try { display.release(); } catch (Exception ignored) { } display = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) { } projection = null; }
    }

    @Override
    public void onDestroy() {
        running = false;
        teardownMedia();
        if (dispatcher != null) dispatcher.close();
        if (player != null) player.close();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
