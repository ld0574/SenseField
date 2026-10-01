package com.openkhub.sensefield;

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
import java.util.List;
import java.util.UUID;

public final class CaptureService extends Service {
    private static volatile boolean running;
    static boolean isRunning() { return running; }
    static final String ACTION_START = "com.openkhub.sensefield.START";
    static final String ACTION_TOGGLE_PAUSE = "com.openkhub.sensefield.TOGGLE_PAUSE";
    static final String ACTION_STOP = "com.openkhub.sensefield.STOP";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String TAG = "MapAssistCapture";
    private static final String CHANNEL = "mapassist_capture";
    private static final int NOTIFICATION_ID = 104;
    private static final long FRAME_PERIOD_MS = 83; // About 12 sampled frames per second.
    private static final long DISPLAY_CHECK_PERIOD_MS = 500;
    private final Object processingLock = new Object();
    private HandlerThread workerThread;
    private Handler worker;
    private NotificationManager notificationManager;
    private CuePlayer cuePlayer;
    private CueDispatcher cueDispatcher;
    private CueSettings cueSettings;
    private MinimapOverlay minimapOverlay;
    private final OverlayCaptureGuard overlayCaptureGuard = new OverlayCaptureGuard();
    private final CueArbiter cueArbiter = new CueArbiter();
    private final CueArbiter.DirectCueOutput directCueOutput =
            new CueArbiter.DirectCueOutput() {
                @Override public boolean categoryEnabled(CueRequest.Category category) {
                    return cueSettings != null && cueSettings.categoryEnabled(category);
                }

                @Override public boolean outputAvailable(CueRequest.Category category,
                                                          int requestedChannels) {
                    return cueDispatcher != null && cueSettings != null
                            && (cueSettings.enabledChannels(category) & requestedChannels) != 0;
                }

                @Override public boolean dispatch(NativeFrameResult frame, long observedAtMs) {
                    return dispatchNativeCue(frame.cueKind, frame.cueDirection,
                            frame.cuePriority, observedAtMs);
                }
            };
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
    private final BlackFrameMonitor blackFrameMonitor = new BlackFrameMonitor();
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
    private long minimapAppearMinGapMs;
    private int frameWidth;
    private int frameHeight;
    private int frameRotation = -1;
    private boolean discardFrameAfterRotation;
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
    // Near-zone relation layer state for routing and the session audit.
    private boolean nearZoneActive;
    private boolean nearCategoryWasEnabled = true;
    private int lastRelationState = Integer.MIN_VALUE;
    private final int[] nearZoneStateFrames = new int[5];
    private int nearZoneEnters;
    private int nearZoneSuppressed;
    private int radarPauses;
    private int radarResumes;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
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
                        boolean expectFrames = frameWidth > frameHeight;
                        if (!expectFrames && recoveryRunnable != null) {
                            worker.removeCallbacks(recoveryRunnable);
                            recoveryRunnable = null;
                        }
                        if (captureHealth.check(SystemClock.elapsedRealtime(), expectFrames) ==
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
                GameProfile.settings(this).edit().putBoolean("capture_paused", paused).apply();
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
                GameProfile.Loaded loaded = GameProfile.loadResolved(this);
                GameProfile profile = loaded.profile;
                profileName = profile.name + " · " + profile.version;
                maxObservationAgeMs = profile.eventInts[0];
                // A development profile may carry an older, shorter native
                // cooldown. The accessible marker path has a hard floor so an
                // imported legacy profile cannot restore five-second chatter.
                minimapAppearMinGapMs = CueRouting.effectiveMinimapAppearanceGap(
                        profile.eventInts[2]);
                visionMemoryEnabled = GameProfile.settings(this)
                        .getBoolean(GameProfile.PREF_VISION_MEMORY,
                                GameProfile.DEFAULT_VISION_MEMORY);
                syncVisionMemoryOutputsLocked();
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
                        profile.yoloxParamAsset, profile.yoloxBinAsset,
                        profile.yoloxConfidence, profile.yoloxNms,
                        profile.yoloxClassKinds, profile.yoloxClassThresholds,
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
                if (profile.relation != null) {
                    if (!NativeBridge.nativeConfigureRelation(nativeSession,
                            profile.relation.floats, profile.relation.ints)) {
                        throw new IllegalStateException("Native near-zone layer rejected profile");
                    }
                    nearZoneActive = true;
                }
                if (loaded.fallbackReason != null) {
                    Log.w(TAG, "Near-zone profile not in use: " + loaded.fallbackReason);
                }
                Log.i(TAG, "SessionConfig sessionId=" + auditSessionId
                        + " profileSource=" + loaded.source
                        + " nearZone=" + nearZoneActive
                        + " nearZoneCalibrated=" + (profile.relation != null
                                && profile.relation.calibrated)
                        + " profileName=" + profile.name
                        + " profileVersion=" + profile.version
                        + " verified=" + profile.verified
                        + " minimapYolox=" + profile.minimapYolox
                        + " confidence=" + profile.yoloxConfidence
                        + " nms=" + profile.yoloxNms
                        + " minimapMinGapMs=" + minimapAppearMinGapMs
                        + " visionMemoryEnabled=" + visionMemoryEnabled
                        + " visionCategoryEnabled=" + cueSettings.categoryEnabled(
                                CueRequest.Category.VISION_MEMORY)
                        + " cuePreset=" + GameProfile.settings(this).getString(
                                "cue_preset", CueSettings.PRESET_STANDARD)
                        + " cueChannels=" + cueSettings.enabledChannels()
                        + " visionChannels=" + cueSettings.enabledChannels(
                                CueRequest.Category.VISION_MEMORY)
                        + " speakAppear=" + cueSettings.speakAppear());

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
                                .putBoolean("capture_active", false)
                                .putBoolean("capture_paused", false)
                                .putBoolean("capture_waiting_for_image", false)
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
                GameProfile.settings(this).edit()
                        .putBoolean("capture_active", true)
                        .putBoolean("capture_paused", false)
                        .putBoolean("capture_waiting_for_image", false)
                        .apply();
                captureHealth.start(SystemClock.elapsedRealtime());
                scheduleDisplayWatchdogLocked(sessionGeneration);
                refreshNotification();
            } catch (IOException | JSONException | RuntimeException error) {
                Log.e(TAG, "Could not start local capture", error);
                stopWithStatus("无法开始截屏：" + error.getMessage());
            }
        }
    }

    private Display defaultDisplay() {
        return getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
    }

    private Point screenSize() {
        Display display = defaultDisplay();
        Point size = new Point();
        if (display != null) display.getRealSize(size);
        if (size.x < 1 || size.y < 1) {
            size.x = getResources().getDisplayMetrics().widthPixels;
            size.y = getResources().getDisplayMetrics().heightPixels;
        }
        return size;
    }

    private int screenRotation() {
        Display display = defaultDisplay();
        return display == null ? -1 : display.getRotation();
    }

    private void createReader(int width, int height) {
        frameWidth = width;
        frameHeight = height;
        frameRotation = screenRotation();
        int generation = ++readerGeneration;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(source -> onImageAvailable(source, generation), worker);
    }

    /** Reset native tracking while recording the generation used for cue dedupe. */
    private void resetNativeLocked() {
        if (nativeSession != 0) {
            NativeBridge.nativeReset(nativeSession);
            nativeResetGeneration++;
        }
        // Native tracks and Java output state share the same reset boundary.
        // The next confirmed appearance must be eligible for a fresh cue, but
        // a stale overlay marker or suppressed frame must never survive it.
        cueArbiter.reset();
        clearCueCategoriesLocked();
        if (minimapOverlay != null) minimapOverlay.clear();
        overlayCaptureGuard.clearMarkers();
        // Native reset also cleared the relation layer; log a fresh state.
        lastRelationState = Integer.MIN_VALUE;
    }

    /** Native/session resets invalidate every cue that could outlive the frame stream. */
    private void clearCueCategoriesLocked() {
        if (cueDispatcher == null) return;
        cueDispatcher.clearAll();
    }

    /** Apply live output preferences without stopping native recognition. */
    private void syncVisionMemoryOutputsLocked() {
        if (cueSettings == null) return;
        boolean configured = GameProfile.settings(this).getBoolean(
                GameProfile.PREF_VISION_MEMORY, GameProfile.DEFAULT_VISION_MEMORY);
        if (configured != visionMemoryEnabled) visionMemoryEnabled = configured;
        boolean categoryEnabled = cueSettings.categoryEnabled(
                CueRequest.Category.VISION_MEMORY);
        boolean feedbackEnabled = visionMemoryEnabled && categoryEnabled;
        boolean wasEnabled = cueArbiter.isVisualMemoryEnabled();
        cueArbiter.setVisualMemoryEnabled(feedbackEnabled);
        boolean peripheralEnabled = cueSettings.categoryEnabled(
                CueRequest.Category.PERIPHERAL_THREAT);
        boolean peripheralWasEnabled = cueArbiter.isPeripheralThreatEnabled();
        cueArbiter.setPeripheralThreatEnabled(peripheralEnabled);
        if (peripheralWasEnabled && !peripheralEnabled && cueDispatcher != null) {
            cueDispatcher.clearCategory(CueRequest.Category.PERIPHERAL_THREAT);
        }
        if (wasEnabled && !feedbackEnabled && cueDispatcher != null) {
            cueDispatcher.clearCategory(CueRequest.Category.VISION_MEMORY);
        }
        boolean nearEnabled = cueSettings.categoryEnabled(CueRequest.Category.NEAR_ZONE);
        if (nearCategoryWasEnabled && !nearEnabled && cueDispatcher != null) {
            cueDispatcher.clearCategory(CueRequest.Category.NEAR_ZONE);
        }
        nearCategoryWasEnabled = nearEnabled;
        boolean visualEnabled = feedbackEnabled &&
                !overlayCaptureGuard.isSuppressed() &&
                (cueSettings.enabledChannels(CueRequest.Category.VISION_MEMORY)
                        & CueRequest.CHANNEL_VISUAL) != 0;
        if (visualEnabled) {
            if (minimapOverlay == null) minimapOverlay = MinimapOverlay.createIfAllowed(this);
        } else if (minimapOverlay != null) {
            minimapOverlay.clear();
            minimapOverlay.close();
            minimapOverlay = null;
            overlayCaptureGuard.clearMarkers();
        } else if (!overlayCaptureGuard.isSuppressed()) {
            overlayCaptureGuard.clearMarkers();
        }
    }

    private void resizeIfNeeded() {
        Point current = screenSize();
        int rotation = screenRotation();
        boolean sizeChanged = current.x != frameWidth || current.y != frameHeight;
        boolean rotationChanged = CaptureGeometry.rotationChanged(frameRotation, rotation);
        if (!sizeChanged && !rotationChanged) return;
        if (!sizeChanged) {
            frameRotation = rotation;
            resetNativeLocked();
            if (minimapOverlay != null) minimapOverlay.clear();
            lastFrameLandscape = frameWidth > frameHeight;
            discardFrameAfterRotation = true;
            blackFrameMonitor.reset();
            Log.i(TAG, "Capture rotation changed to " + rotation
                    + " without a size change; temporal state reset");
            return;
        }
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
        lastFrameLandscape = frameWidth > frameHeight;
        discardFrameAfterRotation = rotationChanged;
        blackFrameMonitor.reset();
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
                    blackFrameMonitor.reset();
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
                    // Brief ImageReader stalls recover automatically and are
                    // common around game and orientation transitions. Keep the
                    // recovery in diagnostics and the notification instead of
                    // interrupting play with a spoken status message.
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
            synchronized (processingLock) {
                if (stopping || source != reader || generation != readerGeneration) return;
                Point currentSize = screenSize();
                if (currentSize.x != frameWidth || currentSize.y != frameHeight) {
                    // Do not infer from a frame in the previous display geometry.
                    resizeAfterClose = true;
                    return;
                }
                if (width != frameWidth || height != frameHeight) return;
                int rotation = screenRotation();
                if (CaptureGeometry.rotationChanged(frameRotation, rotation)) {
                    frameRotation = rotation;
                    resetNativeLocked();
                    if (minimapOverlay != null) minimapOverlay.clear();
                    lastFrameLandscape = landscape;
                    discardFrameAfterRotation = true;
                    blackFrameMonitor.reset();
                    Log.i(TAG, "Capture rotation changed to " + rotation
                            + " on frame; temporal state reset");
                }
                if (discardFrameAfterRotation) {
                    discardFrameAfterRotation = false;
                    return;
                }
            }
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
                boolean wasBlack = blackFrameMonitor.isBlack();
                BlackFrameMonitor.Action blackAction = blackFrameMonitor.update(
                        blackFrame, minimapOverlay != null, now);
                if (blackFrame && !wasBlack) {
                    resetNativeLocked();
                    GameProfile.settings(this).edit()
                            .putBoolean("capture_waiting_for_image", true).apply();
                    Log.w(TAG, "CaptureBlackFrame sessionId=" + auditSessionId
                            + " state=WAITING");
                    refreshNotification();
                } else if (!blackFrame && wasBlack) {
                    GameProfile.settings(this).edit()
                            .putBoolean("capture_waiting_for_image", false).apply();
                    Log.i(TAG, "CaptureBlackFrame sessionId=" + auditSessionId
                            + " state=RECOVERED overlaySuppressed="
                            + overlayCaptureGuard.isSuppressed());
                    refreshNotification();
                }
                if (blackAction == BlackFrameMonitor.Action.DISABLE_OVERLAY) {
                    // Close only our own secure window. Never try to bypass
                    // protection on game or other application content.
                    overlayCaptureGuard.suppressForBlackFrames();
                    minimapOverlay.close();
                    minimapOverlay = null;
                    Log.w(TAG, "CaptureBlackFrame sessionId=" + auditSessionId
                            + " state=OVERLAY_DISABLED");
                }
                if (blackAction == BlackFrameMonitor.Action.STOP) {
                    Log.w(TAG, "Capture is consistently black; protected or unavailable content");
                    finishAuditSessionLocked("black_frames");
                    stopWithStatus("画面持续黑屏，采集已停止；可能是受保护内容或系统限制");
                    return;
                }
                if (landscape != lastFrameLandscape && nativeSession != 0) {
                    resetNativeLocked();
                    if (minimapOverlay != null) minimapOverlay.clear();
                    if (!landscape) latestNativeMicros = 0;
                }
                lastFrameLandscape = landscape;
                if (landscape && !blackFrame && !paused && nativeSession != 0) {
                    syncVisionMemoryOutputsLocked();
                    if (minimapOverlay != null && overlayCaptureGuard.observeFrame(
                            pixels, width, height, plane.getRowStride())) {
                        Log.w(TAG, "Overlay was re-captured by MediaProjection; "
                                + "visual overlay disabled for this session");
                        minimapOverlay.clear();
                        minimapOverlay.close();
                        minimapOverlay = null;
                    }
                    long processingAtMs = SystemClock.elapsedRealtime();
                    captureHealth.frameProcessed(processingAtMs);
                    recordLandscapeProcessedFrameLocked(processingAtMs);
                    int[] result = NativeBridge.nativeProcess(nativeSession, pixels,
                            width, height, plane.getRowStride(), observedAtMs, now);
                    if (result != null && result.length >= 5) {
                        NativeFrameResult frame = NativeBridge.parseFrameResult(result, observedAtMs);
                        if (frame.observationCount < 0) Log.e(TAG, "Invalid direct image buffer");
                        latestNativeMicros = frame.processingMicros;
                        latestLocatorState = frame.locatorState;
                        latestLocatorScoreMilli = frame.locatorScoreMilli;
                        if (latestLocatorState != lastLoggedLocatorState) {
                            lastLoggedLocatorState = latestLocatorState;
                            Log.i(TAG, "MinimapLayout sessionId=" + auditSessionId
                                    + " state=" + locatorStateName(latestLocatorState)
                                    + " scoreMilli=" + latestLocatorScoreMilli
                                    + " roi=" + frame.minimapRoi.left + ","
                                    + frame.minimapRoi.top + ","
                                    + frame.minimapRoi.width() + ","
                                    + frame.minimapRoi.height());
                        }
                        if (minimapOverlay != null) {
                            if (minimapOverlay.update(frame)) {
                                overlayCaptureGuard.recordRenderedFrame(frame);
                            } else {
                                overlayCaptureGuard.clearMarkers();
                                if (minimapOverlay.isClosed()) {
                                    minimapOverlay.close();
                                    minimapOverlay = null;
                                }
                            }
                        }
                        // Let the arbiter submit a direct cue through the same
                        // dispatcher policy used by all other output. A
                        // minimap APPEAR is suppressed only after that direct
                        // submission is actually accepted.
                        CueArbiter.Decision decision = cueArbiter.arbitrate(
                                frame, SystemClock.elapsedRealtime(), minimapAppearMinGapMs,
                                directCueOutput, observedAtMs);
                        playVisionMemoryTransitions(frame, decision.minimapAppearances,
                                observedAtMs, decision.suppressedMinimap);
                        // Near-zone events bypass the minimap APPEAR cooldown:
                        // native occupancy episodes and REARM are their dedupe.
                        handleNearZone(frame, observedAtMs);
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

    private void playVisionMemoryTransitions(NativeFrameResult frame,
                                              List<TrackedEntity> appearances,
                                              long observedAtMs,
                                              List<CueArbiter.Suppressed> suppressed) {
        for (CueArbiter.Suppressed item : suppressed) {
            TrackedEntity entity = item.entity;
            Log.i(TAG, "VisionMemoryEvent sessionId=" + auditSessionId
                    + " event=APPEAR"
                    + " trackId=" + entity.trackId
                    + " cueSuppressed=true suppression=" + item.reason);
        }
        if (cueDispatcher == null || appearances == null || appearances.isEmpty()) return;
        boolean audible = cueSettings == null || NearZoneRouting.farAppearAudible(
                nearZoneActive, cueSettings.categoryEnabled(CueRequest.Category.NEAR_ZONE),
                cueSettings.farAppearPreference());
        if (!audible) {
            // Near-zone cues replace the distant new-portrait tone; the
            // overlay still draws the marker from the native snapshot.
            for (TrackedEntity entity : appearances) {
                Log.i(TAG, "VisionMemoryEvent sessionId=" + auditSessionId
                        + " event=APPEAR"
                        + " trackId=" + entity.trackId
                        + " cueSuppressed=true suppression=near_zone_active");
            }
            return;
        }
        for (TrackedEntity entity : appearances) {
            int position = minimapPosition(entity.bbox.centerX(), entity.bbox.centerY(),
                    frame.minimapRoi);
            int channels = CueRequest.CHANNEL_TONE;
            if (minimapOverlay != null) channels |= CueRequest.CHANNEL_VISUAL;
            String speech = null;
            if (cueSettings != null && cueSettings.speakAppear()) {
                channels |= CueRequest.CHANNEL_SPEECH;
                speech = CueRouting.unlocatedMinimapEnemySpeech();
            }
            if (cueSettings != null && (cueSettings.enabledChannels(
                    CueRequest.Category.VISION_MEMORY) & CueRequest.CHANNEL_HAPTIC) != 0) {
                channels |= CueRequest.CHANNEL_HAPTIC;
            }
            int event = entity.transition;
            String cueId = auditSessionId + ":" + nextCueId++;
            CueRequest request = new CueRequest(auditSessionId, cueId,
                    CueEventKeys.visionMemory(nativeResetGeneration, entity.trackId, event),
                    "VISION_APPEAR", CueRequest.Category.VISION_MEMORY, 40,
                    observedAtMs, observedAtMs + 800, channels, 2,
                    CueRouting.unlocatedMinimapEnemyDirection(),
                    CueRouting.unlocatedMinimapEnemyDirection(), speech);
            long frameAgeMs = Math.max(0, SystemClock.elapsedRealtime() - observedAtMs);
            CueDispatcher.DispatchResult dispatched = cueDispatcher.submit(request);
            boolean stale = recordCueDispatch(dispatched);
            Log.i(TAG, "CueEvent sessionId=" + auditSessionId
                    + " cueId=" + cueId
                    + " kind=2"
                    + " direction=" + CueRouting.unlocatedMinimapEnemyDirection()
                    + " observedAtMs=" + observedAtMs
                    + " frameAgeMs=" + frameAgeMs
                    + " nativeMicros=" + latestNativeMicros
                    + " stale=" + stale
                    + " audioQueued=" + dispatched.audioQueued());
            Log.i(TAG, "VisionMemoryEvent sessionId=" + auditSessionId
                    + " event=APPEAR"
                    + " trackId=" + entity.trackId
                    + " mapPosition=" + position
                    + " movement=" + entity.movementDirection
                    + " ageMs=" + entity.ageMs
                    + " feedbackQueued=" + dispatched.audioQueued());
        }
    }

    /** Route one frame of native near-zone relation output. Called under processingLock. */
    private void handleNearZone(NativeFrameResult frame, long observedAtMs) {
        NativeFrameResult.Relation relation = frame.relation;
        if (!nearZoneActive || !relation.available()) return;
        if (relation.state >= 0 && relation.state < nearZoneStateFrames.length) {
            nearZoneStateFrames[relation.state]++;
        }
        if (relation.state != lastRelationState) {
            Log.i(TAG, "NearZoneState sessionId=" + auditSessionId
                    + " from=" + (lastRelationState == Integer.MIN_VALUE ? "RESET"
                            : NearZoneRouting.stateName(lastRelationState))
                    + " to=" + NearZoneRouting.stateName(relation.state)
                    + " reliable=" + relation.reliable
                    + " atMs=" + observedAtMs);
            lastRelationState = relation.state;
        }
        switch (relation.event) {
            case NearZoneRouting.EVENT_NEAR_ENTER:
                nearZoneEnters++;
                dispatchNearZoneCue(relation, observedAtMs);
                break;
            case NearZoneRouting.EVENT_RADAR_PAUSED:
            case NearZoneRouting.EVENT_RADAR_RESUMED:
                if (relation.event == NearZoneRouting.EVENT_RADAR_PAUSED) radarPauses++;
                else radarResumes++;
                // Internal availability changes are kept for diagnosis;
                // they do not interrupt the player's game with status sounds.
                logNearZoneEvent(relation, observedAtMs, "-", "SUPPRESSED");
                break;
            case NearZoneRouting.EVENT_SUPPRESSED:
                nearZoneSuppressed++;
                logNearZoneEvent(relation, observedAtMs, "-", "SUPPRESSED");
                break;
            default:
                break;
        }
    }

    private void dispatchNearZoneCue(NativeFrameResult.Relation relation, long observedAtMs) {
        if (cueDispatcher == null || cueSettings == null) return;
        if (!cueSettings.categoryEnabled(CueRequest.Category.NEAR_ZONE)) {
            logNearZoneEvent(relation, observedAtMs, "-", "CATEGORY_DISABLED");
            return;
        }
        String cueId = auditSessionId + ":" + nextCueId++;
        CueRequest request = new CueRequest(auditSessionId, cueId,
                CueEventKeys.nearZone(nativeResetGeneration, relation.episodeId),
                "NEAR_ZONE", CueRequest.Category.NEAR_ZONE, NearZoneRouting.NEAR_PRIORITY,
                observedAtMs, observedAtMs + NearZoneRouting.NEAR_TTL_MS,
                cueSettings.nearRequestedChannels(),
                NearZoneRouting.TONE_NEAR, 0, 0, NearZoneRouting.speech(relation.sector),
                relation.pan);
        submitRelationCue(request, NearZoneRouting.KIND_NEAR_ZONE, relation, observedAtMs);
    }

    private void submitRelationCue(CueRequest request, int kind,
                                   NativeFrameResult.Relation relation, long observedAtMs) {
        long frameAgeMs = Math.max(0, SystemClock.elapsedRealtime() - observedAtMs);
        CueDispatcher.DispatchResult dispatched = cueDispatcher.submit(request);
        boolean stale = recordCueDispatch(dispatched);
        Log.i(TAG, "CueEvent sessionId=" + auditSessionId
                + " cueId=" + request.cueId
                + " kind=" + kind
                + " direction=0"
                + " observedAtMs=" + observedAtMs
                + " frameAgeMs=" + frameAgeMs
                + " nativeMicros=" + latestNativeMicros
                + " stale=" + stale
                + " audioQueued=" + dispatched.audioQueued());
        logNearZoneEvent(relation, observedAtMs, request.cueId, dispatched.outcome);
    }

    private void logNearZoneEvent(NativeFrameResult.Relation relation, long observedAtMs,
                                  String cueId, String outcome) {
        Log.i(TAG, "NearZoneEvent sessionId=" + auditSessionId
                + " event=" + NearZoneRouting.eventName(relation.event)
                + " episode=" + relation.episodeId
                + " sector=" + relation.sector
                + " panMilli=" + Math.round(relation.pan * 1000f)
                + " distanceMilli=" + Math.round(relation.nearestDistance * 1000f)
                + " suppression=" + NearZoneRouting.suppressionName(relation.suppression)
                + " cueId=" + cueId
                + " outcome=" + outcome
                + " atMs=" + observedAtMs);
    }

    private boolean dispatchNativeCue(int kind, int direction, int nativePriority,
                                      long observedAtMs) {
        if (cueDispatcher == null ||
                !CueRouting.shouldDispatchDirectNativeCue(kind, direction)) {
            return false;
        }
        boolean peripheralThreat = CueRouting.isPeripheralThreat(kind, direction);
        CueRequest.Category category = CueRouting.directCueCategory(kind, direction);
        int priority = kind == 4 ? 100 : kind == 5 ? 60
                : kind == 3 ? 80 : Math.max(40, nativePriority);
        long ttl = kind == 4 ? 2000 : kind == 5 ? 2500
                : kind == 3 ? 1200 : maxObservationAgeMs;
        int channels = CueRouting.directCueRequestedChannels(kind, direction);
        String speech = null;
        if (kind == 4) {
            speech = "你已阵亡";
        } else if (kind == 5) {
            speech = "你已复活";
        } else {
            if (kind == 3) {
                speech = "危险信号";
            }
        }
        String cueId = auditSessionId + ":" + nextCueId++;
        CueRequest request = new CueRequest(auditSessionId, cueId,
                CueEventKeys.nativeCue(nativeResetGeneration, kind, direction),
                peripheralThreat ? "PERIPHERAL_THREAT" : kindName(kind), category,
                priority, observedAtMs, observedAtMs + ttl,
                channels, kind, direction, direction, speech);
        long frameAgeMs = Math.max(0, SystemClock.elapsedRealtime() - observedAtMs);
        CueDispatcher.DispatchResult dispatched = cueDispatcher.submit(request);
        boolean stale = recordCueDispatch(dispatched);
        Log.i(TAG, "CueEvent sessionId=" + auditSessionId
                + " cueId=" + cueId
                + " kind=" + kind
                + " direction=" + direction
                + " observedAtMs=" + observedAtMs
                + " frameAgeMs=" + frameAgeMs
                + " nativeMicros=" + latestNativeMicros
                + " stale=" + stale
                + " audioQueued=" + dispatched.audioQueued());
        return dispatched.acceptedChannels != 0;
    }

    private boolean recordCueDispatch(CueDispatcher.DispatchResult dispatched) {
        detectedCues++;
        CueRouting.CueAccounting accounting = CueRouting.cueAccounting(
                dispatched.reason, dispatched.audioQueued());
        if (accounting == CueRouting.CueAccounting.STALE) staleCues++;
        else if (accounting == CueRouting.CueAccounting.QUEUED) queuedCues++;
        else audioQueueFailures++;
        return accounting == CueRouting.CueAccounting.STALE;
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

    /** Diagnostic map-center bucket; never use as a player-relative cue. */
    private static int minimapPosition(float x, float y, android.graphics.RectF roi) {
        if (roi == null || roi.width() <= 0 || roi.height() <= 0) return 0;
        float dx = (x - roi.centerX()) / (roi.width() * 0.5f);
        float dy = (y - roi.centerY()) / (roi.height() * 0.5f);
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
                .setContentTitle(getString(R.string.capture_notification_title, state))
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
                : blackFrameMonitor.isBlack()
                        || captureHealth.state() == CaptureHealthMonitor.State.STARVED
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
            GameProfile.settings(this).edit()
                    .putBoolean("capture_active", false)
                    .putBoolean("capture_paused", false)
                    .putBoolean("capture_waiting_for_image", false)
                    .remove("last_capture_status")
                    .apply();
            startedAtMs = SystemClock.elapsedRealtime();
            lastProcessedAtMs = 0;
            lastNotificationAtMs = 0;
            blackFrameMonitor.reset();
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
            cueArbiter.reset();
            cueArbiter.setVisualMemoryEnabled(false);
            latestNativeMicros = 0;
            latestLocatorState = -1;
            latestLocatorScoreMilli = 0;
            lastLoggedLocatorState = Integer.MIN_VALUE;
            lastFrameLandscape = false;
            visionMemoryEnabled = false;
            nearZoneActive = false;
            nearCategoryWasEnabled = true;
            lastRelationState = Integer.MIN_VALUE;
            java.util.Arrays.fill(nearZoneStateFrames, 0);
            nearZoneEnters = 0;
            nearZoneSuppressed = 0;
            radarPauses = 0;
            radarResumes = 0;
            overlayCaptureGuard.reset();
            profileName = "";
            auditSessionId = UUID.randomUUID().toString();
            auditSessionStartedAtMs = startedAtMs;
            nextCueId = 1;
            landscapeProcessedFrames = 0;
            firstLandscapeProcessedAtMs = -1;
            lastLandscapeProcessedAtMs = -1;
            maxLandscapeProcessedGapMs = 0;
            auditSessionActive = true;
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
        if (nearZoneActive) {
            // Frame counts per relation state give evidence coverage without
            // reconstructing wall-clock intervals across pauses.
            Log.i(TAG, "NearZoneSummary sessionId=" + auditSessionId
                    + " unknownFrames=" + nearZoneStateFrames[0]
                    + " clearFrames=" + nearZoneStateFrames[1]
                    + " pendingFrames=" + nearZoneStateFrames[2]
                    + " occupiedFrames=" + nearZoneStateFrames[3]
                    + " rearmFrames=" + nearZoneStateFrames[4]
                    + " nearEnters=" + nearZoneEnters
                    + " suppressed=" + nearZoneSuppressed
                    + " radarPauses=" + radarPauses
                    + " radarResumes=" + radarResumes);
        }
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
                        .putBoolean("capture_active", false)
                        .putBoolean("capture_paused", false)
                        .putBoolean("capture_waiting_for_image", false)
                        .putString("last_capture_status", status).apply();
            }
            stopThroughStartId = latestServiceStartId;
        }
        if (stopThroughStartId > 0) stopSelfResult(stopThroughStartId);
        else stopSelf();
    }

    private void releaseCapture() {
        stopping = true;
        cueArbiter.reset();
        if (cueDispatcher != null) {
            cueDispatcher.close();
            cueDispatcher = null;
        }
        if (minimapOverlay != null) {
            minimapOverlay.close();
            minimapOverlay = null;
        }
        overlayCaptureGuard.reset();
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
        running = false;
        synchronized (processingLock) {
            finishAuditSessionLocked("destroyed");
            releaseCapture();
        }
        GameProfile.settings(this).edit()
                .putBoolean("capture_active", false)
                .putBoolean("capture_paused", false)
                .putBoolean("capture_waiting_for_image", false)
                .apply();
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
