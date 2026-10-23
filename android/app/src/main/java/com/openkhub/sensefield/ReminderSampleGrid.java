package com.openkhub.sensefield;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.Arrays;

/**
 * Wraps short buttons using their actual font size and the available page width. Neighbouring
 * buttons always keep the standard control gap, horizontally and vertically.
 */
final class ReminderSampleGrid extends ViewGroup {
    private final int maximumColumns;
    private final int gap;
    private final boolean wrapNames;
    private boolean fillRow;
    private boolean reserveIntrinsicHeight;
    private int intrinsicHeight;
    private int columns = 1;
    private int cellWidth;
    private int rowCount;
    private int[] rowHeights = new int[0];

    public ReminderSampleGrid(Context context) { this(context, 2); }

    ReminderSampleGrid(Context context, int maximumColumns) {
        this(context, maximumColumns, false);
    }

    ReminderSampleGrid(Context context, int maximumColumns, boolean wrapNames) {
        this(context, maximumColumns, wrapNames, UiKit.GAP_CONTROL);
    }

    ReminderSampleGrid(Context context, int maximumColumns, boolean wrapNames, float gapDp) {
        super(context);
        this.maximumColumns = Math.max(1, maximumColumns);
        this.wrapNames = wrapNames;
        gap = UiKit.dp(context, gapDp);
    }

    /** Action-row mode: visible buttons share the full width; a lone button fills the row. */
    ReminderSampleGrid fillRow() {
        fillRow = true;
        return this;
    }

    /** AlertDialog reserves this height before giving the remaining space to its message. */
    ReminderSampleGrid reserveDialogHeight() {
        reserveIntrinsicHeight = true;
        return this;
    }

    @Override public int getMinimumHeight() {
        return reserveIntrinsicHeight ? Math.max(super.getMinimumHeight(), intrinsicHeight)
                : super.getMinimumHeight();
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
            int preferredWidth = preferredWidth(child);
            if (wrapNames && child instanceof TextView) {
                TextView label = (TextView) child;
                // Keep full sound names, allowing two-line labels at ordinary font sizes.
                // Measuring the actual font also reflows large text to fewer columns.
                int readableWidth = Math.round(label.getPaint().measureText("方位语音"))
                        + child.getPaddingLeft() + child.getPaddingRight();
                preferredWidth = Math.min(preferredWidth, Math.max(child.getMinimumWidth(), readableWidth));
            }
            desiredCellWidth = Math.max(desiredCellWidth, preferredWidth);
            count++;
        }
        columns = Math.max(1, Math.min(maximumColumns,
                (available + gap) / (desiredCellWidth + gap)));
        if (fillRow) columns = Math.max(1, Math.min(columns, count));
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
        intrinsicHeight = height;
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    /** Intrinsic labels decide columns; MATCH_PARENT fields must not force one column. */
    private int preferredWidth(View view) {
        int content = 0;
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            for (String line : label.getText().toString().split("\\n"))
                content = Math.max(content, (int) Math.ceil(label.getPaint().measureText(line)));
            android.graphics.drawable.Drawable[] icons = label.getCompoundDrawablesRelative();
            for (int i : new int[] {0, 2})
                if (icons[i] != null) content += icons[i].getBounds().width() + label.getCompoundDrawablePadding();
        } else if (view instanceof android.widget.Spinner) {
            View selected = ((android.widget.Spinner) view).getSelectedView();
            content = selected == null ? UiKit.dp(getContext(), 56) : preferredWidth(selected);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            boolean horizontal = group instanceof android.widget.LinearLayout
                    && ((android.widget.LinearLayout) group).getOrientation() == android.widget.LinearLayout.HORIZONTAL;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                if (horizontal) content += preferredWidth(child);
                else content = Math.max(content, preferredWidth(child));
            }
        } else content = view.getMeasuredWidth();
        return Math.max(view.getMinimumWidth(), content + view.getPaddingLeft() + view.getPaddingRight());
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
