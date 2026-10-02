package com.openkhub.sensefield;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import org.junit.Test;

public final class DiagnosticArchiveTest {
    @Test public void checkpointSurvivesUntilNormalFinish() throws Exception {
        File root = Files.createTempDirectory("sensefield-diagnostics").toFile();
        String id = "diag-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
        DiagnosticArchive archive = new DiagnosticArchive(root, id, "{}");
        archive.checkpoint("{\"state\":\"capturing\"}");
        assertTrue(new File(archive.directory, "checkpoint.json").isFile());

        archive.finish("{\"reason\":\"stopped\"}");
        assertFalse(new File(archive.directory, "checkpoint.json").isFile());
        assertTrue(new File(archive.directory, "summary.json").isFile());
        DiagnosticArchive.delete(root);
    }

    @Test public void failedSummaryWriteKeepsTheLastCheckpoint() throws Exception {
        File root = Files.createTempDirectory("sensefield-diagnostics").toFile();
        DiagnosticArchive archive = new DiagnosticArchive(root,
                "diag-" + System.currentTimeMillis() + "-" + UUID.randomUUID(), "{}");
        archive.checkpoint("{\"state\":\"paused\"}");
        // A directory at the target makes the atomic replacement fail.
        assertTrue(new File(archive.directory, "summary.json").mkdir());
        Files.write(new File(archive.directory, "summary.json/occupied").toPath(), new byte[]{1});
        try {
            archive.finish("{\"reason\":\"stopped\"}");
            org.junit.Assert.fail("Expected summary replacement failure");
        } catch (java.io.IOException expected) {
            assertTrue(new File(archive.directory, "checkpoint.json").isFile());
        } finally { DiagnosticArchive.delete(root); }
    }
}
