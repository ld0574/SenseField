package com.openkhub.sensefield;

import android.content.Context;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.json.*;

/** Versioned game packs. Never accesses the network from a recognizer or UI thread. */
final class GameModelStore {
    interface Progress { void update(int percent); }
    interface Source { AsrModelStore.Download open(String name, long offset) throws IOException; }
    static final class Entry {
        final String name, hash;
        final long bytes;
        Entry(JSONObject data) throws JSONException {
            name = data.getString("name"); hash = data.getString("sha256"); bytes = data.getLong("bytes");
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}") || name.contains("..")
                    || !hash.matches("[0-9a-f]{64}") || bytes < 1 || bytes > 512L * 1024 * 1024)
                throw new IllegalArgumentException("game_model_entry");
        }
    }
    static final class Spec {
        final String id, version;
        final List<Entry> files = new ArrayList<>();
        final long bytes;
        Spec(String id, JSONObject data) throws JSONException {
            this.id = id; version = data.getString("version");
            if (!id.matches("[a-z][a-z0-9-]{0,63}") || !version.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}"))
                throw new IllegalArgumentException("game_model_version");
            JSONArray items = data.getJSONArray("files");
            if (items.length() < 1 || items.length() > 64) throw new IllegalArgumentException("game_model_files");
            Set<String> names = new HashSet<>(); long total = 0;
            for (int i = 0; i < items.length(); i++) {
                Entry file = new Entry(items.getJSONObject(i));
                if (!names.add(file.name)) throw new IllegalArgumentException("game_model_duplicate");
                total += file.bytes; files.add(file);
            }
            if (total > 1024L * 1024 * 1024) throw new IllegalArgumentException("game_model_size");
            bytes = total;
        }
    }
    private static final Map<String, GameModelStore> STORES = new HashMap<>();
    private final File root;
    final Spec spec;
    private final Source source;
    private final Map<String, Long> timestamps = new HashMap<>();
    private boolean verified;

    GameModelStore(File root, Spec spec, Source source) { this.root = root; this.spec = spec; this.source = source; }
    static synchronized GameModelStore get(Context context, String id) throws Exception {
        GameModelStore store = STORES.get(id);
        if (store == null) {
            JSONObject manifest;
            try (InputStream input = context.getAssets().open("game-models.json")) {
                byte[] bytes = readBounded(input, 65536);
                manifest = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (manifest.getInt("schema") != 1) throw new IOException("game_model_schema");
            Spec spec = new Spec(id, manifest.getJSONObject("packs").getJSONObject(id));
            store = new GameModelStore(new File(context.getNoBackupFilesDir(), "game-models/" + id), spec,
                    new GatewaySource(context.getApplicationContext(), spec));
            STORES.put(id, store);
        }
        return store;
    }
    static File file(Context context, String id, String name) throws IOException {
        // Each game has its own namespace; model.bin in one game cannot bind another game's file.
        try {
            return get(context, id).checkedFile(name);
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("game_model_index", error); }
    }
    synchronized boolean ready() {
        if (!verified) return false;
        for (Entry e : spec.files) {
            File file = new File(installed(), e.name);
            if (!file.isFile() || file.length() != e.bytes || !Objects.equals(timestamps.get(e.name), file.lastModified())) {
                verified = false; return false;
            }
        }
        return true;
    }
    synchronized File checkedFile(String name) throws IOException {
        if (!ready()) throw new IOException("游戏识别资源尚未准备，请先联网开始一次辅助。");
        for (Entry e : spec.files) if (e.name.equals(name)) return new File(installed(), name);
        throw new IOException("game_model_unknown_file");
    }
    private File installed() { return new File(root, spec.version); }
    private void remember() {
        timestamps.clear();
        for (Entry e : spec.files) timestamps.put(e.name, new File(installed(), e.name).lastModified());
        verified = true;
    }
    synchronized void ensure(Progress progress, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        if (ready()) { progress.update(100); return; }
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("game_model_directory");
        try (RandomAccessFile lock = new RandomAccessFile(new File(root, ".lock"), "rw");
                FileLock ignored = lock.getChannel().lock()) {
            if (verifyDirectory(installed())) { remember(); progress.update(100); return; }
            File stage = new File(root, spec.version + ".staging");
            if (!stage.isDirectory() && !stage.mkdirs()) throw new IOException("game_model_directory");
            long present = 0;
            for (Entry e : spec.files) present += Math.min(e.bytes, new File(stage, e.name).length());
            if (root.getUsableSpace() > 0 && root.getUsableSpace() < spec.bytes - present + 1024 * 1024)
                throw new IOException("game_model_space");
            long completed = 0;
            for (Entry e : spec.files) {
                checkCancelled(cancelled);
                File path = new File(stage, e.name);
                if (!matches(path, e)) {
                    if (path.length() >= e.bytes) Files.deleteIfExists(path.toPath());
                    long offset = path.length();
                    try (AsrModelStore.Download response = source.open(e.name, offset)) {
                        if (response.status != 200 && response.status != 206) throw new IOException("game_model_http_" + response.status);
                        boolean append = response.status == 206;
                        if (append && !Objects.equals(response.range, "bytes " + offset + "-" + (e.bytes - 1) + "/" + e.bytes))
                            throw new IOException("game_model_range");
                        long downloaded = append ? offset : 0;
                        try (RandomAccessFile out = new RandomAccessFile(path, "rw")) {
                            out.setLength(downloaded); out.seek(downloaded);
                            byte[] block = new byte[65536]; int n;
                            while ((n = response.stream.read(block)) != -1) {
                                checkCancelled(cancelled);
                                if (n > e.bytes - downloaded) throw new IOException("game_model_size");
                                out.write(block, 0, n); downloaded += n;
                                progress.update((int) ((completed + downloaded) * 99 / spec.bytes));
                            }
                            out.getFD().sync();
                        }
                    }
                    if (path.length() < e.bytes) throw new IOException("game_model_incomplete");
                    if (!matches(path, e)) { Files.deleteIfExists(path.toPath()); throw new IOException("game_model_checksum"); }
                }
                completed += e.bytes;
            }
            checkCancelled(cancelled);
            if (!verifyDirectory(stage)) throw new IOException("game_model_checksum");
            // A version becomes visible only when every file has passed verification.
            File old = new File(root, spec.version + ".invalid");
            if (installed().exists()) {
                if (old.exists()) deleteTree(old);
                Files.move(installed().toPath(), old.toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
            try { Files.move(stage.toPath(), installed().toPath(), StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException failure) {
                if (old.exists()) Files.move(old.toPath(), installed().toPath(), StandardCopyOption.ATOMIC_MOVE);
                throw failure;
            }
            remember(); progress.update(100);
            if (old.exists()) deleteTree(old);
        }
    }
    private boolean verifyDirectory(File folder) throws Exception {
        if (!folder.isDirectory() || Files.isSymbolicLink(folder.toPath())) return false;
        for (Entry e : spec.files) if (!matches(new File(folder, e.name), e)) return false;
        return true;
    }
    private static boolean matches(File file, Entry e) throws Exception {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || file.length() != e.bytes) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return e.hash.equals(hash.toString());
    }
    private static void deleteTree(File file) throws IOException {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("game_model_directory");
            for (File child : children) deleteTree(child);
        }
        Files.deleteIfExists(file.toPath());
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("game_model_cancelled");
    }
    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[4096]; int n;
        while ((n = input.read(block)) != -1) { if (n > limit - bytes.size()) throw new IOException("game_model_metadata_size"); bytes.write(block, 0, n); }
        return bytes.toByteArray();
    }
    private static final class GatewaySource implements Source {
        private final Context context;
        private final Spec spec;
        private final OkHttpClient client = new OkHttpClient.Builder().followRedirects(false)
                .followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS).build();
        private String ticket;
        private String serviceIdentity;
        private long expires;
        GatewaySource(Context context, Spec spec) { this.context = context; this.spec = spec; }
        public AsrModelStore.Download open(String name, long offset) throws IOException {
            AssistantSettings settings = AssistantSettings.from(context);
            String identity = settings.endpoint + "\n" + settings.token;
            if (!identity.equals(serviceIdentity)) { ticket = null; serviceIdentity = identity; }
            if (!AssistantSettings.validEndpoint(settings.endpoint)) throw new IOException("game_model_endpoint");
            if (ticket == null || System.currentTimeMillis() / 1000 + 30 >= expires) {
                try {
                    JSONObject body = new JSONObject().put("game", spec.id).put("version", spec.version);
                    Request.Builder request = new Request.Builder().url(settings.endpoint + "/v1/game-models/ticket")
                            .header("X-SenseField-Installation", settings.installationId)
                            .post(RequestBody.create(body.toString(), MediaType.get("application/json")));
                    if (!settings.token.isEmpty()) request.header("Authorization", "Bearer " + settings.token);
                    try (Response response = client.newCall(request.build()).execute()) {
                        if (!response.isSuccessful() || response.body() == null) throw new IOException("game_model_ticket_" + response.code());
                        JSONObject result = new JSONObject(new String(readBounded(response.body().byteStream(), 4096), java.nio.charset.StandardCharsets.UTF_8));
                        ticket = result.getString("ticket"); expires = result.getLong("expires");
                    }
                } catch (JSONException error) { throw new IOException("game_model_ticket", error); }
            }
            Request.Builder request = new Request.Builder().url(settings.endpoint + "/v1/game-models/" + spec.id + "/" + spec.version + "/" + name)
                    .header("Authorization", "Bearer " + ticket).header("Accept-Encoding", "identity");
            if (offset > 0) request.header("Range", "bytes=" + offset + "-");
            Response response = client.newCall(request.build()).execute();
            if (response.body() == null) { response.close(); throw new IOException("game_model_response"); }
            if (response.code() == 401 || response.code() == 403) ticket = null;
            return new AsrModelStore.Download(response.code(), response.header("Content-Range"), response.body().byteStream(), response);
        }
    }
}
