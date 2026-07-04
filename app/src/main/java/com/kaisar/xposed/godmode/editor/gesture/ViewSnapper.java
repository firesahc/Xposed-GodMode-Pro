package com.kaisar.xposed.godmode.editor.gesture;

import android.view.View;
import android.view.ViewGroup;

/**
 * 网格/边缘吸附辅助工具。
 * 从 EventHandlerHook 提取的纯数学吸附逻辑。
 */
public final class ViewSnapper {

    private ViewSnapper() {}

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

}
