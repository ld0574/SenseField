package com.openkhub.sensefield;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class AsrModelStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    final byte[] model = "small synthetic model".getBytes(), tokens = "fixture tokens".getBytes();
    byte[] zip(String modelName, byte[] content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(modelName)); out.write(content); out.closeEntry();
            out.putNextEntry(new ZipEntry("tokens.txt")); out.write(tokens); out.closeEntry();
        }
        return bytes.toByteArray();
    }
    static String hash(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format("%02x", value & 255));
        return text.toString();
    }
    AsrModelStore.Spec spec(byte[] zip) throws Exception {
        return new AsrModelStore.Spec("https://cdn.example/models/asr.zip", zip.length, hash(zip), model.length, hash(model), tokens.length, hash(tokens));
    }
    static AsrModelStore.Download download(int status, String range, InputStream stream) {
        return new AsrModelStore.Download(status, range, stream, stream);
    }
    @Test public void interruptedDownloadResumesThenReusesVerifiedCache() throws Exception {
        byte[] zip = zip("model.int8.onnx", model); File dir = folder.newFolder();
        List<Long> offsets = new ArrayList<>();
        AsrModelStore.Source source = (url, offset) -> {
            offsets.add(offset);
            if (offset == 0) return download(200, null, new InputStream() {
                int at;
                public int read() throws IOException { if (at >= 70) throw new IOException("network lost"); return zip[at++] & 255; }
                public int read(byte[] bytes, int start, int count) throws IOException {
                    if (at >= 70) throw new IOException("network lost");
                    int n = Math.min(count, 70 - at); System.arraycopy(zip, at, bytes, start, n); at += n; return n;
                }
            });
            return download(206, "bytes " + offset + "-" + (zip.length - 1) + "/" + zip.length,
                    new ByteArrayInputStream(zip, (int) offset, zip.length - (int) offset));
        };
        AsrModelStore store = new AsrModelStore(dir, spec(zip), source);
        assertThrows(IOException.class, () -> store.ensure(p -> {}, () -> false));
        assertEquals(70, new File(dir, "download.zip.part").length());
        File[] files = store.ensure(p -> {}, () -> false);
        assertArrayEquals(model, Files.readAllBytes(files[0].toPath()));
        assertArrayEquals(tokens, Files.readAllBytes(files[1].toPath()));
        assertFalse(new File(dir, "download.zip.part").exists());
        store.ensure(p -> {}, () -> false);
        assertEquals(Arrays.asList(0L, 70L), offsets);
        // A new process validates the previous app cache without any network.
        new AsrModelStore(dir, spec(zip), (url, offset) -> { throw new AssertionError("redundant download"); })
                .ensure(p -> {}, () -> false);
    }
    @Test public void ignoredRangeRestartsInsteadOfAppending() throws Exception {
        byte[] zip = zip("model.int8.onnx", model); File dir = folder.newFolder();
        Files.write(new File(dir, "download.zip.part").toPath(), Arrays.copyOf(zip, 33));
        AsrModelStore store = new AsrModelStore(dir, spec(zip), (url, offset) -> {
            assertEquals(33, offset); return download(200, null, new ByteArrayInputStream(zip));
        });
        assertArrayEquals(model, Files.readAllBytes(store.ensure(p -> {}, () -> false)[0].toPath()));
    }
    @Test public void shortEofPreservesPrefixForRetry() throws Exception {
        byte[] zip = zip("model.int8.onnx", model); File dir = folder.newFolder();
        AsrModelStore store = new AsrModelStore(dir, spec(zip), (url, offset) -> offset == 0
                ? download(200, null, new ByteArrayInputStream(zip, 0, 33))
                : download(206, "bytes " + offset + "-" + (zip.length - 1) + "/" + zip.length,
                        new ByteArrayInputStream(zip, (int)offset, zip.length - (int)offset)));
        assertThrows(IOException.class, () -> store.ensure(p -> {}, () -> false));
        assertEquals(33, new File(dir, "download.zip.part").length());
        assertArrayEquals(model, Files.readAllBytes(store.ensure(p -> {}, () -> false)[0].toPath()));
    }
    @Test public void mismatchedRangeRejectedBeforeAppending() throws Exception {
        byte[] zip = zip("model.int8.onnx", model); File dir = folder.newFolder();
        Files.write(new File(dir, "download.zip.part").toPath(), Arrays.copyOf(zip, 33));
        AsrModelStore store = new AsrModelStore(dir, spec(zip), (url, offset) -> download(206,
                "bytes 0-" + (zip.length - 1) + "/" + zip.length, new ByteArrayInputStream(zip)));
        assertThrows(IOException.class, () -> store.ensure(p -> {}, () -> false));
        assertEquals(33, new File(dir, "download.zip.part").length());
    }
    @Test public void corruptArchiveDeletedAndCannotReplaceCache() throws Exception {
        byte[] zip = zip("model.int8.onnx", model), corrupt = zip.clone(); corrupt[60] ^= 8;
        File dir = folder.newFolder();
        AsrModelStore store = new AsrModelStore(dir, spec(zip), (url, offset) -> download(200, null, new ByteArrayInputStream(corrupt)));
        assertThrows(IOException.class, () -> store.ensure(p -> {}, () -> false));
        assertFalse(new File(dir, "download.zip.part").exists());
        assertFalse(new File(dir, "model.int8.onnx").exists());
    }
    @Test public void rejectsTraversalAndOversizedExtraction() throws Exception {
        for (byte[] zip : new byte[][]{ zip("../model.int8.onnx", model), zip("model.int8.onnx", new byte[model.length + 1]) }) {
            File dir = folder.newFolder();
            AsrModelStore store = new AsrModelStore(dir, spec(zip), (url, offset) -> download(200, null, new ByteArrayInputStream(zip)));
            assertThrows(IOException.class, () -> store.ensure(p -> {}, () -> false));
            assertFalse(new File(dir, "model.int8.onnx").exists());
            assertFalse(new File(dir, "model.int8.onnx.new").exists());
        }
    }
    @Test public void perFileDigestCheckedEvenWithValidArchiveDigest() throws Exception {
        byte[] wrong = model.clone(); wrong[0] ^= 1;
        byte[] zip = zip("model.int8.onnx", wrong); File dir = folder.newFolder();
        assertThrows(IOException.class, () -> new AsrModelStore(dir, spec(zip),
                (url, offset) -> download(200, null, new ByteArrayInputStream(zip))).ensure(p -> {}, () -> false));
    }
    @Test public void duplicateEntryRejected() throws Exception {
        // Repeat the first local ZIP record. ZipInputStream validates local entries,
        // so this fixture requires no central directory and permits a duplicate.
        byte[] original = zip("model.int8.onnx", model);
        int next = -1;
        for (int i = 4; i < original.length - 3; i++)
            if (original[i] == 0x50 && original[i+1] == 0x4b && original[i+2] == 3 && original[i+3] == 4) { next = i; break; }
        assertTrue(next > 0);
        byte[] duplicate = new byte[next * 2]; System.arraycopy(original, 0, duplicate, 0, next); System.arraycopy(original, 0, duplicate, next, next);
        File dir = folder.newFolder();
        assertThrows(IOException.class, () -> new AsrModelStore(dir, spec(duplicate),
                (url, offset) -> download(200, null, new ByteArrayInputStream(duplicate))).ensure(p -> {}, () -> false));
    }
    @Test public void cancelledPreparationDoesNotDownload() throws Exception {
        byte[] zip = zip("model.int8.onnx", model);
        assertThrows(InterruptedIOException.class, () -> new AsrModelStore(folder.newFolder(), spec(zip),
                (url, offset) -> { throw new AssertionError("cancel ignored"); }).ensure(p -> {}, () -> true));
    }
    @Test public void redirectsRemainOnHttpsOrigin() {
        URI origin = URI.create("https://cdn.example/asr.zip");
        assertTrue(AsrModelStore.HttpsSource.sameOrigin(origin, URI.create("https://cdn.example:443/new.zip")));
        for (String target : new String[]{"http://cdn.example/asr.zip", "https://elsewhere.example/asr.zip", "https://cdn.example:8443/asr.zip", "https://user@cdn.example/asr.zip", "https:///missing-host"})
            assertFalse(target, AsrModelStore.HttpsSource.sameOrigin(origin, URI.create(target)));
        assertFalse(AsrModelStore.validRange("bytes 5-9/10", 4, 10));
        assertTrue(AsrModelStore.validRange("bytes 5-9/10", 5, 10));
    }
    AsrModelStore.Spec splitSpec(byte[] zip, int cut) throws Exception {
        return new AsrModelStore.Spec(new AsrModelStore.Part[]{
                new AsrModelStore.Part("https://cdn.example/asr.zip.part01", cut, hash(Arrays.copyOf(zip, cut))),
                new AsrModelStore.Part("https://cdn.example/asr.zip.part02", zip.length-cut,
                        hash(Arrays.copyOfRange(zip, cut, zip.length)))},
                zip.length, hash(zip), model.length, hash(model), tokens.length, hash(tokens));
    }
    @Test public void splitDownloadResumesCurrentPartAndIgnoresRangeWithoutLosingFirstPart() throws Exception {
        for (boolean ignoresRange : new boolean[]{false, true}) {
            byte[] zip = zip("model.int8.onnx", model); int cut = 100;
            File dir = folder.newFolder(); List<String> calls = new ArrayList<>();
            AsrModelStore store = new AsrModelStore(dir, splitSpec(zip, cut), (url, offset) -> {
                calls.add(url + ":" + offset);
                if (url.endsWith("part01")) return download(200, null, new ByteArrayInputStream(zip, 0, cut));
                if (offset == 0) return download(200, null, new ByteArrayInputStream(zip, cut, 33));
                assertEquals(33, offset);
                return ignoresRange ? download(200, null, new ByteArrayInputStream(zip, cut, zip.length-cut))
                        : download(206, "bytes 33-" + (zip.length-cut-1) + "/" + (zip.length-cut),
                                new ByteArrayInputStream(zip, cut+33, zip.length-cut-33));
            });
            assertThrows(IOException.class, () -> store.ensure(p -> {}, () -> false));
            assertEquals(cut+33, new File(dir, "download.zip.part").length());
            assertArrayEquals(model, Files.readAllBytes(store.ensure(p -> {}, () -> false)[0].toPath()));
            assertEquals(Arrays.asList("https://cdn.example/asr.zip.part01:0",
                    "https://cdn.example/asr.zip.part02:0", "https://cdn.example/asr.zip.part02:33"), calls);
        }
    }
    @Test public void corruptSecondPartPreservesFirstAndRetriesOnlySecond() throws Exception {
        byte[] zip = zip("model.int8.onnx", model); int cut = 100;
        byte[] corrupt = Arrays.copyOfRange(zip, cut, zip.length); corrupt[10] ^= 1;
        File dir = folder.newFolder(); int[] attempt = {0}; List<String> calls = new ArrayList<>();
        AsrModelStore store = new AsrModelStore(dir, splitSpec(zip, cut), (url, offset) -> {
            calls.add(url); assertEquals(0, offset);
            if (url.endsWith("part01")) return download(200, null, new ByteArrayInputStream(zip, 0, cut));
            return download(200, null, new ByteArrayInputStream(attempt[0]++ == 0
                    ? corrupt : Arrays.copyOfRange(zip, cut, zip.length)));
        });
        assertThrows(IOException.class, () -> store.ensure(p -> {}, () -> false));
        assertArrayEquals(Arrays.copyOf(zip, cut), Files.readAllBytes(new File(dir, "download.zip.part").toPath()));
        assertArrayEquals(model, Files.readAllBytes(store.ensure(p -> {}, () -> false)[0].toPath()));
        assertEquals(3, calls.size());
    }
    @Test public void oldSingleFilePrefixAndVerifiedModelCacheRemainReusable() throws Exception {
        byte[] zip = zip("model.int8.onnx", model); int cut = 100;
        File dir = folder.newFolder();
        Files.write(new File(dir, "download.zip.part").toPath(), Arrays.copyOf(zip, cut+33));
        AsrModelStore store = new AsrModelStore(dir, splitSpec(zip, cut), (url, offset) -> {
            assertTrue(url.endsWith("part02")); assertEquals(33, offset);
            return download(206, "bytes 33-" + (zip.length-cut-1) + "/" + (zip.length-cut),
                    new ByteArrayInputStream(zip, cut+33, zip.length-cut-33));
        });
        File[] files = store.ensure(p -> {}, () -> false);
        assertArrayEquals(model, Files.readAllBytes(files[0].toPath()));
        new AsrModelStore(dir, splitSpec(zip, cut), (u, o) -> { throw new AssertionError("cache redownload"); })
                .ensure(p -> {}, () -> false);
    }
    @Test public void splitMetadataRejectsWrongSumDuplicatePartsAndUnknownFormat() throws Exception {
        byte[] zip = zip("model.int8.onnx", model);
        AsrModelStore.Spec spec = splitSpec(zip, 100);
        assertThrows(IllegalArgumentException.class, () -> new AsrModelStore.Spec(spec.parts,
                zip.length+1, hash(zip), model.length, hash(model), tokens.length, hash(tokens)));
        assertThrows(IllegalArgumentException.class, () -> new AsrModelStore.Spec(
                new AsrModelStore.Part[]{spec.parts[0], spec.parts[0]}, 200, hash(zip),
                model.length, hash(model), tokens.length, hash(tokens)));
        org.json.JSONObject data = new org.json.JSONObject()
                .put("files", new org.json.JSONObject()
                        .put("model.int8.onnx", new org.json.JSONObject().put("size", model.length).put("sha256", hash(model)))
                        .put("tokens.txt", new org.json.JSONObject().put("size", tokens.length).put("sha256", hash(tokens))))
                .put("download", new org.json.JSONObject().put("format", "concat-zip-v1")
                        .put("archive_bytes", zip.length).put("archive_sha256", hash(zip))
                        .put("parts", new org.json.JSONArray()
                                .put(new org.json.JSONObject().put("url", spec.parts[0].url).put("bytes", 100).put("sha256", spec.parts[0].hash))
                                .put(new org.json.JSONObject().put("url", spec.parts[1].url).put("bytes", zip.length-100).put("sha256", spec.parts[1].hash))));
        assertEquals(2, AsrModelStore.Spec.fromJson(data).parts.length);
        data.getJSONObject("download").put("format", "other");
        assertThrows(IllegalArgumentException.class, () -> AsrModelStore.Spec.fromJson(data));
    }
}
