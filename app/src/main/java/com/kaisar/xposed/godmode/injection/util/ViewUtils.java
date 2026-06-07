package com.kaisar.xposed.godmode.injection.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.kaisar.xposed.godmode.engine.traversal.ViewTraversal;

import java.lang.ref.WeakReference;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * 视图工具方法 — 视图上下文解析、位置获取、层级计算等共享逻辑。
 * <p>
 * 从 {@code ViewHelper} 拆分，职责单一，无规则依赖。
 */
public final class ViewUtils {

    private ViewUtils() {}

    /**
     * 从视图递归查找其所在 Activity。
     *
     * @param view 目标视图
     * @return 关联的 Activity，若无法找到则返回 null
     */
    public static Activity getAttachedActivityFromView(View view) {
        Activity activity = getActivityFromViewContext(view.getContext());
        if (activity != null) {
            return activity;
        } else {
            ViewParent parent = view.getParent();
            return parent instanceof ViewGroup ? getAttachedActivityFromView((View) parent) : null;
        }
    }

    private static Activity getActivityFromViewContext(Context context) {
        return getActivityFromViewContext(context, 0);
    }

    private static Activity getActivityFromViewContext(Context context, int depth) {
        if (depth > 20) return null;
        if (context == null) return null;
        if (context instanceof Activity) {
            return (Activity) context;
        } else if (context instanceof ContextWrapper) {
            Context baseContext = ((ContextWrapper) context).getBaseContext();
            if (baseContext == context) {
                try {
                    baseContext = (Context) XposedHelpers.getObjectField(context, "mBase");
                } catch (Exception e) {
                    return null;
                }
            }
            return getActivityFromViewContext(baseContext, depth + 1);
        }
        return null;
    }

    /**
     * 构建视图树节点列表（广度遍历）。
     *
     * @param view 根视图
     * @return 弱引用节点列表
     */
    public static List<WeakReference<View>> buildViewNodes(View view) {
        return ViewTraversal.buildViewNodes(view);
    }

    /**
     * 获取视图在窗口中的位置矩形。
     *
     * @param v 目标视图
     * @return 位置矩形（left, top, right, bottom）
     */
    public static Rect getLocationInWindow(View v) {
        int[] out = new int[2];
        v.getLocationInWindow(out);
        int l = out[0];
        int t = out[1];
        int r = l + v.getWidth();
        int b = t + v.getHeight();
        return new Rect(l, t, r, b);
    }

    /**
     * 为视图生成唯一键（activity 名 + depth 路径）。
     *
     * @param view 目标视图
     * @return 唯一键字符串
     */
    public static String getViewKey(View view) {
        Activity act = getAttachedActivityFromView(view);
        String actName = act != null ? act.getComponentName().getClassName() : "unknown";
        int[] depth = getViewHierarchyDepth(view);
        StringBuilder sb = new StringBuilder(actName);
        for (int d : depth) sb.append('_').append(d);
        return sb.toString();
    }

    /**
     * 查找视图的最顶层父视图（根视图）。
     *
     * @param v 目标视图
     * @return 顶层父视图
     */
    public static View findTopParentViewByChildView(View v) {
        return ViewTraversal.findTopParentView(v);
    }

    /**
     * 递归查找视图的 ViewRootImpl。
     *
     * @param parent 视图父节点
     * @return ViewRootImpl 对象，或 null
     */
    public static Object findViewRootImplByChildView(ViewParent parent) {
        if (parent.getParent() == null) {
            return !(parent instanceof ViewGroup) ? parent : null;
        } else {
            return findViewRootImplByChildView(parent.getParent());
        }
    }

    /**
     * 获取视图的层级深度路径。
     *
     * @param view 目标视图
     * @return 深度路径数组
     */
    public static int[] getViewHierarchyDepth(View view) {
        return ViewTraversal.getViewHierarchyDepth(view);
    }
}
