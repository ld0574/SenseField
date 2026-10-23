package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ImageView;

/** Grid and correction gestures share ImageView's actual drawable transform. No marked bitmap copies. */
final class Match3BoardPreview extends ImageView {
    interface Listener {
        void onImageTap(int x, int y);
        void onFirstCorner();
        boolean onBoardSelected(int left, int top, int right, int bottom);
    }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix inverse = new Matrix();
    private final float[] matrixValues = new float[9];
    private final int slop;
    private BoardGeometry geometry;
    private Listener listener;
    private boolean selecting, pressed, moved;
    private float downX, downY;
    private float[] downPoint, firstCorner;
    private RectF draft;

    Match3BoardPreview(Context context) {
        super(context);
        setAdjustViewBounds(true);
        setScaleType(ScaleType.FIT_CENTER);
        setMaxHeight(UiKit.dp(context, Math.max(160, getResources().getConfiguration().screenHeightDp - 128)));
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(true);
        describe();
    }

    void setListener(Listener listener) { this.listener = listener; }

    @Override public void setImageBitmap(Bitmap bitmap) {
        if (bitmap == null) { setImageDrawable(null); return; }
        BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
        // Screenshot pixels, sampling coordinates and drawable coordinates must be identical.
        // ImageView otherwise rescales a bitmap whose density differs from this display.
        drawable.setTargetDensity(bitmap.getDensity() == Bitmap.DENSITY_NONE ? 160 : bitmap.getDensity());
        setImageDrawable(drawable);
    }
    void setGeometry(BoardGeometry geometry) { this.geometry = geometry; describe(); invalidate(); }
    void beginSelection() { cancelSelection(); selecting = true; describe(); invalidate(); }
    void cancelSelection() {
        selecting = false; pressed = false; moved = false; firstCorner = null; draft = null;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        describe(); invalidate();
    }
    boolean isSelecting() { return selecting; }

    private void describe() {
        setContentDescription(selecting ? "棋盘范围框选：拖出整个棋盘，或依次点两个对角。也可用上方百分比输入框调整。"
                : geometry == null ? "截图预览：尚未定位棋盘，可使用手动棋盘校准。"
                : "棋盘截图预览：" + geometry.rows + " 行 " + geometry.cols + " 列。点击格子试读。可用上方校准输入框调整范围。");
    }

    private float[] imagePoint(float x, float y, boolean clamp) {
        if (getDrawable() == null || !getImageMatrix().invert(inverse)) return null;
        float[] point = {x - getPaddingLeft(), y - getPaddingTop()};
        inverse.mapPoints(point);
        int width = getDrawable().getIntrinsicWidth(), height = getDrawable().getIntrinsicHeight();
        if (width <= 0 || height <= 0) return null;
        if (!clamp && (point[0] < 0 || point[0] >= width || point[1] < 0 || point[1] >= height)) return null;
        point[0] = Math.max(0, Math.min(width, point[0]));
        point[1] = Math.max(0, Math.min(height, point[1]));
        return point;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downPoint = imagePoint(event.getX(), event.getY(), false);
            if (downPoint == null) return false;
            downX = event.getX(); downY = event.getY(); moved = false; pressed = true;
            if (selecting && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (!pressed) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            pressed = false; draft = null; invalidate();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        float dx = event.getX() - downX, dy = event.getY() - downY;
        moved |= dx * dx + dy * dy > slop * slop;
        float[] point = imagePoint(event.getX(), event.getY(), selecting);
        if (point == null) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                pressed = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }
        if (selecting && (moved || firstCorner != null)) {
            float[] anchor = firstCorner == null ? downPoint : firstCorner;
            draft = rectangle(anchor, point); invalidate();
        }
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
        pressed = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        if (selecting) {
            if (!moved && firstCorner == null) {
                firstCorner = downPoint; draft = null;
                if (listener != null) listener.onFirstCorner();
                invalidate();
            } else {
                RectF box = rectangle(firstCorner == null ? downPoint : firstCorner, point);
                boolean accepted = listener != null && listener.onBoardSelected(Math.round(box.left), Math.round(box.top),
                        Math.round(box.right), Math.round(box.bottom));
                firstCorner = null; draft = null;
                if (accepted) cancelSelection(); else invalidate();
            }
        } else if (!moved && listener != null) {
            listener.onImageTap((int) point[0], (int) point[1]);
        }
        performClick();
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private static RectF rectangle(float[] a, float[] b) {
        return new RectF(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getDrawable() == null) return;
        int save = canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop()); canvas.concat(getImageMatrix());
        getImageMatrix().getValues(matrixValues);
        float scale = Math.max(.001f, Math.abs(matrixValues[Matrix.MSCALE_X]));
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(UiKit.dp(getContext(), 2) / scale);
        paint.setColor(Color.rgb(0, 200, 120));
        if (geometry != null) {
            for (int col = 0; col <= geometry.cols; col++)
                canvas.drawLine(geometry.cellLeft(col), geometry.top, geometry.cellLeft(col), geometry.bottom, paint);
            for (int row = 0; row <= geometry.rows; row++)
                canvas.drawLine(geometry.left, geometry.cellTop(row), geometry.right, geometry.cellTop(row), paint);
        }
        paint.setColor(Color.rgb(255, 155, 20));
        if (draft != null) canvas.drawRect(draft, paint);
        if (firstCorner != null) canvas.drawCircle(firstCorner[0], firstCorner[1], UiKit.dp(getContext(), 6) / scale, paint);
        canvas.restoreToCount(save);
    }
}
