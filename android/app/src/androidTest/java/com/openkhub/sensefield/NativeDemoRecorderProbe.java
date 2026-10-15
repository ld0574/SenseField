package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;
import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only hardware AVC + stereo AAC: microphone left, playback capture right.
 * Channel separation makes a silent playback source impossible to hide behind mic loopback.
 * This is a standalone projection probe, not the eventual shared assistant capture path.
 */
final class NativeDemoRecorderProbe {
    private static final int RATE = 48000, CHUNK = 960;
    private final Context context;
    private final MediaProjection projection;
    private final File directory;
    private final CountDownLatch drained = new CountDownLatch(2);
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final Object muxerLock = new Object();
    private final List<Packet> pending = new ArrayList<>();
    private int pendingBytes;
    private MediaCodec video, audio;
    private Surface surface;
    private VirtualDisplay display;
    private AudioRecord mic, playback;
    private MediaMuxer muxer;
    private boolean muxerStarted;
    private int videoTrack = -1, audioTrack = -1;
    private volatile boolean running;
    private volatile long stopAtMs = Long.MAX_VALUE;
    private Thread capture;
    private long beganAtMs, samples, micSamples, playbackSamples, videoPackets, audioPackets;
    private double micSquares, playbackSquares;
    private int micPeak, playbackPeak;
    private boolean micSilenced;
    private String videoCodec, audioCodec;

    NativeDemoRecorderProbe(Context context, MediaProjection projection, File directory) {
        this.context = context; this.projection = projection; this.directory = directory;
    }

    void start() throws Exception {
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        videoCodec = video.getName(); audioCodec = audio.getName();
        MediaFormat picture = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 544, 960);
        picture.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        picture.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
        picture.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
        picture.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 15);
        picture.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        video.configure(picture, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = video.createInputSurface();
        MediaFormat sound = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
        sound.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        sound.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        sound.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 4);
        audio.configure(sound, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        muxer = new MediaMuxer(new File(directory, "native-probe.partial.mp4").getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        AudioFormat input = new AudioFormat.Builder().setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("unsupported_audio_format");
        AudioRecord.Builder microphone = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(input).setBufferSizeInBytes(Math.max(minimum, CHUNK * 8));
        if (Build.VERSION.SDK_INT >= 30) microphone.setPrivacySensitive(false);
        mic = microphone.build();
        AudioPlaybackCaptureConfiguration output = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
        playback = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(output).setAudioFormat(input)
                .setBufferSizeInBytes(Math.max(minimum, CHUNK * 8)).build();
        if (mic.getState() != AudioRecord.STATE_INITIALIZED || playback.getState() != AudioRecord.STATE_INITIALIZED)
            throw new IllegalStateException("audio_record_not_initialized");
        running = true; beganAtMs = SystemClock.elapsedRealtime();
        video.start(); audio.start(); mic.startRecording(); playback.startRecording();
        new Thread(() -> drain(video, true), "probe-avc-drain").start();
        new Thread(() -> drain(audio, false), "probe-aac-drain").start();
        capture = new Thread(this::captureAudio, "probe-audio-capture"); capture.start();
        display = projection.createVirtualDisplay("sensefield-native-demo-probe", 544, 960,
                context.getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
        if (display == null) throw new IllegalStateException("virtual_display_unavailable");
    }

    private void captureAudio() {
        short[] left = new short[CHUNK], right = new short[CHUNK];
        try {
            while (running) {
                int a = mic.read(left, 0, CHUNK, AudioRecord.READ_BLOCKING);
                int b = playback.read(right, 0, CHUNK, AudioRecord.READ_BLOCKING);
                if (!running) break;
                if (a <= 0 || b <= 0) throw new IllegalStateException("capture_read_" + a + "_" + b);
                int count = Math.max(a, b);
                int slot;
                do { slot = audio.dequeueInputBuffer(10000); } while (running && slot < 0);
                if (slot < 0) break;
                ByteBuffer input = audio.getInputBuffer(slot).order(ByteOrder.LITTLE_ENDIAN);
                input.clear();
                for (int i = 0; i < count; i++) {
                    short m = i < a ? left[i] : 0, p = i < b ? right[i] : 0;
                    input.putShort(m); input.putShort(p);
                    micSquares += (double)m * m; playbackSquares += (double)p * p;
                    micPeak = Math.max(micPeak, Math.abs((int)m)); playbackPeak = Math.max(playbackPeak, Math.abs((int)p));
                }
                audio.queueInputBuffer(slot, 0, count * 4, samples * 1000000 / RATE, 0);
                samples += count; micSamples += a; playbackSamples += b;
            }
            micSilenced = mic.getActiveRecordingConfiguration() != null && mic.getActiveRecordingConfiguration().isClientSilenced();
            int slot = audio.dequeueInputBuffer(1000000);
            if (slot < 0) throw new IllegalStateException("audio_eos_input_timeout");
            audio.queueInputBuffer(slot, 0, 0, samples * 1000000 / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        } catch (Exception error) { failure.compareAndSet(null, "audio_capture_" + error.getClass().getSimpleName()); }
    }

    private void drain(MediaCodec codec, boolean picture) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long firstVideoPts = -1;
        try {
            while (true) {
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(codec.getOutputFormat(), picture);
                } else if (index >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (picture) {
                            if (firstVideoPts < 0) firstVideoPts = info.presentationTimeUs;
                            info.presentationTimeUs = Math.max(0, info.presentationTimeUs - firstVideoPts);
                        }
                        ByteBuffer bytes = codec.getOutputBuffer(index);
                        bytes.position(info.offset); bytes.limit(info.offset + info.size);
                        byte[] copied = new byte[info.size]; bytes.get(copied);
                        writePacket(new Packet(picture, copied, info.presentationTimeUs, info.flags));
                        if (picture) videoPackets++; else audioPackets++;
                    }
                    boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(index, false);
                    if (end) break;
                }
                if (!running && SystemClock.elapsedRealtime() - stopAtMs > 6000) throw new IllegalStateException("encoder_drain_timeout");
            }
        } catch (Exception error) { failure.compareAndSet(null, (picture ? "video_" : "audio_") + error.getClass().getSimpleName()); }
        finally { drained.countDown(); }
    }

    private void addTrack(MediaFormat format, boolean picture) {
        synchronized (muxerLock) {
            int track = muxer.addTrack(format);
            if (picture) videoTrack = track; else audioTrack = track;
            if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
                muxer.start(); muxerStarted = true;
                for (Packet packet : pending) writeNow(packet);
                pending.clear(); pendingBytes = 0;
            }
        }
    }
    private void writePacket(Packet packet) {
        synchronized (muxerLock) {
            if (muxerStarted) writeNow(packet);
            else {
                pendingBytes += packet.bytes.length;
                if (pendingBytes > 4 * 1024 * 1024) throw new IllegalStateException("format_wait_buffer_full");
                pending.add(packet);
            }
        }
    }
    private void writeNow(Packet packet) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, packet.bytes.length, packet.pts, packet.flags);
        muxer.writeSampleData(packet.picture ? videoTrack : audioTrack, ByteBuffer.wrap(packet.bytes), info);
    }
    private static final class Packet {
        final boolean picture;
        final byte[] bytes;
        final long pts;
        final int flags;
        Packet(boolean picture, byte[] bytes, long pts, int flags) {
            this.picture = picture; this.bytes = bytes; this.pts = pts; this.flags = flags;
        }
    }

    void finish(boolean completedDuration) throws Exception {
        stopAtMs = SystemClock.elapsedRealtime(); running = false;
        try {
            if (capture != null) capture.join(2500);
            if (capture != null && capture.isAlive()) {
                try { mic.stop(); playback.stop(); } catch (RuntimeException ignored) { }
                capture.join(1000); failure.compareAndSet(null, "capture_stop_timeout");
            }
            if (display != null) { display.release(); display = null; }
            if (video != null) video.signalEndOfInputStream();
            if (!drained.await(8, TimeUnit.SECONDS)) failure.compareAndSet(null, "encoder_finish_timeout");
            synchronized (muxerLock) {
                if (muxerStarted) { muxer.stop(); muxerStarted = false; }
                if (muxer != null) { muxer.release(); muxer = null; }
            }
        } catch (Exception error) { failure.compareAndSet(null, "finish_" + error.getClass().getSimpleName()); }
        finally {
            for (AudioRecord record : new AudioRecord[]{mic, playback}) if (record != null) {
                try { record.stop(); } catch (RuntimeException ignored) { } record.release();
            }
            for (MediaCodec codec : new MediaCodec[]{video, audio}) if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) { } codec.release();
            }
            if (surface != null) surface.release();
            if (display != null) display.release();
        }
        if (!completedDuration) failure.compareAndSet(null, "stopped_before_duration");
        boolean complete = failure.get() == null && videoPackets > 10 && audioPackets > 20;
        File file = new File(directory, "native-probe.partial.mp4");
        if (complete) {
            File finalFile = new File(directory, "native-probe.mp4");
            Files.move(file.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING); file = finalFile;
        }
        JSONObject report = new JSONObject().put("success", complete).put("test_only", true)
                .put("completed_duration", completedDuration)
                .put("actual_game", false).put("natural_speech_intelligibility_verified", false)
                .put("video_codec", videoCodec).put("audio_codec", audioCodec)
                .put("duration_ms", stopAtMs - beganAtMs).put("video_packets", videoPackets).put("audio_packets", audioPackets)
                .put("left_channel", "phone_microphone").put("right_channel", "audio_playback_capture")
                .put("mic_samples", micSamples).put("playback_samples", playbackSamples).put("mic_silenced", micSilenced)
                .put("mic_rms", Math.sqrt(micSquares / Math.max(1, samples))).put("mic_peak", micPeak)
                .put("playback_rms", Math.sqrt(playbackSquares / Math.max(1, samples))).put("playback_peak", playbackPeak)
                .put("file", file.getName()).put("bytes", file.length()).put("error", failure.get() == null ? JSONObject.NULL : failure.get());
        Files.write(new File(directory, "report.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
