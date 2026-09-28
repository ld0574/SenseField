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
    private static final int MARKER_OFFSET = 12;
    // Keep event decoding aligned with the native marker record, which also
    // carries a track id after the transition event.
    private static final int MARKER_STRIDE = MinimapOverlay.MARKER_STRIDE;

    private final Object processingLock = new Object();
    private HandlerThread workerThread;
    private Handler worker;
    private NotificationManager notificationManager;
    private CuePlayer cuePlayer;
    private CueDispatcher cueDispatcher;
    private CueSettings cueSettings;
    private MinimapOverlay minimapOverlay;
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
    private int latestLocatorState = -1;
    private int latestLocatorScoreMilli;
    private int lastLoggedLocatorState = Integer.MIN_VALUE;
    private int maxObservationAgeMs;
    private int frameWidth;
    private int frameHeight;
    private boolean lastFrameLandscape;
    private boolean visionMemoryEnabled;
    private String auditSessionId;
    private long auditSessionStartedAtMs;
    private long nextCueId;
    private int landscapeProcessedFrames;
    private long firstLandscapeProcessedAtMs;
    private long lastLandscapeProcessedAtMs;
    private long maxLandscapeProcessedGapMs;
    private boolean auditSessionActive;
    private int latestServiceStartId;
    private final CaptureHealthMonitor captureHealth = new CaptureHealthMonitor();
    private int readerGeneration;
    private int starvationCount;
    private int recoveryAttempts;
    private int recoverySuccesses;
    private Runnable recoveryRunnable;
    private Runnable displayWatchdog;
    private long captureGeneration;
    private long nativeResetGeneration;

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

    /** Schedule a watchdog tied to one capture session. Must be called under processingLock. */
    private void scheduleDisplayWatchdogLocked(final long expectedSessionGeneration) {
        if (worker == null) return;
        final Runnable watchdog = new Runnable() {
            @Override public void run() {
                synchronized (processingLock) {
                    if (stopping || expectedSessionGeneration != captureGeneration
                            || projection == null || reader == null) return;
                    try {
                        // Rotation can stop frame callbacks before the old reader
                        // reaches its next periodic size check.
                        resizeIfNeeded();
                        if (captureHealth.check(SystemClock.elapsedRealtime()) ==
                                CaptureHealthMonitor.State.STARVED) {
                            starvationCount++;
                            scheduleRecoveryLocked(expectedSessionGeneration);
                        }
                    } catch (RuntimeException error) {
                        Log.e(TAG, "Could not resize capture after display change", error);
                        stopWithStatus("横屏切换后无法继续截屏");
                        return;
                    }
                    // Keep the post inside the same lock as the validity check.
                    // releaseCapture() removes this callback under that lock, so
                    // an old watchdog cannot reattach itself to a new session.
                    if (!stopping && expectedSessionGeneration == captureGeneration
                            && worker != null) {
                        worker.postDelayed(this, DISPLAY_CHECK_PERIOD_MS);
                    }
                }
            }
        };
        displayWatchdog = watchdog;
        worker.postDelayed(watchdog, DISPLAY_CHECK_PERIOD_MS);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (!ACTION_START.equals(action) && !ACTION_STOP.equals(action)
                && !ACTION_TOGGLE_PAUSE.equals(action)) return START_NOT_STICKY;
        long expectedSessionGeneration = 0;
        synchronized (processingLock) {
            latestServiceStartId = startId;
            if (ACTION_START.equals(action)) {
                // Invalidate callbacks from the previous projection before
                // releasing the lock to prepare the replacement session.
                expectedSessionGeneration = ++captureGeneration;
            }
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
                resetNativeLocked();
                captureHealth.pause(paused, SystemClock.elapsedRealtime());
                if (paused && minimapOverlay != null) minimapOverlay.clear();
                if (cueDispatcher != null) {
                    if (paused) cueDispatcher.pause();
                    else cueDispatcher.resume();
                }
            }
            refreshNotification();
            return START_NOT_STICKY;
        }
        beginCaptureAttempt(startId, expectedSessionGeneration);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == Activity.RESULT_OK && resultData != null) {
            startCapture(resultCode, resultData, expectedSessionGeneration);
        }
        else stopWithStatus("截屏授权无效，请重新授权");
        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent resultData,
                              long expectedSessionGeneration) {
        synchronized (processingLock) {
            if (stopping || expectedSessionGeneration != captureGeneration) return;
            try {
                // startForegroundService() has a short system deadline. Enter
                // foreground state before loading the model or preparing audio.
                startForeground(NOTIFICATION_ID, notification("正在准备截屏"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                if (cuePlayer == null) cuePlayer = new CuePlayer(this);
                cueSettings = new CueSettings(this);
                cueDispatcher = new CueDispatcher(cuePlayer, cueSettings,
                        new CueAuditListener(), SystemClock::elapsedRealtime);
                GameProfile profile = GameProfile.load(this);
                profileName = profile.name + " · " + profile.version;
                maxObservationAgeMs = profile.eventInts[0];
                visionMemoryEnabled = GameProfile.settings(this)
                        .getBoolean("vision_memory", false);
                if (visionMemoryEnabled &&
                        cueSettings.categoryEnabled(CueRequest.Category.VISION_MEMORY) &&
                        (cueSettings.enabledChannels() & CueRequest.CHANNEL_VISUAL) != 0) {
                    minimapOverlay = MinimapOverlay.createIfAllowed(this);
                }
                GameProfile.TemplateData enemy = profile.enemyTemplate;
                GameProfile.TemplateData ping = profile.pingTemplate;
                GameProfile.PlayerLifeData playerLife = profile.playerLife;
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
                        profile.yoloxConfidence, profile.yoloxNms,
                        profile.minimapLocatorEnabled,
                        profile.minimapLocatorFloats,
                        profile.minimapLocatorInts,
                        profile.minimapLocatorDescriptor,
                        playerLife != null,
                        playerLife == null ? null : playerLife.roiAndThresholds,
                        playerLife == null ? 0 : playerLife.maxDhashDistance,
                        playerLife == null ? null : playerLife.hashes,
                        playerLife == null ? null : playerLife.states,
                        playerLife == null ? null : playerLife.luma,
                        playerLife == null ? null : playerLife.chroma);
                if (nativeSession == 0) throw new IllegalStateException("Native recognizer rejected profile");

                // Android 14+ requires the mediaProjection foreground type before
                // obtaining the one-use token from the consent result.
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                projection = manager.getMediaProjection(resultCode, resultData);
                if (projection == null) throw new IllegalStateException("Screen capture was not granted");
                MediaProjection currentProjection = projection;
                final long sessionGeneration = expectedSessionGeneration;
                projectionCallback = new MediaProjection.Callback() {
                    @Override public void onStop() {
                        int stopThroughStartId;
                        synchronized (processingLock) {
                            if (stopping || projection != currentProjection
                                    || sessionGeneration != captureGeneration) return;
                            captureHealth.revoke();
                            logCaptureHealth("REVOKED", "projection_stopped");
                            dispatchSystemCue("CAPTURE_REVOKED", "截屏授权已结束",
                                    100, 2000, CueRequest.CHANNEL_SPEECH |
                                            CueRequest.CHANNEL_HAPTIC);
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
                captureHealth.start(SystemClock.elapsedRealtime());
                scheduleDisplayWatchdogLocked(sessionGeneration);
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
        int generation = ++readerGeneration;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(source -> onImageAvailable(source, generation), worker);
    }

    /** Reset native tracking while recording the generation used for cue dedupe. */
    private void resetNativeLocked() {
        if (nativeSession == 0) return;
        NativeBridge.nativeReset(nativeSession);
        nativeResetGeneration++;
    }

    private void resizeIfNeeded() {
        Point current = screenSize();
        if (current.x == frameWidth && current.y == frameHeight) return;
        boolean recoveryPending =
                captureHealth.state() == CaptureHealthMonitor.State.RECOVERING;
        if (recoveryRunnable != null) {
            if (worker != null) worker.removeCallbacks(recoveryRunnable);
            recoveryRunnable = null;
        }
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
        resetNativeLocked();
        if (minimapOverlay != null) minimapOverlay.clear();
        blackSinceMs = 0;
        if (recoveryPending)
            captureHealth.recoveryRebuilt(SystemClock.elapsedRealtime());
        Log.i(TAG, "Capture resized to " + frameWidth + "x" + frameHeight);
    }

    private void scheduleRecoveryLocked(long expectedSessionGeneration) {
        long delay = captureHealth.beginRecovery();
        if (delay < 0) {
            logCaptureHealth("FAILED", "attempts_exhausted");
            dispatchSystemCue("CAPTURE_FAILED", "截屏恢复失败，请重新授权",
                    100, 2000, CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC);
            stopWithStatus("截屏画面中断且恢复失败，请重新授权");
            return;
        }
        recoveryAttempts++;
        logCaptureHealth("RECOVERING", "frame_starvation");
        refreshNotification();
        final int expectedGeneration = readerGeneration;
        final long expectedCaptureGeneration = expectedSessionGeneration;
        recoveryRunnable = () -> {
            synchronized (processingLock) {
                if (stopping || projection == null || virtualDisplay == null ||
                        expectedCaptureGeneration != captureGeneration ||
                        captureHealth.state() != CaptureHealthMonitor.State.RECOVERING ||
                        expectedGeneration != readerGeneration) return;
                try {
                    virtualDisplay.setSurface(null);
                    if (reader != null) {
                        reader.setOnImageAvailableListener(null, null);
                        reader.close();
                    }
                    Point size = screenSize();
                    createReader(size.x, size.y);
                    virtualDisplay.resize(frameWidth, frameHeight,
                            getResources().getDisplayMetrics().densityDpi);
                    virtualDisplay.setSurface(reader.getSurface());
                    resetNativeLocked();
                    if (minimapOverlay != null) minimapOverlay.clear();
                    blackSinceMs = 0;
                    captureHealth.recoveryRebuilt(SystemClock.elapsedRealtime());
                    logCaptureHealth(captureHealth.state().name(), "reader_rebuilt_waiting_for_frame");
                    refreshNotification();
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not rebuild capture reader", error);
                    captureHealth.recoveryFailed();
                    logCaptureHealth(captureHealth.state().name(), "reader_rebuild_failed");
                    if (captureHealth.state() == CaptureHealthMonitor.State.FAILED) {
                        dispatchSystemCue("CAPTURE_FAILED", "截屏恢复失败，请重新授权",
                                100, 2000, CueRequest.CHANNEL_SPEECH |
                                        CueRequest.CHANNEL_HAPTIC);
                        stopWithStatus("截屏画面中断且恢复失败，请重新授权");
                    }
                }
            }
        };
        worker.postDelayed(recoveryRunnable, delay);
    }

    private void logCaptureHealth(String state, String reason) {
        long now = SystemClock.elapsedRealtime();
        long last = captureHealth.lastFrameAtMs();
        long lastProcessed = captureHealth.lastProcessedAtMs();
        Log.i(TAG, "CaptureHealth sessionId=" + auditSessionId
                + " state=" + state
                + " reason=" + reason
                + " attempt=" + captureHealth.attempts()
                + " readerGeneration=" + readerGeneration
                + " elapsedSinceFrameMs=" + (last < 0 ? -1 : Math.max(0, now - last))
                + " elapsedSinceProcessedMs=" + (lastProcessed < 0 ? -1 :
                        Math.max(0, now - lastProcessed)));
    }

    private void onImageAvailable(ImageReader source, int generation) {
        Image image = null;
        boolean resizeAfterClose = false;
        boolean refreshAfterFrame = false;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            final long now = SystemClock.elapsedRealtime();
            synchronized (processingLock) {
                if (stopping || source != reader || generation != readerGeneration) return;
                CaptureHealthMonitor.State previous = captureHealth.state();
                captureHealth.frameArrived(now);
                if (previous == CaptureHealthMonitor.State.RECOVERING ||
                        previous == CaptureHealthMonitor.State.STARVED) {
                    recoverySuccesses++;
                    logCaptureHealth("HEALTHY", "frame_resumed");
                    dispatchSystemCue("CAPTURE_RECOVERED", "截屏已恢复",
                            20, 3000, CueRequest.CHANNEL_SPEECH);
                    refreshNotification();
                }
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
                if (stopping || source != reader || generation != readerGeneration) return;
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
                    resetNativeLocked();
                    if (minimapOverlay != null) minimapOverlay.clear();
                    if (!landscape) latestNativeMicros = 0;
                }
                lastFrameLandscape = landscape;
                if (landscape && !paused && nativeSession != 0) {
                    long processingAtMs = SystemClock.elapsedRealtime();
                    captureHealth.frameProcessed(processingAtMs);
                    recordLandscapeProcessedFrameLocked(processingAtMs);
                    int[] result = NativeBridge.nativeProcess(nativeSession, pixels,
                            width, height, plane.getRowStride(), observedAtMs, now);
                    if (result != null && result.length >= 5) {
                        if (result[3] < 0) Log.e(TAG, "Invalid direct image buffer");
                        latestNativeMicros = result[4];
                        if (result.length >= 11) {
                            latestLocatorState = result[5];
                            latestLocatorScoreMilli = result[6];
                            if (latestLocatorState != lastLoggedLocatorState) {
                                lastLoggedLocatorState = latestLocatorState;
                                Log.i(TAG, "MinimapLayout sessionId=" + auditSessionId
                                        + " state=" + locatorStateName(latestLocatorState)
                                        + " scoreMilli=" + latestLocatorScoreMilli
                                        + " roiPpm=" + result[7] + "," + result[8]
                                        + "," + result[9] + "," + result[10]);
                            }
                        }
                        if (minimapOverlay != null && result.length >= 12) {
                            minimapOverlay.update(result);
                        }
                        if (visionMemoryEnabled)
                            playVisionMemoryTransitions(result, observedAtMs);
                        // Minimap APPEAR is dispatched by the marker path, which
                        // carries the stable track id needed for exact deduplication.
                        if (result[0] > 0 && (result[0] != 2 || !visionMemoryEnabled))
                            dispatchNativeCue(result[0], result[1], result[2], observedAtMs);
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
                        if (!stopping && source == reader && generation == readerGeneration)
                            resizeIfNeeded();
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

    private void playVisionMemoryTransitions(int[] result, long observedAtMs) {
        if (cueDispatcher == null ||
                !CueSettings.visionMemoryDispatchEnabled(visionMemoryEnabled,
                        cueSettings != null && cueSettings.categoryEnabled(
                                CueRequest.Category.VISION_MEMORY)) ||
                result.length < MARKER_OFFSET || result[11] <= 0) return;
        int count = Math.min(8, result[11]);
        for (int index = 0; index < count; index++) {
            int base = MARKER_OFFSET + index * MARKER_STRIDE;
            if (base + MARKER_STRIDE > result.length) break;
            int event = result[base + 7];
            if (event == 0) continue;
            int position = minimapPosition(
                    result[base + 2] + result[base + 4] / 2,
                    result[base + 3] + result[base + 5] / 2,
                    result[7], result[8], result[9], result[10]);
            int movement = result[base + 1];
            int direction = movement != 0 ? movement : cardinalForPosition(position);
            int trackId = result[base + 8];
            int channels = minimapOverlay == null ? 0 : CueRequest.CHANNEL_VISUAL;
            String speech = null;
            int priority;
            long ttl;
            if (event == 1) {
                channels |= CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC;
                if (cueSettings != null && cueSettings.speakAppear()) {
                    channels |= CueRequest.CHANNEL_SPEECH;
                    speech = positionText(position) + "敌人出现";
                }
                priority = 40;
                ttl = 800;
            } else {
                channels |= CueRequest.CHANNEL_HAPTIC;
                if (movement != 0) {
                    channels |= CueRequest.CHANNEL_SPEECH;
                    speech = positionText(position) + "敌人消失，"
                            + movementText(movement) + "移动";
                }
                priority = 60;
                ttl = 1500;
            }
            String cueId = auditSessionId + ":" + nextCueId++;
            CueRequest request = new CueRequest(auditSessionId, cueId,
                    CueEventKeys.visionMemory(nativeResetGeneration, trackId, event),
                    event == 1 ? "VISION_APPEAR" : "VISION_DISAPPEAR",
                    CueRequest.Category.VISION_MEMORY, priority, observedAtMs,
                    observedAtMs + ttl, channels, 2, direction, direction, speech);
            CueDispatcher.DispatchResult dispatched = cueDispatcher.submit(request);
            Log.i(TAG, "VisionMemoryEvent sessionId=" + auditSessionId
                    + " event=" + (event == 1 ? "APPEAR" : "DISAPPEAR")
                    + " position=" + position
                    + " trackId=" + trackId
                    + " movement=" + movement
                    + " ageMs=" + result[base + 6]
                    + " feedbackQueued=" + dispatched.audioQueued());
        }
    }

    private void dispatchNativeCue(int kind, int direction, int nativePriority,
                                   long observedAtMs) {
        if (cueDispatcher == null) return;
        CueRequest.Category category = kind == 3
                ? CueRequest.Category.DANGER
                : kind >= 4 ? CueRequest.Category.PLAYER_STATE
                : CueRequest.Category.VISION_MEMORY;
        int priority = kind == 4 ? 100 : kind == 5 ? 60
                : kind == 3 ? 80 : Math.max(40, nativePriority);
        long ttl = kind == 4 ? 2000 : kind == 5 ? 2500
                : kind == 3 ? 1200 : maxObservationAgeMs;
        int channels;
        String speech = null;
        if (kind == 4) {
            channels = CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC;
            speech = "你已阵亡";
        } else if (kind == 5) {
            channels = CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC;
            speech = "你已复活";
        } else {
            channels = CueRequest.CHANNEL_TONE;
            if (kind == 3) {
                channels |= CueRequest.CHANNEL_SPEECH;
                speech = "危险信号";
            } else if (direction == 3 || direction == 4) {
                channels |= CueRequest.CHANNEL_SPEECH;
                speech = (direction == 3 ? "上方" : "下方") + "有敌人";
            }
        }
        String cueId = auditSessionId + ":" + nextCueId++;
        CueRequest request = new CueRequest(auditSessionId, cueId,
                CueEventKeys.nativeCue(nativeResetGeneration, kind, direction),
                kindName(kind), category, priority, observedAtMs, observedAtMs + ttl,
                channels, kind, direction, direction, speech);
        long frameAgeMs = Math.max(0, SystemClock.elapsedRealtime() - observedAtMs);
        boolean stale = SystemClock.elapsedRealtime() > request.expiresAtMs;
        CueDispatcher.DispatchResult dispatched = cueDispatcher.submit(request);
        detectedCues++;
        if (stale) staleCues++;
        else if (dispatched.audioQueued()) queuedCues++;
        else audioQueueFailures++;
        Log.i(TAG, "CueEvent sessionId=" + auditSessionId
                + " cueId=" + cueId
                + " kind=" + kind
                + " direction=" + direction
                + " observedAtMs=" + observedAtMs
                + " frameAgeMs=" + frameAgeMs
                + " nativeMicros=" + latestNativeMicros
                + " stale=" + stale
                + " audioQueued=" + dispatched.audioQueued());
    }

    private void dispatchSystemCue(String kind, String speech, int priority,
                                   long ttl, int channels) {
        if (cueDispatcher == null || auditSessionId == null) return;
        long now = SystemClock.elapsedRealtime();
        String cueId = auditSessionId + ":" + nextCueId++;
        cueDispatcher.submit(new CueRequest(auditSessionId, cueId,
                "system:" + kind, kind, CueRequest.Category.SYSTEM, priority,
                now, now + ttl, channels, 0, 0, 0, speech));
    }

    private final class CueAuditListener implements CueDispatcher.Listener {
        @Override public void onDispatch(CueRequest request,
                                         CueDispatcher.DispatchResult result) {
            Log.i(TAG, "CueDispatch sessionId=" + request.sessionId
                    + " cueId=" + request.cueId
                    + " eventKey=" + request.eventKey
                    + " kind=" + request.kind
                    + " category=" + request.category
                    + " priority=" + request.priority
                    + " createdAtMs=" + request.createdAtMs
                    + " expiresAtMs=" + request.expiresAtMs
                    + " requestedMask=" + request.requestedChannels
                    + " acceptedMask=" + result.acceptedChannels
                    + " outcome=" + result.outcome
                    + " dropReason=" + result.reason);
        }

        @Override public void onPlayback(CueRequest request, String channel,
                                         long atMs, String result) {
            Log.i(TAG, "CuePlayback sessionId=" + request.sessionId
                    + " cueId=" + request.cueId
                    + " channel=" + channel
                    + " atMs=" + atMs
                    + " result=" + result);
        }
    }

    private static String kindName(int kind) {
        if (kind == 1) return "MAIN_ENEMY";
        if (kind == 2) return "MINIMAP_ENEMY";
        if (kind == 3) return "DANGER_PING";
        if (kind == 4) return "PLAYER_DEAD";
        if (kind == 5) return "PLAYER_ALIVE";
        return "UNKNOWN";
    }

    private static int cardinalForPosition(int position) {
        if (position == 1 || position == 5 || position == 7) return 1;
        if (position == 2 || position == 6 || position == 8) return 2;
        if (position == 3) return 3;
        if (position == 4) return 4;
        return 0;
    }

    private static String positionText(int position) {
        if (position == 1) return "左侧";
        if (position == 2) return "右侧";
        if (position == 3) return "上方";
        if (position == 4) return "下方";
        if (position == 5) return "左上";
        if (position == 6) return "右上";
        if (position == 7) return "左下";
        if (position == 8) return "右下";
        return "附近";
    }

    private static String movementText(int direction) {
        if (direction == 1) return "向左";
        if (direction == 2) return "向右";
        if (direction == 3) return "向上";
        if (direction == 4) return "向下";
        return "";
    }

    private static int minimapPosition(int x, int y, int roiX, int roiY,
                                       int roiWidth, int roiHeight) {
        if (roiWidth <= 0 || roiHeight <= 0) return 0;
        float dx = (x - (roiX + roiWidth * 0.5f)) / (roiWidth * 0.5f);
        float dy = (y - (roiY + roiHeight * 0.5f)) / (roiHeight * 0.5f);
        if (Math.abs(dx) < 0.18f && Math.abs(dy) < 0.18f) return 0;
        if (Math.abs(dx) < Math.abs(dy) * 0.45f) return dy < 0 ? 3 : 4;
        if (Math.abs(dy) < Math.abs(dx) * 0.45f) return dx < 0 ? 1 : 2;
        if (dx < 0) return dy < 0 ? 5 : 7;
        return dy < 0 ? 6 : 8;
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
                        + (latestLocatorState < 0 ? "" : " · 地图"
                        + locatorStateName(latestLocatorState)
                        + locatorScoreText(latestLocatorScoreMilli))
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
        String healthState = captureHealth.state() == CaptureHealthMonitor.State.RECOVERING
                ? "恢复截屏 " + captureHealth.attempts() + "/3"
                : captureHealth.state() == CaptureHealthMonitor.State.STARVED
                ? "等待画面恢复" : null;
        notificationManager.notify(NOTIFICATION_ID,
                notification(healthState != null ? healthState : paused ? "提示已暂停" :
                        frameWidth <= frameHeight ? "等待横屏" : "正在处理画面"));
    }

    private void beginCaptureAttempt(int startId, long expectedSessionGeneration) {
        synchronized (processingLock) {
            if (expectedSessionGeneration != captureGeneration) return;
            finishAuditSessionLocked("restarted");
            releaseCapture();
            if (cuePlayer != null) {
                if (cueDispatcher != null) {
                    cueDispatcher.close();
                    cueDispatcher = null;
                }
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
            starvationCount = 0;
            recoveryAttempts = 0;
            recoverySuccesses = 0;
            readerGeneration = 0;
            nativeResetGeneration = 0;
            latestNativeMicros = 0;
            latestLocatorState = -1;
            latestLocatorScoreMilli = 0;
            lastLoggedLocatorState = Integer.MIN_VALUE;
            lastFrameLandscape = false;
            visionMemoryEnabled = false;
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
                + " starvationCount=" + starvationCount
                + " recoveryAttempts=" + recoveryAttempts
                + " recoverySuccesses=" + recoverySuccesses
                + " locatorState=" + locatorStateName(latestLocatorState)
                + " locatorScoreMilli=" + latestLocatorScoreMilli
                + " reason=" + reason);
        auditSessionActive = false;
    }

    private static String locatorStateName(int state) {
        if (state == 0) return "搜索中";
        if (state == 1) return "已锁定";
        if (state == 2) return "短暂保持";
        return "未启用";
    }

    private static String locatorScoreText(int scoreMilli) {
        // Native uses -2 as the no-candidate sentinel. Keep that internal
        // instead of showing a confusing “-200%” in the notification.
        if (scoreMilli < -1000) return "";
        return " " + Math.round(scoreMilli / 10f) + "%";
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
        if (cueDispatcher != null) {
            cueDispatcher.close();
            cueDispatcher = null;
        }
        if (minimapOverlay != null) {
            minimapOverlay.close();
            minimapOverlay = null;
        }
        if (worker != null && displayWatchdog != null) {
            worker.removeCallbacks(displayWatchdog);
            displayWatchdog = null;
        }
        if (worker != null && recoveryRunnable != null) {
            worker.removeCallbacks(recoveryRunnable);
            recoveryRunnable = null;
        }
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
