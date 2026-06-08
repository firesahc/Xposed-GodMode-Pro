package com.kaisar.xposed.godmode.injection.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;

import java.lang.ref.WeakReference;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * 瑙嗗浘宸ュ叿鏂规硶 鈥?瑙嗗浘涓婁笅鏂囪В鏋愩€佷綅缃幏鍙栥€佸眰绾ц绠楃瓑鍏变韩閫昏緫銆? * <p>
 * 浠?{@code ViewHelper} 鎷嗗垎锛岃亴璐ｅ崟涓€锛屾棤瑙勫垯渚濊禆銆? */
public final class ViewUtils {

    private ViewUtils() {}

    /**
     * 浠庤鍥鹃€掑綊鏌ユ壘鍏舵墍鍦?Activity銆?     *
     * @param view 鐩爣瑙嗗浘
     * @return 鍏宠仈鐨?Activity锛岃嫢鏃犳硶鎵惧埌鍒欒繑鍥?null
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
     * 鏋勫缓瑙嗗浘鏍戣妭鐐瑰垪琛紙骞垮害閬嶅巻锛夈€?     *
     * @param view 鏍硅鍥?     * @return 寮卞紩鐢ㄨ妭鐐瑰垪琛?     */
    public static List<WeakReference<View>> buildViewNodes(View view) {
        return ViewTraversal.buildViewNodes(view);
    }

    /**
     * 鑾峰彇瑙嗗浘鍦ㄧ獥鍙ｄ腑鐨勪綅缃煩褰€?     *
     * @param v 鐩爣瑙嗗浘
     * @return 浣嶇疆鐭╁舰锛坙eft, top, right, bottom锛?     */
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
     * 涓鸿鍥剧敓鎴愬敮涓€閿紙activity 鍚?+ depth 璺緞锛夈€?     *
     * @param view 鐩爣瑙嗗浘
     * @return 鍞竴閿瓧绗︿覆
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
     * 鏌ユ壘瑙嗗浘鐨勬渶椤跺眰鐖惰鍥撅紙鏍硅鍥撅級銆?     *
     * @param v 鐩爣瑙嗗浘
     * @return 椤跺眰鐖惰鍥?     */
    public static View findTopParentViewByChildView(View v) {
        return ViewTraversal.findTopParentView(v);
    }

    /**
     * 閫掑綊鏌ユ壘瑙嗗浘鐨?ViewRootImpl銆?     *
     * @param parent 瑙嗗浘鐖惰妭鐐?     * @return ViewRootImpl 瀵硅薄锛屾垨 null
     */
    public static Object findViewRootImplByChildView(ViewParent parent) {
        if (parent.getParent() == null) {
            return !(parent instanceof ViewGroup) ? parent : null;
        } else {
            return findViewRootImplByChildView(parent.getParent());
        }
    }

    /**
     * 鑾峰彇瑙嗗浘鐨勫眰绾ф繁搴﹁矾寰勩€?     *
     * @param view 鐩爣瑙嗗浘
     * @return 娣卞害璺緞鏁扮粍
     */
    public static int[] getViewHierarchyDepth(View view) {
        return ViewTraversal.getViewHierarchyDepth(view);
    }
}
