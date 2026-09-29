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
import java.util.List;

/** Non-interactive, opt-in overlay for confirmed minimap tracks. */
final class MinimapOverlay implements AutoCloseable {
    private static final String TAG = "MapAssistOverlay";
    private static final int MARKER_OFFSET = 12;
    static final int MARKER_STRIDE = 9;
    private static final int MAX_MARKERS = 8;

    private final WindowManager windows;
    private final MarkerView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean attached;

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
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.START | Gravity.TOP;
        params.setTitle("听野小地图残留提示");
        windows.addView(view, params);
        attached = true;
    }

    void update(int[] nativeResult) {
        if (!attached || nativeResult == null || nativeResult.length <= MARKER_OFFSET) return;
        int count = Math.max(0, Math.min(MAX_MARKERS, nativeResult[11]));
        List<Marker> markers = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int base = MARKER_OFFSET + index * MARKER_STRIDE;
            if (base + MARKER_STRIDE > nativeResult.length) break;
            markers.add(new Marker(
                    nativeResult[base], nativeResult[base + 1],
                    nativeResult[base + 2] / 1_000_000f,
                    nativeResult[base + 3] / 1_000_000f,
                    nativeResult[base + 4] / 1_000_000f,
                    nativeResult[base + 5] / 1_000_000f,
                    nativeResult[base + 6]));
        }
        view.post(() -> view.setMarkers(markers));
    }

    void clear() {
        if (attached) view.post(() -> view.setMarkers(List.of()));
    }

    @Override public void close() {
        if (!attached) return;
        attached = false;
        main.post(() -> {
            try {
                if (view.isAttachedToWindow()) windows.removeViewImmediate(view);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not remove minimap overlay", error);
            }
        });
    }

    private static final class Marker {
        final int state;
        final int direction;
        final float x;
        final float y;
        final float width;
        final float height;
        final int ageMs;

        Marker(int state, int direction, float x, float y,
               float width, float height, int ageMs) {
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
        private final Path arrow = new Path();
        private final float density;
        private List<Marker> markers = List.of();

        MarkerView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
        }

        void setMarkers(List<Marker> next) {
            markers = List.copyOf(next);
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

            if (marker.state == 2) {
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
                stroke.setColor(Color.argb(245, 255, 91, 107));
                canvas.drawCircle(centerX, centerY, radius, stroke);
                drawDirection(canvas, centerX, centerY, radius, marker.direction,
                        Color.argb(245, 255, 211, 92));
            }
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
