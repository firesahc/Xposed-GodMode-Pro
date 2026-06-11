package com.kaisar.xposed.godmode.rule;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 视图在编辑前的原始状态快照。
 * <p>
 * 在 PropertyEditorPanel.show() 或 ModifyGestureHandler.startDrag() 等
 * 视图被修改前捕获，确保 {@link RuleRecordFactory#makeModifyRule(View, ViewSnapshot)}
 * 创建修改规则时使用的始终是原始数据，而非被编辑器实时修改后的值。
 * <p>
 * 解决了 {@code makeModifyRule(view)} 从已修改视图读取 text/orig* 导致
 * 匹配字段被污染、重新应用失败的问题。
 */
public class ViewSnapshot {

    /** 原始文本内容（匹配字段） */
    public final String text;

    /** 用于撤销的原始文本 */
    public final String origText;

    /** 原始宽度（优先 LayoutParam 常量，回退像素宽） */
    public final int origWidth;

    /** 原始高度（优先 LayoutParam 常量，回退像素高） */
    public final int origHeight;

    /** 原始透明度 */
    public final float origAlpha;

    /** 原始左边距（用于偏移量计算基准） */
    public final int origLeftMargin;

    /** 原始上边距（用于偏移量计算基准） */
    public final int origTopMargin;

    public ViewSnapshot(String text, String origText,
                        int origWidth, int origHeight, float origAlpha,
                        int origLeftMargin, int origTopMargin) {
        this.text = text;
        this.origText = origText;
        this.origWidth = origWidth;
        this.origHeight = origHeight;
        this.origAlpha = origAlpha;
        this.origLeftMargin = origLeftMargin;
        this.origTopMargin = origTopMargin;
    }

    /**
     * 从视图捕获当前状态作为快照。
     * <p>
     * <b>必须在视图被任何编辑器操作修改前调用。</b>
     * 通常的调用时机：
     * <ul>
     *   <li>PropertyEditorPanel.show() 打开属性编辑面板时</li>
     *   <li>ModifyGestureHandler.startDrag() 开始拖拽手势时</li>
     * </ul>
     *
     * @param view 目标视图（未修改状态）
     * @return 包含所有原始状态值的快照
     */
    public static ViewSnapshot capture(View view) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        int lpWidth = -1, lpHeight = -1;
        int leftMargin = 0, topMargin = 0;

        if (lp != null) {
            lpWidth = lp.width;
            lpHeight = lp.height;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                leftMargin = mlp.leftMargin;
                topMargin = mlp.topMargin;
            }
        }

        int pixelWidth = view.getWidth();
        int pixelHeight = view.getHeight();

        int origWidth = lpWidth > 0 ? lpWidth : pixelWidth;
        int origHeight = lpHeight > 0 ? lpHeight : pixelHeight;

        float origAlpha = view.getAlpha();

        String text;
        if (view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            text = t != null ? t.toString() : "";
        } else {
            text = "";
        }

        return new ViewSnapshot(text, text, origWidth, origHeight, origAlpha,
                leftMargin, topMargin);
    }
}
