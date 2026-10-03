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
    private static final long CAPTURE_INTERVAL_MS = 1000;
    private static final long MIN_ANNOUNCE_GAP_MS = 6000;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private CuePlayer player;
    private CueDispatcher dispatcher;
    private Match3Sampler sampler;
    private char[][] lastMatrix;
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
        if (sampler == null) {
            var prefs = GameProfile.settings(this);
            sampler = new Match3Sampler(this,
                    Math.max(6, Math.min(9, prefs.getInt("match3_rows", 8))),
                    Math.max(6, Math.min(9, prefs.getInt("match3_cols", 8))),
                    prefs.getInt("match3_l", 4), prefs.getInt("match3_t", 18),
                    prefs.getInt("match3_r", 96), prefs.getInt("match3_b", 82));
        }
        char[][] matrix = sampler.sample(frame);
        if (lastMatrix != null && !matrixChanged(lastMatrix, matrix)) {
            return;
        }
        lastMatrix = matrix;
        long now = SystemClock.elapsedRealtime();
        if (now - lastAnnounceAt < MIN_ANNOUNCE_GAP_MS) {
            return;
        }
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(matrix);
        if (swaps.isEmpty() && Match3Board.findRuns(matrix).isEmpty()) {
            return;  // 无可消除且无现成三连：不播报，避免刷屏
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
