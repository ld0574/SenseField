package com.openkhub.sensefield;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Local diagnostics sharing the existing capture stream; no second projection. */
final class DiagnosticRecorder {
    static final String PREF_IMAGES = "diagnostic_images";
    static final long IMAGE_PERIOD_MS = 10000;
    static final int CONTEXT_SCREEN_EDGE = 480;
    static final int CONTEXT_MAP_EDGE = 512;
    static final long CONTEXT_FRAME_BYTES = 2L * 1024 * 1024;
    static final int MAX_IMAGE_JOBS = 8;
    static final long MAX_IMAGE_JOB_BYTES = 16L * 1024 * 1024;
    private static final long CLEAR_FRAME_BYTES = 6L * 1024 * 1024;
    // IO is shared across sessions; restarting capture must not multiply its image backlog.
    private static final DiagnosticImageBudget IMAGE_BUDGET = new DiagnosticImageBudget(
            MAX_IMAGE_JOBS, MAX_IMAGE_JOB_BYTES);
    static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SenseFieldDiagnosticIO");
        thread.setDaemon(true);
        return thread;
    });
    static volatile DiagnosticRecorder current;
    private static final ScheduledExecutorService HEARTBEATS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "SenseFieldDiagnosticHeartbeat");
        thread.setDaemon(true);
        return thread;
    });
    final String sessionId;
    final DiagnosticGame game;
    final File directory;
    final long startedAtMs;
    volatile boolean finished;
    volatile String failure = "";
    private final Context context;
    private final boolean allowPortrait;
    private DiagnosticArchive archive;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicInteger imageDrops = new AtomicInteger();
    private final Object imageStateLock = new Object();
    private final DiagnosticImageWindow<ImageSample> imageWindow = new DiagnosticImageWindow<>();
    private final DiagnosticImageBudget imageBudget = IMAGE_BUDGET;
    private final LinkedHashSet<Long> scheduledImageFrames = new LinkedHashSet<>();
    private volatile long imageGeneration;
    private volatile boolean imageLimit;
    private volatile boolean imagesEnabled;
    private long nextImageAtMs;
    private long nextDeviceSampleAtMs;
    private long lastFlushAtMs;
    private volatile long frameSequence;
    private volatile long imageCount;
    private boolean markerPending;
    private long lastContextObservedAtMs = -1;
    private long lastImageObservedAtMs = -1;
    private volatile long contextCopiedFrames;
    private volatile long contextCopyMicros;
    private volatile long maxContextCopyMicros;
    private volatile long maxContextFrameBytes;
    private volatile long contextWindows;
    private volatile long contextRateLimited;
    private volatile long contextDeferredFrames;
    // Written only on the single IO executor, read without the capture lock.
    // Cumulative job cost includes image encoding, writes and job metadata.
    private volatile long imageJobsCompleted;
    private volatile long imageJobWallMicros;
    private volatile long imageJobCpuMicros;
    private volatile JSONObject lastDeviceSample;
    private volatile JSONObject latestState;
    private volatile LoadSample latestLoadSample = new LoadSample(-1, -1, -1);
    private ScheduledFuture<?> heartbeat;

    /** Immutable device load snapshot for readers outside the capture worker. */
    static final class LoadSample {
        /** Battery temperature reported by ACTION_BATTERY_CHANGED, in tenths °C; -1 if unavailable. */
        final int tempTenthsC;
        /** Android thermal status; -1 if unavailable. */
        final int thermalStatus;
        /** elapsedRealtime timestamp of this sample. */
        final long sampledAtMs;

        LoadSample(int tempTenthsC, int thermalStatus, long sampledAtMs) {
            this.tempTenthsC = tempTenthsC;
            this.thermalStatus = thermalStatus;
            this.sampledAtMs = sampledAtMs;
        }
    }

    static File root(Context context) { return new File(context.getFilesDir(), "diagnostics"); }

    static String activeDirectory() {
        DiagnosticRecorder recorder = current;
        return recorder != null && !recorder.finished ? recorder.directory.getName() : null;
    }

    static void recoverIncomplete(Context context) {
        File directory = root(context);
        IO.execute(() -> {
            try { DiagnosticRecovery.recover(directory, activeDirectory(), System.currentTimeMillis()); }
            catch (IOException error) { Log.w("SenseFieldDiagnostics", "Could not recover all records", error); }
        });
    }

    static boolean imagesEnabledPreference(Context context) {
        android.content.SharedPreferences settings = GameProfile.settings(context);
        return !settings.contains(PREF_IMAGES) || settings.getBoolean(PREF_IMAGES, true);
    }

    static DiagnosticRecorder start(Context context, String sessionId, long startedAtMs) {
        return start(context, sessionId, startedAtMs, DiagnosticGame.HONOR);
    }

    static DiagnosticRecorder start(Context context, String sessionId, long startedAtMs,
                                    boolean allowPortrait) {
        return start(context, sessionId, startedAtMs, allowPortrait ? DiagnosticGame.MATCH3 : DiagnosticGame.HONOR);
    }

    static DiagnosticRecorder start(Context context, String sessionId, long startedAtMs, DiagnosticGame game) {
        DiagnosticRecorder recorder = new DiagnosticRecorder(context, sessionId, startedAtMs, game);
        current = recorder;
        return recorder;
    }

    private DiagnosticRecorder(Context supplied, String sessionId, long startedAtMs, DiagnosticGame game) {
        this.game = game;
        this.allowPortrait = game == DiagnosticGame.MATCH3;
        context = supplied.getApplicationContext();
        this.sessionId = sessionId;
        this.startedAtMs = startedAtMs;
        latestState = object("game_id", game.id, "state", "starting", "snapshot_at_ms", startedAtMs,
                "frames_expected", false, "last_frame_arrived_at_ms", -1,
                "last_frame_observed_at_ms", -1, "last_frame_completed_at_ms", -1,
                "processed_frames", 0, "landscape_processed_frames", 0);
        imagesEnabled = imagesEnabledPreference(context);
        directory = new File(root(context), "diag-" + System.currentTimeMillis() + "-" + sessionId);
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        String versionName;
        try {
            versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            versionName = "unknown";
        }
        JSONObject metadata = object("schema", "sensefield.diagnostics", "schema_version", 1,
                "game_id", game.id, "game_name", game.label,
                "session_id", sessionId, "started_elapsed_ms", startedAtMs,
                "started_wall_ms", System.currentTimeMillis(), "model", Build.MODEL,
                "app_version", versionName,
                "manufacturer", Build.MANUFACTURER, "brand", Build.BRAND,
                "product", Build.PRODUCT, "android_release", Build.VERSION.RELEASE,
                "sdk", Build.VERSION.SDK_INT, "screen_width_px", display.widthPixels,
                "screen_height_px", display.heightPixels, "density_dpi", display.densityDpi,
                "images_enabled", imagesEnabled, "portrait_images_allowed", allowPortrait, "image_period_ms", IMAGE_PERIOD_MS,
                "context_sample_period_ms", DiagnosticImageWindow.SAMPLE_PERIOD_MS,
                "context_pre_ms", DiagnosticImageWindow.PRE_WINDOW_MS,
                "context_post_ms", DiagnosticImageWindow.POST_WINDOW_MS,
                "context_max_post_ms", DiagnosticImageWindow.MAX_WINDOW_MS,
                "context_auto_gap_ms", DiagnosticImageWindow.AUTO_WINDOW_GAP_MS,
                "context_max_frames", DiagnosticImageWindow.MAX_CACHED_FRAMES,
                "context_frame_bytes_limit", CONTEXT_FRAME_BYTES,
                "context_screen_edge", CONTEXT_SCREEN_EDGE, "context_map_edge", CONTEXT_MAP_EDGE,
                "image_jobs_limit", MAX_IMAGE_JOBS, "image_job_bytes_limit", MAX_IMAGE_JOB_BYTES,
                "image_job_budget_scope", "shared across sessions in this app process; peak counters are process lifetime",
                "image_duration_limit_ms", 1200000, "image_bytes_limit", DiagnosticArchive.IMAGE_LIMIT,
                "time_note", "Game HUD time is visible in optional screenshots; elapsed_ms is app-session time.",
                "image_note", "Optional local sampled context plus event/periodic screenshots; JPEG, not continuous video or exact replay pixels. Event context may be rate limited or incomplete; see image_context/image_request records.",
                "resolution_note", "screen_*_px is the display baseline; each frame records the actual projection width/height.");
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            metadata.put("version_name", info.versionName);
            metadata.put("version_code", info.getLongVersionCode());
        } catch (Exception ignored) { /* Diagnostics must not prevent capture. */ }
        IO.execute(() -> {
            try { archive = new DiagnosticArchive(root(context), directory.getName(), metadata.toString(2)); }
            catch (Exception error) { failed(error); }
        });
        checkpoint(SystemClock.elapsedRealtime());
        heartbeat = HEARTBEATS.scheduleWithFixedDelay(() -> checkpoint(SystemClock.elapsedRealtime()),
                DiagnosticCheckpoint.PERIOD_MS, DiagnosticCheckpoint.PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    static JSONObject object(Object... fields) {
        JSONObject out = new JSONObject();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            try { out.put(String.valueOf(fields[i]), fields[i + 1]); }
            catch (JSONException ignored) { /* Nonfinite metrics are unavailable. */ }
        }
        return out;
    }

    private static JSONArray array(Object values) {
        try { return new JSONArray(values); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    void setImagesEnabled(boolean enabled) {
        synchronized (imageStateLock) {
            imagesEnabled = enabled;
            imageGeneration++;
            clearImageContextLocked();
            scheduledImageFrames.clear();
            nextImageAtMs = 0;
        }
        record("image_setting", object("enabled", enabled));
    }

    void markIssue() {
        long requestedAtMs = SystemClock.elapsedRealtime();
        record("user_marker", object("source", "notification", "requested_at_ms", requestedAtMs,
                "images_enabled", imagesEnabled));
        synchronized (imageStateLock) {
            if (finished || !failure.isEmpty() || !imagesEnabled || imageLimit
                    || requestedAtMs - startedAtMs > 1200000) return;
            markerPending = true;
            // Export recent context now, even if pausing/notification UI prevents a next frame.
            // Anchor to the last capture time; the request's real timestamp is recorded above.
            if (lastContextObservedAtMs >= 0 && requestedAtMs >= lastImageObservedAtMs
                    && requestedAtMs - lastImageObservedAtMs <= DiagnosticImageWindow.PRE_WINDOW_MS) {
                exportWindowLocked(imageWindow.trigger(lastImageObservedAtMs, true),
                        "user_marker", requestedAtMs, true);
            }
        }
    }

    void audit(String message) {
        if (message.contains("sessionId=") && !message.contains("sessionId=" + sessionId)) return;
        record("audit", object("message", message));
    }

    void dispatch(CueRequest request, String outcome, String reason, int acceptedChannels) {
        if (finished || !failure.isEmpty()) return;
        record("CueDispatch", object("cue_id", request.cueId, "event_key", request.eventKey,
                "category", request.category == null ? "unknown" : request.category.name(),
                "outcome", outcome, "reason", reason,
                "accepted_channels", acceptedChannels));
    }

    void playback(CueRequest request, String channel, long atMs, String result) {
        if (finished || !failure.isEmpty()) return;
        record("CuePlayback", object("cue_id", request.cueId, "event_key", request.eventKey,
                "channel", channel, "at_ms", atMs, "result", result));
    }

    void profile(GameProfile p, String source, CueSettings cues) {
        record("profile", object("source", source, "name", p.name, "version", p.version,
                "rois_xywh", array(p.rois), "input_size", p.yoloxInputSize,
                "confidence", p.yoloxConfidence, "class_thresholds", array(p.yoloxClassThresholds),
                "observation_age_budget_ms", p.eventInts[0],
                "relation_floats", p.relation == null ? JSONObject.NULL : array(p.relation.floats),
                "relation_ints", p.relation == null ? JSONObject.NULL : array(p.relation.ints),
                "cue_channels", cues.enabledChannels(), "near_channels", cues.nearRequestedChannels()));
    }

    void frame(NativeFrameResult f, DiagnosticSnapshot raw, ByteBuffer pixels,
               int width, int height, int rowStride, long observedAtMs, long completedAtMs,
               int ageBudgetMs) {
        if (finished || !failure.isEmpty()) return;
        long sequence = ++frameSequence;
        JSONArray observations = new JSONArray();
        int overAge = 0;
        for (long[] row : raw.observations) {
            long age = raw.engineAtMs - row[7];
            if (raw.engineAtMs >= 0 && age > ageBudgetMs) overAge++;
            observations.put(object("kind", row[0], "direction", row[1], "bbox_ppm",
                    array(new long[]{row[2], row[3], row[4], row[5]}),
                    "confidence_milli", row[6], "observed_at_ms", row[7]));
        }
        JSONArray entities = new JSONArray();
        for (TrackedEntity e : f.entities) entities.put(object("kind", e.entityKind,
                "track_id", e.trackId, "state", e.state, "freshness_ms", e.freshnessMs,
                "last_seen_ms", e.lastSeenAtMs, "confidence", e.confidence,
                "bbox_xywh", array(new float[]{e.bbox.left, e.bbox.top, e.bbox.width(), e.bbox.height()})));
        NativeFrameResult.Relation r = f.relation;
        record("frame", object("frame_index", sequence, "observed_at_ms", observedAtMs,
                "completed_at_ms", completedAtMs, "engine_at_ms", raw.engineAtMs,
                "age_at_engine_ms", raw.engineAtMs < 0 ? -1 : raw.engineAtMs - observedAtMs,
                "age_at_completion_ms", completedAtMs - observedAtMs,
                "native_micros", f.processingMicros, "observation_age_budget_ms", ageBudgetMs,
                "over_age_budget_count", overAge, "observation_count", f.observationCount,
                "raw_observations", observations, "entities", entities,
                "frame_width", width, "frame_height", height, "row_stride", rowStride,
                "locator_state", f.locatorState, "locator_score_milli", f.locatorScoreMilli,
                "minimap_roi_xywh", array(new float[]{f.minimapRoi.left, f.minimapRoi.top,
                        f.minimapRoi.width(), f.minimapRoi.height()}),
                "relation", object("state", NearZoneRouting.stateName(r.state),
                        "event", NearZoneRouting.eventName(r.event), "reliable", r.reliable,
                        "distance", r.nearestDistance, "sector", r.sector, "episode_id", r.episodeId,
                        "suppression", NearZoneRouting.suppressionName(r.suppression))));
        captureImages(f, pixels, width, height, rowStride, sequence, observedAtMs, completedAtMs);
    }

    private void captureImages(NativeFrameResult f, ByteBuffer pixels, int width, int height,
                               int rowStride, long sequence, long observedAtMs, long completedAtMs) {
        synchronized (imageStateLock) {
            if (!imagesEnabled || imageLimit || (!allowPortrait && width <= height)
                    || completedAtMs - startedAtMs > 1200000) {
                clearImageContextLocked();
                return;
            }
            boolean manual = markerPending;
            boolean event = f.relation.event == NearZoneRouting.EVENT_NEAR_ENTER;
            boolean periodic = completedAtMs >= nextImageAtMs;
            lastImageObservedAtMs = observedAtMs;
            boolean sampleDue = imageWindow.shouldSample(observedAtMs);
            try {
                ImageSample contextSample = null;
                if (sampleDue || event || manual) {
                    long copyStartedNs = System.nanoTime();
                    ImageSample sample = copyImageSample(f, pixels, width, height, rowStride,
                            sequence, observedAtMs, CONTEXT_SCREEN_EDGE, CONTEXT_MAP_EDGE);
                    long micros = (System.nanoTime() - copyStartedNs) / 1000;
                    contextCopyMicros += micros;
                    maxContextCopyMicros = Math.max(maxContextCopyMicros, micros);
                    if (sample != null && sample.bytes <= CONTEXT_FRAME_BYTES) {
                        contextCopiedFrames++;
                        maxContextFrameBytes = Math.max(maxContextFrameBytes, sample.bytes);
                        imageWindow.add(sequence, observedAtMs, sample);
                        lastContextObservedAtMs = observedAtMs;
                        contextSample = sample;
                    } else {
                        imageDrops.incrementAndGet();
                        record("image_request", object("frame_index", sequence,
                                "status", "copy_unavailable", "reason", "context"));
                    }
                }

                DiagnosticImageWindow.TriggerResult<ImageSample> triggered = event || manual
                        ? imageWindow.trigger(observedAtMs, manual) : null;
                long currentWindowId = triggered == null
                        ? imageWindow.activeWindowId(observedAtMs) : triggered.id;
                // Keep the original clear event/periodic image. Queue it before the pre-window
                // so context cannot consume all admission slots ahead of the triggering frame.
                if (periodic || event || manual) {
                    ImageSample clearFrame = copyImageSample(f, pixels, width, height, rowStride,
                            sequence, observedAtMs, 960, 768);
                    enqueueImageLocked(clearFrame, event ? "near_enter" : manual ? "user_marker" : "periodic",
                            currentWindowId);
                    nextImageAtMs = completedAtMs + IMAGE_PERIOD_MS;
                }
                if (event || manual) {
                    markerPending = false;
                    exportWindowLocked(triggered, manual ? "user_marker_frame" : "near_enter", completedAtMs, false);
                } else if (sampleDue) {
                    if (currentWindowId != 0) {
                        // Use the owned sample without rereading a released ImageReader buffer.
                        if (contextSample != null)
                            enqueueImageLocked(contextSample, "post_context", currentWindowId);
                    }
                }
            } catch (RuntimeException error) {
                imageDrops.incrementAndGet();
                record("image_request", object("frame_index", sequence,
                        "status", "copy_failed", "error", error.getClass().getSimpleName()));
            }
        }
    }

    private static ImageSample copyImageSample(NativeFrameResult f, ByteBuffer pixels, int width,
                                               int height, int rowStride, long sequence,
                                               long observedAtMs, int screenEdge, int mapEdge) {
        DiagnosticPixels screen = DiagnosticPixels.copy(pixels, width, height, rowStride,
                0, 0, width, height, screenEdge);
        int x0 = Math.max(0, (int) Math.floor(f.minimapRoi.left * width));
        int y0 = Math.max(0, (int) Math.floor(f.minimapRoi.top * height));
        int x1 = Math.min(width, (int) Math.ceil(f.minimapRoi.right * width));
        int y1 = Math.min(height, (int) Math.ceil(f.minimapRoi.bottom * height));
        DiagnosticPixels map = DiagnosticPixels.copy(pixels, width, height, rowStride,
                x0, y0, x1, y1, mapEdge);
        if (screen == null) return null;
        return new ImageSample(sequence, observedAtMs, screen, map,
                new int[]{x0, y0, x1 - x0, y1 - y0});
    }

    private void exportWindowLocked(DiagnosticImageWindow.TriggerResult<ImageSample> window,
                                    String reason, long requestedAtMs, boolean reserveMarkerFrame) {
        if (window.status == DiagnosticImageWindow.TriggerStatus.ACCEPTED) contextWindows++;
        if (window.status == DiagnosticImageWindow.TriggerStatus.RATE_LIMITED) contextRateLimited++;
        JSONArray frames = new JSONArray();
        for (DiagnosticImageWindow.Entry<ImageSample> entry : window.entries) {
            frames.put(entry.frameIndex);
            // A tap can occur before its next processed frame. Do not let that tap's
            // pre-context consume the final slot/bytes needed by the subsequent clear frame.
            if (reserveMarkerFrame && !scheduledImageFrames.contains(entry.frameIndex)
                    && (imageBudget.pendingFrames() >= MAX_IMAGE_JOBS - 1
                    || imageBudget.pendingBytes() + entry.payload.bytes > MAX_IMAGE_JOB_BYTES - CLEAR_FRAME_BYTES)) {
                contextDeferredFrames++;
                record("image_request", object("frame_index", entry.frameIndex, "window_id", window.id,
                        "status", "deferred_for_marker_frame", "reason", reason));
                continue;
            }
            enqueueImageLocked(entry.payload, "pre_context", window.id);
        }
        record("image_context", object("window_id", window.id, "status", window.status.name(),
                "reason", reason, "requested_at_ms", requestedAtMs,
                "start_observed_at_ms", window.startMs, "end_observed_at_ms", window.endMs,
                "pre_frame_indices", frames, "sampling_note", "Sampled context; may be incomplete."));
    }

    private void enqueueImageLocked(ImageSample sample, String reason, long windowId) {
        if (sample == null) { imageDrops.incrementAndGet(); return; }
        if (scheduledImageFrames.contains(sample.sequence)) {
            record("image_request", object("frame_index", sample.sequence, "observed_at_ms", sample.observedAtMs,
                    "reason", reason, "window_id", windowId, "status", "already_requested"));
            return;
        }
        if (!imageBudget.tryAcquire(sample.bytes)) {
            imageDrops.incrementAndGet();
            record("image_request", object("frame_index", sample.sequence, "observed_at_ms", sample.observedAtMs,
                    "reason", reason, "window_id", windowId, "status", "queue_limited"));
            return;
        }
        scheduledImageFrames.add(sample.sequence);
        while (scheduledImageFrames.size() > 64)
            scheduledImageFrames.remove(scheduledImageFrames.iterator().next());
        long generation = imageGeneration;
        record("image_request", object("frame_index", sample.sequence, "observed_at_ms", sample.observedAtMs,
                "reason", reason, "window_id", windowId, "status", "queued", "raw_bytes", sample.bytes));
        IO.execute(() -> {
            long jobStartedNs = System.nanoTime();
            long jobCpuStartedNs = Debug.threadCpuTimeNanos();
            try {
                if (archive == null) return;
                if (!imageRequestValid(generation)) {
                    appendImageResult(sample, reason, windowId, "cancelled");
                    return;
                }
                long before = imageCount;
                saveImage(sample.screen, "screen-" + sample.sequence + "-" + sample.observedAtMs + ".jpg",
                        sample.sequence, sample.observedAtMs, null, generation, reason, windowId);
                saveImage(sample.map, "map-" + sample.sequence + "-" + sample.observedAtMs + ".jpg",
                        sample.sequence, sample.observedAtMs, sample.sourceBounds, generation, reason, windowId);
                long saved = imageCount - before;
                appendImageResult(sample, reason, windowId, saved == 2 ? "saved_pair" : saved == 1 ? "partial" : "not_saved");
                archive.flush();
            } catch (Exception error) { failed(error); }
            finally {
                imageJobWallMicros += Math.max(0, (System.nanoTime() - jobStartedNs) / 1000);
                imageJobCpuMicros += Math.max(0, (Debug.threadCpuTimeNanos() - jobCpuStartedNs) / 1000);
                imageJobsCompleted++;
                imageBudget.release(sample.bytes);
            }
        });
    }

    private boolean imageRequestValid(long generation) {
        return imagesEnabled && generation == imageGeneration && !imageLimit;
    }

    private void appendImageResult(ImageSample sample, String reason, long windowId,
                                    String status) throws IOException {
        long atMs = SystemClock.elapsedRealtime();
        archive.append(object("type", "image_request_result", "at_ms", atMs,
                "elapsed_ms", Math.max(0, atMs - startedAtMs),
                "data", object("frame_index", sample.sequence, "observed_at_ms", sample.observedAtMs,
                        "reason", reason, "window_id", windowId, "status", status)).toString());
    }

    void clearImageContext(String reason) {
        synchronized (imageStateLock) {
            long windowId = lastImageObservedAtMs < 0 ? 0 : imageWindow.activeWindowId(lastImageObservedAtMs);
            record("image_context_reset", object("window_id", windowId, "reason", reason,
                    "last_observed_at_ms", lastImageObservedAtMs, "post_window_interrupted", windowId != 0));
            clearImageContextLocked();
        }
    }

    private void clearImageContextLocked() {
        imageWindow.clear();
        lastContextObservedAtMs = -1;
        lastImageObservedAtMs = -1;
        markerPending = false;
    }

    private static final class ImageSample {
        final long sequence;
        final long observedAtMs;
        final DiagnosticPixels screen;
        final DiagnosticPixels map;
        final int[] sourceBounds;
        final long bytes;

        ImageSample(long sequence, long observedAtMs, DiagnosticPixels screen, DiagnosticPixels map,
                    int[] sourceBounds) {
            this.sequence = sequence;
            this.observedAtMs = observedAtMs;
            this.screen = screen;
            this.map = map;
            this.sourceBounds = sourceBounds;
            bytes = ((long) screen.argb.length + (map == null ? 0 : map.argb.length)) * 4;
        }
    }

    private void saveImage(DiagnosticPixels data, String name, long sequence, long observedAtMs,
                           int[] sourceBounds, long generation, String reason, long windowId) throws IOException {
        if (data == null || !imageRequestValid(generation)) return;
        Bitmap bitmap = Bitmap.createBitmap(data.argb, data.width, data.height, Bitmap.Config.ARGB_8888);
        byte[] bytes;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, sourceBounds == null ? 75 : 92, out))
                throw new IOException("无法保存诊断画面");
            bytes = out.toByteArray();
        } finally { bitmap.recycle(); }
        if (!imageRequestValid(generation)) return;
        if (!archive.image(name, bytes)) {
            imageLimit = true;
            archive.append(object("type", "image_limit", "at_ms", SystemClock.elapsedRealtime()).toString());
            return;
        }
        imageCount++;
        archive.append(object("type", "image", "frame_index", sequence, "observed_at_ms", observedAtMs,
                "file", "images/" + name, "output_width", data.width, "output_height", data.height,
                "reason", reason, "window_id", windowId,
                "source_bounds_xywh", sourceBounds == null ? JSONObject.NULL : array(sourceBounds)).toString());
    }

    private synchronized void sampleDevice(long now) {
        if (now < nextDeviceSampleAtMs) return;
        nextDeviceSampleAtMs = now + 10000;
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            AudioManager audio = context.getSystemService(AudioManager.class);
            PowerManager power = context.getSystemService(PowerManager.class);
            int thermalStatus = power == null ? -1 : power.getCurrentThermalStatus();
            int batteryTempTenthsC = battery == null ? -1 :
                    battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int batteryStatus = battery == null ? BatteryManager.BATTERY_STATUS_UNKNOWN :
                    battery.getIntExtra(BatteryManager.EXTRA_STATUS,
                            BatteryManager.BATTERY_STATUS_UNKNOWN);
            int batteryPlugged = battery == null ? -1 :
                    battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            JSONObject sample = object("battery_temp_tenths_c", batteryTempTenthsC,
                    "battery_level", battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                    "battery_status", batteryStatus,
                    "battery_plugged", batteryPlugged,
                    "charging", batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                            batteryStatus == BatteryManager.BATTERY_STATUS_FULL,
                    "media_volume", audio == null ? -1 : audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    "media_volume_max", audio == null ? -1 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    "thermal_status", thermalStatus,
                    "thermal_status_name", thermalStatusName(thermalStatus));
            lastDeviceSample = sample;
            latestLoadSample = new LoadSample(batteryTempTenthsC, thermalStatus,
                    SystemClock.elapsedRealtime());
            record("device", sample);
        } catch (RuntimeException ignored) { /* Optional device state. */ }
    }

    LoadSample latestLoadSample() { return latestLoadSample; }

    private static String thermalStatusName(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "none";
            case PowerManager.THERMAL_STATUS_LIGHT: return "light";
            case PowerManager.THERMAL_STATUS_MODERATE: return "moderate";
            case PowerManager.THERMAL_STATUS_SEVERE: return "severe";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "shutdown";
            default: return "unavailable";
        }
    }

    void record(String type, JSONObject data) {
        if (finished || !failure.isEmpty()) return;
        long at = SystemClock.elapsedRealtime();
        String line = object("type", type, "session_id", sessionId, "at_ms", at,
                "elapsed_ms", Math.max(0, at - startedAtMs), "data", data).toString();
        if (pending.incrementAndGet() > 256) { pending.decrementAndGet(); dropped.incrementAndGet(); return; }
        IO.execute(() -> {
            try {
                if (archive == null) return;
                if (!archive.append(line)) dropped.incrementAndGet();
                long now = SystemClock.elapsedRealtime();
                if (now - lastFlushAtMs >= 500 || !"frame".equals(type)) {
                    archive.flush(); lastFlushAtMs = now;
                }
            } catch (Exception error) { failed(error); }
            finally { pending.decrementAndGet(); }
        });
    }

    /** Published snapshots are immutable and never require the processing lock to read. */
    void publishState(JSONObject state) { if (!finished) latestState = state; }

    /** A local read-only snapshot for same-signature ADB diagnostics; no exported component. */
    @androidx.annotation.Keep
    String stateForDiagnostics() {
        JSONObject state = latestState;
        return state == null ? "{}" : state.toString();
    }

    private void checkpoint(long nowMs) {
        if (finished || !failure.isEmpty()) return;
        try {
            sampleDevice(nowMs);
            JSONObject data = DiagnosticCheckpoint.at(latestState, nowMs);
            data.put("frame_count", frameSequence);
            data.put("image_count", imageCount);
            data.put("dropped_events", dropped.get());
            data.put("dropped_image_requests", imageDrops.get());
            data.put("image_context_stats", imageContextStats());
            data.put("last_device", lastDeviceSample == null ? JSONObject.NULL : lastDeviceSample);
            record("checkpoint", data);
            IO.execute(() -> {
                try {
                    if (archive != null) archive.checkpoint(object("session_id", sessionId,
                            "at_ms", nowMs, "data", data).toString(2));
                } catch (Exception error) { failed(error); }
            });
        } catch (Exception error) { failed(error); }
    }

    void finish(String reason) {
        if (finished) return;
        clearImageContext("finish:" + reason);
        finished = true;
        if (heartbeat != null) heartbeat.cancel(false);
        long at = SystemClock.elapsedRealtime();
        JSONObject finalState = latestState;
        IO.execute(() -> {
            try {
                if (archive != null) archive.finish(object("session_id", sessionId,
                        "game_id", game.id,
                        "ended_at_ms", at, "duration_ms", Math.max(0, at - startedAtMs),
                        "reason", reason, "frame_count", frameSequence, "image_count", imageCount,
                        "interrupted", false, "end_observed", true, "last_state", finalState,
                        "images_limited", imageLimit, "dropped_events", dropped.get(),
                        "dropped_image_requests", imageDrops.get(), "diagnostic_error", failure,
                        "image_context_stats", imageContextStats(),
                        "playback_note", "TTS callbacks and vibration requests do not prove actual sound/haptic delivery.").toString(2));
            } catch (Exception error) { failed(error); }
        });
    }

    private void failed(Exception error) {
        failure = "诊断记录失败：" + error.getClass().getSimpleName();
        Log.w("SenseFieldDiagnostics", failure);
    }

    private JSONObject imageContextStats() {
        return object("copied_context_frames", contextCopiedFrames, "context_copy_micros", contextCopyMicros,
                "max_context_copy_micros", maxContextCopyMicros, "max_context_frame_bytes", maxContextFrameBytes,
                "context_windows", contextWindows, "context_rate_limited", contextRateLimited,
                "context_deferred_frames", contextDeferredFrames,
                "image_jobs_completed", imageJobsCompleted,
                "image_job_wall_micros", imageJobWallMicros,
                "image_job_cpu_micros", imageJobCpuMicros,
                "image_queue_scope", "shared process budget; peaks cover process lifetime",
                "pending_image_frames", imageBudget.pendingFrames(), "pending_image_bytes", imageBudget.pendingBytes(),
                "peak_image_frames", imageBudget.peakFrames(), "peak_image_bytes", imageBudget.peakBytes());
    }

    /** Schema, completed image jobs, wall/CPU microseconds, pending shared frames/bytes. */
    long[] imageWorkStats() {
        return new long[]{1, imageJobsCompleted, imageJobWallMicros, imageJobCpuMicros,
                imageBudget.pendingFrames(), imageBudget.pendingBytes()};
    }
}
