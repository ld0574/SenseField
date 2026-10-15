package com.openkhub.sensefield;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.AudioFormat;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Platform OGG decoder. All IO/decoding is called from audio workers, never recognition/UI locks. */
final class BundledSpeechAssets {
    static final String ROOT = "speech/";
    private final Context context;
    private final String voice;
    private final int rate;
    private Map<String, JSONObject> entries;
    private final Map<String, short[]> guideCache = new LinkedHashMap<>();

    BundledSpeechAssets(Context context, String voice, int rate) {
        this.context = context.getApplicationContext();
        this.voice = BundledSpeechCatalog.voice(voice);
        this.rate = BundledSpeechCatalog.rate(rate);
    }

    String profile() { return "bundled-v1:" + voice + ":" + rate; }

    synchronized void loadIndex() throws IOException {
        if (entries != null) return;
        try (InputStream input = context.getAssets().open(ROOT + "manifest.json")) {
            byte[] bytes = boundedRead(input, 1_000_000);
            JSONObject manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            boolean fixture = manifest.optBoolean("test_fixture", false);
            if (manifest.getInt("schema") != 1 || fixture != BuildConfig.SPEECH_TEST_FIXTURE
                    || !fixture && !manifest.getBoolean("source_selected_by_user"))
                throw new IOException("Unverified speech source");
            JSONArray files = manifest.getJSONArray("files");
            Map<String, JSONObject> index = new HashMap<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.getJSONObject(i);
                String text = entry.getString("text");
                if (!BundledSpeechCatalog.id(text).equals(entry.getString("id")))
                    throw new IOException("Speech text checksum mismatch");
                String key = key(entry.getString("voice"), entry.getInt("rate"), text);
                if (index.put(key, entry) != null) throw new IOException("Duplicate speech entry");
            }
            for (String text : BundledSpeechCatalog.fixed()) {
                if (!index.containsKey(key(voice, rate, text))) throw new IOException("Incomplete fixed speech");
            }
            for (String text : BundledSpeechCatalog.guide()) {
                if (!index.containsKey(key("xiaoxiao", 100, text))) throw new IOException("Incomplete guide speech");
            }
            entries = index;
        } catch (JSONException error) { throw new IOException("Invalid speech manifest", error); }
    }

    void warmFixed(java.util.function.BooleanSupplier cancelled) throws IOException {
        loadIndex();
        for (String text : BundledSpeechCatalog.fixed()) {
            if (cancelled.getAsBoolean()) return;
            if (AlertSpeechCache.get(profile(), text) == null) {
                short[] pcm = decodeEntry(voice, rate, text, 6);
                AlertSpeechCache.put(profile(), text, pcm);
            }
        }
    }

    short[] load(String text, boolean narration) throws IOException {
        if (!narration) {
            short[] cached = AlertSpeechCache.get(profile(), text);
            if (cached != null) return cached;
            short[] pcm = decodeEntry(voice, rate, text, 6);
            AlertSpeechCache.put(profile(), text, pcm);
            return pcm;
        }
        synchronized (this) {
            short[] cached = guideCache.get(text);
            if (cached != null) return cached;
        }
        short[] pcm = decodeEntry("xiaoxiao", 100, text, 30);
        synchronized (this) {
            if (guideCache.size() >= 3) guideCache.remove(guideCache.keySet().iterator().next());
            guideCache.put(text, pcm);
        }
        return pcm;
    }

    private short[] decodeEntry(String voice, int rate, String text, int maxSeconds) throws IOException {
        loadIndex();
        final JSONObject entry = entries.get(key(voice, rate, text));
        if (entry == null) throw new IOException("Recording does not match current text");
        try {
            String path = entry.getString("path");
            String canonical = maxSeconds == 30 ? "guide/" + BundledSpeechCatalog.id(text) + ".ogg"
                    : String.format(java.util.Locale.ROOT, "fixed/%s/%03d/%s.ogg", voice, rate,
                    BundledSpeechCatalog.id(text));
            if (!canonical.equals(path))
                throw new IOException("Invalid speech asset path");
            byte[] encoded;
            try (InputStream input = context.getAssets().open(ROOT + path)) {
                encoded = boundedRead(input, 2_000_000);
            }
            if (encoded.length != entry.getInt("size")
                    || !BundledSpeechCatalog.sha256(encoded).equals(entry.getString("sha256")))
                throw new IOException("Speech file checksum mismatch");
            return decodeOgg(ROOT + path, maxSeconds);
        } catch (JSONException error) {
            throw new IOException("Invalid speech entry", error);
        }
    }

    private short[] decodeOgg(String path, int maxSeconds) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try (AssetFileDescriptor file = context.getAssets().openFd(path)) {
            extractor.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
            if (extractor.getTrackCount() != 1) throw new IOException("Speech must contain one audio track");
            MediaFormat format = extractor.getTrackFormat(0);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (!"audio/opus".equals(mime) || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1)
                throw new IOException("Speech must be mono Opus");
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            if (sampleRate != 48000) throw new IOException("Opus decode sample rate must be 48 kHz");
            extractor.selectTrack(0);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnded = false, outputEnded = false;
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!outputEnded) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Speech decode cancelled");
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Speech decoder timeout");
                if (!inputEnded) {
                    int slot = decoder.dequeueInputBuffer(10_000);
                    if (slot >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(slot);
                        if (buffer == null) throw new IOException("Missing speech input buffer");
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            decoder.queueInputBuffer(slot, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int slot = decoder.dequeueOutputBuffer(info, 10_000);
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = decoder.getOutputFormat();
                    if (output.getInteger(MediaFormat.KEY_SAMPLE_RATE) != sampleRate
                            || output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1
                            || output.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)
                        throw new IOException("Unexpected speech PCM format");
                } else if (slot >= 0) {
                    ByteBuffer buffer = decoder.getOutputBuffer(slot);
                    if (buffer == null) throw new IOException("Missing speech output buffer");
                    if (info.size > 0) {
                        if (decoded.size() + info.size > sampleRate * maxSeconds * 2)
                            throw new IOException("Speech duration exceeds budget");
                        byte[] block = new byte[info.size];
                        buffer.position(info.offset); buffer.limit(info.offset + info.size);
                        buffer.get(block); decoded.write(block);
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(slot, false);
                }
            }
            byte[] bytes = decoded.toByteArray();
            if (bytes.length == 0 || bytes.length % 2 != 0) throw new IOException("Empty speech PCM");
            short[] pcm = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
            return AlertSpeechCache.resample(pcm, sampleRate, AlertSpeechCache.SAMPLE_RATE);
        } catch (RuntimeException error) { throw new IOException("Cannot decode speech", error); }
        finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (RuntimeException ignored) {}
                decoder.release();
            }
            extractor.release();
        }
    }

    private static byte[] boundedRead(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int n;
        while ((n = input.read(block)) != -1) {
            if (output.size() + n > limit) throw new IOException("Speech asset exceeds budget");
            output.write(block, 0, n);
        }
        return output.toByteArray();
    }

    private static String key(String voice, int rate, String text) { return voice + ":" + rate + ":" + text; }
}
