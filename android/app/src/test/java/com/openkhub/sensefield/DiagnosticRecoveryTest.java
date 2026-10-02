package com.openkhub.sensefield;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.zip.ZipFile;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class DiagnosticRecoveryTest {
    private File root;
    private File directory;
    private String sessionId;

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("sensefield-recovery").toFile();
        sessionId = UUID.randomUUID().toString();
        directory = DiagnosticArchive.session(root, "diag-123456-" + sessionId);
        assertTrue(directory.mkdirs());
        write("metadata.json", new JSONObject().put("session_id", sessionId)
                .put("started_elapsed_ms", 1000).toString());
        write("events.jsonl", "{\"type\":\"frame\"}\n");
    }

    @After public void cleanup() throws Exception { DiagnosticArchive.delete(root); }

    @Test public void orphanRecoversLastCountersWithoutGuessingStopTimeOrCause() throws Exception {
        checkpoint();
        assertEquals(1, DiagnosticRecovery.recover(root, null, 900000));
        JSONObject summary = json("summary.json");
        assertTrue(summary.getBoolean("interrupted"));
        assertFalse(summary.getBoolean("end_observed"));
        assertEquals("process_interrupted", summary.getString("reason"));
        assertTrue(summary.isNull("ended_at_ms"));
        assertTrue(summary.isNull("duration_ms"));
        assertEquals(5000, summary.getLong("duration_lower_bound_ms"));
        assertEquals(12, summary.getInt("frame_count"));
        assertEquals("paused", summary.getJSONObject("last_state").getString("state"));
        assertTrue(new File(directory, "checkpoint.json").isFile());
        assertEquals(DiagnosticRecovery.Status.INTERRUPTED, DiagnosticRecovery.status(directory, null));
    }

    @Test public void liveSessionIsNeverRecoveredOrLabelledInterrupted() throws Exception {
        checkpoint();
        byte[] events = Files.readAllBytes(new File(directory, "events.jsonl").toPath());
        assertEquals(0, DiagnosticRecovery.recover(root, directory.getName(), 900000));
        assertFalse(new File(directory, "summary.json").exists());
        assertArrayEquals(events, Files.readAllBytes(new File(directory, "events.jsonl").toPath()));
        assertEquals(DiagnosticRecovery.Status.ACTIVE,
                DiagnosticRecovery.status(directory, directory.getName()));
    }

    @Test public void finalizedSummaryIsPreservedByteForByte() throws Exception {
        String original = new JSONObject().put("session_id", sessionId).put("reason", "stopped")
                .put("duration_ms", 15000).toString();
        write("summary.json", original);
        assertEquals(0, DiagnosticRecovery.recover(root, null, 900000));
        assertEquals(original, text("summary.json"));
        assertEquals(DiagnosticRecovery.Status.FINISHED, DiagnosticRecovery.status(directory, null));
    }

    @Test public void missingCheckpointDoesNotPretendToHaveZeroFrames() throws Exception {
        DiagnosticRecovery.recover(root, null, 900000);
        JSONObject summary = json("summary.json");
        assertEquals("missing", summary.getString("checkpoint_status"));
        assertTrue(summary.isNull("frame_count"));
        assertTrue(summary.isNull("last_known_at_ms"));
        assertTrue(summary.isNull("duration_lower_bound_ms"));
    }

    @Test public void mismatchedCheckpointCannotSupplyAnotherSessionsCounters() throws Exception {
        write("checkpoint.json", new JSONObject().put("session_id", UUID.randomUUID().toString())
                .put("at_ms", 10000).put("data", new JSONObject().put("frame_count", 999)).toString());
        DiagnosticRecovery.recover(root, null, 900000);
        JSONObject summary = json("summary.json");
        assertEquals("invalid", summary.getString("checkpoint_status"));
        assertTrue(summary.isNull("frame_count"));
        assertTrue(summary.isNull("last_state"));
    }

    @Test public void partialMetadataIsPreservedAndMarkedUnavailable() throws Exception {
        write("metadata.json", "{\"model\":");
        Files.delete(new File(directory, "events.jsonl").toPath());
        DiagnosticRecovery.recover(root, null, 900000);
        assertTrue(json("metadata.json").getBoolean("device_metadata_missing"));
        assertEquals(sessionId, json("metadata.json").getString("session_id"));
        assertEquals("{\"model\":", text("metadata-incomplete.txt"));
        assertTrue(new File(directory, "events.jsonl").isFile());
    }

    @Test public void truncatedEventBytesAreSavedAndJsonLinesRemainParseable() throws Exception {
        String tail = "{\"type\":\"frame\",\"data\":";
        write("events.jsonl", "{\"type\":\"frame\"}\n" + tail);
        DiagnosticRecovery.recover(root, null, 900000);
        assertEquals(tail, text("events-truncated-tail.txt"));
        assertTrue(json("summary.json").getBoolean("truncated_event_tail_saved"));
        int lines = 0;
        for (String line : Files.readAllLines(new File(directory, "events.jsonl").toPath())) {
            new JSONObject(line);
            lines++;
        }
        assertEquals(2, lines);
    }

    @Test public void validLastEventWithoutNewlineIsKept() throws Exception {
        write("events.jsonl", "{\"type\":\"frame\"}");
        DiagnosticRecovery.recover(root, null, 900000);
        assertFalse(new File(directory, "events-truncated-tail.txt").exists());
        assertEquals(2, Files.readAllLines(new File(directory, "events.jsonl").toPath()).size());
    }

    @Test public void repeatedRecoveryDoesNotAppendDuplicateEvents() throws Exception {
        checkpoint();
        DiagnosticRecovery.recover(root, null, 900000);
        byte[] summary = Files.readAllBytes(new File(directory, "summary.json").toPath());
        byte[] events = Files.readAllBytes(new File(directory, "events.jsonl").toPath());
        assertEquals(0, DiagnosticRecovery.recover(root, null, 999000));
        assertArrayEquals(summary, Files.readAllBytes(new File(directory, "summary.json").toPath()));
        assertArrayEquals(events, Files.readAllBytes(new File(directory, "events.jsonl").toPath()));
    }

    @Test public void recoveredZipIncludesFinalSummaryAndOriginalCheckpoint() throws Exception {
        checkpoint();
        DiagnosticRecovery.recover(root, null, 900000);
        File zip = new File(root, "export/diagnostics.zip");
        DiagnosticArchive.export(directory, zip, "玩家反馈");
        try (ZipFile archive = new ZipFile(zip)) {
            for (String name : new String[]{"metadata.json", "events.jsonl", "summary.json",
                    "checkpoint.json", "feedback.txt"}) assertNotNull(archive.getEntry(name));
        }
    }

    private void checkpoint() throws Exception {
        write("checkpoint.json", new JSONObject().put("session_id", sessionId).put("at_ms", 6000)
                .put("data", new JSONObject().put("state", "paused").put("frame_count", 12)
                        .put("image_count", 2)).toString());
    }

    private void write(String name, String value) throws Exception {
        Files.write(new File(directory, name).toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private JSONObject json(String name) throws Exception {
        return new JSONObject(text(name));
    }

    private String text(String name) throws Exception {
        return new String(Files.readAllBytes(new File(directory, name).toPath()), StandardCharsets.UTF_8);
    }
}
