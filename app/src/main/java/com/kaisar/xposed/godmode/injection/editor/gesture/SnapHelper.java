package com.kaisar.xposed.godmode.injection.editor.gesture;

import android.view.View;
import android.view.ViewGroup;

/**
 * 网格/边缘吸附辅助工具。
 * 从 EventHandlerHook 提取的纯数学吸附逻辑。
 */
public final class SnapHelper {

    /** 默认网格间距 (dp) */
    public static final int GRID_SIZE_DP = 16;

    /** 边缘吸附阈值 (dp) */
    public static final int EDGE_SNAP_THRESHOLD_DP = 12;

    private SnapHelper() {}

    /**
     * 对边距进行网格吸附。
     * @param margin 原始边距值 (px)
     * @param gridSizePx 网格大小 (px)
     * @return 吸附后的边距值
     */
    public static int snapToGrid(int margin, int gridSizePx) {
        if (gridSizePx <= 0) return margin;
        int remainder = margin % gridSizePx;
        if (remainder < gridSizePx / 2) {
            return margin - remainder;
        } else {
            return margin + (gridSizePx - remainder);
        }
    }

    /**
     * 将目标边距吸附到兄弟视图边缘。
     * @param target 目标视图
     * @param targetLeftMargin 目标左边距
     * @param targetTopMargin 目标上边距
     * @param snapThresholdPx 吸附阈值 (px)
     * @return { snappedX, snappedY }
     */
    public static int[] snapToSiblings(View target, int targetLeftMargin,
            int targetTopMargin, int snapThresholdPx) {
        ViewGroup parent = (ViewGroup) target.getParent();
        if (parent == null) return new int[]{targetLeftMargin, targetTopMargin};

        int targetRight = targetLeftMargin + target.getWidth();
        int targetBottom = targetTopMargin + target.getHeight();
        int snappedX = targetLeftMargin;
        int snappedY = targetTopMargin;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View sibling = parent.getChildAt(i);
            if (sibling == target || sibling.getVisibility() != View.VISIBLE) continue;

            ViewGroup.MarginLayoutParams mlp =
                    (ViewGroup.MarginLayoutParams) sibling.getLayoutParams();
            int sibLeft = mlp.leftMargin;
            int sibTop = mlp.topMargin;
            int sibRight = sibLeft + sibling.getWidth();
            int sibBottom = sibTop + sibling.getHeight();

            if (Math.abs(targetLeftMargin - sibLeft) < snapThresholdPx)
                snappedX = sibLeft;
            if (Math.abs(targetLeftMargin - sibRight) < snapThresholdPx)
                snappedX = sibRight;
            if (Math.abs(targetRight - sibLeft) < snapThresholdPx)
                snappedX = sibLeft - target.getWidth();
            if (Math.abs(targetRight - sibRight) < snapThresholdPx)
                snappedX = sibRight - target.getWidth();

            if (Math.abs(targetTopMargin - sibTop) < snapThresholdPx)
                snappedY = sibTop;
            if (Math.abs(targetTopMargin - sibBottom) < snapThresholdPx)
                snappedY = sibBottom;
            if (Math.abs(targetBottom - sibTop) < snapThresholdPx)
                snappedY = sibTop - target.getHeight();
            if (Math.abs(targetBottom - sibBottom) < snapThresholdPx)
                snappedY = sibBottom - target.getHeight();
        }
        return new int[]{snappedX, snappedY};
    }

    /**
     * 将 dp 值转换为当前密度的 px 值。
     */
    public static int dpToPx(View view, int dp) {
        float density = view.getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
