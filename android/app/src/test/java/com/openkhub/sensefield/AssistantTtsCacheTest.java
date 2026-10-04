package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class AssistantTtsCacheTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void sweepRemovesOnlyOldOwnedWavsInCacheRoot() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        long now = System.currentTimeMillis();
        File oldCurrentFormat = touch(cache, "assistant_0123456789abcdef.wav",
                now - AssistantTtsCache.ORPHAN_GRACE_MS - 1);
        File oldLegacyFormat = touch(cache, "assistant_abcdef.wav",
                now - AssistantTtsCache.ORPHAN_GRACE_MS - 1);
        File freshAssistant = touch(cache, "assistant_aaaaaaaa.wav", now);
        File unrelatedAssistant = touch(cache, "assistant_notes.wav",
                now - AssistantTtsCache.ORPHAN_GRACE_MS - 1);
        File cueTone = touch(cache, "cue_main.wav", now - AssistantTtsCache.ORPHAN_GRACE_MS - 1);
        File nested = new File(cache, "nested");
        assertTrue(nested.mkdir());
        File nestedAssistant = touch(nested, "assistant_aaaaaaaa.wav",
                now - AssistantTtsCache.ORPHAN_GRACE_MS - 1);

        assertEquals(2, AssistantTtsCache.deleteStaleFiles(cache, now));
        assertFalse(oldCurrentFormat.exists());
        assertFalse(oldLegacyFormat.exists());
        assertTrue(freshAssistant.exists());
        assertTrue(unrelatedAssistant.exists());
        assertTrue(cueTone.exists());
        assertTrue(nestedAssistant.exists());
    }

    @Test public void sweepNeverFollowsAnOwnedLookingSymlinkOutsideCache() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        File outside = temporaryFolder.newFile("outside.wav");
        long now = System.currentTimeMillis();
        assertTrue(outside.setLastModified(now - AssistantTtsCache.ORPHAN_GRACE_MS - 1));
        File link = new File(cache, "assistant_abcdef.wav");
        Files.createSymbolicLink(link.toPath(), outside.toPath());

        assertEquals(0, AssistantTtsCache.deleteStaleFiles(cache, now));
        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertTrue(outside.isFile());
    }

    @Test public void concurrentSweepSkipsAFileOwnedByLiveUtterance() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        File active = AssistantTtsCache.createOutputFile(cache);
        assertTrue(active.createNewFile());
        long now = System.currentTimeMillis();
        assertTrue(active.setLastModified(now - AssistantTtsCache.ORPHAN_GRACE_MS - 1));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> sweep = executor.submit(
                    () -> AssistantTtsCache.deleteStaleFiles(cache, now));
            assertEquals(0, (int) sweep.get(5, TimeUnit.SECONDS));
            assertTrue("active assistant output must survive recovery", active.isFile());
        } finally {
            AssistantTtsCache.delete(active);
            executor.shutdownNow();
        }
        assertFalse(active.exists());
    }

    @Test public void separatePlayersKeepTheirActiveWavsWhileCompletedOutputIsRemoved()
            throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        File firstPlayerOutput = AssistantTtsCache.createOutputFile(cache);
        File secondPlayerOutput = AssistantTtsCache.createOutputFile(cache);
        assertTrue(firstPlayerOutput.createNewFile());
        assertTrue(secondPlayerOutput.createNewFile());
        long now = System.currentTimeMillis();
        long staleTime = now - AssistantTtsCache.ORPHAN_GRACE_MS - 1;
        assertTrue(firstPlayerOutput.setLastModified(staleTime));
        assertTrue(secondPlayerOutput.setLastModified(staleTime));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> sweep = executor.submit(
                    () -> AssistantTtsCache.deleteStaleFiles(cache, now));
            assertEquals(0, (int) sweep.get(5, TimeUnit.SECONDS));
            assertTrue(firstPlayerOutput.exists());
            assertTrue(secondPlayerOutput.exists());

            // Normal playback completion releases its path. The other CuePlayer remains active.
            AssistantTtsCache.delete(firstPlayerOutput);
            assertFalse(firstPlayerOutput.exists());
            assertEquals(0, AssistantTtsCache.deleteStaleFiles(cache, now));
            assertTrue(secondPlayerOutput.exists());
        } finally {
            AssistantTtsCache.delete(firstPlayerOutput);
            AssistantTtsCache.delete(secondPlayerOutput);
            executor.shutdownNow();
        }
    }

    @Test public void failedSynthesisCanReleaseAnOutputThatWasNeverCreated() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        File output = AssistantTtsCache.createOutputFile(cache);
        assertFalse(output.exists());

        // TTS can fail before opening its destination; cleanup must still release ownership.
        AssistantTtsCache.delete(output);
        assertFalse(output.exists());

        // A late file left by an engine after failure is now an orphan and is recoverable.
        assertTrue(output.createNewFile());
        long now = System.currentTimeMillis();
        assertTrue(output.setLastModified(now - AssistantTtsCache.ORPHAN_GRACE_MS - 1));
        assertEquals(1, AssistantTtsCache.deleteStaleFiles(cache, now));
        assertFalse(output.exists());
    }

    @Test public void concurrentPlayersScheduleTheProcessSweepOnlyOnce() throws Exception {
        AssistantTtsCache.StartupSweepGate gate = new AssistantTtsCache.StartupSweepGate();
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("test start was not released");
                    return gate.claim();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int claims = 0;
            for (Future<Boolean> result : results) if (result.get(5, TimeUnit.SECONDS)) claims++;
            assertEquals(1, claims);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static File touch(File directory, String name, long modified) throws Exception {
        File file = new File(directory, name);
        assertTrue(file.createNewFile());
        assertTrue(file.setLastModified(modified));
        return file;
    }
}
