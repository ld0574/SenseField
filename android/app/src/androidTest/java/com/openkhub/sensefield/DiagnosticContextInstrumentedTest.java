package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Mixed-game records, actual settings navigation, recreation and isolated deletion on an emulator. */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticContextInstrumentedTest {
    private final List<File> created = new ArrayList<>();
    private File backup, match3, honor, unknown, match3Export, honorExport, unknownExport;

    private static Context context() { return instrumentation().getTargetContext(); }
    private static Instrumentation instrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private static void drain() throws Exception { DiagnosticRecorder.IO.submit(() -> { }).get(5, TimeUnit.SECONDS); }

    @Before public void setUp() throws Exception {
        Assume.assumeTrue("Fixtures and delete checks run only on an emulator",
                "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
        assertFalse(CaptureService.isRunning()); assertFalse(Match3LiveService.isRunning());
        drain();
        backup = new File(context().getCacheDir(), "diagnostic-context-backup-" + UUID.randomUUID());
        assertTrue(backup.mkdirs());
        for (File existing : DiagnosticArchive.sessions(DiagnosticRecorder.root(context())))
            Files.move(existing.toPath(), new File(backup, existing.getName()).toPath());
        // Honor is newer: Match3 must not select it simply because it is the latest record.
        match3 = fixture(DiagnosticGame.MATCH3, System.currentTimeMillis(), true);
        honor = fixture(DiagnosticGame.HONOR, System.currentTimeMillis() + 1000, false);
        unknown = fixture(DiagnosticGame.UNKNOWN, System.currentTimeMillis() + 2000, false);
        match3Export = exportFile(match3); honorExport = exportFile(honor); unknownExport = exportFile(unknown);
    }

    @After public void tearDown() throws Exception {
        if (backup == null) return;
        drain();
        for (File file : created) DiagnosticArchive.delete(file);
        File[] preserved = backup.listFiles();
        if (preserved != null) for (File file : preserved)
            Files.move(file.toPath(), new File(DiagnosticRecorder.root(context()), file.getName()).toPath());
        DiagnosticArchive.delete(backup);
    }

    private File fixture(DiagnosticGame game, long time, boolean legacy) throws Exception {
        String id = UUID.randomUUID().toString();
        File directory = new File(DiagnosticRecorder.root(context()), "diag-" + time + "-" + id);
        assertTrue(directory.mkdirs()); created.add(directory);
        org.json.JSONObject metadata = DiagnosticRecorder.object("schema", "sensefield.diagnostics", "session_id", id);
        if (legacy) metadata.put("portrait_images_allowed", true);
        else if (game != DiagnosticGame.UNKNOWN) metadata.put("game_id", game.id);
        DiagnosticArchive.writeJson(directory, "metadata.json", metadata.toString());
        DiagnosticArchive.writeJson(directory, "summary.json", DiagnosticRecorder.object("session_id", id,
                "reason", "synthetic_context_test", "interrupted", false).toString());
        return directory;
    }

    private File exportFile(File session) throws Exception {
        File directory = new File(context().getCacheDir(), "diagnostic-exports");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, "sensefield-" + session.getName() + "-123.zip");
        Files.write(file.toPath(), "synthetic export".getBytes(StandardCharsets.UTF_8)); created.add(file);
        return file;
    }

    private static View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = find(((ViewGroup) root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }

    private static <T extends View> T first(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            T found = first(((ViewGroup) root).getChildAt(i), type); if (found != null) return found;
        }
        return null;
    }

    private static Intent fromSettings(Class<? extends Activity> settings) {
        AtomicReference<Intent> launched = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() != null && DiagnosticsActivity.class.getName().equals(
                        intent.getComponent().getClassName())) {
                    launched.set(new Intent(intent));
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        instrumentation().addMonitor(monitor);
        try (ActivityScenario<? extends Activity> page = ActivityScenario.launch(new Intent(context(), settings))) {
            page.onActivity(activity -> activity.findViewById(android.R.id.content)
                    .findViewWithTag("ui_nav:测试记录与反馈").performClick());
            assertNotNull("The real settings row must launch the diagnostic screen", launched.get());
            return launched.get();
        } finally { instrumentation().removeMonitor(monitor); }
    }

    private static void loaded(ActivityScenario<DiagnosticsActivity> page, int expected) {
        int[] count = {-1}; long until = SystemClock.elapsedRealtime() + 5000;
        while (count[0] != expected && SystemClock.elapsedRealtime() < until) {
            page.onActivity(activity -> count[0] = first(activity.findViewById(android.R.id.content), RadioGroup.class).getChildCount());
            if (count[0] != expected) SystemClock.sleep(50);
        }
        assertEquals("Record loading must complete", expected, count[0]);
    }

    private void checkRecords(Activity activity, File own, File other) {
        RadioGroup group = first(activity.findViewById(android.R.id.content), RadioGroup.class);
        assertEquals(2, group.getChildCount());
        assertNotNull(group.findViewWithTag(own.getName()));
        assertNotNull(group.findViewWithTag(unknown.getName()));
        assertNull("Another game's record must not be presented as this game's", group.findViewWithTag(other.getName()));
        assertTrue(((RadioButton) group.findViewWithTag(unknown.getName())).getText().toString().startsWith("未识别游戏"));
    }

    @Test public void match3EntryScopesRecordsAndRestoresSelectionAndFeedback() throws Exception {
        Intent intent = fromSettings(Match3SettingsActivity.class);
        assertEquals(DiagnosticGame.MATCH3.id, intent.getStringExtra(DiagnosticsActivity.EXTRA_GAME));
        try (ActivityScenario<DiagnosticsActivity> page = ActivityScenario.launch(intent)) {
            loaded(page, 2);
            page.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertNotNull(find(root, "开心消消乐辅助")); assertNull(find(root, "王者荣耀辅助"));
                checkRecords(activity, match3, honor);
                RadioGroup group = first(root, RadioGroup.class);
                assertTrue(((RadioButton) group.findViewWithTag(match3.getName())).isChecked());
                ((RadioButton) group.findViewWithTag(unknown.getName())).setChecked(true);
                EditText note = first(root, EditText.class);
                assertTrue(note.getHint().toString().contains("交换框"));
                note.setText("第12关，交换框偏了一格");
            });
            page.recreate(); loaded(page, 2);
            page.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertNotNull(find(root, "开心消消乐辅助")); checkRecords(activity, match3, honor);
                assertTrue(((RadioButton) root.findViewWithTag(unknown.getName())).isChecked());
                assertEquals("第12关，交换框偏了一格", first(root, EditText.class).getText().toString());
            });
        }
        UiQualityInstrumentedTest.capture(intent, "match3-feedback");
    }

    @Test public void honorEntryAndLegacyDefaultKeepHonorContext() throws Exception {
        Intent entry = fromSettings(AppSettingsActivity.class);
        assertEquals(DiagnosticGame.HONOR.id, entry.getStringExtra(DiagnosticsActivity.EXTRA_GAME));
        for (Intent intent : new Intent[]{entry, new Intent(context(), DiagnosticsActivity.class)}) {
            try (ActivityScenario<DiagnosticsActivity> page = ActivityScenario.launch(intent)) {
                loaded(page, 2);
                page.onActivity(activity -> {
                    View root = activity.findViewById(android.R.id.content);
                    assertNotNull(find(root, "王者荣耀辅助")); assertNull(find(root, "开心消消乐辅助"));
                    checkRecords(activity, honor, match3);
                    assertTrue(first(root, EditText.class).getHint().toString().contains("附近没人"));
                });
            }
        }
        UiQualityInstrumentedTest.capture(entry, "honor-feedback");
    }

    private static boolean confirmDelete(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if ("删除".contentEquals(node.getText() == null ? "" : node.getText()))
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        for (int i = 0; i < node.getChildCount(); i++) if (confirmDelete(node.getChild(i))) return true;
        return false;
    }

    @Test public void deletingMatch3PreservesOtherGamesUnknownRecordsAndTheirExports() throws Exception {
        try (ActivityScenario<DiagnosticsActivity> page = ActivityScenario.launch(
                DiagnosticsActivity.intent(context(), DiagnosticGame.MATCH3))) {
            loaded(page, 2);
            page.onActivity(activity -> find(activity.findViewById(android.R.id.content), "删除开心消消乐记录").performClick());
            boolean confirmed = false; long until = SystemClock.elapsedRealtime() + 5000;
            while (!confirmed && SystemClock.elapsedRealtime() < until) {
                confirmed = confirmDelete(instrumentation().getUiAutomation().getRootInActiveWindow());
                if (!confirmed) SystemClock.sleep(50);
            }
            assertTrue("The actual confirmation must be accepted", confirmed);
            loaded(page, 1); drain();
            assertFalse(match3.exists()); assertFalse(match3Export.exists());
            assertTrue(honor.isDirectory()); assertTrue(honorExport.isFile());
            assertTrue(unknown.isDirectory()); assertTrue(unknownExport.isFile());
        }
    }
}
