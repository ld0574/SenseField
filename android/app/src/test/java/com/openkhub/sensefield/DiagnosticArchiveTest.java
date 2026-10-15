package com.openkhub.sensefield;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import org.junit.Test;

public final class DiagnosticArchiveTest {
    @Test public void boundedElementSamplesShareArchiveLimitsAndAppearInTheExport() throws Exception {
        File root=Files.createTempDirectory("sensefield-element-archive").toFile();
        try(DiagnosticArchive archive=new DiagnosticArchive(root,"diag-"+System.currentTimeMillis()+"-"+UUID.randomUUID(),"{}")) {
            assertTrue(archive.elementImage("u1-1.png",new byte[]{1,2,3}));
            assertFalse(archive.elementImage("u1-2.png",new byte[20000]));
            for(String bad:new String[]{"../a.png","u25-1.png","u1-3.png"})try { archive.elementImage(bad,new byte[]{1});org.junit.Assert.fail(bad); }
                catch(java.io.IOException expected) { }
            File zip=new File(root,"exports/test.zip");DiagnosticArchive.export(archive.directory,zip,"");
            try(java.util.zip.ZipFile opened=new java.util.zip.ZipFile(zip)) { assertTrue(opened.getEntry("elements/u1-1.png")!=null); }
        } finally { DiagnosticArchive.delete(root); }
    }
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
