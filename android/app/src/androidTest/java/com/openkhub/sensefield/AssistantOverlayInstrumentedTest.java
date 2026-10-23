package com.openkhub.sensefield;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** A real overlay with alternating framework insets; never captures audio or images. */
@RunWith(AndroidJUnit4.class)
public class AssistantOverlayInstrumentedTest {
    private static Object field(AssistantOverlay overlay, String name) throws Exception {
        Field field = AssistantOverlay.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(overlay);
    }

    private static int[] geometry(View root, WindowManager.LayoutParams layout) {
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        return new int[] {layout.x, layout.y, layout.width, layout.height,
                location[0], location[1], root.getWidth(), root.getHeight()};
    }

    private static void assertPhysicalGeometry(String label, int[] actual) {
        assertEquals(label + ": layout x matches the real screen location", actual[0], actual[4]);
        assertEquals(label + ": layout y matches the real screen location", actual[1], actual[5]);
        assertEquals(label + ": layout width matches the real view width", actual[2], actual[6]);
        assertEquals(label + ": layout height matches the real view height", actual[3], actual[7]);
    }

    private static void tap(UiAutomation automation, int x, int y) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 60,
                MotionEvent.ACTION_UP, x, y, 0);
        try {
            assertTrue("UiAutomation must inject tap down", automation.injectInputEvent(down, true));
            SystemClock.sleep(60);
            assertTrue("UiAutomation must inject tap up", automation.injectInputEvent(up, true));
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static WindowInsets syntheticInsets(int round, int sample) {
        boolean shifted = ((round + sample) & 1) == 0;
        return new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.displayCutout(),
                        Insets.of(shifted ? 88 : 0, 0, 0, 0))
                .setInsetsIgnoringVisibility(WindowInsets.Type.systemBars(),
                        Insets.of(0, shifted ? 80 : 0, 0, shifted ? 48 : 0))
                .build();
    }

    @Test public void programmaticExpansionIsPhysicallyStableOnBothEdges() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("This required real-overlay test must not be silently skipped: grant display-over-other-apps permission first",
                Settings.canDrawOverlays(context));
        assumeTrue("Synthetic display-insets checks require Android 11 or newer",
                Build.VERSION.SDK_INT >= 30);

        AtomicReference<AssistantOverlay> reference = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AssistantOverlay overlay = new AssistantOverlay(context, new AssistantOverlay.Listener() {
                public void readScreen() { }
                public void repeat() { }
                public void mark() { }
                public void pauseVoice() { }
            });
            reference.set(overlay);
            overlay.show();
        });

        AssistantOverlay overlay = reference.get();
        assertNotNull("Overlay instance must be created", overlay);
        try {
            View root = (View) field(overlay, "root");
            WindowManager.LayoutParams layout =
                    (WindowManager.LayoutParams) field(overlay, "layout");
            Field rightDocked = AssistantOverlay.class.getDeclaredField("rightDocked");
            rightDocked.setAccessible(true);
            assertNotNull("Overlay root must be attached", root);
            assertNotNull("Overlay layout must be created", layout);

            WindowManager windows = context.getSystemService(WindowManager.class);
            DisplayMetrics display = new DisplayMetrics();
            windows.getDefaultDisplay().getRealMetrics(display);
            int collapsedSize = Math.round(AssistantOverlay.HANDLE_DP * context.getResources().getDisplayMetrics().density);
            int margin = Math.round(8 * context.getResources().getDisplayMetrics().density);
            Insets safe = windows.getMaximumWindowMetrics().getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());

            for (boolean dockRight : new boolean[] {false, true}) {
                final boolean side = dockRight;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    try {
                        rightDocked.setBoolean(overlay, side);
                    } catch (IllegalAccessException error) {
                        throw new AssertionError(error);
                    }
                    overlay.setExpanded(false);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();

                for (int round = 0; round < 10; round++) {
                    final int cycle = round;
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(
                            () -> overlay.setExpanded(true));
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    int[] expandedBaseline = geometry(root, layout);
                    assertPhysicalGeometry("programmatically expanded round " + round + " on "
                            + (side ? "right" : "left"), expandedBaseline);
                    int expectedExpandedX = side
                            ? display.widthPixels - safe.right - margin - expandedBaseline[2]
                            : safe.left + margin;
                    assertEquals("Expanded panel stays against the safe "
                                    + (side ? "right" : "left") + " edge",
                            expectedExpandedX, expandedBaseline[0]);

                    for (int sample = 0; sample < 2; sample++) {
                        final int insetSample = sample;
                        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                                root.dispatchApplyWindowInsets(syntheticInsets(cycle, insetSample)));
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                        int[] afterInsets = geometry(root, layout);
                        assertArrayEquals("Synthetic insets must not shift expanded layout or its real screen position",
                                expandedBaseline, afterInsets);
                        assertPhysicalGeometry("expanded inset sample " + sample + " round " + round
                                + " on " + (side ? "right" : "left"), afterInsets);
                    }

                    InstrumentationRegistry.getInstrumentation().runOnMainSync(
                            () -> overlay.setExpanded(false));
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    int[] collapsed = geometry(root, layout);
                    assertPhysicalGeometry("programmatically collapsed round " + round + " on "
                            + (side ? "right" : "left"), collapsed);
                    assertEquals("Collapsed handle is physically docked to the "
                                    + (side ? "right" : "left") + " display edge",
                            side ? display.widthPixels - collapsedSize : 0, collapsed[0]);
                    assertEquals("Collapsed handle layout stays at the physical display edge",
                            collapsed[0], collapsed[4]);
                    assertEquals("Collapsed handle size matches WindowManager layout",
                            collapsedSize, collapsed[2]);
                    assertEquals("Collapsed handle has its expected physical size",
                            collapsedSize, collapsed[6]);
                }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(overlay::close);
        }
    }

    @Test public void actualDotAndOutsideTapsToggleOnBothEdges() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("This required real-overlay test must not be silently skipped: grant display-over-other-apps permission first",
                Settings.canDrawOverlays(context));
        assumeTrue("Synthetic display-insets checks require Android 11 or newer",
                Build.VERSION.SDK_INT >= 30);

        WindowManager windows = context.getSystemService(WindowManager.class);
        AtomicReference<AssistantOverlay> reference = new AtomicReference<>();
        AtomicReference<View> backplateReference = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Full-screen transparent touch sink prevents test taps from reaching
            // whatever app happens to be underneath the overlays.
            View backplate = new View(context);
            backplate.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            backplate.setClickable(true);
            backplate.setOnTouchListener((view, event) -> true);
            WindowManager.LayoutParams sinkLayout = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            sinkLayout.gravity = Gravity.TOP | Gravity.LEFT;
            sinkLayout.setTitle("AssistantOverlayInstrumentedTestInputSink");
            if (Build.VERSION.SDK_INT >= 30) sinkLayout.setFitInsetsTypes(0);
            windows.addView(backplate, sinkLayout);
            backplateReference.set(backplate);

            AssistantOverlay overlay = new AssistantOverlay(context, new AssistantOverlay.Listener() {
                public void readScreen() { }
                public void repeat() { }
                public void mark() { }
                public void pauseVoice() { }
            });
            reference.set(overlay);
            overlay.show();
        });

        AssistantOverlay overlay = reference.get();
        View backplate = backplateReference.get();
        assertNotNull("Overlay window must attach when permission is enabled", overlay);
        assertNotNull("Test input sink must cover the underlying UI", backplate);
        try {
            View root = (View) field(overlay, "root");
            WindowManager.LayoutParams layout =
                    (WindowManager.LayoutParams) field(overlay, "layout");
            Field rightDocked = AssistantOverlay.class.getDeclaredField("rightDocked");
            rightDocked.setAccessible(true);
            assertNotNull("Overlay root must be attached", root);
            assertNotNull("Overlay layout must be created", layout);

            UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            DisplayMetrics display = new DisplayMetrics();
            windows.getDefaultDisplay().getRealMetrics(display);
            int collapsedSize = Math.round(AssistantOverlay.HANDLE_DP * context.getResources().getDisplayMetrics().density);
            int margin = Math.round(8 * context.getResources().getDisplayMetrics().density);
            Insets safe = windows.getMaximumWindowMetrics().getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
            int[] displaySize = new int[] {display.widthPixels, display.heightPixels};
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            for (boolean dockRight : new boolean[] {false, true}) {
                final boolean side = dockRight;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    try {
                        rightDocked.setBoolean(overlay, side);
                    } catch (IllegalAccessException error) {
                        throw new AssertionError(error);
                    }
                    overlay.setExpanded(false);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();

                for (int round = 0; round < 10; round++) {
                    final int cycle = round;
                    int[] collapsedBeforeTap = geometry(root, layout);
                    assertPhysicalGeometry("collapsed before dot tap round " + round + " on "
                            + (side ? "right" : "left"), collapsedBeforeTap);
                    assertEquals("Collapsed dot is docked before it is tapped",
                            side ? displaySize[0] - collapsedSize : 0, collapsedBeforeTap[0]);
                    tap(automation, collapsedBeforeTap[4] + collapsedBeforeTap[6] / 2,
                            collapsedBeforeTap[5] + collapsedBeforeTap[7] / 2);
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    assertTrue("Tapping the collapsed dot must expand the panel",
                            overlay.isExpanded());

                    int[] expandedBaseline = geometry(root, layout);
                    assertPhysicalGeometry("expanded round " + round + " on "
                            + (side ? "right" : "left"), expandedBaseline);
                    int expectedExpandedX = side
                            ? displaySize[0] - safe.right - margin - expandedBaseline[2]
                            : safe.left + margin;
                    assertEquals("Expanded panel stays against the safe "
                                    + (side ? "right" : "left") + " edge",
                            expectedExpandedX, expandedBaseline[0]);

                    for (int sample = 0; sample < 2; sample++) {
                        final int insetSample = sample;
                        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                                root.dispatchApplyWindowInsets(syntheticInsets(cycle, insetSample)));
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                        int[] afterInsets = geometry(root, layout);
                        assertArrayEquals("Synthetic insets must not shift expanded layout or its real screen position",
                                expandedBaseline, afterInsets);
                        assertPhysicalGeometry("expanded inset sample " + sample + " round " + round
                                + " on " + (side ? "right" : "left"), afterInsets);
                    }

                    int outsideX = side ? safe.left + margin
                            : displaySize[0] - safe.right - margin;
                    tap(automation, outsideX, displaySize[1] / 2);
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    assertFalse("Tapping outside the expanded panel must collapse it",
                            overlay.isExpanded());
                    int[] collapsed = geometry(root, layout);
                    assertPhysicalGeometry("collapsed round " + round + " on "
                            + (side ? "right" : "left"), collapsed);
                    assertEquals("Collapsed handle is physically docked to the "
                                    + (side ? "right" : "left") + " display edge",
                            side ? displaySize[0] - collapsedSize : 0, collapsed[0]);
                    assertEquals("Collapsed handle layout stays at the physical display edge",
                            collapsed[0], collapsed[4]);
                    assertEquals("Collapsed handle has its expected physical size",
                            collapsedSize, collapsed[6]);
                    assertEquals("Collapsed handle size matches WindowManager layout",
                            collapsedSize, collapsed[2]);
                }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                overlay.close();
                if (backplate.getParent() != null) windows.removeViewImmediate(backplate);
            });
        }
    }

    @Test public void largeTextControlsStayScrollableWithoutMovingTheDock() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(Settings.canDrawOverlays(context));
        AtomicReference<AssistantOverlay> reference = new AtomicReference<>();
        final int[] marks = new int[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AssistantOverlay overlay = new AssistantOverlay(context, new AssistantOverlay.Listener() {
                public void readScreen() { }
                public void repeat() { }
                public void mark() { marks[0]++; }
                public void pauseVoice() { }
            });
            reference.set(overlay); overlay.show();
            overlay.history("这里保留最近的问答。大字时所有操作和回复仍可滚动查看。");
            overlay.setExpanded(true);
        });
        AssistantOverlay overlay = reference.get();
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            View root = (View) field(overlay, "root");
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) field(overlay, "layout");
            int[] before = geometry(root, params);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                android.widget.ScrollView scroll = (android.widget.ScrollView) ((android.view.ViewGroup) root).getChildAt(0);
                android.view.ViewGroup content = (android.view.ViewGroup) scroll.getChildAt(0);
                ReminderSampleGrid buttons = (ReminderSampleGrid) content.getChildAt(1);
                assertEquals(4, buttons.getChildCount());
                for (int i = 0; i < buttons.getChildCount(); i++) {
                    android.widget.Button button = (android.widget.Button) buttons.getChildAt(i);
                    assertTrue(button.getHeight() >= UiKit.dp(context, 56));
                    assertTrue(button.getRight() <= buttons.getWidth());
                    assertTrue(button.getLayout().getHeight() <= button.getHeight()
                            - button.getCompoundPaddingTop() - button.getCompoundPaddingBottom());
                }
                buttons.getChildAt(2).performClick();
                View last = buttons.getChildAt(3);
                scroll.scrollTo(0, Math.max(0, buttons.getTop() + last.getBottom() - scroll.getHeight()));
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue("The last control remains reachable by scrolling", buttons.getChildAt(3).getGlobalVisibleRect(visible));
            });
            assertEquals(1, marks[0]);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertArrayEquals("Text reflow never changes the overlay's dock", before, geometry(root, params));
            android.graphics.Bitmap capture = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(capture);
            String phase = InstrumentationRegistry.getArguments().getString("preview_phase", "after").replaceAll("[^a-zA-Z0-9._-]", "_");
            java.io.File dir = new java.io.File(context.getExternalFilesDir("ui-0.4.3"), phase);
            assertTrue(dir.isDirectory() || dir.mkdirs());
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(dir, "assistant-overlay.png"))) {
                assertTrue(capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
            } finally { capture.recycle(); }
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync(overlay::close); }
    }
}
