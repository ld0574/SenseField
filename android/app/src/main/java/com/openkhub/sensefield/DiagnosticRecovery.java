package com.openkhub.sensefield;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Recovers orphaned records on the diagnostic IO worker, never an active session. */
final class DiagnosticRecovery {
    private static final long JSON_LIMIT = 256 * 1024;
    enum Status { ACTIVE, FINISHED, INTERRUPTED, INCOMPLETE }

    private DiagnosticRecovery() {}

    static Status status(File directory, String activeDirectory) {
        if (directory.getName().equals(activeDirectory)) return Status.ACTIVE;
        try {
            JSONObject summary = read(new File(directory, "summary.json"));
            if (sessionId(directory).equals(summary.optString("session_id"))
                    && summary.has("reason"))
                return summary.optBoolean("interrupted", false) ? Status.INTERRUPTED : Status.FINISHED;
        } catch (IOException | JSONException ignored) { /* Missing/incomplete final summary. */ }
        return Status.INCOMPLETE;
    }

    static int recover(File root, String activeDirectory, long wallMs) throws IOException {
        int count = 0;
        IOException failure = null;
        for (File directory : DiagnosticArchive.sessions(root)) {
            if (status(directory, activeDirectory) != Status.INCOMPLETE) continue;
            try { recoverOne(directory, wallMs); count++; }
            catch (IOException error) { failure = error; }
        }
        if (failure != null) throw failure;
        return count;
    }

    private static void recoverOne(File directory, long wallMs) throws IOException {
        String id = sessionId(directory);
        JSONObject metadata;
        try {
            metadata = read(new File(directory, "metadata.json"));
            if (!id.equals(metadata.optString("session_id"))) throw new JSONException("session mismatch");
        } catch (IOException | JSONException error) {
            preserve(directory, "metadata.json", "metadata-incomplete.txt");
            metadata = DiagnosticRecorder.object("schema", "sensefield.diagnostics", "schema_version", 1,
                    "session_id", id, "metadata_recovered", true, "device_metadata_missing", true);
            DiagnosticArchive.writeJson(directory, "metadata.json", metadata.toString());
        }
        JSONObject checkpoint = null;
        String checkpointStatus = "missing";
        File marker = new File(directory, "checkpoint.json");
        if (marker.isFile()) {
            try {
                checkpoint = read(marker);
                if (!id.equals(checkpoint.optString("session_id"))) throw new JSONException("session mismatch");
                checkpointStatus = "available";
            } catch (IOException | JSONException error) { checkpoint = null; checkpointStatus = "invalid"; }
        }
        JSONObject state = checkpoint == null ? null : checkpoint.optJSONObject("data");
        long started = metadata.optLong("started_elapsed_ms", -1);
        long lastKnown = checkpoint == null ? -1 : checkpoint.optLong("at_ms", -1);
        boolean savedTail = repairEventTail(directory);
        JSONObject recovery = DiagnosticRecorder.object("type", "session_recovered", "session_id", id,
                "recovered_wall_ms", wallMs, "data", DiagnosticRecorder.object(
                        "reason", "process_interrupted", "checkpoint_status", checkpointStatus));
        File events = new File(directory, "events.jsonl");
        boolean recoveryLogged = events.length() + recovery.toString().getBytes(StandardCharsets.UTF_8).length
                + 1 <= DiagnosticArchive.LOG_LIMIT;
        if (recoveryLogged) {
            try (FileOutputStream out = new FileOutputStream(events, true)) {
                out.write((recovery + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
        }
        JSONObject summary = DiagnosticRecorder.object("session_id", id, "reason", "process_interrupted",
                "interrupted", true, "recovered", true, "end_observed", false,
                "ended_at_ms", JSONObject.NULL, "duration_ms", JSONObject.NULL,
                "duration_lower_bound_ms", started >= 0 && lastKnown >= started
                        ? lastKnown - started : JSONObject.NULL,
                "last_known_at_ms", lastKnown >= 0 ? lastKnown : JSONObject.NULL,
                "recovered_wall_ms", wallMs, "checkpoint_status", checkpointStatus,
                "frame_count", state == null || !state.has("frame_count")
                        ? JSONObject.NULL : state.opt("frame_count"),
                "image_count", state == null || !state.has("image_count")
                        ? JSONObject.NULL : state.opt("image_count"),
                "last_state", state == null ? JSONObject.NULL : state,
                "truncated_event_tail_saved", savedTail, "recovery_event_recorded", recoveryLogged,
                "recovery_note", "Final summary missing; actual stop time and cause are unknown. Last state is a checkpoint, not a reconstructed complete session.",
                "playback_note", "TTS callbacks and vibration requests do not prove actual sound/haptic delivery.");
        preserve(directory, "summary.json", "summary-incomplete.txt");
        try { DiagnosticArchive.writeJson(directory, "summary.json", summary.toString(2)); }
        catch (JSONException error) { throw new IOException("无法恢复诊断摘要", error); }
        // Keep checkpoint and any damaged tail as forensic evidence in the ZIP.
    }

    private static JSONObject read(File file) throws IOException, JSONException {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || file.length() > JSON_LIMIT)
            throw new IOException("诊断文件缺失或超出读取范围");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private static String sessionId(File directory) {
        String name = directory.getName();
        return name.substring(name.indexOf('-', 5) + 1);
    }

    private static void preserve(File directory, String source, String target) throws IOException {
        File file = new File(directory, source);
        if (!file.exists()) return;
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("诊断文件路径无效");
        Files.move(file.toPath(), new File(directory, target).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Save an interrupted final JSON line, so events.jsonl remains parseable. */
    private static boolean repairEventTail(File directory) throws IOException {
        File file = new File(directory, "events.jsonl");
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("诊断文件路径无效");
        try (RandomAccessFile log = new RandomAccessFile(file, "rw")) {
            long length = log.length();
            if (length == 0) return false;
            if (length > DiagnosticArchive.LOG_LIMIT) throw new IOException("诊断日志超出读取范围");
            log.seek(length - 1);
            if (log.read() == '\n') return false;
            long start = length;
            // The file is bounded to 16 MB; scan backwards in bounded chunks.
            byte[] block = new byte[8192];
            boolean found = false;
            while (start > 0 && !found) {
                long offset = Math.max(0, start - block.length);
                int n = (int) (start - offset);
                log.seek(offset);
                log.readFully(block, 0, n);
                for (int i = n - 1; i >= 0; i--) if (block[i] == '\n') {
                    start = offset + i + 1;
                    found = true;
                    break;
                }
                if (!found) start = offset;
            }
            byte[] tail = new byte[(int) (length - start)];
            log.seek(start);
            log.readFully(tail);
            boolean valid = false;
            if (tail.length <= JSON_LIMIT) {
                try { new JSONObject(new String(tail, StandardCharsets.UTF_8)); valid = true; }
                catch (JSONException ignored) { /* Preserve incomplete JSON bytes. */ }
            }
            if (valid) {
                log.seek(length);
                log.write('\n');
            } else {
                try (FileOutputStream out = new FileOutputStream(
                        new File(directory, "events-truncated-tail.txt"))) {
                    out.write(tail);
                    out.getFD().sync();
                }
                log.setLength(start);
            }
            log.getFD().sync();
            return !valid;
        }
    }
}
