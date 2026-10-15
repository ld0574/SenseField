package com.openkhub.sensefield;

import android.content.Context;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.json.JSONArray;

/** Pinned CDN resources; download/extract only on a worker, never on the recognition thread. */
final class AsrModelStore {
    interface Progress { void update(int percent); }
    interface Source {
        Download open(String url, long offset) throws IOException;
    }
    static final class Download implements AutoCloseable {
        final int status;
        final String range;
        final InputStream stream;
        private final AutoCloseable response;
        Download(int status, String range, InputStream stream, AutoCloseable response) {
            this.status = status; this.range = range; this.stream = stream; this.response = response;
        }
        @Override public void close() throws IOException {
            try { response.close(); } catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException("download_close", error); }
        }
    }
    static final class Spec {
        final String url, archiveHash, modelHash, tokensHash;
        final long archiveBytes, modelBytes, tokensBytes;
        final Part[] parts;
        Spec(String url, long archiveBytes, String archiveHash,
                long modelBytes, String modelHash, long tokensBytes, String tokensHash) {
            this(new Part[]{new Part(url, archiveBytes, archiveHash)}, archiveBytes, archiveHash,
                    modelBytes, modelHash, tokensBytes, tokensHash);
        }
        Spec(Part[] parts, long archiveBytes, String archiveHash,
                long modelBytes, String modelHash, long tokensBytes, String tokensHash) {
            if (parts == null || parts.length < 1 || parts.length > 8
                    || archiveBytes <= 0 || archiveBytes > 512L * 1024 * 1024
                    || modelBytes <= 0 || tokensBytes <= 0
                    || !archiveHash.matches("[0-9a-f]{64}") || !modelHash.matches("[0-9a-f]{64}")
                    || !tokensHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("model_metadata");
            long total = 0;
            Set<String> urls = new HashSet<>();
            for (Part part : parts) {
                if (part == null || part.bytes > archiveBytes - total || !urls.add(part.url))
                    throw new IllegalArgumentException("model_parts");
                total += part.bytes;
            }
            if (total != archiveBytes) throw new IllegalArgumentException("model_parts_size");
            this.parts = parts.clone(); this.url = parts[0].url;
            this.archiveBytes = archiveBytes; this.archiveHash = archiveHash;
            this.modelBytes = modelBytes; this.modelHash = modelHash;
            this.tokensBytes = tokensBytes; this.tokensHash = tokensHash;
        }
        static Spec fromJson(JSONObject data) throws Exception {
            JSONObject download = data.getJSONObject("download"), files = data.getJSONObject("files");
            JSONObject model = files.getJSONObject("model.int8.onnx"), tokens = files.getJSONObject("tokens.txt");
            if (!download.has("parts"))
                return new Spec(download.getString("url"), download.getLong("archive_bytes"), download.getString("archive_sha256"),
                        model.getLong("size"), model.getString("sha256"), tokens.getLong("size"), tokens.getString("sha256"));
            if (!"concat-zip-v1".equals(download.getString("format")))
                throw new IllegalArgumentException("model_parts_format");
            JSONArray items = download.getJSONArray("parts");
            if (items.length() < 1 || items.length() > 8) throw new IllegalArgumentException("model_parts");
            Part[] parts = new Part[items.length()];
            for (int i = 0; i < parts.length; i++) {
                JSONObject item = items.getJSONObject(i);
                parts[i] = new Part(item.getString("url"), item.getLong("bytes"), item.getString("sha256"));
            }
            return new Spec(parts, download.getLong("archive_bytes"), download.getString("archive_sha256"),
                    model.getLong("size"), model.getString("sha256"), tokens.getLong("size"), tokens.getString("sha256"));
        }
        static Spec read(Context context) throws Exception {
            try (InputStream stream = context.getAssets().open("sensevoice.metadata.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] block = new byte[4096]; int count;
                while ((count = stream.read(block)) != -1) {
                    if (bytes.size() + count > 32768) throw new IOException("model_metadata_size");
                    bytes.write(block, 0, count);
                }
                return fromJson(new JSONObject(bytes.toString("UTF-8")));
            }
        }
    }
    static final class Part {
        final String url, hash;
        final long bytes;
        Part(String url, long bytes, String hash) {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || bytes <= 0 || hash == null || !hash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("model_part_metadata");
            this.url = url; this.bytes = bytes; this.hash = hash;
        }
    }
    private static final ExecutorService PREPARATION = Executors.newSingleThreadExecutor();
    private static AsrModelStore shared;
    private final File directory;
    private final Spec spec;
    private final Source source;
    private boolean verifiedThisProcess;
    private long modelModified, tokensModified;
    AsrModelStore(File directory, Spec spec, Source source) {
        this.directory = directory; this.spec = spec; this.source = source;
    }
    static synchronized AsrModelStore get(Context context) throws Exception {
        if (shared == null) shared = new AsrModelStore(new File(context.getNoBackupFilesDir(), "sensevoice-int8"),
                Spec.read(context), new HttpsSource());
        return shared;
    }
    static void prepare(Context context, Progress progress, java.util.function.Consumer<String> complete) {
        Context app = context.getApplicationContext();
        PREPARATION.execute(() -> {
            try {
                get(app).ensure(progress, () -> !AssistantSettings.voiceEnabled(GameProfile.settings(app)));
                complete.accept("语音资源已准备好，之后可离线使用。");
            } catch (Exception error) {
                complete.accept("语音资源尚未准备好，请检查网络和剩余空间后重试；已下载部分会保留。");
            }
        });
    }
    synchronized File[] ensure(Progress progress, BooleanSupplier cancelled) throws Exception {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("model_cancelled");
        File model = new File(directory, "model.int8.onnx"), tokens = new File(directory, "tokens.txt");
        if (verifiedThisProcess && model.length() == spec.modelBytes && tokens.length() == spec.tokensBytes
                && model.lastModified() == modelModified && tokens.lastModified() == tokensModified)
            { progress.update(100); return new File[]{model, tokens}; }
        if (verified(model, spec.modelBytes, spec.modelHash) && verified(tokens, spec.tokensBytes, spec.tokensHash)) {
            remember(model, tokens); progress.update(100); return new File[]{model, tokens};
        }
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("model_directory");
        File partial = new File(directory, "download.zip.part");
        if (partial.length() > spec.archiveBytes) Files.delete(partial.toPath());
        long required = spec.archiveBytes - partial.length() + spec.modelBytes + spec.tokensBytes;
        if (directory.getUsableSpace() > 0 && directory.getUsableSpace() < required) throw new IOException("model_space");
        // Keep the same combined prefix as the previous single-file downloader.
        // Completed parts and even an old CDN partial remain reusable. A server
        // ignoring Range restarts only the current part, never completed parts.
        long partStart = 0;
        for (Part part : spec.parts) {
            if (partial.length() >= partStart + part.bytes) {
                if (!verifiedSegment(partial, partStart, part.bytes, part.hash)) {
                    discardSuffix(partial, partStart); throw new IOException("model_part_checksum");
                }
                partStart += part.bytes;
                continue;
            }
            long offset = partial.length() - partStart;
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("model_cancelled");
            progress.update((int) (partial.length() * 100 / spec.archiveBytes));
            try (Download response = source.open(part.url, offset)) {
                boolean append = response.status == 206;
                if (response.status != 200 && !append) throw new IOException("model_http_" + response.status);
                if (append && !validRange(response.range, offset, part.bytes)) throw new IOException("model_range");
                long downloaded = append ? offset : 0;
                try (RandomAccessFile out = new RandomAccessFile(partial, "rw")) {
                    out.setLength(partStart + downloaded);
                    out.seek(partStart + downloaded);
                    byte[] block = new byte[65536]; int count, last = -1;
                    while ((count = response.stream.read(block)) != -1) {
                        if (cancelled.getAsBoolean()) throw new InterruptedIOException("model_cancelled");
                        if (downloaded + count > part.bytes) {
                            discardSuffix(partial, partStart); throw new IOException("model_size");
                        }
                        out.write(block, 0, count); downloaded += count;
                        int percent = (int) ((partStart + downloaded) * 100 / spec.archiveBytes);
                        if (percent / 5 != last / 5 || last == -1) { progress.update(percent); last = percent; }
                    }
                    out.getFD().sync();
                }
            }
            if (partial.length() < partStart + part.bytes) throw new IOException("model_incomplete");
            if (!verifiedSegment(partial, partStart, part.bytes, part.hash)) {
                discardSuffix(partial, partStart); throw new IOException("model_part_checksum");
            }
            partStart += part.bytes;
        }
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("model_cancelled");
        // A short EOF may be a chunked connection closing early. Keep the
        // bounded prefix for Range retry; only a full corrupt archive is removed.
        if (partial.length() < spec.archiveBytes) throw new IOException("model_incomplete");
        if (!verified(partial, spec.archiveBytes, spec.archiveHash)) {
            Files.deleteIfExists(partial.toPath()); throw new IOException("model_checksum");
        }
        File modelNew = new File(directory, "model.int8.onnx.new"), tokensNew = new File(directory, "tokens.txt.new");
        Set<String> seen = new HashSet<>();
        try (ZipInputStream archive = new ZipInputStream(new FileInputStream(partial))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !seen.add(name)
                        || (!name.equals("model.int8.onnx") && !name.equals("tokens.txt"))) throw new IOException("model_archive_entry");
                File target = name.equals("model.int8.onnx") ? modelNew : tokensNew;
                long expected = name.equals("model.int8.onnx") ? spec.modelBytes : spec.tokensBytes;
                try (FileOutputStream out = new FileOutputStream(target)) {
                    byte[] block = new byte[65536]; int count; long total = 0;
                    while ((count = archive.read(block)) != -1) {
                        if (cancelled.getAsBoolean()) throw new InterruptedIOException("model_cancelled");
                        if (total + count > expected) throw new IOException("model_extracted_size");
                        out.write(block, 0, count); total += count;
                    }
                    out.getFD().sync();
                }
            }
            if (seen.size() != 2 || !verified(modelNew, spec.modelBytes, spec.modelHash)
                    || !verified(tokensNew, spec.tokensBytes, spec.tokensHash)) throw new IOException("model_extracted_checksum");
            Files.move(modelNew.toPath(), model.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.move(tokensNew.toPath(), tokens.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(partial.toPath()); remember(model, tokens); progress.update(100);
            return new File[]{model, tokens};
        } finally {
            Files.deleteIfExists(modelNew.toPath()); Files.deleteIfExists(tokensNew.toPath());
        }
    }
    private void remember(File model, File tokens) {
        verifiedThisProcess = true; modelModified = model.lastModified(); tokensModified = tokens.lastModified();
    }
    private static void discardSuffix(File file, long keepBytes) throws IOException {
        if (keepBytes == 0) Files.deleteIfExists(file.toPath());
        else try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(keepBytes); out.getFD().sync();
        }
    }
    private static boolean verifiedSegment(File file, long start, long size, String hash) throws Exception {
        if (!file.isFile() || file.length() < start + size) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(file)) {
            stream.getChannel().position(start);
            byte[] block = new byte[65536]; long remaining = size;
            while (remaining > 0) {
                int count = stream.read(block, 0, (int)Math.min(block.length, remaining));
                if (count == -1) return false;
                digest.update(block, 0, count); remaining -= count;
            }
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return hash.equals(value.toString());
    }
    static boolean validRange(String range, long offset, long total) {
        if (range == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(range);
        try { return m.matches() && Long.parseLong(m.group(1)) == offset
                    && Long.parseLong(m.group(2)) == total - 1 && Long.parseLong(m.group(3)) == total; }
        catch (NumberFormatException error) { return false; }
    }
    static boolean verified(File file, long size, String hash) throws Exception {
        if (!file.isFile() || file.length() != size) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(file)) {
            byte[] block = new byte[65536]; int count;
            while ((count = stream.read(block)) != -1) digest.update(block, 0, count);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return hash.equals(value.toString());
    }
    static final class HttpsSource implements Source {
        private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
        @Override public Download open(String url, long offset) throws IOException {
            URI origin = URI.create(url); String current = url;
            for (int redirects = 0; redirects <= 3; redirects++) {
                Request.Builder request = new Request.Builder().url(current);
                if (offset > 0) request.header("Range", "bytes=" + offset + "-");
                Call call = client.newCall(request.build()); Response response = call.execute();
                if (response.isRedirect()) {
                    String location = response.header("Location"); response.close();
                    if (location == null) throw new IOException("model_redirect");
                    URI next = URI.create(current).resolve(location);
                    if (!ReleaseDownloadPolicy.artifactRedirect(origin, next)) throw new IOException("model_redirect_origin");
                    current = next.toString(); continue;
                }
                if (response.body() == null) { response.close(); throw new IOException("model_response"); }
                return new Download(response.code(), response.header("Content-Range"), response.body().byteStream(), response);
            }
            throw new IOException("model_redirect_loop");
        }
        static boolean sameOrigin(URI left, URI right) {
            return ReleaseDownloadPolicy.sameOrigin(left, right);
        }
    }
}
