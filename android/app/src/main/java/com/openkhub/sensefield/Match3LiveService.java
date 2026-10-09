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
import java.util.UUID;

/** Local Match3 capture: bounded conversion, stable boards and session-owned touch/diagnostics. */
public class Match3LiveService extends Service {
    private static final String TAG = "Match3Live";
    static final String ACTION_START = "com.openkhub.sensefield.m3live.START";
    static final String ACTION_STOP = "com.openkhub.sensefield.m3live.STOP";
    static final String ACTION_EXPLORE_ON = "com.openkhub.sensefield.m3live.EXPLORE_ON";
    static final String ACTION_EXPLORE_OFF = "com.openkhub.sensefield.m3live.EXPLORE_OFF";
    static final String ACTION_REFRESH_VISUAL = "com.openkhub.sensefield.m3live.REFRESH_VISUAL";
    static final String ACTION_MARK_ISSUE = "com.openkhub.sensefield.m3live.MARK_ISSUE";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_DATA = "data";
    static final String EXTRA_FULL_DISPLAY = "full_display_capture";
    private static final int NOTIFICATION_ID = 3002;
    private static final long CAPTURE_INTERVAL_MS = 800;
    private static final int STABLE_FRAMES = 3;
    private static final long MIN_ANNOUNCE_GAP_MS = 6000;
    private static final long IDLE_HINT_MS = 15000;
    private static volatile boolean running;
    private static volatile boolean exploring;
    private static java.lang.ref.WeakReference<Match3LiveService> debugInstance = new java.lang.ref.WeakReference<>(null);
    static Match3LiveService testInstance() { return BuildConfig.DEBUG ? debugInstance.get() : null; }
    static boolean isRunning() { return running; }
    static boolean isExploreMode() { return exploring; }
    static final String EXTRA_START_REQUEST_ID = "match3_start_request_id";
    private static volatile StartResult lastStartResult;

    /** Acknowledges this request only once the projection and frame listener are ready. */
    static final class StartResult {
        final String requestId;
        final boolean ready;
        StartResult(String requestId, boolean ready) { this.requestId = requestId; this.ready = ready; }
    }
    static StartResult startResult(String requestId) {
        StartResult result = lastStartResult;
        return result != null && requestId != null && requestId.equals(result.requestId) ? result : null;
    }
    private void acknowledgeStart(Intent intent, boolean ready) {
        lastStartResult = new StartResult(intent.getStringExtra(EXTRA_START_REQUEST_ID), ready);
        Log.i(TAG, "Match3StartResult ready=" + ready);
    }

    private final Match3ProjectionSession projectionSession = new Match3ProjectionSession();
    private MediaProjection projection;
    private VirtualDisplay display;
    private volatile ImageReader reader;
    private volatile Session active;
    private HandlerThread thread;
    private Handler handler;
    private CuePlayer player;
    private CueDispatcher dispatcher;

    // Every mutable recognition field belongs to one projection; worker-only except exploreMode.
    private static final class Session {
        final long generation;
        final MediaProjection projection;
        final ImageReader reader;
        final DiagnosticRecorder diagnostics;
        Match3Sampler sampler;
        BoardGeometry geometry;
        final Match3GeometryConfirmation geometryConfirmation = new Match3GeometryConfirmation();
        volatile Match3Hint currentHint;
        volatile String hintCueId;
        long boardRevision, lastHintSpokenRevision = -1;
        int hintAttempts;
        char[][] lastStableMatrix;
        Match3HintOverlay overlay;
        Match3OverlayCaptureFilter overlayFilter;
        long filteredOverlayFrames, overlayFilterMicros;
        final BlackFrameMonitor blackMonitor = new BlackFrameMonitor();
        boolean visualSuppressed, fullDisplayCapture;

        Bitmap frame;
        volatile long frameAt;
        long processedFrames;
        long lastProcessedAt = -CAPTURE_INTERVAL_MS;
        char[][][] rawWindow = new char[STABLE_FRAMES][][];
        int rawIdx, rawFill;
        final Match3BoardConfirmation confirmation = new Match3BoardConfirmation();
        List<Match3Board.Swap> lastSwaps;
        long lastChangeAt, lastAnnounceAt, lastTouchHandledAt;
        int hintCount, liveRows = 8, liveCols = 8;
        boolean popupAnnounced, abstainAnnounced, boardValid;
        boolean overlayHintAnnounced, calibrationConflictAnnounced;
        volatile boolean projectionStopped;
        volatile boolean exploreMode;
        Session(long generation, MediaProjection projection, ImageReader reader,
                DiagnosticRecorder diagnostics) {
            this.generation = generation;
            this.projection = projection;
            this.reader = reader;
            this.diagnostics = diagnostics;
            lastTouchHandledAt = SystemClock.elapsedRealtime();
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        Log.i(TAG, "onStartCommand action=" + action + " start_id=" + startId
                + " flags=" + flags + " projection_alive=" + (projection != null)
                + " display_alive=" + (display != null));
        if (ACTION_MARK_ISSUE.equals(action) || ACTION_REFRESH_VISUAL.equals(action) || ACTION_EXPLORE_ON.equals(action)
                || ACTION_EXPLORE_OFF.equals(action)) {
            projectionSession.noteCommand(startId);
            Session s = active;
            if (s != null && s.projectionStopped) {
                if (ACTION_MARK_ISSUE.equals(action)) s.diagnostics.markIssue();
                else android.widget.Toast.makeText(this, "屏幕录制已结束，请重新开始实时识别。",
                        android.widget.Toast.LENGTH_LONG).show();
                return START_NOT_STICKY;
            }
            if (s == null || !isCurrent(s)) {
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            handler().post(() -> projectionSession.runIfCurrent(s.generation, s.projection, () -> {
                if (ACTION_MARK_ISSUE.equals(action)) s.diagnostics.markIssue();
                else if (ACTION_REFRESH_VISUAL.equals(action)) {
                    if (!GameProfile.settings(this).getBoolean("match3_hint_highlight_enabled", true)) {
                        closeOverlay(s);
                        s.diagnostics.audit("Match3VisualDisabled reason=user_setting");
                    } else {
                        SenseFieldReaderService.hideRowNumbers();
                        if (s.currentHint != null) updateHintOverlay(s, s.currentHint);
                    }
                } else {
                    s.exploreMode = ACTION_EXPLORE_ON.equals(action);
                    exploring = s.exploreMode;
                    if (dispatcher != null) dispatcher.clearAll();
                    s.lastTouchHandledAt = SystemClock.elapsedRealtime();
                    s.lastSwaps = null;
                    invalidateBoard(s, "POINT_READ_MODE");
                }
                return true;
            }));
            return START_NOT_STICKY;
        }
        if (intent == null || ACTION_STOP.equals(action)) {
            projectionSession.invalidate(startId);
            teardownMedia();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            projectionSession.noteCommand(startId);
            if (active == null) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        // Check before teardown: Android projection resultData may only be used once.
        if (projection != null && display != null
                && projectionSession.ignoreDuplicateStart(startId)) {
            Session current = active;
            if (current != null) current.diagnostics.audit("Match3DuplicateStart ignored start_id=" + startId);
            acknowledgeStart(intent, running);
            return START_NOT_STICKY;
        }
        long generation = projectionSession.beginStart(startId);
        teardownMedia();
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null || CaptureService.isRunning()) {
            acknowledgeStart(intent, false);
            if (CaptureService.isRunning()) android.widget.Toast.makeText(this,
                    "地图识别正在使用录屏，请先停止后再开启消消乐实时识别。",
                    android.widget.Toast.LENGTH_LONG).show();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
        DiagnosticRecorder diagnostics = DiagnosticRecorder.start(this,
                UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), true);
        String failureReason = "foreground_start_failed";
        try {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            failureReason = "projection_token_invalid";
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            MediaProjection currentProjection = manager.getMediaProjection(
                    intent.getIntExtra(EXTRA_RESULT_CODE, -1), data);
            if (currentProjection == null) throw new IllegalStateException("Projection unavailable");
            projection = currentProjection;
            if (!projectionSession.attach(generation, currentProjection))
                throw new IllegalStateException("Projection superseded");
            currentProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onCapturedContentResize(int width, int height) {
                    Session current = active;
                    if (current != null && current.generation == generation && current.fullDisplayCapture) {
                        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                        getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
                        resizeCapture(current, width, height, metrics.densityDpi);
                    }
                }
                @Override public void onStop() {
                    projectionSession.runIfGeneration(generation, () -> {
                        int stopId = projectionSession.stopIfCurrent(generation, currentProjection);
                        if (stopId <= 0) return;
                        diagnostics.audit("Match3ProjectionStopped start_id=" + stopId);
                        Session stopped = active;
                        if (stopped != null && stopped.generation == generation) {
                            suspendStoppedProjection(stopped);
                        } else {
                            diagnostics.finish("projection_stopped_during_start");
                            stopSelfResult(stopId);
                        }
                        android.widget.Toast.makeText(Match3LiveService.this,
                                "屏幕录制已结束，请重新开始实时识别。若经常中断，请检查游戏加速和省电设置。",
                                android.widget.Toast.LENGTH_LONG).show();
                    });
                }
            }, handler());
            failureReason = "virtual_display_failed";
            ImageReader currentReader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels,
                    PixelFormat.RGBA_8888, 2);
            reader = currentReader;
            display = currentProjection.createVirtualDisplay("sensefield-m3live",
                    dm.widthPixels, dm.heightPixels, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    currentReader.getSurface(), null, handler());
            if (display == null) throw new IllegalStateException("Virtual display unavailable");
            failureReason = "session_setup_failed";
            Session s = new Session(generation, currentProjection, currentReader, diagnostics);
            s.fullDisplayCapture = intent.getBooleanExtra(EXTRA_FULL_DISPLAY, false);
            active = s;
            if (!isCurrent(s)) throw new IllegalStateException("Projection stopped during setup");
            running = true;
            diagnostics.audit("Match3Session game=happy-anipop capture_interval_ms=" + CAPTURE_INTERVAL_MS);
            currentReader.setOnImageAvailableListener(source -> onImageAvailable(source, s), handler());
            scheduleTick(s);
            acknowledgeStart(intent, true);
            Log.i(TAG, "实时识别已启动 " + dm.widthPixels + "x" + dm.heightPixels);
        } catch (RuntimeException error) {
            acknowledgeStart(intent, false);
            Log.w(TAG, "创建消消乐录屏失败: " + failureReason + " " + error.getClass().getSimpleName());
            diagnostics.audit("Match3StartFailure reason=" + failureReason
                    + " error=" + error.getClass().getSimpleName());
            diagnostics.finish(failureReason);
            projectionSession.invalidate(startId);
            teardownMedia();
            stopForeground(STOP_FOREGROUND_REMOVE);
            android.widget.Toast.makeText(this, "projection_token_invalid".equals(failureReason)
                            ? "录屏授权已失效，请回到听野重新开始实时识别。"
                            : "无法启动实时识别，请重新授权后重试。",
                    android.widget.Toast.LENGTH_LONG).show();
            stopSelfResult(startId);
        }
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

    private boolean isCurrent(Session s) {
        return s != null && active == s && s.reader == reader
                && projectionSession.isCurrent(s.generation, s.projection);
    }

    /** Keep the diagnostic session available for marking/export, without continuing capture. */
    private void suspendStoppedProjection(Session s) {
        s.projectionStopped = true;
        invalidateBoard(s, "PROJECTION_STOPPED");
        closeOverlay(s);
        s.boardValid = false;
        s.exploreMode = exploring = false;
        s.lastSwaps = null;
        handler().removeCallbacksAndMessages(s);
        if (dispatcher != null) dispatcher.clearAll();
        SenseFieldReaderService.hideRowNumbers();
        if (reader != null) { try { reader.close(); } catch (Exception ignored) { } reader = null; }
        if (display != null) { try { display.release(); } catch (Exception ignored) { } display = null; }
        projection = null;
        if (s.frame != null && !s.frame.isRecycled()) s.frame.recycle();
        s.frame = null;
        s.diagnostics.publishState(DiagnosticRecorder.object("game_id", "happy-anipop",
                "state", "projection_stopped", "frames_expected", false,
                "snapshot_at_ms", SystemClock.elapsedRealtime(), "processed_frames", s.processedFrames));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private void onImageAvailable(ImageReader source, Session s) {
        Image image = null;
        Bitmap bitmap = null;
        try {
            // Retired readers are closed on this same worker; never acquire from a stale callback.
            if (source != s.reader || !isCurrent(s)) return;
            // Always drain, including skipped frames, so neither ImageReader slot stays occupied.
            image = source.acquireLatestImage();
            if (image == null || source != s.reader || !isCurrent(s)) return;
            long observedAt = SystemClock.elapsedRealtime();
            if (observedAt - s.lastProcessedAt < CAPTURE_INTERVAL_MS) return;
            s.lastProcessedAt = observedAt;
            bitmap = imageToBitmap(image);
            if (!isCurrent(s)) return;
            if (s.frame != null) s.frame.recycle();
            s.frame = bitmap;
            bitmap = null;
            s.frameAt = observedAt;
            Match3HintOverlay currentOverlay = s.overlay;
            Match3Hint renderedHint = currentOverlay == null ? null : currentOverlay.renderedHint();
            if (renderedHint != null) {
                if (s.overlayFilter == null) s.overlayFilter = new Match3OverlayCaptureFilter(this);
                long started = SystemClock.elapsedRealtimeNanos();
                boolean filtered = s.overlayFilter.clean(s.frame, renderedHint, currentOverlay.alpha());
                s.overlayFilterMicros += (SystemClock.elapsedRealtimeNanos() - started) / 1000;
                if (filtered && ++s.filteredOverlayFrames == 1)
                    s.diagnostics.audit("Match3VisualCapture mode=current_frame_unblend secure=false");
            }
            if (checkOverlayCapture(s, s.frame)) processFrame(s, s.frame);
            s.processedFrames++;
            s.diagnostics.publishState(DiagnosticRecorder.object("game_id", "happy-anipop",
                    "state", s.popupAnnounced ? "popup" : s.exploreMode ? "touch_read" : "capturing",
                    "snapshot_at_ms", SystemClock.elapsedRealtime(), "frames_expected", true,
                    "processed_frames", s.processedFrames, "last_frame_observed_at_ms", observedAt,
                    "last_frame_arrived_at_ms", observedAt,
                    "last_frame_completed_at_ms", SystemClock.elapsedRealtime(),
                    "frame_width", image.getWidth(), "frame_height", image.getHeight(),
                    "board_valid", s.boardValid, "rows", s.liveRows, "cols", s.liveCols,
                    "board_revision", s.boardRevision,
                    "geometry", s.geometry == null ? null : DiagnosticRecorder.object(
                            "left", s.geometry.left, "top", s.geometry.top,
                            "right", s.geometry.right, "bottom", s.geometry.bottom,
                            "origin", "frame_top_left_pixels"),
                    "hint", s.currentHint == null ? null : DiagnosticRecorder.object(
                            "revision", s.currentHint.revision,
                            "from_row", s.currentHint.swap.fromRow + 1, "from_col", s.currentHint.swap.fromCol + 1,
                            "to_row", s.currentHint.swap.toRow + 1, "to_col", s.currentHint.swap.toCol + 1,
                            "origin", "board_top_left_one_based"),
                    "highlight_suppressed", s.visualSuppressed,
                    "highlight_visible", s.overlay != null && s.overlay.renderedHint() != null,
                    "overlay_capture_filtered_frames", s.filteredOverlayFrames,
                    "overlay_capture_filter_micros", s.overlayFilterMicros));
            if (isCurrent(s)) {
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer rgba = plane.getBuffer().duplicate();
                rgba.rewind();
                s.diagnostics.frame(NativeFrameResult.empty(), DiagnosticSnapshot.parse(null), rgba,
                        image.getWidth(), image.getHeight(), plane.getRowStride(),
                        observedAt, SystemClock.elapsedRealtime(), (int) CAPTURE_INTERVAL_MS);
            }
        } catch (Exception error) {
            if (isCurrent(s)) {
                invalidateBoard(s, "FRAME_ERROR");
                Log.w(TAG, "帧处理失败: " + error.getClass().getSimpleName());
            }
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (image != null) try { image.close(); } catch (RuntimeException ignored) { }
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int width = image.getWidth(), height = image.getHeight();
        int rowPadding = plane.getRowStride() - plane.getPixelStride() * width;
        Bitmap full = Bitmap.createBitmap(width + rowPadding / plane.getPixelStride(), height,
                Bitmap.Config.ARGB_8888);
        try {
            ByteBuffer pixels = plane.getBuffer().duplicate();
            pixels.rewind();
            full.copyPixelsFromBuffer(pixels);
            if (rowPadding > 0) return Bitmap.createBitmap(full, 0, 0, width, height);
            Bitmap result = full;
            full = null;
            return result;
        } finally {
            if (full != null && !full.isRecycled()) full.recycle();
        }
    }

    private void scheduleTick(Session s) {
        handler().postAtTime(() -> tick(s), s, SystemClock.uptimeMillis() + CAPTURE_INTERVAL_MS);
    }

    private void tick(Session s) {
        if (!isCurrent(s)) return;
        if (s.exploreMode && s.frame != null && !s.popupAnnounced
                && SystemClock.elapsedRealtime() - s.frameAt <= CAPTURE_INTERVAL_MS * 2) {
            handleExploreTouch(s, s.frame);
        } else idleCheck(s);
        if (isCurrent(s)) scheduleTick(s);
    }

    private static void resetWindow(Session s) {
        s.rawWindow = new char[STABLE_FRAMES][][];
        s.rawIdx = s.rawFill = 0;
        s.boardValid = false;
        s.confirmation.reset();
    }

    private boolean saveCalibration(Session s, int[] bounds, int rows, int cols) {
        return projectionSession.runIfCurrent(s.generation, s.projection, () -> {
            GameProfile.settings(this).edit()
                    .putInt("match3_l", bounds[0]).putInt("match3_t", bounds[1])
                    .putInt("match3_r", bounds[2]).putInt("match3_b", bounds[3])
                    .putInt("match3_rows", rows).putInt("match3_cols", cols)
                    .putBoolean("match3_calibrated", true).apply();
            return true;
        });
    }

    private void processFrame(Session s, Bitmap frame) {
        if (!isCurrent(s)) return;
        s.boardValid = false; // This temporary processing state must not clear a valid drawing.
        if (Match3Coach.isPopupShowing(frame)) {
            if (!s.popupAnnounced) s.diagnostics.audit("Match3Status state=popup speech=silent");
            s.popupAnnounced = true;
            invalidateBoard(s, "POPUP");
            return;
        }
        if (s.popupAnnounced) {
            s.popupAnnounced = false;
            invalidateBoard(s, "POPUP_CLOSED");
            s.diagnostics.audit("Match3Status state=popup_closed speech=silent");
            return;
        }
        if (s.geometry != null && (s.geometry.frameWidth != frame.getWidth()
                || s.geometry.frameHeight != frame.getHeight())) invalidateBoard(s, "CAPTURE_SIZE");
        // A new level can still have readable colours inside the OLD rectangle.
        // Revalidate using this same 800ms sample, rather than trusting those colours.
        BoardGeometry candidate = estimateGeometry(frame);
        if (s.geometry != null && !s.geometry.sameGrid(candidate))
            invalidateBoard(s, candidate == null ? "GEOMETRY_UNVERIFIED" : "GEOMETRY_CHANGED");
        if (s.sampler == null && !prepareGeometry(s, frame, candidate)) return;
        if (s.exploreMode) { handleExploreTouch(s, frame); return; }
        char[][] raw = s.sampler.sample(frame);
        if (s.currentHint != null && countDiffCells(s.lastStableMatrix, raw) > 0)
            invalidateHint(s, "BOARD_CHANGED");
        s.rawWindow[s.rawIdx] = raw;
        s.rawIdx = (s.rawIdx + 1) % STABLE_FRAMES;
        if (s.rawFill < STABLE_FRAMES) s.rawFill++;
        if (s.rawFill < STABLE_FRAMES) return;
        // Observe EVERY raw frame. Skipping the non-majority frame would leave an
        // accepted A in this gate and let A/B/A immediately resurrect its old hint.
        boolean consecutivelyConfirmed = s.confirmation.accept(raw);
        char[][] matrix = majorityMatrix(s.rawWindow);
        int unknown = countUnknown(matrix), total = matrix.length * matrix[0].length;
        if (isUnreadableBoard(matrix)) {
            if (!s.abstainAnnounced) {
                s.abstainAnnounced = true;
                s.diagnostics.audit("Match3GeometryRejected reason=unreadable unknown=" + unknown + "/" + total);
            }
            invalidateBoard(s, "UNREADABLE");
            return;
        }
        // Do not restore a previous-window hint over a board that is currently moving.
        if (!matrixEquals(matrix, raw) || !consecutivelyConfirmed) return;
        // Visible matches have not finished resolving. Never recommend a new
        // exchange while an automatic elimination/cascade is still on screen.
        if (!Match3Board.findRuns(matrix).isEmpty()) {
            invalidateHint(s, "CASCADE");
            s.confirmation.reset();
            return;
        }
        s.boardValid = true;
        boolean changed = !matrixEquals(s.lastStableMatrix, matrix);
        // After stable confirmation, even one changed cell is a new revision.
        // Ignoring it forever could keep a hint across a real obstacle/tile change.
        if (changed) {
            invalidateHint(s, "BOARD_CHANGED");
            s.lastStableMatrix = matrix;
            s.lastSwaps = Match3MoveRanker.rankedSwaps(matrix);
            s.boardRevision++; s.hintCount = 0; s.hintAttempts = 0;
            s.lastChangeAt = SystemClock.elapsedRealtime();
            s.diagnostics.audit("BoardRecognized rows=" + matrix.length + " cols=" + matrix[0].length
                    + " unknown=" + unknown + "/" + total + " swaps=" + s.lastSwaps.size()
                    + " revision=" + s.boardRevision + " " + s.geometry);
        }
        if (s.currentHint == null && s.lastSwaps != null && !s.lastSwaps.isEmpty()) {
            s.currentHint = new Match3Hint(s.diagnostics.sessionId, s.boardRevision, s.frameAt,
                    s.geometry, s.lastSwaps.get(0));
            Match3Hint hint = s.currentHint;
            s.diagnostics.audit("Match3Hint revision=" + hint.revision + " from="
                    + hint.swap.fromRow + "," + hint.swap.fromCol + " to="
                    + hint.swap.toRow + "," + hint.swap.toCol + " origin=top_left_zero_based "
                    + Match3MoveRanker.evidence(hint.swap));
            updateHintOverlay(s, hint);
        }
        if (changed && s.lastSwaps.isEmpty())
            s.diagnostics.audit("Match3Status state=no_swap speech=silent");
        maybeAnnounceHint(s, false);
    }

    private BoardGeometry estimateGeometry(Bitmap frame) {
        BoardGeometry candidate = Match3Sampler.autoDetectGeometry(frame);
        var prefs = GameProfile.settings(this);
        if (candidate == null && prefs.getBoolean("match3_calibrated", false)) {
            int[] pct = {prefs.getInt("match3_l", 4), prefs.getInt("match3_t", 18),
                    prefs.getInt("match3_r", 96), prefs.getInt("match3_b", 82)};
            if (Match3Sampler.plausibleCalibration(frame.getWidth(), frame.getHeight(),
                    pct[0], pct[1], pct[2], pct[3], prefs.getInt("match3_rows", 8),
                    prefs.getInt("match3_cols", 8), new int[2])) {
                candidate = Match3Sampler.verifiedGeometry(frame, new int[]{
                        frame.getWidth() * pct[0] / 100, frame.getHeight() * pct[1] / 100,
                        frame.getWidth() * pct[2] / 100, frame.getHeight() * pct[3] / 100});
            }
        }
        return candidate;
    }

    private boolean prepareGeometry(Session s, Bitmap frame, BoardGeometry candidate) {
        BoardGeometry confirmed = s.geometryConfirmation.accept(candidate);
        if (confirmed == null) {
            if (candidate == null && !s.abstainAnnounced && !s.exploreMode) {
                s.abstainAnnounced = true;
                s.diagnostics.audit("Match3GeometryRejected reason=bounds_or_grid_unconfirmed");
            }
            return false;
        }
        Match3Sampler sampler = new Match3Sampler(this, confirmed);
        boolean adopted = false;
        try {
        if (isUnreadableBoard(sampler.sample(frame))) {
            sampler.close();
            s.geometryConfirmation.reset();
            if (!s.abstainAnnounced) {
                s.abstainAnnounced = true;
                s.diagnostics.audit("Match3GeometryRejected reason=cells_unreadable");
            }
            return false;
        }
        var prefs = GameProfile.settings(this);
        int[] savedBounds = {prefs.getInt("match3_l", 4), prefs.getInt("match3_t", 18),
                prefs.getInt("match3_r", 96), prefs.getInt("match3_b", 82)};
        BoardGeometry saved = null;
        try { saved = BoardGeometry.fromPercent(frame.getWidth(), frame.getHeight(),
                prefs.getInt("match3_rows", 8), prefs.getInt("match3_cols", 8), savedBounds); }
        catch (IllegalArgumentException ignored) { }
        boolean calibrationConflict = saved == null || saved.rows != confirmed.rows || saved.cols != confirmed.cols
                || !java.util.Arrays.equals(savedBounds, confirmed.percentages());
        if (prefs.getBoolean("match3_calibrated", false) && calibrationConflict
                && !s.calibrationConflictAnnounced && !s.exploreMode) {
            s.calibrationConflictAnnounced = true;
            s.diagnostics.audit("Match3CalibrationConflict stored=" + saved + " detected=" + confirmed);
        }
        if (!saveCalibration(s, confirmed.percentages(), confirmed.rows, confirmed.cols)) {
            sampler.close(); return false;
        }
        s.geometry = confirmed; s.sampler = sampler;
        adopted = true;
        s.liveRows = confirmed.rows; s.liveCols = confirmed.cols; s.abstainAnnounced = false;
        resetWindow(s);
        s.diagnostics.audit("Match3GeometryConfirmed " + confirmed + " samples=3 axes=independent");
        if (!prefs.getBoolean("match3_hint_highlight_enabled", true))
            updateRowNumbers(s, confirmed.percentages(), confirmed.rows);
        else SenseFieldReaderService.hideRowNumbers();
        return true;
        } finally {
            if (!adopted) sampler.close();
        }
    }

    private void invalidateHint(Session s, String reason) {
        Match3Hint old = s.currentHint;
        s.currentHint = null;
        String cueId = s.hintCueId; s.hintCueId = null;
        if (dispatcher != null && cueId != null) dispatcher.cancelCue(cueId, reason);
        if (s.overlay != null) s.overlay.clear();
        if (s.overlayFilter != null) s.overlayFilter.clear();
        if (old != null) s.diagnostics.audit("Match3HintCleared revision=" + old.revision + " reason=" + reason);
    }

    private void invalidateBoard(Session s, String reason) {
        invalidateHint(s, reason);
        if (s.sampler != null) s.sampler.close();
        s.sampler = null; s.geometry = null; s.geometryConfirmation.reset();
        s.lastStableMatrix = null; s.lastSwaps = null;
        resetWindow(s);
    }

    private void closeOverlay(Session s) {
        if (s != null && s.overlay != null) {
            s.overlay.close(); s.overlay = null; s.blackMonitor.reset();
            if (s.overlayFilter != null) s.overlayFilter.clear();
        }
    }

    private void updateHintOverlay(Session s, Match3Hint hint) {
        if (s.visualSuppressed || !s.fullDisplayCapture
                || !GameProfile.settings(this).getBoolean("match3_hint_highlight_enabled", true)) return;
        if (s.overlay == null) s.overlay = Match3HintOverlay.create(this);
        if (s.overlay != null && !s.overlay.isClosed()) {
            s.overlay.update(hint, () -> active == s && !s.projectionStopped && s.currentHint == hint);
        } else if (!s.overlayHintAnnounced) {
            s.overlayHintAnnounced = true;
            s.diagnostics.audit("Match3VisualUnavailable reason=permission_or_window");
        }
    }

    private void suppressVisual(Session s, String reason) {
        s.visualSuppressed = true;
        closeOverlay(s);
        s.diagnostics.audit("Match3VisualDisabled reason=" + reason);
        if (!"permission_or_window".equals(reason)) new Handler(getMainLooper()).post(() -> {
            if (active == s && !s.projectionStopped) android.widget.Toast.makeText(this,
                    "本机暂时无法安全显示交换高亮，继续使用语音提示。", android.widget.Toast.LENGTH_LONG).show();
        });
    }

    private boolean checkOverlayCapture(Session s, Bitmap frame) {
        if (s.overlay == null) return true;
        if (s.overlay.isClosed() || !android.provider.Settings.canDrawOverlays(this)) {
            suppressVisual(s, "permission_or_window");
            return true;
        }
        Match3Hint shown = s.overlay.renderedHint();
        if (shown == null) return true;
        boolean black = blackBoardRegion(frame, shown.geometry);
        BlackFrameMonitor.Action action = s.blackMonitor.update(black, true, SystemClock.elapsedRealtime());
        if (action == BlackFrameMonitor.Action.DISABLE_OVERLAY) {
            suppressVisual(s, "capture_blackout");
            return false; // wait for a clean frame after removing our window
        }
        return !black;
    }

    private static boolean blackBoardRegion(Bitmap frame, BoardGeometry g) {
        int bright = 0, count = 0;
        for (int row = 0; row < g.rows; row++) for (int col = 0; col < g.cols; col++) {
            int px = frame.getPixel(g.centerX(col), g.centerY(row)); count++;
            if (android.graphics.Color.red(px) + android.graphics.Color.green(px)
                    + android.graphics.Color.blue(px) > 18) bright++;
        }
        return bright == 0 && count > 0;
    }

    private void maybeAnnounceHint(Session s, boolean repeat) {
        Match3Hint hint = s.currentHint;
        long now = SystemClock.elapsedRealtime();
        if (hint == null || !s.boardValid || now - s.frameAt > 5000
                || now - s.lastAnnounceAt < MIN_ANNOUNCE_GAP_MS
                || s.hintAttempts >= 3 || !repeat && s.lastHintSpokenRevision == hint.revision) return;
        // Initial status speech can be ahead of this one; the playback guard rejects old boards.
        ensureDispatcher(s);
        String cueId = hint.sessionId + ":hint:" + hint.revision + ":" + now;
        if (s.hintCueId != null) dispatcher.cancelCue(s.hintCueId, "REPLACED");
        s.hintCueId = cueId; s.lastAnnounceAt = now; s.hintAttempts++;
        s.diagnostics.audit("Match3Speech: " + hint.speech);
        CueDispatcher.DispatchResult result = dispatcher.submit(new CueRequest(hint.sessionId, cueId, "m3live:hint:" + hint.revision,
                "消消乐交换提示", CueRequest.Category.SYSTEM, 70, now, now + 10000,
                Match3LiveCuePolicy.REQUESTED_CHANNELS, 0, 0, 0, hint.speech,
                Float.NaN, Float.NaN, 0, -1, () -> active == s && !s.projectionStopped
                        && s.currentHint == hint && SystemClock.elapsedRealtime() - s.frameAt <= 5000));
        if (result.audioQueued() || "channels_disabled".equals(result.reason)
                || "category_disabled".equals(result.reason)) s.lastHintSpokenRevision = hint.revision;
    }

    private void updateRowNumbers(Session s, int[] bounds, int rows) {
        projectionSession.runIfCurrent(s.generation, s.projection, () -> {
            boolean available = SenseFieldReaderService.showRowNumbers(this,
                    bounds[0], bounds[1], bounds[2], bounds[3], rows);
            if (!available && !s.overlayHintAnnounced) {
                s.overlayHintAnnounced = true;
                s.diagnostics.audit("Match3RowNumbersUnavailable reason=accessibility_not_enabled speech=silent");
            }
            return true;
        });
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

    static int countUnknown(char[][] m) {
        if (Match3Board.columns(m) == 0) return 0;
        int n = 0;
        for (char[] row : m) for (char c : row) if (Match3Sampler.isUnknown(c)) n++;
        return n;
    }

    static boolean isUnreadableBoard(char[][] matrix) {
        int cols = Match3Board.columns(matrix);
        return cols == 0 || countUnknown(matrix) * 100L > matrix.length * (long) cols * 40;
    }

    private static int countDiffCells(char[][] a, char[][] b) {
        if (a == null || b == null || a.length != b.length) return Integer.MAX_VALUE;
        for (int r = 0; r < a.length; r++) if (a[r].length != b[r].length) return Integer.MAX_VALUE;
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

    private void idleCheck(Session s) {
        long now = SystemClock.elapsedRealtime();
        if (!isCurrent(s)) return;
        if (now - s.frameAt > 5000) { invalidateHint(s, "FRAME_STALE"); return; }
        if (s.exploreMode || s.popupAnnounced || !s.boardValid) return;
        maybeAnnounceHint(s, false);
        if (s.currentHint != null && s.hintCount < 2 && now - s.lastChangeAt >= IDLE_HINT_MS
                && now - s.lastAnnounceAt >= MIN_ANNOUNCE_GAP_MS) {
            s.lastChangeAt = now; s.hintCount++;
            maybeAnnounceHint(s, true);
        }
    }

    private void handleExploreTouch(Session s, Bitmap frame) {
        if (!isCurrent(s) || !s.exploreMode || s.sampler == null || s.popupAnnounced) return;
        long touchAt = SenseFieldReaderService.latestTouchAt();
        long now = SystemClock.elapsedRealtime();
        if (touchAt <= s.lastTouchHandledAt || now - touchAt > 1600) return;
        s.lastTouchHandledAt = touchAt;
        int[] hit = s.sampler.touchRead(frame,
                SenseFieldReaderService.latestTouchX(), SenseFieldReaderService.latestTouchY());
        if (hit == null) return;
        announce(s, "第 " + (hit[0] + 1) + " 行，第 " + (hit[1] + 1) + " 列："
                + s.sampler.pieceName((char) hit[2]) + "，"
                + Match3Coach.quadrantOf(hit[0], hit[1], rows(s), cols(s)) + "区域。");
    }

    private int rows(Session s) { return s.sampler == null ? 8 : s.sampler.rowCount(); }
    private int cols(Session s) { return s.sampler == null ? 8 : s.sampler.colCount(); }

    private void announce(Session s, String speech) {
        try {
            projectionSession.runIfCurrent(s.generation, s.projection, () -> {
            if (!isCurrent(s)) return false;
            ensureDispatcher(s);
            long t = SystemClock.elapsedRealtime();
            String sessionId = s.diagnostics.sessionId;
            s.diagnostics.audit("Match3Speech: " + speech);
            dispatcher.submit(new CueRequest(sessionId, sessionId + ":" + t, "m3live:announce",
                    "消消乐实时播报", CueRequest.Category.SYSTEM, 70, t, t + 10000,
                    Match3LiveCuePolicy.REQUESTED_CHANNELS, 0, 0, 0, speech));
            return true;
            });
        } catch (RuntimeException error) {
            Log.w(TAG, "播报通道异常（不致命）: " + error.getClass().getSimpleName());
            if (isCurrent(s)) s.diagnostics.audit("Match3SpeechFailure error=" + error.getClass().getSimpleName());
        }
    }

    private void ensureDispatcher(Session s) {
            if (dispatcher == null) {
                player = new CuePlayer(this);
                dispatcher = new CueDispatcher(player, new Match3LiveCuePolicy(new CueSettings(this)),
                        new CueDispatcher.Listener() {
                            @Override public void onDispatch(CueRequest request,
                                    CueDispatcher.DispatchResult result) {
                                DiagnosticRecorder recorder = diagnosticsForCue(request);
                                if (recorder != null) recorder.dispatch(request, result.outcome,
                                        result.reason, result.acceptedChannels);
                                auditCue(request, "Match3Dispatch cue_id=" + request.cueId
                                        + " outcome=" + result.outcome + " reason=" + result.reason
                                        + " channels=" + result.acceptedChannels);
                            }
                            @Override public void onPlaybackFailure(CueRequest request, long atMs, String reason) {
                                auditCue(request, "Match3PlaybackFailure cue_id=" + request.cueId
                                        + " at_ms=" + atMs + " reason=" + reason);
                            }
                            @Override public void onPlayback(CueRequest request, String channel,
                                    long atMs, String result) {
                                DiagnosticRecorder recorder = diagnosticsForCue(request);
                                if (recorder != null) recorder.playback(request, channel, atMs, result);
                                auditCue(request, "Match3Playback cue_id=" + request.cueId
                                        + " channel=" + channel + " at_ms=" + atMs + " result=" + result);
                            }
                        }, SystemClock::elapsedRealtime);
            }
    }

    private void auditCue(CueRequest request, String message) {
        DiagnosticRecorder recorder = diagnosticsForCue(request);
        if (recorder != null) recorder.audit(message);
    }

    private DiagnosticRecorder diagnosticsForCue(CueRequest request) {
        Session current = active;
        // Playback listeners run under the dispatcher lock; avoid acquiring the
        // projection lock here, since announce() holds them in the opposite order.
        if (current != null && (current.reader == reader || current.projectionStopped)
                && !current.diagnostics.finished
                && current.diagnostics.sessionId.equals(request.sessionId))
            return current.diagnostics;
        return null;
    }

    private Notification buildNotification() {
        PendingIntent mark = PendingIntent.getService(this, 4,
                new Intent(this, Match3LiveService.class).setAction(ACTION_MARK_ISSUE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 5,
                new Intent(this, Match3LiveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Session s = active;
        boolean stopped = s != null && s.projectionStopped;
        return new Notification.Builder(this, "m3live")
                .setContentTitle(stopped ? "听野 · 消消乐录屏已结束" : "听野 · 消消乐实时识别中")
                .setContentText(stopped ? "请重新开始识别，或点击停止保存记录。" : "正在识别棋盘并语音播报")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .addAction(android.R.drawable.ic_menu_edit, "标记问题", mark)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
                .build();
    }

    private void teardownMedia() {
        running = exploring = false;
        Session old = active;
        active = null;
        ImageReader oldReader = reader;
        VirtualDisplay oldDisplay = display;
        MediaProjection oldProjection = projection;
        reader = null; display = null; projection = null;
        SenseFieldReaderService.hideRowNumbers();
        Runnable retireCapture = () -> {
            // ImageReader.close() invalidates acquired planes, including native ByteBuffers.
            // Retire it only after onImageAvailable has returned and closed its Image.
            if (old != null) { invalidateBoard(old, "SERVICE_STOPPED"); closeOverlay(old); }
            if (oldReader != null) try { oldReader.close(); } catch (RuntimeException ignored) { }
            if (old != null && old.frame != null && !old.frame.isRecycled()) old.frame.recycle();
            if (old != null) {
                old.frame = null;
                old.diagnostics.finish("m3live_session_finished");
            }
        };
        if (handler != null) {
            if (old != null) handler.removeCallbacksAndMessages(old);
            handler.post(retireCapture);
        } else retireCapture.run();
        if (dispatcher != null) dispatcher.clearAll();
        if (oldDisplay != null) try { oldDisplay.release(); } catch (RuntimeException ignored) { }
        if (oldProjection != null) try { oldProjection.stop(); } catch (RuntimeException ignored) { }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // API34 reports the actual mirrored-content size after the compositor has rotated.
        // A configuration event can precede that transition and scale/crop the old content.
        if (android.os.Build.VERSION.SDK_INT >= 34) return;
        Session previous = active;
        if (previous == null || previous.projectionStopped) return;
        handler().post(() -> {
            if (!isCurrent(previous)) return;
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
            resizeCapture(previous, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
        });
    }

    /** Capture-worker only: retire old frames and map the new surface to real captured content. */
    private void resizeCapture(Session previous, int width, int height, int densityDpi) {
            if (!isCurrent(previous) || width <= 0 || height <= 0) return;
            if (previous.reader.getWidth() == width && previous.reader.getHeight() == height) return;
            invalidateBoard(previous, "DISPLAY_CHANGED"); closeOverlay(previous);
            ImageReader replacement = null;
            try {
                replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
                display.setSurface(null);
                display.resize(width, height, densityDpi);
                display.setSurface(replacement.getSurface());
                handler().removeCallbacksAndMessages(previous);
                ImageReader oldReader = reader;
                Session next = new Session(previous.generation, previous.projection, replacement, previous.diagnostics);
                next.fullDisplayCapture = previous.fullDisplayCapture;
                next.visualSuppressed = previous.visualSuppressed;
                next.exploreMode = previous.exploreMode;
                next.boardRevision = previous.boardRevision + 1;
                reader = replacement; active = next;
                replacement.setOnImageAvailableListener(source -> onImageAvailable(source, next), handler());
                oldReader.close();
                if (previous.frame != null && !previous.frame.isRecycled()) previous.frame.recycle();
                previous.frame = null;
                next.diagnostics.audit("Match3DisplayChanged frame=" + width + "x" + height);
                scheduleTick(next);
            } catch (RuntimeException error) {
                if (replacement != null) replacement.close();
                previous.diagnostics.audit("Match3DisplayResizeFailed error=" + error.getClass().getSimpleName());
                previous.projection.stop();
            }
    }

    @Override public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) debugInstance = new java.lang.ref.WeakReference<>(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new android.app.NotificationChannel("m3live",
                "消消乐实时识别", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public void onDestroy() {
        if (debugInstance.get() == this) debugInstance.clear();
        projectionSession.invalidate(0);
        teardownMedia();
        if (dispatcher != null) dispatcher.close();
        if (player != null) player.close();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
