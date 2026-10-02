package com.openkhub.sensefield;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Private, bounded session storage. All calls run on the diagnostic IO worker. */
final class DiagnosticArchive implements AutoCloseable {
    static final long LOG_LIMIT = 16L * 1024 * 1024;
    static final long IMAGE_LIMIT = 60L * 1024 * 1024;
    final File directory;
    private final BufferedWriter writer;
    private long logBytes;
    private long imageBytes;
    private boolean closed;

    DiagnosticArchive(File root, String id, String metadata) throws IOException {
        directory = session(root, id);
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("无法建立诊断目录");
        Files.write(new File(directory, "metadata.json").toPath(), metadata.getBytes(StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(directory, "events.jsonl"), false), StandardCharsets.UTF_8));
        prune(root, 3, directory);
    }

    boolean append(String line) throws IOException {
        if (closed) return false;
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (logBytes + bytes.length + 1 > LOG_LIMIT) return false;
        writer.write(line);
        writer.newLine();
        logBytes += bytes.length + 1;
        return true;
    }

    void flush() throws IOException { if (!closed) writer.flush(); }

    /** Keep the last known state on disk even if the service is killed later. */
    void checkpoint(String json) throws IOException {
        if (closed || json == null) return;
        writeJson(directory, "checkpoint.json", json);
    }

    boolean image(String name, byte[] jpeg) throws IOException {
        if (closed || jpeg == null || imageBytes + jpeg.length > IMAGE_LIMIT) return false;
        if (!name.matches("(?:screen|map)-[0-9]+-[0-9]+\\.jpg")) throw new IOException("无效的画面文件名");
        File images = new File(directory, "images");
        if (!images.mkdirs() && !images.isDirectory()) throw new IOException("无法建立画面目录");
        Files.write(new File(images, name).toPath(), jpeg);
        imageBytes += jpeg.length;
        return true;
    }

    void finish(String summary) throws IOException {
        if (closed) return;
        close();
        writeJson(directory, "summary.json", summary);
        Files.deleteIfExists(new File(directory, "checkpoint.json").toPath());
        Files.deleteIfExists(new File(directory, "checkpoint.json.part").toPath());
    }

    static void writeJson(File directory, String name, String json) throws IOException {
        File temporary = new File(directory, name + ".part");
        if (Files.isSymbolicLink(temporary.toPath())) throw new IOException("诊断文件路径无效");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), new File(directory, name).toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(temporary.toPath(), new File(directory, name).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        writer.close();
    }

    static File session(File root, String id) throws IOException {
        if (id == null || !id.matches("diag-[0-9]+-[a-fA-F0-9-]{36}"))
            throw new IOException("无效的对局记录");
        File target = new File(root, id);
        if (!target.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))
            throw new IOException("无效的诊断路径");
        return target;
    }

    static File[] sessions(File root) {
        File[] found = root.listFiles(f -> f.isDirectory()
                && !Files.isSymbolicLink(f.toPath())
                && f.getName().matches("diag-[0-9]+-[a-fA-F0-9-]{36}"));
        if (found == null) return new File[0];
        Arrays.sort(found, Comparator.comparing(File::getName).reversed());
        return found;
    }

    static void prune(File root, int keep, File active) throws IOException {
        int retained = 0;
        for (File f : sessions(root)) {
            if (f.equals(active)) continue;
            if (retained < keep - 1) {
                retained++;
                continue;
            }
            delete(f);
        }
    }

    static void delete(File file) throws IOException {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        Files.deleteIfExists(file.toPath());
    }

    static void export(File directory, File zip, String feedback) throws IOException {
        if (!directory.isDirectory() || !new File(directory, "metadata.json").isFile())
            throw new IOException("没有可导出的对局记录");
        if (!zip.getParentFile().mkdirs() && !zip.getParentFile().isDirectory())
            throw new IOException("无法建立导出目录");
        File temporary = new File(zip.getParentFile(), zip.getName() + ".part");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temporary))) {
            for (String name : new String[]{"metadata.json", "events.jsonl", "summary.json",
                    "checkpoint.json", "events-truncated-tail.txt", "metadata-incomplete.txt",
                    "summary-incomplete.txt"}) {
                File file = new File(directory, name);
                if (file.isFile()) entry(out, directory, file, name);
            }
            File images = new File(directory, "images");
            File[] files = images.listFiles();
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File file : files) if (file.isFile()
                        && file.getName().matches("(?:screen|map)-[0-9]+-[0-9]+\\.jpg"))
                    entry(out, directory, file, "images/" + file.getName());
            }
            out.putNextEntry(new ZipEntry("feedback.txt"));
            out.write((feedback == null ? "" : feedback).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        } catch (IOException error) { Files.deleteIfExists(temporary.toPath()); throw error; }
        Files.move(temporary.toPath(), zip.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void entry(ZipOutputStream out, File root, File file, String name) throws IOException {
        if (Files.isSymbolicLink(file.toPath()) || !file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("诊断文件路径无效");
        out.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        out.closeEntry();
    }
}
