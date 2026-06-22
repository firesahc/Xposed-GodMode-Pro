package com.kaisar.xposed.godmode.injection.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.Logger;

import java.lang.ref.WeakReference;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * View utility methods — view context resolution, position query,
 * hierarchy depth calculation and other shared logic.
 * <p>
 * Split from {@code ViewHelper} for single-responsibility.
 */
public final class ViewUtils {

    private static final String TAG = "ViewUtils";

    private ViewUtils() {}

    /**
     * Recursively find the Activity hosting the given view.
     *
     * @param view target view
     * @return associated Activity, or null if not found
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
                    Logger.w(TAG, "getActivityFromViewContext reflection failed for context", e);
                    return null;
                }
            }
            return getActivityFromViewContext(baseContext, depth + 1);
        }
        return null;
    }

    /**
     * Build a breadth-first list of view tree nodes.
     *
     * @param view root view
     * @return list of weak references to view nodes
     */
    public static List<WeakReference<View>> buildViewNodes(View view) {
        return ViewTraversal.buildViewNodes(view);
    }

    /**
     * Get the view position rectangle within the window.
     *
     * @param v target view
     * @return position rect (left, top, right, bottom)
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
     * Generate a unique key for the view (activity name + depth path).
     *
     * @param view target view
     * @return unique key string
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
     * Find the top-level parent view (root view) of the given view.
     *
     * @param v target view
     * @return top parent view
     */
    public static View findTopParentViewByChildView(View v) {
        return ViewTraversal.findTopParentView(v);
    }

    /**
     * Recursively find the ViewRootImpl of the given view.
     *
     * @param parent view parent node
     * @return ViewRootImpl object, or null
     */
    public static Object findViewRootImplByChildView(ViewParent parent) {
        if (parent.getParent() == null) {
            return !(parent instanceof ViewGroup) ? parent : null;
        } else {
            return findViewRootImplByChildView(parent.getParent());
        }
    }

    /**
     * Get the hierarchy depth path of the view.
     *
     * @param view target view
     * @return depth path array
     */
    public static int[] getViewHierarchyDepth(View view) {
        return ViewTraversal.getViewHierarchyDepth(view);
    }
}
