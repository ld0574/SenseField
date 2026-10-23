package com.openkhub.sensefield;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DiagnosticGameTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File session(String metadata) throws Exception {
        File directory = temporary.newFolder();
        write(directory, "metadata.json", metadata);
        return directory;
    }

    private static void write(File directory, String name, String text) throws Exception {
        Files.write(new File(directory, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void explicitGameIdentityOverridesLegacyMode() throws Exception {
        assertEquals(DiagnosticGame.MATCH3, DiagnosticGame.read(session(
                "{\"game_id\":\"happy-anipop\",\"portrait_images_allowed\":false}")));
        assertEquals(DiagnosticGame.HONOR, DiagnosticGame.read(session(
                "{\"game_id\":\"honor-of-kings\",\"portrait_images_allowed\":true}")));
    }

    @Test public void oldRecorderModesRemainReadableWithoutChangingFiles() throws Exception {
        for (boolean portrait : new boolean[]{false, true}) {
            File directory = session("{\"schema\":\"sensefield.diagnostics\",\"portrait_images_allowed\":"
                    + portrait + "}");
            byte[] original = Files.readAllBytes(new File(directory, "metadata.json").toPath());
            assertEquals(portrait ? DiagnosticGame.MATCH3 : DiagnosticGame.HONOR, DiagnosticGame.read(directory));
            assertArrayEquals(original, Files.readAllBytes(new File(directory, "metadata.json").toPath()));
        }
    }

    @Test public void recoveredCheckpointCanIdentifyARecordWithDamagedMetadata() throws Exception {
        File directory = session("{incomplete");
        write(directory, "checkpoint.json", "{\"data\":{\"latest_state\":{\"game_id\":\"happy-anipop\"}}}");
        assertEquals(DiagnosticGame.MATCH3, DiagnosticGame.read(directory));
        Files.delete(new File(directory, "checkpoint.json").toPath());
        write(directory, "summary.json", "{\"last_state\":{\"game_id\":\"happy-anipop\"}}");
        assertEquals(DiagnosticGame.MATCH3, DiagnosticGame.read(directory));
    }

    @Test public void unknownOrFutureGamesAreNotRelabelledAsTheCurrentGame() throws Exception {
        assertEquals(DiagnosticGame.UNKNOWN, DiagnosticGame.read(session("{}")));
        assertEquals(DiagnosticGame.UNKNOWN, DiagnosticGame.read(session(
                "{\"game_id\":\"future-game\",\"schema\":\"sensefield.diagnostics\",\"portrait_images_allowed\":true}")));
        assertEquals(DiagnosticGame.UNKNOWN, DiagnosticGame.read(session("{\"portrait_images_allowed\":\"false\"}")));
        assertEquals(DiagnosticGame.UNKNOWN, DiagnosticGame.read(temporary.newFolder()));
    }
}
