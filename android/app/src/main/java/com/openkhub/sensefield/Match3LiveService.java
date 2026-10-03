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

    private static volatile boolean running;

    static boolean isRunning() { return running; }

    private MediaProjection projection;
    private VirtualDisplay display;
    private volatile ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private CuePlayer player;
    private CueDispatcher dispatcher;
    private Match3ProjectionSession projectionSession = new Match3ProjectionSession();
    private volatile Match3Sampler sampler;
    private char[][] lastMatrix;
    private long lastAnnounceAt;
    private long lastProcessedAt;
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            projectionSession.invalidate(startId);
            teardownMedia();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        long sessionGeneration = projectionSession.beginStart(startId);
        projectionSession.runIfGeneration(sessionGeneration, () -> {
            lastMatrix = null;
            lastAnnounceAt = 0;
            lastProcessedAt = 0;
        });
        teardownMedia();   // 重复 START 时先拆旧投影，否则 ContentRecordingSession 冲突
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (CaptureService.isRunning()) {
            Log.w(TAG, "MapAssist 截屏已运行，拒绝同时启动消消乐投影");
            android.widget.Toast.makeText(this,
                    "地图识别正在使用录屏，请先停止地图识别再开启消消乐实时识别。",
                    android.widget.Toast.LENGTH_LONG).show();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        android.content.res.Resources res = getResources();
        android.util.DisplayMetrics dm = res.getDisplayMetrics();
        int frameWidth = dm.widthPixels;
        int frameHeight = dm.heightPixels;
        int frameDpi = dm.densityDpi;
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            Log.w(TAG, "获取投影失败: " + e.getMessage());
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (projection == null) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        MediaProjection currentProjection = projection;
        if (!projectionSession.attach(sessionGeneration, currentProjection)) {
            teardownMedia();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try {
            currentProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    int stopThroughStartId = projectionSession.stopIfCurrent(
                            sessionGeneration, currentProjection);
                    if (stopThroughStartId > 0) stopSelfResult(stopThroughStartId);
                }
            }, handler());
            ImageReader currentReader = ImageReader.newInstance(frameWidth, frameHeight,
                    PixelFormat.RGBA_8888, 2);
            reader = currentReader;
            display = currentProjection.createVirtualDisplay("sensefield-m3live",
                    frameWidth, frameHeight, frameDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    currentReader.getSurface(), null, handler());
            if (display == null) throw new IllegalStateException("Virtual display unavailable");
            if (!projectionSession.isCurrent(sessionGeneration, currentProjection)) {
                teardownMedia();
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            currentReader.setOnImageAvailableListener(imageReader -> onImageAvailable(
                    imageReader, currentReader, sessionGeneration, currentProjection,
                    frameWidth, frameHeight), handler());
        } catch (RuntimeException error) {
            stopAfterProjectionFailure(startId, error);
            return START_NOT_STICKY;
        }
        Log.i(TAG, "实时识别已启动 " + frameWidth + "x" + frameHeight);
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

    private void onImageAvailable(ImageReader sourceReader, ImageReader expectedReader,
                                  long sessionGeneration, MediaProjection expectedProjection,
                                  int frameWidth, int frameHeight) {
        Image image = null;
        Bitmap bitmap = null;
        try {
            // Drain the latest image on every callback. Returning before this
            // acquire can leave both ImageReader slots occupied indefinitely.
            image = sourceReader.acquireLatestImage();
            if (image == null) return;
            long now = SystemClock.elapsedRealtime();
            boolean due = projectionSession.runIfCurrent(sessionGeneration,
                    expectedProjection, () -> {
                        if (sourceReader != reader || expectedReader != reader
                                || now - lastProcessedAt < CAPTURE_INTERVAL_MS) return false;
                        lastProcessedAt = now;
                        return true;
                    });
            if (!due) return;
            bitmap = imageToBitmap(image, frameWidth, frameHeight);
            processFrame(bitmap, expectedReader, sessionGeneration, expectedProjection);
        } catch (Exception e) {
            if (isCurrentReader(expectedReader, sessionGeneration, expectedProjection)) {
                Log.w(TAG, "帧处理失败: " + e.getMessage());
            }
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (image != null) try { image.close(); } catch (RuntimeException ignored) { }
        }
    }

    private Bitmap imageToBitmap(Image image, int frameWidth, int frameHeight) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int rowPadding = rowStride - pixelStride * frameWidth;
        Bitmap full = Bitmap.createBitmap(frameWidth + rowPadding / pixelStride, frameHeight,
                Bitmap.Config.ARGB_8888);
        try {
            full.copyPixelsFromBuffer(buffer);
            if (rowPadding > 0) {
                return Bitmap.createBitmap(full, 0, 0, frameWidth, frameHeight);
            }
            Bitmap result = full;
            full = null;
            return result;
        } finally {
            if (full != null && !full.isRecycled()) full.recycle();
        }
    }

    private boolean isCurrentReader(ImageReader candidate, long sessionGeneration,
                                    MediaProjection expectedProjection) {
        return candidate != null && candidate == reader
                && projectionSession.isCurrent(sessionGeneration, expectedProjection);
    }

    private Match3Sampler createSampler() {
        var prefs = GameProfile.settings(this);
        return new Match3Sampler(this,
                Math.max(6, Math.min(9, prefs.getInt("match3_rows", 8))),
                Math.max(6, Math.min(9, prefs.getInt("match3_cols", 8))),
                prefs.getInt("match3_l", 4), prefs.getInt("match3_t", 18),
                prefs.getInt("match3_r", 96), prefs.getInt("match3_b", 82));
    }

    private void processFrame(Bitmap frame, ImageReader expectedReader,
                              long sessionGeneration, MediaProjection expectedProjection) {
        if (!isCurrentReader(expectedReader, sessionGeneration, expectedProjection)) return;
        Match3Sampler frameSampler = sampler;
        if (frameSampler == null) {
            Match3Sampler createdSampler = createSampler();
            boolean installed = projectionSession.runIfCurrent(sessionGeneration,
                    expectedProjection, () -> {
                        if (expectedReader != reader) return false;
                        if (sampler == null) sampler = createdSampler;
                        return true;
                    });
            if (!installed) return;
            frameSampler = sampler;
        }
        if (frameSampler == null) return;
        char[][] matrix = frameSampler.sample(frame);
        boolean changed = projectionSession.runIfCurrent(sessionGeneration,
                expectedProjection, () -> {
                    if (expectedReader != reader
                            || (lastMatrix != null && !matrixChanged(lastMatrix, matrix))) {
                        return false;
                    }
                    lastMatrix = matrix;
                    return true;
                });
        if (!changed) return;
        long now = SystemClock.elapsedRealtime();
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(matrix);
        if (swaps.isEmpty() && Match3Board.findRuns(matrix).isEmpty()) {
            return;  // 无可消除且无现成三连：不播报，避免刷屏
        }
        StringBuilder sb = new StringBuilder("局面更新。");
        if (!swaps.isEmpty()) {
            sb.append(Match3Board.swapSpeech(swaps.get(0)));
            if (swaps.size() > 1) sb.append("，共 ").append(swaps.size()).append(" 处");
        } else {
            sb.append("棋面上已有可消除组合。");
        }
        String speech = sb.toString();
        projectionSession.runIfCurrent(sessionGeneration, expectedProjection, () -> {
            if (expectedReader != reader || now - lastAnnounceAt < MIN_ANNOUNCE_GAP_MS) {
                return false;
            }
            lastAnnounceAt = now;
            Log.i(TAG, speech);
            announce(speech);
            return true;
        });
    }

    private void stopAfterProjectionFailure(int startId, RuntimeException error) {
        Log.w(TAG, "创建消消乐录屏失败: " + error.getClass().getSimpleName());
        projectionSession.invalidate(startId);
        teardownMedia();
        stopForeground(STOP_FOREGROUND_REMOVE);
        android.widget.Toast.makeText(this,
                "无法启动消消乐实时识别，请重新授权后重试。",
                android.widget.Toast.LENGTH_LONG).show();
        stopSelfResult(startId);
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
            dispatcher = new CueDispatcher(player,
                    new Match3LiveCuePolicy(new CueSettings(this)), listener(),
                    SystemClock::elapsedRealtime);
        }
        long t = SystemClock.elapsedRealtime();
        String session = "m3live";
        dispatcher.submit(new CueRequest(session, session + ":" + t, "m3live:announce",
                "消消乐实时播报", CueRequest.Category.SYSTEM, 70, t, t + 10000,
                Match3LiveCuePolicy.REQUESTED_CHANNELS,
                0, 0, 0, speech));
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
        projectionSession.invalidate(0);
        teardownMedia();
        if (dispatcher != null) dispatcher.close();
        if (player != null) player.close();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
