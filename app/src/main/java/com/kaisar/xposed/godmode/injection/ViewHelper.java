package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.robv.android.xposed.XposedHelpers;

/**
 * Created by jrsen on 17-10-13.
 */

public final class ViewHelper {

    public static final String TAG_GM_CMP = "gm_cmp";

    public static View findViewBestMatch(Activity activity, ViewRule rule) {
        // 多策略匹配：resourceName + depth + viewClass 为主锚点，text/description 为辅助
        boolean strictMode = false;
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> BuildConfigClass = cl.loadClass(activity.getPackageName() + ".BuildConfig");
            int versionCode = BuildConfigClass.getField("VERSION_CODE").getInt(null);
            strictMode = versionCode == rule.matchVersionCode;
        } catch (Exception ignore) {
            try {
                PackageInfo packageInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                strictMode = packageInfo.versionCode == rule.matchVersionCode;
            } catch (PackageManager.NameNotFoundException e) {
                Logger.w(TAG, "See what happened!", e);
            }
        }
        Logger.i(TAG, String.format("strict mode %b, matching view for rule: %s", strictMode, rule));

        if (rule.depth != null && rule.depth.length > 0) {
            Logger.i(TAG, "match view by depth (primary anchor)");
            View viewByDepth = findViewByDepth(activity, rule.depth);
            if (viewByDepth != null) {
                if (isDepthMatch(viewByDepth, rule, strictMode))
                    return viewByDepth;
                View anchored = matchByAnchoredStrategy(activity, rule, strictMode, viewByDepth);
                if (anchored != null) return anchored;
            }
        }

        if (!TextUtils.isEmpty(rule.resourceName)) {
            Logger.i(TAG, "match view by resource name (primary anchor)");
            View viewByRes = activity.findViewById(rule.getViewId(activity.getResources()));
            if (viewByRes != null) {
                View matched = matchView(viewByRes, rule, strictMode);
                if (matched != null) return matched;
            }
        }

        if (!TextUtils.isEmpty(rule.text)) {
            Logger.i(TAG, "match view by text (auxiliary)");
            View viewByText = findViewByText(activity.getWindow().getDecorView(), rule.text);
            if (viewByText != null) {
                View matched = matchView(viewByText, rule, strictMode);
                if (matched != null) return matched;
            }
        }
        if (!TextUtils.isEmpty(rule.description)) {
            Logger.i(TAG, "match view by description (auxiliary)");
            View viewByDesc = findViewByDescription(activity.getWindow().getDecorView(), rule.description);
            if (viewByDesc != null) {
                View matched = matchView(viewByDesc, rule, strictMode);
                if (matched != null) return matched;
            }
        }

        // 最终兜底：仅按 depth 作为最后手段
        if (rule.depth != null && rule.depth.length > 0) {
            View view = findViewByDepth(activity, rule.depth);
            if (view != null) return matchView(view, rule, false);
        }
        return null;
    }

    private static boolean isDepthMatch(View view, ViewRule rule, boolean strictMode) {
        try {
            String viewClass = view.getClass().getName();
            if (!TextUtils.isEmpty(rule.viewClass) && !TextUtils.equals(viewClass, rule.viewClass))
                return false;
            if (strictMode && !TextUtils.isEmpty(rule.resourceName)) {
                try {
                    String resName = view.getResources().getResourceName(view.getId());
                    if (!TextUtils.equals(resName, rule.resourceName)) return false;
                } catch (Resources.NotFoundException ignore) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static View matchByAnchoredStrategy(Activity activity, ViewRule rule, boolean strictMode, View depthView) {
        // 主锚点匹配失败时，尝试在同级视图组中按 resourceName 或 text 匹配
        ViewParent parent = depthView.getParent();
        if (!(parent instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) parent;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            View matched = matchView(child, rule, strictMode);
            if (matched != null) return matched;
        }
        return null;
    }

    private static View matchView(View view, ViewRule rule, boolean strictMode) {
        try {
            Preconditions.checkNotNull(view, "view can't be null");
            Preconditions.checkNotNull(rule, "rule can't be null");
            String resourceName = null;
            try {
                resourceName = view.getResources().getResourceName(view.getId());
            } catch (Resources.NotFoundException ignore) {
            }
            String text = (view instanceof TextView) ? Preconditions.optionDefault(((TextView) view).getText(), "").toString() : "";
            String description = Preconditions.optionDefault(view.getContentDescription(), "").toString();
            String viewClass = view.getClass().getName();
            if (strictMode) {
                return TextUtils.equals(resourceName, rule.resourceName)
                        && TextUtils.equals(text, rule.text)
                        && TextUtils.equals(description, rule.description)
                        && TextUtils.equals(viewClass, rule.viewClass) ? view : null;
            } else {
                return ((!TextUtils.isEmpty(rule.resourceName) && TextUtils.equals(resourceName, rule.resourceName))
                        || (!TextUtils.isEmpty(rule.text) && TextUtils.equals(text, rule.text))
                        || (!TextUtils.isEmpty(rule.description) && TextUtils.equals(description, rule.description))
                        || (!TextUtils.isEmpty(rule.viewClass) && TextUtils.equals(viewClass, rule.viewClass))) ? view : null;

            }
        } catch (Exception e) {
            Logger.w(TAG, "[matchView] exception during matching: " + e.getMessage());
        }
        return null;
    }

    public static View findViewByText(View view, String text) {
        if (view instanceof TextView && TextUtils.equals(((TextView) view).getText(), text)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            final int N = ((ViewGroup) view).getChildCount();
            for (int i = 0; i < N; i++) {
                View childView = findViewByText(((ViewGroup) view).getChildAt(i), text);
                if (childView != null) {
                    return childView;
                }
            }
        }
        return null;
    }

    public static View findViewByDescription(View view, String description) {
        if (TextUtils.equals(view.getContentDescription(), description)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            final int N = ((ViewGroup) view).getChildCount();
            for (int i = 0; i < N; i++) {
                View childView = findViewByDescription(((ViewGroup) view).getChildAt(i), description);
                if (childView != null) {
                    return childView;
                }
            }
        }
        return null;
    }

    public static View findViewByDepth(Activity activity, int[] depths) {
        View view = activity.getWindow().getDecorView();
        for (int depth : depths) {
            view = view instanceof ViewGroup
                    ? ((ViewGroup) view).getChildAt(depth) : null;
            if (view == null) break;
        }
        return view;
    }

    public static View findTopParentViewByChildView(View v) {
        if (v.getParent() == null || !(v.getParent() instanceof ViewGroup)) {
            return v;
        } else {
            return findTopParentViewByChildView((View) v.getParent());
        }
    }

    public static Object findViewRootImplByChildView(ViewParent parent) {
        if (parent.getParent() == null) {
            return !(parent instanceof ViewGroup) ? parent : null;
        } else {
            return findViewRootImplByChildView(parent.getParent());
        }
    }

    public static int[] getViewHierarchyDepth(View view) {
        ArrayList<Integer> depthList = new ArrayList<>();
        ViewParent parent = view.getParent();
        while (parent instanceof ViewGroup) {
            depthList.add(((ViewGroup) parent).indexOfChild(view));
            view = (View) parent;
            parent = parent.getParent();
        }
        int[] depth = new int[depthList.size()];
        for (int i = 0; i < depth.length; i++) {
            depth[i] = depthList.get(depth.length - 1 - i);
        }
        return depth;
    }

    public static ViewRule makeRule(View v) throws PackageManager.NameNotFoundException {
        Activity activity = getAttachedActivityFromView(v);
        Objects.requireNonNull(activity, "Can't found attached activity");
        int[] out = new int[2];
        v.getLocationInWindow(out);
        int x = out[0];
        int y = out[1];
        int width = v.getWidth();
        int height = v.getHeight();

        int[] viewHierarchyDepth = getViewHierarchyDepth(v);
        String activityClassName = activity.getComponentName().getClassName();
        String viewClassName = v.getClass().getName();
        Context context = v.getContext();
        Resources res = context.getResources();
        String resourceName = null;
        try {
            resourceName = v.getId() != View.NO_ID ? res.getResourceName(v.getId()) : null;
        } catch (Resources.NotFoundException ignore) {
            //the resource id may be declared in the plugin apk
        }
        String text = (v instanceof TextView && !TextUtils.isEmpty(((TextView) v).getText())) ? ((TextView) v).getText().toString() : "";
        String description = (!TextUtils.isEmpty(v.getContentDescription())) ? v.getContentDescription().toString() : "";
        String alias = !TextUtils.isEmpty(text) ? text : description;
        String packageName = context.getPackageName();
        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(packageName, 0);
        String label = packageInfo.applicationInfo.loadLabel(context.getPackageManager()).toString();
        String versionName = packageInfo.versionName;
        int versionCode = packageInfo.versionCode;
        return new ViewRule(label, packageName, versionName, versionCode, BuildConfig.VERSION_CODE, "", alias, x, y, width, height, viewHierarchyDepth, activityClassName, viewClassName, resourceName, text, description, View.INVISIBLE, System.currentTimeMillis());
    }

    public static ViewRule makeRemoveRule(View v) throws PackageManager.NameNotFoundException {
        // ruleTag is left null to indicate remove rule (backward compat with old JSON)
        return makeRule(v);
    }

    public static ViewRule makeModifyRule(View view) {
        Activity act = getAttachedActivityFromView(view);
        ViewRule rule = new ViewRule("",
                act != null ? act.getPackageName() : "",
                "", 0, 0, "", "", 0, 0, 0, 0,
                getViewHierarchyDepth(view),
                act != null ? act.getComponentName().getClassName() : "",
                view.getClass().getName(), "", "", "",
                View.VISIBLE, System.currentTimeMillis());
        rule.ruleTag = "modify";
        rule.captureOriginals(view);
        fillCoordinates(rule, view);
        return rule;
    }

    public static void fillCoordinates(ViewRule rule, View v) {
        int[] out = new int[2];
        v.getLocationInWindow(out);
        rule.x = out[0];
        rule.y = out[1];
        rule.width = v.getWidth();
        rule.height = v.getHeight();
    }

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
            return getActivityFromViewContext(baseContext);
        } else {
            return null;
        }
    }

    public static Bitmap snapshotView(View view) {
        Bitmap b = Bitmap.createBitmap(Math.max(view.getWidth(), 1), Math.max(view.getHeight(), 1), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(-view.getScrollX(), -view.getScrollY());
        view.draw(c);
        return b;
    }

    public static void drawRuleMask(Bitmap bitmap, ViewRule rule) {
        Paint markPaint = new Paint();
        markPaint.setColor(Color.RED);
        markPaint.setAlpha(100);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawRect(rule.x, rule.y, rule.x + rule.width, rule.y + rule.height, markPaint);
    }

    public static Bitmap cloneViewAsBitmap(View view) {
        Bitmap bitmap = snapshotView(view);

        Paint paint = new Paint();
        paint.setAntiAlias(false);

        // Draw optical bounds
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);

        Canvas canvas = new Canvas(bitmap);
        drawRect(canvas, paint, 0, 0, canvas.getWidth() - 1, canvas.getHeight() - 1);

        // Draw clip bounds
        paint.setColor(Color.rgb(63, 127, 255));
        paint.setStyle(Paint.Style.FILL);

        Context context = view.getContext();
        int lineLength = dipsToPixels(context, 8);
        int lineWidth = dipsToPixels(context, 1);
        drawRectCorners(canvas, 0, 0, canvas.getWidth(), canvas.getHeight(),
                paint, lineLength, lineWidth);
        return bitmap;
    }

    private static int dipsToPixels(Context context, int dips) {
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dips * scale + 0.5f);
    }

    private static void drawRect(Canvas canvas, Paint paint, int x1, int y1, int x2, int y2) {
        float[] debugLines = new float[16];

        debugLines[0] = x1;
        debugLines[1] = y1;
        debugLines[2] = x2;
        debugLines[3] = y1;

        debugLines[4] = x2;
        debugLines[5] = y1;
        debugLines[6] = x2;
        debugLines[7] = y2;

        debugLines[8] = x2;
        debugLines[9] = y2;
        debugLines[10] = x1;
        debugLines[11] = y2;

        debugLines[12] = x1;
        debugLines[13] = y2;
        debugLines[14] = x1;
        debugLines[15] = y1;

        canvas.drawLines(debugLines, paint);
    }

    private static void drawRectCorners(Canvas canvas, int x1, int y1, int x2, int y2, Paint paint,
                                        int lineLength, int lineWidth) {
        drawCorner(canvas, paint, x1, y1, lineLength, lineLength, lineWidth);
        drawCorner(canvas, paint, x1, y2, lineLength, -lineLength, lineWidth);
        drawCorner(canvas, paint, x2, y1, -lineLength, lineLength, lineWidth);
        drawCorner(canvas, paint, x2, y2, -lineLength, -lineLength, lineWidth);
    }

    private static void drawCorner(Canvas c, Paint paint, int x1, int y1, int dx, int dy, int lw) {
        fillRect(c, paint, x1, y1, x1 + dx, y1 + lw * sign(dy));
        fillRect(c, paint, x1, y1, x1 + lw * sign(dx), y1 + dy);
    }

    private static void fillRect(Canvas canvas, Paint paint, int x1, int y1, int x2, int y2) {
        if (x1 != x2 && y1 != y2) {
            if (x1 > x2) {
                int tmp = x1;
                x1 = x2;
                x2 = tmp;
            }
            if (y1 > y2) {
                int tmp = y1;
                y1 = y2;
                y2 = tmp;
            }
            canvas.drawRect(x1, y1, x2, y2, paint);
        }
    }

    private static int sign(int x) {
        return (x >= 0) ? 1 : -1;
    }

    public static List<WeakReference<View>> buildViewNodes(View view) {
        ArrayList<WeakReference<View>> views = new ArrayList<>();
        if (view.getVisibility() == View.VISIBLE && !TAG_GM_CMP.equals(view.getTag())) {
            views.add(new WeakReference<>(view));
            if (view instanceof ViewGroup) {
                final int N = ((ViewGroup) view).getChildCount();
                for (int i = 0; i < N; i++) {
                    View childView = ((ViewGroup) view).getChildAt(i);
                    views.addAll(buildViewNodes(childView));
                }
            }
        }
        return views;
    }

    public static Rect getLocationInWindow(View v) {
        int[] out = new int[2];
        v.getLocationInWindow(out);
        int l = out[0];
        int t = out[1];
        int r = l + v.getWidth();
        int b = t + v.getHeight();
        return new Rect(l, t, r, b);
    }

    public static String getViewKey(View view) {
        Activity act = getAttachedActivityFromView(view);
        String actName = act != null ? act.getComponentName().getClassName() : "unknown";
        int[] depth = getViewHierarchyDepth(view);
        StringBuilder sb = new StringBuilder(actName);
        for (int d : depth) sb.append('_').append(d);
        return sb.toString();
    }

}
