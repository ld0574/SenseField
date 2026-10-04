package com.openkhub.sensefield;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Owns the temporary WAV files used only to render assistant TTS. */
final class AssistantTtsCache {
    // Give a previous process's TTS engine time to release an output descriptor before recovery.
    static final long ORPHAN_GRACE_MS = 60_000L;

    private static final Pattern OWNED_NAME = Pattern.compile(
            "assistant_(?:[0-9a-f]{1,16}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\\.wav");
    private static final Object LOCK = new Object();
    private static final Set<String> ACTIVE_PATHS = new HashSet<>();
    private static final StartupSweepGate PROCESS_STARTUP_GATE = new StartupSweepGate();

    private AssistantTtsCache() {}

    static File createOutputFile(File cacheDirectory) throws IOException {
        File root = cacheDirectory.getCanonicalFile();
        if (!root.isDirectory()) throw new IOException("Assistant cache directory unavailable");
        synchronized (LOCK) {
            for (int attempt = 0; attempt < 4; attempt++) {
                File output = new File(root, "assistant_" + UUID.randomUUID() + ".wav");
                String path = output.getCanonicalPath();
                if (output.exists() || ACTIVE_PATHS.contains(path)) continue;
                ACTIVE_PATHS.add(path);
                return output;
            }
            throw new IOException("Could not allocate a unique assistant TTS cache file");
        }
    }

    /** Claims the one recovery schedule for this process, shared by all CuePlayer instances. */
    static boolean claimProcessStartupSweep() {
        return PROCESS_STARTUP_GATE.claim();
    }

    /**
     * Removes old assistant TTS outputs from the cache root only. Fresh files and outputs being
     * synthesized or played by any CuePlayer in this process are preserved.
     */
    static int deleteStaleFiles(File cacheDirectory, long nowMs) throws IOException {
        File root = cacheDirectory.getCanonicalFile();
        if (!root.isDirectory()) throw new IOException("Assistant cache directory unavailable");
        File[] entries = root.listFiles();
        if (entries == null) throw new IOException("Could not list assistant cache directory");

        int deleted = 0;
        synchronized (LOCK) {
            for (File candidate : entries) {
                if (!isOwnedAssistantWav(root, candidate)) continue;
                if (Files.isSymbolicLink(candidate.toPath())
                        || !Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS))
                    continue;
                String path = candidate.getCanonicalPath();
                if (ACTIVE_PATHS.contains(path)) continue;
                long modified = candidate.lastModified();
                if (modified <= 0 || nowMs < modified
                        || nowMs - modified < ORPHAN_GRACE_MS) continue;
                if (candidate.delete()) deleted++;
            }
        }
        return deleted;
    }

    /** Deletes and unregisters one file previously returned by createOutputFile. */
    static void delete(File file) {
        if (file == null) return;
        synchronized (LOCK) {
            File parent = file.getParentFile();
            if (parent == null) return;
            try {
                File root = parent.getCanonicalFile();
                if (!isOwnedAssistantWav(root, file) || Files.isSymbolicLink(file.toPath())) return;
                ACTIVE_PATHS.remove(file.getCanonicalPath());
                if (file.exists()) file.delete();
            } catch (IOException ignored) {
                // Cache deletion is best-effort; startup recovery can remove a leftover later.
            }
        }
    }

    private static boolean isOwnedAssistantWav(File root, File candidate) throws IOException {
        if (candidate == null || !OWNED_NAME.matcher(candidate.getName()).matches()) return false;
        File parent = candidate.getParentFile();
        return parent != null && root.equals(parent.getCanonicalFile());
    }

    static final class StartupSweepGate {
        private boolean claimed;

        synchronized boolean claim() {
            if (claimed) return false;
            claimed = true;
            return true;
        }
    }
}
