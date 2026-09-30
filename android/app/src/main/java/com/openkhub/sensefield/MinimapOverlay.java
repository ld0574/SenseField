package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Non-interactive, opt-in overlay for confirmed minimap tracks. */
final class MinimapOverlay implements AutoCloseable {
    private static final String TAG = "MapAssistOverlay";
    private static final int SELF_CAPTURE_PROBE_LEFT_COLOR = Color.rgb(
            OverlayCaptureGuard.PROBE_LEFT_RED, OverlayCaptureGuard.PROBE_LEFT_GREEN,
            OverlayCaptureGuard.PROBE_LEFT_BLUE);
    private static final int SELF_CAPTURE_PROBE_RIGHT_COLOR = Color.rgb(
            OverlayCaptureGuard.PROBE_RIGHT_RED, OverlayCaptureGuard.PROBE_RIGHT_GREEN,
            OverlayCaptureGuard.PROBE_RIGHT_BLUE);
    // Kept as ABI documentation for callers compiled against the 0.2.2
    // marker packet. New callers should use NativeFrameResult instead.
    private static final int MARKER_OFFSET = 12;
    static final int MARKER_STRIDE = 9;
    static final int MAX_MARKERS = 9;

    private final WindowManager windows;
    private final MarkerView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager.LayoutParams layoutParams;
    // WindowManager and View mutations are confined to the main looper. The
    // capture worker only builds immutable marker snapshots and posts them.
    private volatile boolean attached;
    private volatile boolean closed;
    private List<Marker> pendingMarkers = Collections.emptyList();

    static MinimapOverlay createIfAllowed(Context context) {
        if (!Settings.canDrawOverlays(context)) return null;
        try {
            return new MinimapOverlay(context.getApplicationContext());
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not create minimap overlay", error);
            return null;
        }
    }

    private MinimapOverlay(Context context) {
        windows = context.getSystemService(WindowManager.class);
        view = new MarkerView(context);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.START | Gravity.TOP;
        layoutParams.setTitle("听野小地图残留提示");
        main.post(this::attachOnMainThread);
    }

    private void attachOnMainThread() {
        if (closed || attached) return;
        try {
            windows.addView(view, layoutParams);
            attached = true;
            view.setMarkers(pendingMarkers);
        } catch (RuntimeException error) {
            // addView can fail after attaching the view (for example when a
            // WindowManager policy check races the permission transition).
            // Remove that partial attachment before retiring this instance so
            // a later settings toggle cannot leak an overlay window.
            try {
                if (attached || view.isAttachedToWindow()) {
                    windows.removeViewImmediate(view);
                }
            } catch (RuntimeException cleanupError) {
                Log.w(TAG, "Could not clean up partially attached overlay", cleanupError);
            }
            attached = false;
            closed = true;
            pendingMarkers = Collections.emptyList();
            Log.w(TAG, "Could not attach minimap overlay", error);
        }
    }

    boolean update(NativeFrameResult frame) {
        if (closed || frame == null) return false;
        List<Marker> markers = new ArrayList<>(Math.min(MAX_MARKERS, frame.entities.size()));
        for (TrackedEntity entity : frame.entities) {
            if (!entity.isMinimapTrack() || entity.state == TrackedEntity.STATE_EXPIRED
                    || (entity.state != TrackedEntity.STATE_VISIBLE
                    && entity.state != TrackedEntity.STATE_LOST)
                    || !validNormalizedBox(entity)
                    || markers.size() >= MAX_MARKERS) continue;
            float width = Math.max(0f, entity.bbox.width());
            float height = Math.max(0f, entity.bbox.height());
            markers.add(new Marker(entity.entityKind, entity.state,
                    entity.movementDirection, entity.bbox.left, entity.bbox.top,
                    width, height, entity.ageMs));
        }
        List<Marker> snapshot = Collections.unmodifiableList(new ArrayList<>(markers));
        main.post(() -> {
            if (closed) return;
            pendingMarkers = snapshot;
            if (attached) view.setMarkers(snapshot);
        });
        return attached;
    }

    boolean isClosed() {
        return closed;
    }

    private static boolean validNormalizedBox(TrackedEntity entity) {
        return Float.isFinite(entity.bbox.left) && Float.isFinite(entity.bbox.top)
                && Float.isFinite(entity.bbox.right) && Float.isFinite(entity.bbox.bottom)
                && entity.bbox.left >= 0f && entity.bbox.top >= 0f
                && entity.bbox.right > entity.bbox.left
                && entity.bbox.bottom > entity.bbox.top
                && entity.bbox.right <= 1.001f && entity.bbox.bottom <= 1.001f;
    }

    /** Compatibility entry point for callers that still hold a packed JNI result. */
    void update(int[] nativeResult) {
        update(NativeFrameResult.parse(nativeResult));
    }

    void clear() {
        if (closed) return;
        main.post(() -> {
            if (closed) return;
            pendingMarkers = Collections.emptyList();
            if (attached) view.setMarkers(pendingMarkers);
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        main.post(() -> {
            try {
                if (attached || view.isAttachedToWindow()) {
                    windows.removeViewImmediate(view);
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not remove minimap overlay", error);
            } finally {
                attached = false;
                pendingMarkers = Collections.emptyList();
            }
        });
    }

    private static final class Marker {
        final int entityKind;
        final int state;
        final int direction;
        final float x;
        final float y;
        final float width;
        final float height;
        final int ageMs;

        Marker(int entityKind, int state, int direction, float x, float y,
               float width, float height, int ageMs) {
            this.entityKind = entityKind;
            this.state = state;
            this.direction = direction;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.ageMs = ageMs;
        }
    }

    private static final class MarkerView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint probe = new Paint();
        private final Path arrow = new Path();
        private final float density;
        private List<Marker> markers = Collections.emptyList();

        MarkerView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
        }

        void setMarkers(List<Marker> next) {
            markers = Collections.unmodifiableList(new ArrayList<>(next));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (Marker marker : markers) drawMarker(canvas, marker);
        }

        private void drawMarker(Canvas canvas, Marker marker) {
            float centerX = (marker.x + marker.width * 0.5f) * getWidth();
            float centerY = (marker.y + marker.height * 0.5f) * getHeight();
            float radius = Math.max(marker.width * getWidth(), marker.height * getHeight())
                    * 0.5f + 3f * density;
            radius = Math.max(9f * density, Math.min(24f * density, radius));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeWidth(3f * density);

            boolean player = marker.entityKind == TrackedEntity.KIND_MINIMAP_PLAYER;
            if (marker.state == TrackedEntity.STATE_LOST) {
                int alpha = Math.max(0,
                        235 - Math.max(0, marker.ageMs) * 235 / 4000);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(Color.argb(Math.min(160, alpha), 18, 24, 31));
                canvas.drawCircle(centerX, centerY, radius, fill);
                stroke.setColor(Color.argb(alpha, 238, 244, 255));
                canvas.drawCircle(centerX, centerY, radius, stroke);
                canvas.drawLine(centerX - radius * 0.58f, centerY + radius * 0.58f,
                        centerX + radius * 0.58f, centerY - radius * 0.58f, stroke);
                drawDirection(canvas, centerX, centerY, radius, marker.direction,
                        Color.argb(alpha, 109, 222, 255));
            } else {
                stroke.setColor(Color.argb(245,
                        player ? 91 : 255, player ? 196 : 91, player ? 255 : 107));
                canvas.drawCircle(centerX, centerY, radius, stroke);
                drawDirection(canvas, centerX, centerY, radius, marker.direction,
                        Color.argb(245, player ? 159 : 255, player ? 235 : 211,
                                player ? 255 : 92));
            }
            // FLAG_SECURE should keep this pixel out of MediaProjection. If a
            // device/OEM still includes it, CaptureService latches off only
            // this visual layer while retaining native tracking and audio.
            probe.setStyle(Paint.Style.FILL);
            probe.setAntiAlias(false);
            probe.setColor(SELF_CAPTURE_PROBE_LEFT_COLOR);
            canvas.drawRect(centerX - OverlayCaptureGuard.PROBE_HALF_WIDTH_PX,
                    centerY - OverlayCaptureGuard.PROBE_HALF_HEIGHT_PX,
                    centerX, centerY + OverlayCaptureGuard.PROBE_HALF_HEIGHT_PX, probe);
            probe.setColor(SELF_CAPTURE_PROBE_RIGHT_COLOR);
            canvas.drawRect(centerX,
                    centerY - OverlayCaptureGuard.PROBE_HALF_HEIGHT_PX,
                    centerX + OverlayCaptureGuard.PROBE_HALF_WIDTH_PX,
                    centerY + OverlayCaptureGuard.PROBE_HALF_HEIGHT_PX, probe);
        }

        private void drawDirection(Canvas canvas, float x, float y, float radius,
                                   int direction, int color) {
            if (direction < 1 || direction > 4) return;
            float dx = direction == 1 ? -1f : direction == 2 ? 1f : 0f;
            float dy = direction == 3 ? -1f : direction == 4 ? 1f : 0f;
            float sideX = -dy;
            float sideY = dx;
            float tipX = x + dx * radius * 1.65f;
            float tipY = y + dy * radius * 1.65f;
            float baseX = x + dx * radius * 1.08f;
            float baseY = y + dy * radius * 1.08f;
            arrow.reset();
            arrow.moveTo(tipX, tipY);
            arrow.lineTo(baseX + sideX * radius * 0.28f,
                    baseY + sideY * radius * 0.28f);
            arrow.lineTo(baseX - sideX * radius * 0.28f,
                    baseY - sideY * radius * 0.28f);
            arrow.close();
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
            canvas.drawPath(arrow, fill);
        }
    }
}
