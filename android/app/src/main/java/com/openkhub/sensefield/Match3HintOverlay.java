package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.function.BooleanSupplier;

/** A static, touch-through window. A valid hint stays attached until explicitly cleared. */
final class Match3HintOverlay implements AutoCloseable {
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HintView view;
    private final WindowManager.LayoutParams params;
    private volatile boolean closed, attached;
    private volatile Match3Hint rendered;
    private volatile long visibleFromMs;

    static Match3HintOverlay create(Context context) {
        if (!Settings.canDrawOverlays(context)) return null;
        try { return new Match3HintOverlay(context.getApplicationContext()); }
        catch (RuntimeException ignored) { return null; }
    }

    @android.annotation.SuppressLint("RtlHardcoded") // Capture pixels have an absolute left origin in every locale.
    private Match3HintOverlay(Context context) {
        windows = context.getSystemService(WindowManager.class);
        view = new HintView(context);
        params = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.alpha = .8f;
        if (Build.VERSION.SDK_INT >= 31) {
            InputManager input = context.getSystemService(InputManager.class);
            params.alpha = Math.min(params.alpha, input.getMaximumObscuringOpacityForTouch());
        }
        params.setTitle("听野消消乐交换提示");
    }

    float alpha() { return params.alpha; }
    boolean isClosed() { return closed; }
    Match3Hint renderedHint() { return closed || !attached ? null : rendered; }
    long visibleDurationMs() {
        return renderedHint() == null ? 0 : Math.max(0, SystemClock.elapsedRealtime() - visibleFromMs);
    }

    void update(Match3Hint hint, BooleanSupplier valid) {
        if (closed) return;
        main.post(() -> {
            if (closed || !valid.getAsBoolean()) return;
            Point size = new Point(); windows.getDefaultDisplay().getRealSize(size);
            BoardGeometry g = hint.geometry;
            if (!g.mapsToDisplay(size.x, size.y)) { clearOnMain(); return; }
            float sx = size.x / (float) g.frameWidth, sy = size.y / (float) g.frameHeight;
            params.x = (int) (g.left * sx); params.y = (int) (g.top * sy);
            params.width = Math.max(1, (int) Math.ceil((g.right - g.left) * sx));
            params.height = Math.max(1, (int) Math.ceil((g.bottom - g.top) * sy));
            view.hint = hint; view.sx = sx; view.sy = sy;
            try {
                if (attached) windows.updateViewLayout(view, params);
                else { windows.addView(view, params); attached = true; }
                if (rendered != hint) visibleFromMs = SystemClock.elapsedRealtime();
                rendered = hint; view.invalidate();
            } catch (RuntimeException error) { close(); }
        });
    }

    void clear() { rendered = null; main.post(this::clearOnMain); }
    private void clearOnMain() {
        if (attached) try { windows.removeViewImmediate(view); } catch (RuntimeException ignored) { }
        attached = false; rendered = null; view.hint = null;
    }
    @Override public void close() { closed = true; rendered = null; main.post(this::clearOnMain); }

    static float[][] probes(Match3Hint hint) {
        BoardGeometry g = hint.geometry; Match3Board.Swap s = hint.swap;
        return new float[][]{
                {g.centerX(s.fromCol) / (float) g.frameWidth,
                        (g.cellTop(s.fromRow) + g.cellHeight() * .12f) / g.frameHeight},
                {g.centerX(s.toCol) / (float) g.frameWidth,
                        (g.cellTop(s.toRow) + g.cellHeight() * .12f) / g.frameHeight}};
    }

    /** Includes the guide corridor as well as the target cells, in capture coordinates. */
    static Rect captureBounds(Match3Hint hint) {
        BoardGeometry g = hint.geometry; Match3Board.Swap swap = hint.swap;
        float left = g.cellLeft(Math.min(swap.fromCol, swap.toCol));
        float top = g.cellTop(Math.min(swap.fromRow, swap.toRow));
        float right = g.cellLeft(Math.max(swap.fromCol, swap.toCol) + 1);
        float bottom = g.cellTop(Math.max(swap.fromRow, swap.toRow) + 1);
        Match3HintGuide guide = Match3HintGuide.forHint(hint);
        if (guide != null) {
            float margin = Math.min(g.cellWidth(), g.cellHeight()) * .5f + 1;
            left = Math.min(left, Math.min(guide.startX, guide.endX) - margin);
            top = Math.min(top, Math.min(guide.startY, guide.endY) - margin);
            right = Math.max(right, Math.max(guide.startX, guide.endX) + margin);
            bottom = Math.max(bottom, Math.max(guide.startY, guide.endY) + margin);
        }
        return new Rect(Math.max(g.left, (int) Math.floor(left)), Math.max(g.top, (int) Math.floor(top)),
                Math.min(g.right, (int) Math.ceil(right)), Math.min(g.bottom, (int) Math.ceil(bottom)));
    }

    /** Same drawing as the real window; used only on the capture worker. */
    static Bitmap captureMask(Context context, Match3Hint hint, int left, int top, int width, int height) {
        Bitmap mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        HintView drawing = new HintView(context);
        drawing.hint = hint;
        Canvas canvas = new Canvas(mask);
        canvas.translate(hint.geometry.left - left, hint.geometry.top - top);
        drawing.drawHint(canvas);
        return mask;
    }

    static final class HintView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint probe = new Paint();
        private final RectF rect = new RectF();
        private final float density;
        Match3Hint hint; float sx = 1, sy = 1;
        HintView(Context context) {
            super(context); density = context.getResources().getDisplayMetrics().density;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        @Override protected void onDraw(Canvas canvas) {
            drawHint(canvas);
        }
        void drawHint(Canvas canvas) {
            if (hint == null) return;
            BoardGeometry g = hint.geometry; Match3Board.Swap s = hint.swap;
            float cell = Math.min(g.cellWidth() * sx, g.cellHeight() * sy);
            // Keep a broad yellow band and a dark rim on both light and dark tiles.
            // Cap target widths for small boards. The separate guide corridor is
            // included in captureBounds so every overlay pixel can be removed.
            float outerWidth = Math.min(10 * density, cell * .24f);
            float innerWidth = Math.min(6 * density, cell * .14f);
            float inset = outerWidth / 2 + Math.max(1, cell * .01f);
            Match3HintGuide guide = Match3HintGuide.forHint(hint);
            for (int pass = 0; pass < 2; pass++) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(pass == 0 ? 0xff071018 : 0xffffffff);
                paint.setStrokeWidth((pass == 0 ? outerWidth : innerWidth) * .65f);
                if (guide != null) drawGuide(canvas, g, guide, cell);
                paint.setColor(pass == 0 ? 0xff071018 : 0xfffff000);
                paint.setStrokeWidth(pass == 0 ? outerWidth : innerWidth);
                drawCell(canvas,g,s.fromRow,s.fromCol,inset);
                if(s.actionType==Match3Action.Type.ACTIVATE) {
                    canvas.drawCircle((g.centerX(s.fromCol)-g.left)*sx,(g.centerY(s.fromRow)-g.top)*sy,
                            Math.min(g.cellWidth()*sx,g.cellHeight()*sy)*.16f,paint);
                    continue;
                }
                drawCell(canvas,g,s.toRow,s.toCol,inset);
                float x1, y1, x2, y2;
                if (s.fromRow == s.toRow) {
                    x1 = (g.centerX(s.fromCol) + g.cellWidth() * .25f - g.left) * sx;
                    x2 = (g.centerX(s.toCol) - g.cellWidth() * .25f - g.left) * sx;
                    y1 = y2 = (g.cellTop(s.fromRow) + g.cellHeight() * .32f - g.top) * sy;
                } else {
                    y1 = (g.centerY(s.fromRow) + g.cellHeight() * .25f - g.top) * sy;
                    y2 = (g.centerY(s.toRow) - g.cellHeight() * .25f - g.top) * sy;
                    x1 = x2 = (g.cellLeft(s.fromCol) + g.cellWidth() * .32f - g.left) * sx;
                }
                canvas.drawLine(x1, y1, x2, y2, paint);
                float length = cell * .16f;
                float dx = x2 - x1, dy = y2 - y1, norm = (float) Math.hypot(dx, dy);
                if (norm > 0) {
                    float ux = dx / norm * length, uy = dy / norm * length;
                    canvas.drawLine(x1, y1, x1 + ux - uy, y1 + uy + ux, paint);
                    canvas.drawLine(x1, y1, x1 + ux + uy, y1 + uy - ux, paint);
                    canvas.drawLine(x2, y2, x2 - ux - uy, y2 - uy + ux, paint);
                    canvas.drawLine(x2, y2, x2 - ux + uy, y2 - uy - ux, paint);
                }
            }
            for (float[] point : probes(hint)) {
                float x = (point[0] * g.frameWidth - g.left) * sx;
                float y = (point[1] * g.frameHeight - g.top) * sy;
                probe.setColor(0xffff00ff); canvas.drawRect(x - 4, y - 2, x, y + 2, probe);
                probe.setColor(0xff00ff00); canvas.drawRect(x, y - 2, x + 4, y + 2, probe);
            }
        }
        private void drawGuide(Canvas canvas, BoardGeometry g, Match3HintGuide guide, float cell) {
            float startX = (guide.startX - g.left) * sx, startY = (guide.startY - g.top) * sy;
            float endX = (guide.endX - g.left) * sx, endY = (guide.endY - g.top) * sy;
            float dx = endX - startX, dy = endY - startY, length = (float) Math.hypot(dx, dy);
            float ux = dx / length, uy = dy / length;
            canvas.drawCircle(startX, startY, cell * .11f, paint);
            canvas.drawLine(startX + ux * cell * .13f, startY + uy * cell * .13f, endX, endY, paint);
            float backX = endX - ux * cell * .28f, backY = endY - uy * cell * .28f;
            canvas.drawLine(endX, endY, backX - uy * cell * .18f, backY + ux * cell * .18f, paint);
            canvas.drawLine(endX, endY, backX + uy * cell * .18f, backY - ux * cell * .18f, paint);
        }
        private void drawCell(Canvas canvas, BoardGeometry g, int row, int col, float inset) {
            rect.set((g.cellLeft(col) - g.left) * sx + inset,
                    (g.cellTop(row) - g.top) * sy + inset,
                    (g.cellLeft(col + 1) - g.left) * sx - inset,
                    (g.cellTop(row + 1) - g.top) * sy - inset);
            canvas.drawRoundRect(rect, 6 * density, 6 * density, paint);
        }
    }
}
