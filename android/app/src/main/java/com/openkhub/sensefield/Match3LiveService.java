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
    static final String ACTION_MARK_ISSUE = "com.openkhub.sensefield.m3live.MARK_ISSUE";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_DATA = "data";
    private static final int NOTIFICATION_ID = 3002;
    private static final long CAPTURE_INTERVAL_MS = 800;
    private static final int STABLE_FRAMES = 3;
    private static final long MIN_ANNOUNCE_GAP_MS = 6000;
    private static final long IDLE_HINT_MS = 15000;
    private static volatile boolean running;
    private static volatile boolean exploring;
    static boolean isRunning() { return running; }
    static boolean isExploreMode() { return exploring; }

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
        Bitmap frame;
        long frameAt;
        long processedFrames;
        long lastProcessedAt = -CAPTURE_INTERVAL_MS;
        char[][][] rawWindow = new char[STABLE_FRAMES][][];
        int rawIdx, rawFill;
        char[][] lastAnnouncedMatrix;
        final Match3BoardConfirmation confirmation = new Match3BoardConfirmation();
        List<Match3Board.Swap> lastSwaps;
        long lastChangeAt, lastAnnounceAt, lastTouchHandledAt;
        int hintCount, liveRows = 8, liveCols = 8;
        boolean popupAnnounced, abstainAnnounced, boardValid;
        int unreadableStreak;
        boolean unreadableAnnounced, tinyBoxAnnounced, overlayHintAnnounced;
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
        if (ACTION_MARK_ISSUE.equals(action) || ACTION_EXPLORE_ON.equals(action)
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
                else {
                    s.exploreMode = ACTION_EXPLORE_ON.equals(action);
                    exploring = s.exploreMode;
                    if (dispatcher != null) dispatcher.clearAll();
                    s.lastTouchHandledAt = SystemClock.elapsedRealtime();
                    s.lastSwaps = null;
                    resetWindow(s);
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
            return START_NOT_STICKY;
        }
        long generation = projectionSession.beginStart(startId);
        teardownMedia();
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null || CaptureService.isRunning()) {
            if (CaptureService.isRunning()) android.widget.Toast.makeText(this,
                    "地图识别正在使用录屏，请先停止后再开启消消乐实时识别。",
                    android.widget.Toast.LENGTH_LONG).show();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
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
            active = s;
            if (!isCurrent(s)) throw new IllegalStateException("Projection stopped during setup");
            running = true;
            diagnostics.audit("Match3Session game=happy-anipop capture_interval_ms=" + CAPTURE_INTERVAL_MS);
            currentReader.setOnImageAvailableListener(source -> onImageAvailable(source, s), handler());
            scheduleTick(s);
            Log.i(TAG, "实时识别已启动 " + dm.widthPixels + "x" + dm.heightPixels);
        } catch (RuntimeException error) {
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
            processFrame(s, s.frame);
            s.processedFrames++;
            s.diagnostics.publishState(DiagnosticRecorder.object("game_id", "happy-anipop",
                    "state", s.popupAnnounced ? "popup" : s.exploreMode ? "touch_read" : "capturing",
                    "snapshot_at_ms", SystemClock.elapsedRealtime(), "frames_expected", true,
                    "processed_frames", s.processedFrames, "last_frame_observed_at_ms", observedAt,
                    "last_frame_completed_at_ms", SystemClock.elapsedRealtime(),
                    "frame_width", image.getWidth(), "frame_height", image.getHeight(),
                    "board_valid", s.boardValid, "rows", s.liveRows, "cols", s.liveCols));
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
                s.boardValid = false;
                s.lastSwaps = null;
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
        s.unreadableStreak = 0;
        s.unreadableAnnounced = false;
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
        s.boardValid = false;
        boolean popup = Match3Coach.isPopupShowing(frame);
        if (popup) {
            if (!s.popupAnnounced && !s.exploreMode) announce(s, "检测到游戏说明弹窗。读完或跳过后就可以开始了。");
            s.popupAnnounced = true;
            resetWindow(s);
            return;
        }
        if (s.popupAnnounced) {
            s.popupAnnounced = false;
            resetWindow(s);
            if (!s.exploreMode) announce(s, "说明已结束，可以开始游戏了。");
            return;
        }

        if (s.sampler == null) {
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

                char[][] probe = candidate.sample(frame);
                if (countUnknown(probe) * 100 > probe.length * probe[0].length * 40) {
                    Log.i(TAG, "自动适配命中但采样验证失败（未知过多），本帧不采纳");
                    candidate = null;
                }
            }
            if (candidate != null) {
                if (!saveCalibration(s, auto, candRows, candCols)) return;
                s.liveRows = candRows;
                s.liveCols = candCols;
                s.sampler = candidate;
                s.abstainAnnounced = false;
                updateRowNumbers(s, auto, candRows);
                Log.i(TAG, "棋盘自动适配: l=" + auto[0] + "% t=" + auto[1] + "% r=" + auto[2]
                        + "% b=" + auto[3] + "% 格数=" + candRows + "x" + candCols
                        + "（试采验证通过） " + describeCalibration(s, frame));
            } else if (calibrated) {

                int l = prefs.getInt("match3_l", 4), t = prefs.getInt("match3_t", 18);
                int r = prefs.getInt("match3_r", 96), b = prefs.getInt("match3_b", 82);
                int n = Match3Sampler.detectGridCount(frame, new int[]{l, t, r, b});
                int rows = n > 0 ? n : Math.max(6, Math.min(9, prefs.getInt("match3_rows", 8)));
                int cols = n > 0 ? n : Math.max(6, Math.min(9, prefs.getInt("match3_cols", 8)));
                int[] cell = new int[2];
                if (!Match3Sampler.plausibleCalibration(frame.getWidth(), frame.getHeight(),
                        l, t, r, b, rows, cols, cell)) {
                    if (!s.tinyBoxAnnounced) {
                        s.tinyBoxAnnounced = true;
                        s.diagnostics.audit("Match3CalibrationRejected cell=" + cell[0] + "x" + cell[1]
                                + "px " + describeCalibration(s, frame));
                        announce(s, "棋盘标定区域太小或超出画面，读不清格子。请重新框选整个棋盘。");
                    }
                    return;
                }
                if (n > 0) {
                    if (!projectionSession.runIfCurrent(s.generation, s.projection, () -> {
                        prefs.edit().putInt("match3_rows", rows).putInt("match3_cols", cols).apply();
                        return true;
                    })) return;
                }
                s.liveRows = rows;
                s.liveCols = cols;
                s.sampler = new Match3Sampler(this, rows, cols, l, t, r, b);
                s.abstainAnnounced = false;
                updateRowNumbers(s, new int[]{l, t, r, b}, rows);
                Log.i(TAG, "沿用手动标定 " + rows + "x" + cols
                        + (n > 0 ? "（格数自检=" + n + "）" : "（格数自检弃权，按存值读）")
                        + " " + describeCalibration(s, frame));
            } else {

                if (!s.abstainAnnounced && !s.exploreMode) {
                    announce(s, "还没找到棋盘位置。请先框选标定棋盘区域，或者多等几秒我再试。");
                    s.abstainAnnounced = true;
                    Log.i(TAG, "棋盘自动适配未命中且无手动标定，ABSTAIN 等待框选或后续帧重试");
                }
                return;
            }
        }
        if (s.exploreMode) {
            handleExploreTouch(s, frame);
            return;
        }
        char[][] matrix = s.sampler.sample(frame);

        s.rawWindow[s.rawIdx] = matrix;
        s.rawIdx = (s.rawIdx + 1) % STABLE_FRAMES;
        if (s.rawFill < STABLE_FRAMES) s.rawFill++;
        if (s.rawFill < STABLE_FRAMES) return;
        matrix = majorityMatrix(s.rawWindow);

        // 实时棋盘链路仅在本地识别；实验云端实现不进入录屏线程。

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
            int candRows = rows(s), candCols = cols(s);
            if (auto != null) {
                int n = Match3Sampler.detectGridCount(frame, auto);
                candRows = n > 0 ? n : rows(s);
                candCols = n > 0 ? n : cols(s);
                candidate = new Match3Sampler(this, candRows, candCols, auto[0], auto[1], auto[2], auto[3]);
            }

            char[][] fixed = (candidate != null ? candidate : s.sampler).sample(frame);
            int fixedCells = fixed.length * fixed[0].length;
            if (countUnknown(fixed) * 100 > fixedCells * 40) {
                Log.i(TAG, "自我修复后仍未识别，不采纳新标定");
                s.confirmation.reset();
                s.unreadableStreak++;
                long now = SystemClock.elapsedRealtime();
                if (shouldAnnounceUnreadable(s.unreadableStreak, s.unreadableAnnounced,
                        now - s.lastAnnounceAt)) {
                    s.unreadableAnnounced = true;
                    s.lastAnnounceAt = now;
                    s.diagnostics.audit("Match3BoardUnreadable unknown=" + unknown + "/" + total
                            + " " + describeCalibration(s, frame));
                    announce(s, "这一盘的格子读不清，请重新框选标定棋盘区域。");
                }
                return;
            }
            if (candidate != null) {
                var prefs = GameProfile.settings(this);
                if (!saveCalibration(s, auto, candRows, candCols)) return;
                s.liveRows = candRows;
                s.liveCols = candCols;
                s.sampler = candidate;
                updateRowNumbers(s, auto, candRows);
            }
            resetWindow(s);
            return; // 新标定重新积累稳定窗，不拿单帧直接播报。
        }
        s.unreadableStreak = 0;
        s.unreadableAnnounced = false;
        // Opening animations and transitions must settle across two majority windows.
        if (!s.confirmation.accept(matrix)) return;
        s.boardValid = true;
        if (matrixEquals(s.lastAnnouncedMatrix, matrix)) {
            return;
        }

        int diffCells = countDiffCells(s.lastAnnouncedMatrix, matrix);
        if (s.lastAnnouncedMatrix != null && diffCells < 2) {
            return;
        }

        boolean isFirst = s.lastAnnouncedMatrix == null;
        s.lastChangeAt = SystemClock.elapsedRealtime();
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(matrix);
        s.lastSwaps = swaps;
        long now = SystemClock.elapsedRealtime();
        if (!isFirst && now - s.lastAnnounceAt < MIN_ANNOUNCE_GAP_MS) {

            return;
        }
        s.lastAnnouncedMatrix = matrix;
        s.hintCount = 0;
        s.lastAnnounceAt = now;
        StringBuilder sb = new StringBuilder(isFirst ? "棋盘识别完成。" : "局面更新。");
        if (!swaps.isEmpty()) {
            sb.append(Match3Coach.swapSpeechWithQuadrant(swaps.get(0), s.liveRows, s.liveCols));
            if (swaps.size() > 1) sb.append("，共 ").append(swaps.size()).append(" 处");
        } else {
            sb.append("暂无可消除交换。");
        }
        Log.i(TAG, sb.toString());
        Log.i(TAG, "播报读数 " + matrix.length + "x" + matrix[0].length
                + " 未知=" + unknown + "/" + total + " 变化=" + diffCells + "格 "
                + describeCalibration(s, frame));
        if (s.diagnostics != null) {
            s.diagnostics.audit("BoardRecognized rows=" + matrix.length + " cols=" + matrix[0].length
                    + " unknown=" + unknown + "/" + total + " swaps=" + swaps.size());

        }
        announce(s, sb.toString());
    }

    private String describeCalibration(Session s, Bitmap frame) {
        var prefs = GameProfile.settings(this);
        int l = prefs.getInt("match3_l", 4), t = prefs.getInt("match3_t", 18);
        int r = prefs.getInt("match3_r", 96), b = prefs.getInt("match3_b", 82);
        int w = frame.getWidth() * (r - l) / 100;
        int h = frame.getHeight() * (b - t) / 100;
        return "标定=" + l + "/" + t + "/" + r + "/" + b + "% 裁剪=" + w + "x" + h
                + "px 单格≈" + w / Math.max(1, cols(s)) + "x" + h / Math.max(1, rows(s))
                + "px 格数=" + rows(s) + "x" + cols(s);
    }

    private void updateRowNumbers(Session s, int[] bounds, int rows) {
        projectionSession.runIfCurrent(s.generation, s.projection, () -> {
            boolean available = SenseFieldReaderService.showRowNumbers(this,
                    bounds[0], bounds[1], bounds[2], bounds[3], rows);
            if (!available && !s.overlayHintAnnounced) {
                s.overlayHintAnnounced = true;
                announce(s, "行号和触屏点读需要开启听野读屏辅助，可在系统无障碍设置中开启。");
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
        int n = 0;
        for (char[] row : m) for (char c : row) if (Match3Sampler.isUnreadable(c)) n++;
        return n;
    }

    static boolean isUnreadableBoard(char[][] matrix) {
        return countUnknown(matrix) * 100 > matrix.length * matrix[0].length * 40;
    }

    static boolean shouldAnnounceUnreadable(int streak, boolean alreadyAnnounced, long sinceLastAnnounceMs) {
        return streak >= 2 && !alreadyAnnounced && sinceLastAnnounceMs >= MIN_ANNOUNCE_GAP_MS;
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
        if (!isCurrent(s) || s.exploreMode || s.popupAnnounced || !s.boardValid
                || now - s.frameAt > 5000) return;
        if (s.lastAnnouncedMatrix != null && s.lastSwaps != null && !s.lastSwaps.isEmpty()
                && s.hintCount < 2 && now - s.lastChangeAt >= IDLE_HINT_MS
                && now - s.lastAnnounceAt >= MIN_ANNOUNCE_GAP_MS) {
            s.lastAnnounceAt = s.lastChangeAt = now;
            s.hintCount++;
            announce(s, (s.hintCount == 1 ? "还在犹豫的话，" : "仍然可以：")
                    + Match3Board.swapSpeech(s.lastSwaps.get(0)) + "。不需要时忽略即可。");
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
                + Match3Coach.pieceName((char) hit[2]) + "，"
                + Match3Coach.quadrantOf(hit[0], hit[1], rows(s), cols(s)) + "区域。");
    }

    private int rows(Session s) { return s.sampler == null ? 8 : s.sampler.rowCount(); }
    private int cols(Session s) { return s.sampler == null ? 8 : s.sampler.colCount(); }

    private void announce(Session s, String speech) {
        try {
            projectionSession.runIfCurrent(s.generation, s.projection, () -> {
            if (!isCurrent(s)) return false;
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
                            @Override public void onPlayback(CueRequest request, String channel,
                                    long atMs, String result) {
                                DiagnosticRecorder recorder = diagnosticsForCue(request);
                                if (recorder != null) recorder.playback(request, channel, atMs, result);
                                auditCue(request, "Match3Playback cue_id=" + request.cueId
                                        + " channel=" + channel + " at_ms=" + atMs + " result=" + result);
                            }
                        }, SystemClock::elapsedRealtime);
            }
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
        SenseFieldReaderService.hideRowNumbers();
        if (handler != null) {
            if (old != null) handler.removeCallbacksAndMessages(old);
            // Recycling is queued after any in-progress recognition using this bitmap.
            handler.post(() -> {
                if (old != null && old.frame != null && !old.frame.isRecycled()) old.frame.recycle();
                if (old != null) old.frame = null;
            });
        }
        if (dispatcher != null) dispatcher.clearAll();
        if (old != null) old.diagnostics.finish("m3live_session_finished");
        if (reader != null) { try { reader.close(); } catch (Exception ignored) { } reader = null; }
        if (display != null) { try { display.release(); } catch (Exception ignored) { } display = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) { } projection = null; }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new android.app.NotificationChannel("m3live",
                "消消乐实时识别", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public void onDestroy() {
        projectionSession.invalidate(0);
        teardownMedia();
        if (dispatcher != null) dispatcher.close();
        if (player != null) player.close();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
