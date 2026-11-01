package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.openkhub.sensefield.ReadOnlyGuard.Hit;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Hand-written expectations for {@link ReadOnlyGuard}: every expected file name, line
 * number and API name below is a literal, never the guard's own output.
 */
public final class ReadOnlyGuardTest {

    private static List<String> lines(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static String onlyApi(List<Hit> hits) {
        assertEquals("expected exactly one hit, got " + hits, 1, hits.size());
        return hits.get(0).apiName();
    }

    @Test public void dispatchGestureIsReported() {
        assertEquals("dispatchGesture",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines("        controller.dispatchGesture(description, callback, null);"))));
    }

    @Test public void gestureDescriptionIsReported() {
        assertEquals("GestureDescription",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines("GestureDescription description = builder.build();"))));
    }

    @Test public void injectInputEventIsReported() {
        assertEquals("injectInputEvent",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines("windowManager.injectInputEvent(event, 0);"))));
    }

    @Test public void performGlobalActionIsReported() {
        assertEquals("performGlobalAction",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines("boolean ok = service.performGlobalAction(1);"))));
    }

    @Test public void globalActionConstantIsReported() {
        assertEquals("GLOBAL_ACTION_",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines("int back = AccessibilityService.GLOBAL_ACTION_BACK;"))));
    }

    @Test public void enqueueInputEventIsReported() {
        assertEquals("enqueueInputEvent",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines("queue.enqueueInputEvent(event);"))));
    }

    @Test public void motionEventObtainIsReported() {
        assertEquals("MotionEvent.obtain",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines("MotionEvent down = MotionEvent.obtain(now, now, 0, x, y, 0);"))));
    }

    @Test public void accessibilityActionCallWithAnUnlistedActionIsReported() {
        assertEquals("performAccessibilityAction",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines(
                        "node.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null);"))));
    }

    @Test public void actionClickConstantIsReported() {
        assertEquals("ACTION_CLICK",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines("int click = AccessibilityNodeInfo.ACTION_CLICK;"))));
    }

    @Test public void actionLongClickConstantIsReported() {
        assertEquals("ACTION_LONG_CLICK",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines("int press = AccessibilityNodeInfo.ACTION_LONG_CLICK;"))));
    }

    @Test public void actionSetTextConstantIsReported() {
        assertEquals("ACTION_SET_TEXT",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines("int write = AccessibilityNodeInfo.ACTION_SET_TEXT;"))));
    }

    @Test public void actionPasteConstantIsReported() {
        assertEquals("ACTION_PASTE",
                onlyApi(ReadOnlyGuard.scan("Fake.java", lines("int drop = AccessibilityNodeInfo.ACTION_PASTE;"))));
    }

    @Test public void theReadOnlyFocusReturnOnOneLineIsNotReported() {
        List<Hit> hits = ReadOnlyGuard.scan("SettingHelpPanel.java", lines(
                "heading.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);"));

        assertEquals("the focus pairing must be let through, got " + hits, 0, hits.size());
    }

    @Test public void theReadOnlyFocusReturnWrappedOverTwoLinesIsNotReported() {
        List<Hit> hits = ReadOnlyGuard.scan("SettingHelpPanel.java", lines(
                "target.performAccessibilityAction(",
                "AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);"));

        assertEquals("the shipped call shape must scan clean, got " + hits, 0, hits.size());
    }

    @Test public void aClickWrappedOverTwoLinesIsReportedOnBothLines() {
        List<Hit> hits = ReadOnlyGuard.scan("SettingHelpPanel.java", lines(
                "target.performAccessibilityAction(",
                "AccessibilityNodeInfo.ACTION_CLICK, null);"));

        assertEquals(2, hits.size());
        assertEquals("SettingHelpPanel.java:1 performAccessibilityAction", hits.get(0).toString());
        assertEquals("SettingHelpPanel.java:2 ACTION_CLICK", hits.get(1).toString());
    }

    @Test public void anAccessibilityActionCallGivenAVariableIsReported() {
        assertEquals("performAccessibilityAction",
                onlyApi(ReadOnlyGuard.scan("Sneaky.java", lines("node.performAccessibilityAction(action, null);"))));
    }

    @Test public void anUnresolvedAccessibilityActionCallIsReported() {
        List<Hit> hits = ReadOnlyGuard.scan("Half.java", lines("node.performAccessibilityAction("));

        assertEquals(1, hits.size());
        assertEquals("performAccessibilityAction", hits.get(0).apiName());
        assertEquals(1, hits.get(0).lineNumber());
    }

    @Test public void theWhitelistNeverSilencesABlacklistedActionOnTheSameCall() {
        List<Hit> hits = ReadOnlyGuard.scan("Both.java", lines(
                "node.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, "
                        + "AccessibilityNodeInfo.ACTION_CLICK, null);"));

        assertEquals("focus pairing may not excuse the click, got " + hits, 1, hits.size());
        assertEquals("ACTION_CLICK", hits.get(0).apiName());
    }

    @Test public void aCommentThatNamesAnActionConstantIsStillAHit() {
        assertEquals("ACTION_PASTE",
                onlyApi(ReadOnlyGuard.scan("Note.java", lines("    // we never send ACTION_PASTE at any node"))));
    }

    @Test public void hitCarriesTheFileNameAndTheOneBasedLineNumber() {
        List<Hit> hits = ReadOnlyGuard.scan("Overlay.java", lines(
                "package com.openkhub.sensefield;",
                "",
                "// a note that never names an API",
                "service.dispatchGesture(description, callback, null);"));

        assertEquals(1, hits.size());
        assertEquals("Overlay.java", hits.get(0).fileName());
        assertEquals(4, hits.get(0).lineNumber());
        assertEquals("dispatchGesture", hits.get(0).apiName());
        assertEquals("Overlay.java:4 dispatchGesture", hits.get(0).toString());
    }

    @Test public void everyLineOfSeveralFilesIsReportedSeparately() {
        List<Hit> hits = ReadOnlyGuard.scan("Both.java", lines(
                "dispatchGesture(a);",
                "clean line",
                "injectInputEvent(b);"));

        assertEquals(2, hits.size());
        assertEquals("Both.java:1 dispatchGesture", hits.get(0).toString());
        assertEquals("Both.java:3 injectInputEvent", hits.get(1).toString());
    }

    @Test public void theNamedInputManagerReadOnlyCallIsNotReported() {
        List<Hit> hits = ReadOnlyGuard.scan("Match3HintOverlay.java", lines(
                "import android.hardware.input.InputManager;",
                "InputManager input = context.getSystemService(InputManager.class);",
                "params.alpha = Math.min(params.alpha, input.getMaximumObscuringOpacityForTouch());",
                "float opacity = InputManager.getMaximumObscuringOpacityForTouch();"));

        assertEquals("whitelist must let the read-only query through, got " + hits, 0, hits.size());
    }

    @Test public void inputManagerPairedWithAnInjectionCallIsStillReported() {
        List<Hit> hits = ReadOnlyGuard.scan("Bad.java", lines(
                "InputManager.injectInputEvent(event, 0);"));

        assertEquals(1, hits.size());
        assertEquals("Bad.java", hits.get(0).fileName());
        assertEquals(1, hits.get(0).lineNumber());
        assertEquals("injectInputEvent", hits.get(0).apiName());
    }

    @Test public void aPartialInputManagerMemberIsNotDeduplicatedAway() {
        assertEquals("InputManager.inject",
                onlyApi(ReadOnlyGuard.scan("Partial.java", lines("InputManager.inject(event);"))));
    }

    @Test public void aNonReadOnlyInputManagerMemberIsReported() {
        assertEquals("InputManager.vibrate",
                onlyApi(ReadOnlyGuard.scan("Bad.java", lines("InputManager.vibrate(pattern);"))));
    }

    @Test public void casingAndStraySpacingDoNotChangeTheVerdict() {
        assertEquals("dispatchGesture",
                onlyApi(ReadOnlyGuard.scan("Shout.java", lines("CONTROLLER.DISPATCHGESTURE(description, callback, null);"))));
        assertEquals("MotionEvent.obtain",
                onlyApi(ReadOnlyGuard.scan("Spaced.java", lines("MotionEvent . obtain (now, now, 0, x, y, 0);"))));
    }

    @Test public void aCommentThatNamesAnApiIsStillAHit() {
        assertEquals("performGlobalAction",
                onlyApi(ReadOnlyGuard.scan("Note.java", lines("    // we never call performGlobalAction here"))));
    }

    @Test public void globalActionCallReportsBothTokens() {
        List<Hit> hits = ReadOnlyGuard.scan("Both.java", lines(
                "service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);"));

        assertEquals(2, hits.size());
        assertEquals("performGlobalAction", hits.get(0).apiName());
        assertEquals("GLOBAL_ACTION_", hits.get(1).apiName());
        assertEquals(1, hits.get(0).lineNumber());
        assertEquals(1, hits.get(1).lineNumber());
    }

    @Test public void emptyInputYieldsNoHits() {
        assertEquals(0, ReadOnlyGuard.scan("Empty.java", Collections.<String>emptyList()).size());
        assertEquals(0, ReadOnlyGuard.scan("Empty.java", lines("")).size());
        assertEquals(0, ReadOnlyGuard.scan("Empty.java", lines("   ", null)).size());
        assertEquals(0, ReadOnlyGuard.scan(null, null).size());
    }

    @Test public void scanTextKeepsTheSameOneBasedLineNumbers() {
        List<Hit> hits = ReadOnlyGuard.scanText("Body.java", "package x;\n\nGestureDescription gd = null;\n");

        assertEquals(1, hits.size());
        assertEquals("Body.java", hits.get(0).fileName());
        assertEquals(3, hits.get(0).lineNumber());
        assertTrue(hits.get(0).apiName(), hits.get(0).apiName().equals("GestureDescription"));
    }

    @Test public void theBlacklistAndTheWhitelistAreTheAdvertisedData() {
        assertEquals(Arrays.asList("dispatchGesture", "GestureDescription", "injectInputEvent",
                        "performGlobalAction", "GLOBAL_ACTION_", "enqueueInputEvent", "MotionEvent.obtain",
                        "performAccessibilityAction", "ACTION_CLICK", "ACTION_LONG_CLICK",
                        "ACTION_SET_TEXT", "ACTION_PASTE"),
                ReadOnlyGuard.INJECTION_APIS);
        assertEquals(12, ReadOnlyGuard.INJECTION_APIS.size());
        assertEquals(Collections.singletonList("getMaximumObscuringOpacityForTouch"),
                ReadOnlyGuard.READ_ONLY_INPUT_MANAGER_CALLS);
        assertEquals(Collections.singletonList("ACTION_ACCESSIBILITY_FOCUS"),
                ReadOnlyGuard.READ_ONLY_ACCESSIBILITY_ACTIONS);
        assertEquals("InputManager", ReadOnlyGuard.INPUT_MANAGER);
    }

    /**
     * The alarm measured against the shipped tree: every {@code .java} of the main source
     * set is scanned, and the only reported lines allowed are the guard's own blacklist
     * data, which has to spell each name out in full in order to be able to hit it. Any
     * other file in the tree that reaches an injection API fails this test, which is what
     * makes the public "we only perceive, we never inject" claim re-checkable instead of
     * asserted. The guard's own file is not exempted from the scan; it is accounted for
     * separately below, and its lines still count as hits.
     */
    @Test public void theMainSourceSetReachesNoInjectionApiTheGuardDoesNotAlreadyName() throws Exception {
        File root = mainSourceRoot();
        assertNotNull("main source set not found from the test working directory", root);

        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root.toPath())) {
            walk.filter(p -> p.toString().replace('\\', '/').endsWith(".java")).sorted().forEach(paths::add);
        }
        assertTrue("main source set is empty, so the scan proves nothing", paths.size() > 1);

        List<Hit> outsideTheGuard = new ArrayList<>();
        List<String> namesNamedByTheGuardItself = new ArrayList<>();
        for (Path path : paths) {
            String text = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
            for (Hit hit : ReadOnlyGuard.scanText(path.getFileName().toString(), text)) {
                if ("ReadOnlyGuard.java".equals(hit.fileName())) {
                    if (!namesNamedByTheGuardItself.contains(hit.apiName())) namesNamedByTheGuardItself.add(hit.apiName());
                } else {
                    outsideTheGuard.add(hit);
                }
            }
        }

        assertEquals("the shipped main source set must reach no injection API, got " + outsideTheGuard,
                0, outsideTheGuard.size());
        List<String> expectedNames = new ArrayList<>(Arrays.asList("dispatchGesture", "GestureDescription",
                "injectInputEvent", "performGlobalAction", "GLOBAL_ACTION_", "enqueueInputEvent",
                "MotionEvent.obtain", "performAccessibilityAction", "ACTION_CLICK", "ACTION_LONG_CLICK",
                "ACTION_SET_TEXT", "ACTION_PASTE"));
        Collections.sort(expectedNames);
        Collections.sort(namesNamedByTheGuardItself);
        assertEquals("every blacklisted name must be spelled out in the guard itself, got "
                + namesNamedByTheGuardItself, expectedNames, namesNamedByTheGuardItself);
    }

    /** Where the main source set sits, resolved from the working directory the tests run in. */
    private static File mainSourceRoot() {
        String[] candidates = {"src/main/java", "app/src/main/java", "../app/src/main/java", "../src/main/java"};
        for (String candidate : candidates) {
            File directory = new File(candidate);
            if (directory.isDirectory()) return directory;
        }
        return null;
    }
}
