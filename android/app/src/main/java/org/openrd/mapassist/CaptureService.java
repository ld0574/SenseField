package org.openrd.mapassist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import android.view.Display;
import android.graphics.Point;

import org.json.JSONException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

public final class CaptureService extends Service {
    static final String ACTION_START = "org.openrd.mapassist.START";
    static final String ACTION_TOGGLE_PAUSE = "org.openrd.mapassist.TOGGLE_PAUSE";
    static final String ACTION_STOP = "org.openrd.mapassist.STOP";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String TAG = "MapAssistCapture";
    private static final String CHANNEL = "mapassist_capture";
    private static final int NOTIFICATION_ID = 104;
    private static final long FRAME_PERIOD_MS = 83; // About 12 sampled frames per second.
    private static final long DISPLAY_CHECK_PERIOD_MS = 500;
    private static final long BLACK_FRAME_GRACE_MS = 5000;
    private static final long BLACK_STOP_AFTER_MS = 10000;

    private final Object processingLock = new Object();
    private HandlerThread workerThread;
    private Handler worker;
    private NotificationManager notificationManager;
    private CuePlayer cuePlayer;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private long nativeSession;
    private String profileName = "";
    private boolean paused;
    private boolean stopping;
    private long startedAtMs;
    private long lastProcessedAtMs;
    private long lastNotificationAtMs;
    private long blackSinceMs;
    private int processedFrames;
    private int detectedCues;
    private int queuedCues;
    private int staleCues;
    private int audioQueueFailures;
    private int latestNativeMicros;
    private int maxObservationAgeMs;
    private int frameWidth;
    private int frameHeight;
    private boolean lastFrameLandscape;
    private String auditSessionId;
    private long auditSessionStartedAtMs;
    private long nextCueId;
    private int landscapeProcessedFrames;
    private long firstLandscapeProcessedAtMs;
    private long lastLandscapeProcessedAtMs;
    private long maxLandscapeProcessedGapMs;
    private boolean auditSessionActive;
    private int latestServiceStartId;
    private final Runnable displayWatchdog = new Runnable() {
        @Override public void run() {
            synchronized (processingLock) {
                if (stopping || projection == null || reader == null) return;
                try {
                    // Rotation can stop frame callbacks before the old reader
                    // reaches its next periodic size check.
                    resizeIfNeeded();
                } catch (RuntimeException error) {
                    Log.e(TAG, "Could not resize capture after display change", error);
                    stopWithStatus("横屏切换后无法继续截屏");
                    return;
                }
            }
            worker.postDelayed(this, DISPLAY_CHECK_PERIOD_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "游戏画面采集", NotificationManager.IMPORTANCE_LOW));
        workerThread = new HandlerThread("MapAssistFrames");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (!ACTION_START.equals(action) && !ACTION_STOP.equals(action)
                && !ACTION_TOGGLE_PAUSE.equals(action)) return START_NOT_STICKY;
        synchronized (processingLock) {
            latestServiceStartId = startId;
        }
        if (ACTION_STOP.equals(action)) {
            stopWithStatus("截屏已停止");
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_PAUSE.equals(action)) {
            synchronized (processingLock) {
                if (stopping || nativeSession == 0) {
                    stopSelfResult(startId);
                    return START_NOT_STICKY;
                }
                paused = !paused;
                NativeBridge.nativeReset(nativeSession);
            }
            refreshNotification();
            return START_NOT_STICKY;
        }
        beginCaptureAttempt(startId);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == Activity.RESULT_OK && resultData != null) {
            startCapture(resultCode, resultData);
        }
        else stopWithStatus("截屏授权无效，请重新授权");
        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent resultData) {
        synchronized (processingLock) {
            try {
                // startForegroundService() has a short system deadline. Enter
                // foreground state before loading the model or preparing audio.
                startForeground(NOTIFICATION_ID, notification("正在准备截屏"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                if (cuePlayer == null) cuePlayer = new CuePlayer(this);
                GameProfile profile = GameProfile.load(this);
                profileName = profile.name + " · " + profile.version;
                maxObservationAgeMs = profile.eventInts[0];
                GameProfile.TemplateData enemy = profile.enemyTemplate;
                GameProfile.TemplateData ping = profile.pingTemplate;
                nativeSession = NativeBridge.nativeCreate(
                        getAssets(),
                        profile.rois, profile.flags, profile.tuning,
                        profile.eventInts, profile.minConfidence,
                        enemy == null ? null : enemy.rgba,
                        enemy == null ? 0 : enemy.width,
                        enemy == null ? 0 : enemy.height,
                        ping == null ? null : ping.rgba,
                        ping == null ? 0 : ping.width,
                        ping == null ? 0 : ping.height,
                        profile.minimapYolox, profile.yoloxInputSize,
                        profile.yoloxConfidence, profile.yoloxNms);
                if (nativeSession == 0) throw new IllegalStateException("Native recognizer rejected profile");

                // Android 14+ requires the mediaProjection foreground type before
                // obtaining the one-use token from the consent result.
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                projection = manager.getMediaProjection(resultCode, resultData);
                if (projection == null) throw new IllegalStateException("Screen capture was not granted");
                MediaProjection currentProjection = projection;
                projectionCallback = new MediaProjection.Callback() {
                    @Override public void onStop() {
                        int stopThroughStartId;
                        synchronized (processingLock) {
                            if (stopping || projection != currentProjection) return;
                            stopping = true;
                            stopThroughStartId = latestServiceStartId;
                        }
                        Log.i(TAG, "MediaProjection ended by Android or the user");
                        GameProfile.settings(CaptureService.this).edit()
                                .putString("last_capture_status", "系统截屏授权已结束").apply();
                        // Do not let a delayed callback from an older capture
                        // session stop a newer start command.
                        stopSelfResult(stopThroughStartId);
                    }
                };
                projection.registerCallback(projectionCallback, worker);
                Point size = screenSize();
                createReader(size.x, size.y);
                virtualDisplay = projection.createVirtualDisplay(
                        "MapAssistCapture", frameWidth, frameHeight,
                        getResources().getDisplayMetrics().densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, worker);
                worker.postDelayed(displayWatchdog, DISPLAY_CHECK_PERIOD_MS);
                refreshNotification();
            } catch (IOException | JSONException | RuntimeException error) {
                Log.e(TAG, "Could not start local capture", error);
                stopWithStatus("无法开始截屏：" + error.getMessage());
            }
        }
    }

    private Point screenSize() {
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Point size = new Point();
        if (display != null) display.getRealSize(size);
        if (size.x < 1 || size.y < 1) {
            size.x = getResources().getDisplayMetrics().widthPixels;
            size.y = getResources().getDisplayMetrics().heightPixels;
        }
        return size;
    }

    private void createReader(int width, int height) {
        frameWidth = width;
        frameHeight = height;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImageAvailable, worker);
    }

    private void resizeIfNeeded() {
        Point current = screenSize();
        if (current.x == frameWidth && current.y == frameHeight) return;
        if (virtualDisplay != null) virtualDisplay.setSurface(null);
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null);
            reader.close();
        }
        createReader(current.x, current.y);
        if (virtualDisplay != null) {
            virtualDisplay.resize(frameWidth, frameHeight,
                    getResources().getDisplayMetrics().densityDpi);
            virtualDisplay.setSurface(reader.getSurface());
        }
        if (nativeSession != 0) NativeBridge.nativeReset(nativeSession);
        blackSinceMs = 0;
        Log.i(TAG, "Capture resized to " + frameWidth + "x" + frameHeight);
    }

    private void onImageAvailable(ImageReader source) {
        Image image = null;
        boolean resizeAfterClose = false;
        boolean refreshAfterFrame = false;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            final long now = SystemClock.elapsedRealtime();
            synchronized (processingLock) {
                if (stopping || source != reader) return;
                if (now - lastProcessedAtMs < FRAME_PERIOD_MS) return;
                lastProcessedAtMs = now;
            }
            Image.Plane plane = image.getPlanes()[0];
            if (plane.getPixelStride() != 4) {
                Log.e(TAG, "Unexpected RGBA pixel stride: " + plane.getPixelStride());
                return;
            }
            ByteBuffer pixels = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            boolean landscape = width > height;
            long observedAtMs = now;
            long frameTimestampNs = image.getTimestamp();
            long ageNs = System.nanoTime() - frameTimestampNs;
            if (frameTimestampNs > 0 && ageNs >= 0 && ageNs < 2_000_000_000L) {
                observedAtMs = now - ageNs / 1_000_000L;
            }
            boolean blackFrame = landscape
                    && allBlack(pixels, width, height, plane.getRowStride());
            synchronized (processingLock) {
                // A newer ACTION_START can replace the reader while this
                // callback is inspecting an already acquired old frame.
                if (stopping || source != reader) return;
                processedFrames++;
                resizeAfterClose = processedFrames % 12 == 0;
                if (!landscape || !blackFrame) {
                    blackSinceMs = 0;
                } else if (blackSinceMs == 0) {
                    blackSinceMs = now;
                }
                if (landscape && now - startedAtMs > BLACK_FRAME_GRACE_MS
                        && blackSinceMs > 0 && now - blackSinceMs >= BLACK_STOP_AFTER_MS) {
                    Log.w(TAG, "Capture is consistently black; protected or unavailable content");
                    stopWithStatus("画面持续黑屏，采集已停止；可能是受保护内容或系统限制");
                    return;
                }
                if (landscape != lastFrameLandscape && nativeSession != 0) {
                    NativeBridge.nativeReset(nativeSession);
                    if (!landscape) latestNativeMicros = 0;
                }
                lastFrameLandscape = landscape;
                if (landscape && !paused && nativeSession != 0) {
                    long processingAtMs = SystemClock.elapsedRealtime();
                    recordLandscapeProcessedFrameLocked(processingAtMs);
                    int[] result = NativeBridge.nativeProcess(nativeSession, pixels,
                            width, height, plane.getRowStride(), observedAtMs, now);
                    if (result != null && result.length == 5) {
                        if (result[3] < 0) Log.e(TAG, "Invalid direct image buffer");
                        latestNativeMicros = result[4];
                        if (result[0] > 0) {
                            String cueId = auditSessionId + ":" + nextCueId++;
                            detectedCues++;
                            long frameAgeMs = SystemClock.elapsedRealtime() - observedAtMs;
                            boolean stale = frameAgeMs > maxObservationAgeMs;
                            boolean audioQueued = false;
                            if (stale) {
                                staleCues++;
                            } else {
                                audioQueued = cuePlayer.play(
                                        result[0], result[1],
                                        observedAtMs + maxObservationAgeMs);
                                if (audioQueued) queuedCues++;
                                else audioQueueFailures++;
                            }
                            Log.i(TAG, "CueEvent sessionId=" + auditSessionId
                                    + " cueId=" + cueId
                                    + " kind=" + result[0]
                                    + " direction=" + result[1]
                                    + " observedAtMs=" + observedAtMs
                                    + " frameAgeMs=" + frameAgeMs
                                    + " nativeMicros=" + latestNativeMicros
                                    + " stale=" + stale
                                    + " audioQueued=" + audioQueued);
                        }
                    }
                }
                if (now - lastNotificationAtMs > 5000) {
                    lastNotificationAtMs = now;
                    refreshAfterFrame = true;
                }
            }
            if (refreshAfterFrame) {
                synchronized (processingLock) {
                    if (!stopping && source == reader) refreshNotification();
                }
            }
        } catch (IllegalStateException error) {
            Log.w(TAG, "A stale ImageReader frame was dropped", error);
        } finally {
            if (image != null) image.close();
            if (resizeAfterClose) {
                try {
                    synchronized (processingLock) {
                        if (!stopping && source == reader) resizeIfNeeded();
                    }
                } catch (RuntimeException error) {
                    Log.e(TAG, "Could not resize capture after rotation", error);
                    stopWithStatus("横屏切换后无法继续截屏");
                }
            }
        }
    }

    private boolean allBlack(ByteBuffer pixels, int width, int height, int rowStride) {
        int inspected = 0;
        int bright = 0;
        for (int gy = 1; gy <= 8; gy++) {
            int y = gy * height / 9;
            for (int gx = 1; gx <= 8; gx++) {
                int x = gx * width / 9;
                int index = y * rowStride + x * 4;
                if (index + 2 >= pixels.limit()) continue;
                int intensity = (pixels.get(index) & 255)
                        + (pixels.get(index + 1) & 255)
                        + (pixels.get(index + 2) & 255);
                inspected++;
                if (intensity > 18) bright++;
            }
        }
        return inspected > 0 && bright == 0;
    }

    private Notification notification(String state) {
        PendingIntent pause = PendingIntent.getService(this, 1,
                actionIntent(ACTION_TOGGLE_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                actionIntent(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("地图感知助手 · " + state)
                .setContentText(profileName + " · 已采样 " + processedFrames
                        + " 帧 · 音频排队 " + queuedCues + "/检测 " + detectedCues
                        + (staleCues == 0 ? "" : " · 过期 " + staleCues)
                        + (audioQueueFailures == 0 ? "" : " · 音频失败 " + audioQueueFailures)
                        + " · 识别 " + latestNativeMicros / 1000 + " ms")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, paused ? "继续" : "暂停", pause)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
                .build();
    }

    private Intent actionIntent(String action) {
        Intent intent = new Intent(this, CaptureService.class);
        intent.setAction(action);
        return intent;
    }

    private void refreshNotification() {
        notificationManager.notify(NOTIFICATION_ID,
                notification(paused ? "提示已暂停" :
                        frameWidth <= frameHeight ? "等待横屏" : "正在处理画面"));
    }

    private void beginCaptureAttempt(int startId) {
        synchronized (processingLock) {
            finishAuditSessionLocked("restarted");
            releaseCapture();
            if (cuePlayer != null) {
                cuePlayer.close();
                cuePlayer = null;
            }
            stopping = false;
            paused = false;
            startedAtMs = SystemClock.elapsedRealtime();
            lastProcessedAtMs = 0;
            lastNotificationAtMs = 0;
            blackSinceMs = 0;
            processedFrames = 0;
            detectedCues = 0;
            queuedCues = 0;
            staleCues = 0;
            audioQueueFailures = 0;
            latestNativeMicros = 0;
            lastFrameLandscape = false;
            profileName = "";
            auditSessionId = UUID.randomUUID().toString();
            auditSessionStartedAtMs = startedAtMs;
            nextCueId = 1;
            landscapeProcessedFrames = 0;
            firstLandscapeProcessedAtMs = -1;
            lastLandscapeProcessedAtMs = -1;
            maxLandscapeProcessedGapMs = 0;
            auditSessionActive = true;
            GameProfile.settings(this).edit().remove("last_capture_status").apply();
            Log.i(TAG, "SessionStart sessionId=" + auditSessionId
                    + " startId=" + startId
                    + " startedElapsedRealtimeMs=" + auditSessionStartedAtMs);
        }
    }

    private void recordLandscapeProcessedFrameLocked(long processedAtMs) {
        if (firstLandscapeProcessedAtMs < 0) {
            firstLandscapeProcessedAtMs = processedAtMs;
        } else {
            maxLandscapeProcessedGapMs = Math.max(maxLandscapeProcessedGapMs,
                    processedAtMs - lastLandscapeProcessedAtMs);
        }
        lastLandscapeProcessedAtMs = processedAtMs;
        landscapeProcessedFrames++;
    }

    private void finishAuditSessionLocked(String reason) {
        if (!auditSessionActive) return;
        long endedAtMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "SessionSummary sessionId=" + auditSessionId
                + " durationMs=" + Math.max(0, endedAtMs - auditSessionStartedAtMs)
                + " processedFrames=" + processedFrames
                + " landscapeProcessedFrames=" + landscapeProcessedFrames
                + " firstProcessedElapsedRealtimeMs=" + firstLandscapeProcessedAtMs
                + " lastProcessedElapsedRealtimeMs=" + lastLandscapeProcessedAtMs
                + " maxProcessedGapMs=" + maxLandscapeProcessedGapMs
                + " detected=" + detectedCues
                + " queued=" + queuedCues
                + " stale=" + staleCues
                + " audioFailures=" + audioQueueFailures
                + " reason=" + reason);
        auditSessionActive = false;
    }

    private void stopWithStatus(String status) {
        int stopThroughStartId;
        synchronized (processingLock) {
            if (!stopping) {
                stopping = true;
                // Keep the first reason, but never let it prevent a later
                // service command from completing shutdown.
                GameProfile.settings(this).edit()
                        .putString("last_capture_status", status).apply();
            }
            stopThroughStartId = latestServiceStartId;
        }
        if (stopThroughStartId > 0) stopSelfResult(stopThroughStartId);
        else stopSelf();
    }

    private void releaseCapture() {
        stopping = true;
        if (worker != null) worker.removeCallbacks(displayWatchdog);
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null);
        }
        if (virtualDisplay != null) {
            virtualDisplay.setSurface(null);
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (projection != null) {
            if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
            projectionCallback = null;
        }
        if (nativeSession != 0) {
            NativeBridge.nativeDestroy(nativeSession);
            nativeSession = 0;
        }
    }

    @Override
    public void onDestroy() {
        synchronized (processingLock) {
            finishAuditSessionLocked("destroyed");
            releaseCapture();
        }
        // stopSelf() normally removes the notification with the service, but
        // make the foreground-service lifecycle explicit for projection and
        // system initiated stops as well.
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (cuePlayer != null) cuePlayer.close();
        workerThread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
