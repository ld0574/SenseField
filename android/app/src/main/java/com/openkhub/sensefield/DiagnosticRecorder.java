package com.openkhub.sensefield;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Local diagnostics sharing the existing capture stream; no second projection. */
final class DiagnosticRecorder {
    static final String PREF_IMAGES = "diagnostic_images";
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
    final File directory;
    final long startedAtMs;
    volatile boolean finished;
    volatile String failure = "";
    private final Context context;
    private DiagnosticArchive archive;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicInteger imageDrops = new AtomicInteger();
    private final AtomicBoolean imagePending = new AtomicBoolean();
    private volatile boolean imageLimit;
    private volatile boolean imagesEnabled;
    private long nextImageAtMs;
    private long nextDeviceSampleAtMs;
    private long lastFlushAtMs;
    private volatile long frameSequence;
    private volatile long imageCount;
    private boolean markerPending;
    private volatile JSONObject lastDeviceSample;
    private volatile JSONObject latestState;
    private ScheduledFuture<?> heartbeat;

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
        DiagnosticRecorder recorder = new DiagnosticRecorder(context, sessionId, startedAtMs);
        current = recorder;
        return recorder;
    }

    private DiagnosticRecorder(Context supplied, String sessionId, long startedAtMs) {
        context = supplied.getApplicationContext();
        this.sessionId = sessionId;
        this.startedAtMs = startedAtMs;
        latestState = object("state", "starting", "snapshot_at_ms", startedAtMs,
                "frames_expected", false, "last_frame_arrived_at_ms", -1,
                "last_frame_observed_at_ms", -1, "last_frame_completed_at_ms", -1,
                "processed_frames", 0, "landscape_processed_frames", 0);
        imagesEnabled = imagesEnabledPreference(context);
        directory = new File(root(context), "diag-" + System.currentTimeMillis() + "-" + sessionId);
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        JSONObject metadata = object("schema", "sensefield.diagnostics", "schema_version", 1,
                "session_id", sessionId, "started_elapsed_ms", startedAtMs,
                "started_wall_ms", System.currentTimeMillis(), "model", Build.MODEL,
                "manufacturer", Build.MANUFACTURER, "brand", Build.BRAND,
                "product", Build.PRODUCT, "android_release", Build.VERSION.RELEASE,
                "sdk", Build.VERSION.SDK_INT, "screen_width_px", display.widthPixels,
                "screen_height_px", display.heightPixels, "density_dpi", display.densityDpi,
                "images_enabled", imagesEnabled, "image_period_ms", 2000,
                "image_duration_limit_ms", 1200000, "image_bytes_limit", DiagnosticArchive.IMAGE_LIMIT,
                "time_note", "Game HUD time is visible in optional screenshots; elapsed_ms is app-session time.",
                "image_note", "Optional local landscape screenshots and minimap crops; JPEG, not exact replay pixels.",
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
        imagesEnabled = enabled;
        record("image_setting", object("enabled", enabled));
    }

    void markIssue() {
        markerPending = true;
        record("user_marker", object("source", "notification"));
    }

    void audit(String message) {
        if (message.contains("sessionId=") && !message.contains("sessionId=" + sessionId)) return;
        record("audit", object("message", message));
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
        boolean force = markerPending || r.event == NearZoneRouting.EVENT_NEAR_ENTER;
        if (!imagesEnabled || imageLimit || width <= height || completedAtMs - startedAtMs > 1200000
                || (completedAtMs < nextImageAtMs && !force)) return;
        if (!imagePending.compareAndSet(false, true)) { imageDrops.incrementAndGet(); return; }
        markerPending = false;
        nextImageAtMs = completedAtMs + 2000;
        try {
            DiagnosticPixels screen = DiagnosticPixels.copy(pixels, width, height, rowStride, 0, 0, width, height, 960);
            int x0 = (int) Math.floor(f.minimapRoi.left * width);
            int y0 = (int) Math.floor(f.minimapRoi.top * height);
            int x1 = (int) Math.ceil(f.minimapRoi.right * width);
            int y1 = (int) Math.ceil(f.minimapRoi.bottom * height);
            DiagnosticPixels map = DiagnosticPixels.copy(pixels, width, height, rowStride, x0, y0, x1, y1, 768);
            IO.execute(() -> {
                try {
                    if (archive == null || !imagesEnabled) return;
                    saveImage(screen, "screen-" + sequence + "-" + observedAtMs + ".jpg", sequence, observedAtMs, null);
                    saveImage(map, "map-" + sequence + "-" + observedAtMs + ".jpg", sequence, observedAtMs,
                            new int[]{x0, y0, x1 - x0, y1 - y0});
                    archive.flush();
                } catch (Exception error) { failed(error); }
                finally { imagePending.set(false); }
            });
        } catch (RuntimeException error) { imagePending.set(false); imageDrops.incrementAndGet(); }
    }

    private void saveImage(DiagnosticPixels data, String name, long sequence, long observedAtMs,
                           int[] sourceBounds) throws IOException {
        if (data == null) return;
        Bitmap bitmap = Bitmap.createBitmap(data.argb, data.width, data.height, Bitmap.Config.ARGB_8888);
        byte[] bytes;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, sourceBounds == null ? 75 : 92, out))
                throw new IOException("无法保存诊断画面");
            bytes = out.toByteArray();
        } finally { bitmap.recycle(); }
        if (!imagesEnabled) return;
        if (!archive.image(name, bytes)) {
            imageLimit = true;
            archive.append(object("type", "image_limit", "at_ms", SystemClock.elapsedRealtime()).toString());
            return;
        }
        imageCount++;
        archive.append(object("type", "image", "frame_index", sequence, "observed_at_ms", observedAtMs,
                "file", "images/" + name, "output_width", data.width, "output_height", data.height,
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
            JSONObject sample = object("battery_temp_tenths_c", battery == null ? -1 :
                    battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1),
                    "battery_level", battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                    "media_volume", audio == null ? -1 : audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    "media_volume_max", audio == null ? -1 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                    "thermal_status", thermalStatus,
                    "thermal_status_name", thermalStatusName(thermalStatus));
            lastDeviceSample = sample;
            record("device", sample);
        } catch (RuntimeException ignored) { /* Optional device state. */ }
    }

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

    private void checkpoint(long nowMs) {
        if (finished || !failure.isEmpty()) return;
        try {
            sampleDevice(nowMs);
            JSONObject data = DiagnosticCheckpoint.at(latestState, nowMs);
            data.put("frame_count", frameSequence);
            data.put("image_count", imageCount);
            data.put("dropped_events", dropped.get());
            data.put("dropped_image_requests", imageDrops.get());
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
        finished = true;
        if (heartbeat != null) heartbeat.cancel(false);
        long at = SystemClock.elapsedRealtime();
        JSONObject finalState = latestState;
        IO.execute(() -> {
            try {
                if (archive != null) archive.finish(object("session_id", sessionId,
                        "ended_at_ms", at, "duration_ms", Math.max(0, at - startedAtMs),
                        "reason", reason, "frame_count", frameSequence, "image_count", imageCount,
                        "interrupted", false, "end_observed", true, "last_state", finalState,
                        "images_limited", imageLimit, "dropped_events", dropped.get(),
                        "dropped_image_requests", imageDrops.get(), "diagnostic_error", failure,
                        "playback_note", "TTS callbacks and vibration requests do not prove actual sound/haptic delivery.").toString(2));
            } catch (Exception error) { failed(error); }
        });
    }

    private void failed(Exception error) {
        failure = "诊断记录失败：" + error.getClass().getSimpleName();
        Log.w("SenseFieldDiagnostics", failure);
    }
}
