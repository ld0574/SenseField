package com.openkhub.sensefield;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GameModelStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    final byte[] first = "synthetic detector".getBytes(), second = "synthetic gallery".getBytes();
    String hash(byte[] data) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) text.append(String.format("%02x", b & 255));
        return text.toString();
    }
    GameModelStore.Spec spec(String version) throws Exception {
        JSONArray files = new JSONArray();
        files.put(new JSONObject().put("name", "model.bin").put("bytes", first.length).put("sha256", hash(first)));
        files.put(new JSONObject().put("name", "gallery.json").put("bytes", second.length).put("sha256", hash(second)));
        return new GameModelStore.Spec("fixture", new JSONObject().put("version", version).put("files", files));
    }
    AsrModelStore.Download response(byte[] bytes, long offset, boolean range) {
        return new AsrModelStore.Download(range ? 206 : 200, range ? "bytes " + offset + "-" + (bytes.length-1) + "/" + bytes.length : null,
                new ByteArrayInputStream(Arrays.copyOfRange(bytes, range ? (int) offset : 0, bytes.length)), () -> {});
    }
    GameModelStore.Source source() { return (name, offset) -> response(name.equals("model.bin") ? first : second, offset, offset > 0); }
    @Test public void installsAtomicallyAndReusesCacheOfflineAcrossRestart() throws Exception {
        File root = temp.newFolder();
        GameModelStore store = new GameModelStore(root, spec("v1"), source());
        store.ensure(p -> {}, () -> false); assertTrue(store.ready());
        assertArrayEquals(first, Files.readAllBytes(store.checkedFile("model.bin").toPath()));
        GameModelStore offline = new GameModelStore(root, spec("v1"), (name, offset) -> { throw new IOException("offline"); });
        offline.ensure(p -> {}, () -> false); assertTrue(offline.ready());
        assertFalse(new File(root, "v1.staging").exists());
    }
    @Test public void interruptedTransferResumesWithoutPublishingHalfPack() throws Exception {
        File root = temp.newFolder();
        GameModelStore interrupted = new GameModelStore(root, spec("v1"), (name, offset) -> new AsrModelStore.Download(200, null,
                new ByteArrayInputStream(Arrays.copyOf(first, 5)), () -> {}));
        assertThrows(IOException.class, () -> interrupted.ensure(p -> {}, () -> false));
        assertFalse(interrupted.ready()); assertFalse(new File(root, "v1").exists());
        List<Long> offsets = new ArrayList<>();
        GameModelStore resumed = new GameModelStore(root, spec("v1"), (name, offset) -> {
            offsets.add(offset); return response(name.equals("model.bin") ? first : second, offset, true);
        });
        resumed.ensure(p -> {}, () -> false); assertEquals(Long.valueOf(5), offsets.get(0)); assertTrue(resumed.ready());
    }
    @Test public void ignoresRangeByRestartingOnlyCurrentFile() throws Exception {
        File root = temp.newFolder(); File partial = new File(root, "v1.staging"); partial.mkdir();
        Files.write(new File(partial, "model.bin").toPath(), Arrays.copyOf(first, 3));
        GameModelStore store = new GameModelStore(root, spec("v1"), (name, offset) -> response(name.equals("model.bin") ? first : second, 0, false));
        store.ensure(p -> {}, () -> false); assertTrue(store.ready());
    }
    @Test public void rejectsCorruptionAndKeepsPriorVersionOnUpgradeFailure() throws Exception {
        File root = temp.newFolder(); GameModelStore old = new GameModelStore(root, spec("v1"), source());
        old.ensure(p -> {}, () -> false);
        GameModelStore bad = new GameModelStore(root, spec("v2"), (name, offset) -> response(new byte[first.length], 0, false));
        assertThrows(IOException.class, () -> bad.ensure(p -> {}, () -> false));
        assertTrue(old.ready()); assertFalse(new File(root, "v2").exists());
        assertThrows(IOException.class, () -> bad.checkedFile("model.bin"));
    }
    @Test public void cancellationAndInvalidRangesNeverPublish() throws Exception {
        File root = temp.newFolder(); GameModelStore store = new GameModelStore(root, spec("v1"), source());
        assertThrows(InterruptedIOException.class, () -> store.ensure(p -> {}, () -> true));
        GameModelStore invalid = new GameModelStore(root, spec("v1"), (name, offset) -> new AsrModelStore.Download(206, "bytes 1-3/4", new ByteArrayInputStream(first), () -> {}));
        assertThrows(IOException.class, () -> invalid.ensure(p -> {}, () -> false));
        assertFalse(invalid.ready());
    }
    @Test public void corruptInstalledCacheIsRepairedAndUnknownPathsRejected() throws Exception {
        File root = temp.newFolder(); GameModelStore store = new GameModelStore(root, spec("v1"), source());
        store.ensure(p -> {}, () -> false);
        Files.write(new File(root, "v1/model.bin").toPath(), new byte[]{0});
        assertFalse(store.ready()); store.ensure(p -> {}, () -> false); assertTrue(store.ready());
        assertThrows(IOException.class, () -> store.checkedFile("../model.bin"));
        JSONObject unsafe = new JSONObject().put("version", "v1").put("files", new JSONArray().put(new JSONObject().put("name", "../x").put("bytes", 1).put("sha256", hash(first))));
        assertThrows(IllegalArgumentException.class, () -> new GameModelStore.Spec("fixture", unsafe));
    }
}
