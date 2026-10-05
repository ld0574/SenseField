package com.openkhub.sensefield;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import java.util.Arrays;

/** Wraps short sample buttons using their actual font size and the available page width. */
final class ReminderSampleGrid extends ViewGroup {
    private final int maximumColumns;
    private final int gap;
    private int columns = 1;
    private int cellWidth;
    private int rowCount;
    private int[] rowHeights = new int[0];

    public ReminderSampleGrid(Context context) { this(context, 3); }

    ReminderSampleGrid(Context context, int maximumColumns) {
        super(context);
        this.maximumColumns = Math.max(1, maximumColumns);
        gap = UiKit.dp(context, 8);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? UiKit.dp(getContext(), PageGeometry.MAX_READING_WIDTH_DP)
                : MeasureSpec.getSize(widthSpec);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int desiredCellWidth = UiKit.dp(getContext(), 56);
        int count = 0;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            desiredCellWidth = Math.max(desiredCellWidth, child.getMeasuredWidth());
            count++;
        }
        columns = Math.max(1, Math.min(maximumColumns,
                (available + gap) / (desiredCellWidth + gap)));
        cellWidth = Math.max(0, (available - (columns - 1) * gap) / columns);
        prepareRows((count + columns - 1) / columns);
        int visibleIndex = 0;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int row = visibleIndex++ / columns;
            rowHeights[row] = Math.max(rowHeights[row], child.getMeasuredHeight());
        }
        int height = getPaddingTop() + getPaddingBottom()
                + Math.max(0, rowCount - 1) * gap;
        for (int row = 0; row < rowCount; row++) height += rowHeights[row];
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    private void prepareRows(int count) {
        rowCount = count;
        if (rowHeights.length < count) rowHeights = new int[count];
        else Arrays.fill(rowHeights, 0);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int visibleIndex = 0;
        int y = getPaddingTop();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            int row = visibleIndex / columns;
            int column = visibleIndex % columns;
            int x = rtl ? getWidth() - getPaddingRight() - cellWidth - column * (cellWidth + gap)
                    : getPaddingLeft() + column * (cellWidth + gap);
            child.layout(x, y, x + cellWidth, y + rowHeights[row]);
            visibleIndex++;
            if (visibleIndex % columns == 0) y += rowHeights[row] + gap;
        }
    }

    @Override protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
}
