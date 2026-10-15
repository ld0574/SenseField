package com.openkhub.sensefield;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A separate consent and foreground lifetime; never starts either game assistant. */
public final class NativeDemoProbeService extends Service {
    private final AtomicBoolean finishing = new AtomicBoolean();
    private NativeDemoRecorderProbe recorder;
    private MediaProjection projection;
    private MediaProjection.Callback callback;
    private volatile boolean bundledCompleted;
    private volatile String bundledAsset = "", bundledError = "";
    private volatile long bundledStartedAtMs;
    private long beganAtMs;
    private String stopReason;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("demo-probe", "端内录制验证", NotificationManager.IMPORTANCE_LOW));
        startForeground(9047, new Notification.Builder(this, "demo-probe")
                .setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("听野：正在进行8秒录制验证")
                .setContentText("录屏、手机麦克风与设备声音；自动结束并保存在本机").build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        if (recorder != null) return START_NOT_STICKY;
        try {
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(
                    intent.getIntExtra("result", 0), intent.getParcelableExtra("data"));
            callback = new MediaProjection.Callback() { @Override public void onStop() { finishProbe("projection_stopped"); } };
            projection.registerCallback(callback, new Handler(Looper.getMainLooper()));
            File dir = getExternalFilesDir("native-demo-probe");
            if (dir == null || !dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("output_directory");
            recorder = new NativeDemoRecorderProbe(this, projection, dir);
            recorder.start();
            beganAtMs = SystemClock.elapsedRealtime();
            android.util.Log.i("NativeDemoProbe", "event=started");
            new Handler(Looper.getMainLooper()).postDelayed(() -> new Thread(this::playTestTone, "demo-test-tone").start(), 1500);
            new Handler(Looper.getMainLooper()).postDelayed(() -> new Thread(this::playBundledSpeech, "demo-test-speech").start(), 4300);
            new Handler(Looper.getMainLooper()).postDelayed(() -> finishProbe("duration"), 8000);
        } catch (Exception error) { writeFailure(error); finishProbe("start_failed"); }
        return START_NOT_STICKY;
    }
    private void playBundledSpeech() {
        if (finishing.get()) return;
        MediaPlayer player = null;
        try {
            Context target = createPackageContext("com.openkhub.sensefield", 0);
            JSONObject catalog;
            try (java.io.InputStream source = target.getAssets().open("speech/manifest.json")) {
                catalog = new JSONObject(new String(source.readAllBytes(), StandardCharsets.UTF_8));
            }
            org.json.JSONArray files = catalog.getJSONArray("files");
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.getJSONObject(i);
                if ("接下来都是示例。".equals(entry.optString("text")) && entry.optInt("rate") == 100) {
                    bundledAsset = "speech/" + entry.getString("path"); break;
                }
            }
            if (bundledAsset.isEmpty()) throw new IllegalStateException("bundled_speech_missing");
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build());
            try (android.content.res.AssetFileDescriptor asset = target.getAssets().openFd(bundledAsset)) {
                player.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            }
            CountDownLatch complete = new CountDownLatch(1);
            player.setOnCompletionListener(done -> { bundledCompleted = true; complete.countDown(); });
            player.prepare(); bundledStartedAtMs = SystemClock.elapsedRealtime(); player.start();
            if (!complete.await(3, TimeUnit.SECONDS)) bundledError = "speech_playback_timeout";
        } catch (Exception error) { bundledError = error.getClass().getSimpleName(); }
        finally { if (player != null) player.release(); }
    }
    private void playTestTone() {
        if (finishing.get()) return;
        AudioTrack track = null;
        try {
            short[] samples = new short[48000 * 2];
            for (int i = 0; i < samples.length; i++) {
                double envelope = Math.min(1, Math.min(i, samples.length - 1 - i) / 480d);
                samples[i] = (short)(4000 * envelope * Math.sin(2 * Math.PI * 880 * i / 48000d));
            }
            track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(samples.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
            track.write(samples, 0, samples.length); track.play();
            Thread.sleep(2300);
        } catch (Exception ignored) { }
        finally { if (track != null) track.release(); }
    }
    private void finishProbe(String reason) {
        if (!finishing.compareAndSet(false, true)) return;
        stopReason = reason;
        android.util.Log.i("NativeDemoProbe", "event=finishing reason=" + reason);
        new Thread(() -> {
            try {
                if (recorder != null) {
                    recorder.finish("duration".equals(stopReason));
                    File reportFile = new File(getExternalFilesDir("native-demo-probe"), "report.json");
                    JSONObject report = new JSONObject(new String(Files.readAllBytes(reportFile.toPath()), StandardCharsets.UTF_8));
                    report.put("stop_reason", stopReason).put("bundled_speech_completed", bundledCompleted).put("bundled_speech_asset", bundledAsset)
                            .put("bundled_speech_start_ms", bundledStartedAtMs > 0 ? bundledStartedAtMs - beganAtMs : -1)
                            .put("bundled_speech_error", bundledError);
                    Files.write(reportFile.toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception error) { writeFailure(error); }
            if (projection != null) {
                if (callback != null) projection.unregisterCallback(callback);
                projection.stop();
            }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }, "demo-probe-finish").start();
    }
    private void writeFailure(Exception error) {
        try {
            File dir = getExternalFilesDir("native-demo-probe");
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) Files.write(
                    new File(dir, "failure.json").toPath(), new JSONObject().put("success", false)
                    .put("error", error.getClass().getSimpleName()).put("message", String.valueOf(error.getMessage()))
                    .toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
    @Override public void onDestroy() { finishProbe("service_destroyed"); super.onDestroy(); }
}
