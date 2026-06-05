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
import com.kaisar.xposed.godmode.engine.traversal.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * Created by jrsen on 17-10-13.
 */

public final class ViewHelper {

    private static final int MAX_REPEATABLE_RESULTS = 50;
    private static final Map<Activity, List<WeakReference<ViewGroup>>> sRecyclerViewCache
        = new WeakHashMap<>();

    public static final String TAG_GM_CMP = GmConstants.TAG_GM_CMP;

    public static View findViewBestMatch(Activity activity, ViewRule rule) {
        if (activity == null || activity.getWindow() == null) return null;
        ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
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
                Logger.w(TAG, "[ViewHelper] Failed to get package info for strict mode check", e);
            }
        }
        Logger.d(TAG, String.format("[ViewHelper] strict mode %b, matching view for rule: %s", strictMode, rule));

        if (rule.depth != null && rule.depth.length > 0) {
            Logger.d(TAG, "[ViewHelper] match view by depth (primary anchor)");
            View viewByDepth = findViewByDepth(activity, rule.depth);
            if (viewByDepth != null) {
                if (isDepthMatch(viewByDepth, rule, strictMode))
                    return viewByDepth;
                View anchored = matchByAnchoredStrategy(rule, strictMode, viewByDepth);
                if (anchored != null) return anchored;
            }
        }

        // 单元素模式：仅信任 depth 锚定，不回退到 text/description/resourceName。
        // 回退会错误匹配 ViewPager / 横向滑动器中同位置不同页面的元素。
        if (!rule.isRepeatable()) {
            if (rule.depth != null && rule.depth.length > 0) {
                View view = findViewByDepth(activity, rule.depth);
                if (view != null && verifySingleElement(view, rule)) return view;
            }
            return null;
        }

        if (!TextUtils.isEmpty(rule.resourceName)) {
            Logger.d(TAG, "[ViewHelper] match view by resource name (primary anchor)");
            View viewByRes = activity.findViewById(rule.getViewId(activity.getResources()));
            if (viewByRes != null) {
                View matched = matchView(viewByRes, rule, strictMode);
                if (matched != null) return matched;
            }
        }

        if (!TextUtils.isEmpty(rule.text)) {
            Logger.d(TAG, "[ViewHelper] match view by text (auxiliary)");
            View viewByText = findViewByText(decorView, rule.text);
            if (viewByText != null) {
                View matched = matchView(viewByText, rule, strictMode);
                if (matched != null) return matched;
            }
        }
        if (!TextUtils.isEmpty(rule.description)) {
            Logger.d(TAG, "[ViewHelper] match view by description (auxiliary)");
            View viewByDesc = findViewByDescription(decorView, rule.description);
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

    private static View matchByAnchoredStrategy(ViewRule rule, boolean strictMode, View depthView) {
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

    private static int computeMatchScore(View view, ViewRule rule) {
        int score = 0;
        if (view.getClass().getName().equals(rule.viewClass)) score += 30;
        if (!TextUtils.isEmpty(rule.resourceName)) {
            try {
                String resName = view.getResources().getResourceName(view.getId());
                if (TextUtils.equals(resName, rule.resourceName)) score += 25;
            } catch (Resources.NotFoundException ignored) {}
        }
        if (!TextUtils.isEmpty(rule.text) && view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            if (t != null && TextUtils.equals(t.toString(), rule.text)) score += 20;
        }
        if (!TextUtils.isEmpty(rule.description)) {
            CharSequence desc = view.getContentDescription();
            if (desc != null && TextUtils.equals(desc.toString(), rule.description)) score += 15;
        }
        if (!TextUtils.isEmpty(rule.parentClass)) {
            ViewParent parent = view.getParent();
            if (parent != null && parent.getClass().getName().equals(rule.parentClass)) score += 10;
        }
        return score;
    }

    private static View matchView(View view, ViewRule rule, boolean strictMode) {
        try {
            Preconditions.checkNotNull(view, "view can't be null");
            Preconditions.checkNotNull(rule, "rule can't be null");
            int score = computeMatchScore(view, rule);
            int threshold = strictMode ? 80 : 30;
            return score >= threshold ? view : null;
        } catch (Exception e) {
            Logger.w(TAG, "[ViewHelper] matchView: exception during matching", e);
        }
        return null;
    }

    private static boolean verifySingleElement(View view, ViewRule rule) {
        return computeMatchScore(view, rule) >= 80;
    }

    public static View findViewByText(View view, String text) {
        return findViewByCondition(view, v -> v instanceof TextView
                && TextUtils.equals(((TextView) v).getText(), text));
    }

    public static View findViewByDescription(View view, String description) {
        return findViewByCondition(view, v -> TextUtils.equals(v.getContentDescription(), description));
    }

    private static View findViewByCondition(View view, ViewPredicate predicate) {
        try {
            if (predicate.test(view)) return view;
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View result = findViewByCondition(vg.getChildAt(i), predicate);
                    if (result != null) return result;
                }
            }
        } catch (Exception e) {
            Logger.w(TAG, "[ViewHelper] findViewByCondition: traversal error", e);
        }
        return null;
    }

    private interface ViewPredicate {
        boolean test(View view);
    }

    public static View findViewByDepth(Activity activity, int[] depths) {
        if (activity == null || activity.getWindow() == null || depths == null) return null;
        return ViewTraversal.findViewByDepth(activity.getWindow().getDecorView(), depths);
    }

    public static View findTopParentViewByChildView(View v) {
        return ViewTraversal.findTopParentView(v);
    }

    public static Object findViewRootImplByChildView(ViewParent parent) {
        if (parent.getParent() == null) {
            return !(parent instanceof ViewGroup) ? parent : null;
        } else {
            return findViewRootImplByChildView(parent.getParent());
        }
    }

    public static int[] getViewHierarchyDepth(View view) {
        return ViewTraversal.getViewHierarchyDepth(view);
    }

    public static ViewGroup findRecyclerViewAncestor(View view) {
        return ViewTraversal.findRecyclerViewAncestor(view);
    }

    // ... getItemPath / findViewByItemPath unchanged ...

    public static boolean isInRecyclerView(View v) {
        return ViewTraversal.isInRecyclerView(v);
    }

    public static String[] getItemPath(View v, ViewGroup recyclerView) {
        ArrayList<String> path = new ArrayList<>();
        View current = v;
        ViewParent parent = v.getParent();
        while (parent != recyclerView && parent instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) parent;
            int idx = vg.indexOfChild(current);
            path.add(idx + ":" + current.getClass().getName());
            current = (View) parent;
            parent = parent.getParent();
        }
        Collections.reverse(path);
        return path.toArray(new String[0]);
    }

    private static View findViewByItemPath(View root, String[] path, int index) {
        if (index >= path.length) return root;
        String entry = path[index];
        int colonPos = entry.indexOf(':');
        int childIdx = Integer.parseInt(entry.substring(0, colonPos));
        String className = entry.substring(colonPos + 1);
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            if (childIdx < vg.getChildCount()) {
                View child = vg.getChildAt(childIdx);
                if (child != null && child.getClass().getName().equals(className)) {
                    return findViewByItemPath(child, path, index + 1);
                }
            }
        }
        return null;
    }

    public static List<View> findViewsInRecyclers(Activity activity, ViewRule rule) {
        if (activity == null || activity.getWindow() == null) return Collections.emptyList();
        List<View> results = new ArrayList<>();
        List<WeakReference<ViewGroup>> cached = sRecyclerViewCache.get(activity);
        if (cached != null) {
            for (WeakReference<ViewGroup> ref : cached) {
                ViewGroup rv = ref.get();
                if (rv != null && rv.isAttachedToWindow()) {
                    scanRecyclerViewItems(rv, rule, results);
                }
            }
            if (!results.isEmpty()) return results;
        }
        List<ViewGroup> foundRecyclers = new ArrayList<>();
        collectRecyclerViewMatches((ViewGroup) activity.getWindow().getDecorView(), rule, results, foundRecyclers);
        List<WeakReference<ViewGroup>> cacheEntry = new ArrayList<>();
        for (ViewGroup rv : foundRecyclers) cacheEntry.add(new WeakReference<>(rv));
        sRecyclerViewCache.put(activity, cacheEntry);
        return results;
    }

    private static void collectRecyclerViewMatches(ViewGroup parent, ViewRule rule, List<View> results) {
        collectRecyclerViewMatches(parent, rule, results, null);
    }

    private static void collectRecyclerViewMatches(ViewGroup parent, ViewRule rule, List<View> results,
            List<ViewGroup> foundRecyclers) {
        if (results.size() >= MAX_REPEATABLE_RESULTS) return;
        if (parent.getClass().getName().contains("RecyclerView")) {
            if (foundRecyclers != null) foundRecyclers.add(parent);
            scanRecyclerViewItems(parent, rule, results);
        }
        for (int i = 0; i < parent.getChildCount() && results.size() < MAX_REPEATABLE_RESULTS; i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup) {
                collectRecyclerViewMatches((ViewGroup) child, rule, results, foundRecyclers);
            }
        }
    }

    private static void scanRecyclerViewItems(ViewGroup recyclerView, ViewRule rule, List<View> results) {
        for (int i = 0; i < recyclerView.getChildCount() && results.size() < MAX_REPEATABLE_RESULTS; i++) {
            View itemRoot = recyclerView.getChildAt(i);
            if (itemRoot != null && itemRoot.getClass().getName().equals(rule.itemRootClass)) {
                if (rule.itemPath != null && rule.itemPath.length > 0) {
                    collectViewsByItemPath(itemRoot, rule.itemPath, 0, rule, results);
                }
            }
        }
    }

    private static void collectViewsByItemPath(View parent, String[] itemPath, int index,
            ViewRule rule, List<View> results) {
        if (results.size() >= MAX_REPEATABLE_RESULTS) return;
        String entry = itemPath[index];
        int colonPos = entry.indexOf(':');
        int childIdx = Integer.parseInt(entry.substring(0, colonPos));
        String className = entry.substring(colonPos + 1);
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup vg = (ViewGroup) parent;
        if (childIdx >= vg.getChildCount()) return;
        View child = vg.getChildAt(childIdx);
        if (child == null || !child.getClass().getName().equals(className)) return;
        if (index == itemPath.length - 1) {
            if (matchView(child, rule, false) != null) results.add(child);
        } else {
            collectViewsByItemPath(child, itemPath, index + 1, rule, results);
        }
    }

    public static List<View> findAllViewsBestMatch(Activity activity, ViewRule rule) {
        if (rule.isRepeatable()) {
            List<View> results = findViewsInRecyclers(activity, rule);
            if (!results.isEmpty()) return results;
        }
        View single = findViewBestMatch(activity, rule);
        if (single != null) {
            List<View> list = new ArrayList<>();
            list.add(single);
            return list;
        }
        return Collections.emptyList();
    }

    private static void populateRepeatableInfo(View v, ViewRule rule) {
        try {
            if (!com.kaisar.xposed.godmode.hook.KeyInterceptor.isInfoFlowMode()) return;
            ViewGroup rv = findRecyclerViewAncestor(v);
            if (rv != null) {
                String[] itemPath = getItemPath(v, rv);
                View current = v;
                ViewParent p = current.getParent();
                while (p != rv && p instanceof ViewGroup) {
                    current = (View) p;
                    p = p.getParent();
                }
                String itemRootClass = current.getClass().getName();
                int matchCount = 0;
                for (int i = 0; i < rv.getChildCount() && i < 20 && matchCount < 2; i++) {
                    View child = rv.getChildAt(i);
                    if (child != null && child.getClass().getName().equals(itemRootClass)) {
                        View found = findViewByItemPath(child, itemPath, 0);
                        if (found != null && matchView(found, rule, false) != null) matchCount++;
                    }
                }
                if (matchCount >= 2) {
                    rule.itemPath = itemPath;
                    rule.itemRootClass = itemRootClass;
                    rule.parentClass = (v.getParent() != null) ? v.getParent().getClass().getName() : null;
                    rule.repeatable = true;
                }
            }
        } catch (Exception e) {
            Logger.w(TAG, "[ViewHelper] populateRepeatableInfo: failed (non-fatal)", e);
        }
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
        ViewRule rule = new ViewRule(label, packageName, versionName, versionCode, BuildConfig.VERSION_CODE, "", alias, x, y, width, height, viewHierarchyDepth, activityClassName, viewClassName, resourceName, text, description, View.INVISIBLE, System.currentTimeMillis());
        populateRepeatableInfo(v, rule);
        return rule;
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
        populateRepeatableInfo(view, rule);
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
        return getActivityFromViewContext(context, 0);
    }

    private static Activity getActivityFromViewContext(Context context, int depth) {
        if (depth > 20) return null; // Prevent infinite recursion
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

    public static Bitmap snapshotView(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        Bitmap b = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
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
        return ViewTraversal.buildViewNodes(view);
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
