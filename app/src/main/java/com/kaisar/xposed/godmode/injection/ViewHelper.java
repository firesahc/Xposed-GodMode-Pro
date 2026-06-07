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
import com.kaisar.xposed.godmode.engine.matcher.CompositeMatcher;
import com.kaisar.xposed.godmode.engine.traversal.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.FieldMapper;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.injection.editor.BitmapUtils;
import com.kaisar.xposed.godmode.injection.editor.ViewRuleFactory;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
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
 *
 * @deprecated 此类正在拆解中，新代码请直接调用具体的工具类：
 * <ul>
 *   <li>{@link BitmapUtils} — 位图操作</li>
 * </ul>
 */
@Deprecated
public final class ViewHelper {

    private static final int MAX_REPEATABLE_RESULTS = 50;
    private static final Map<Activity, List<WeakReference<ViewGroup>>> sRecyclerViewCache
        = new WeakHashMap<>();

    /** engine 组合匹配器 — 优先尝试，兜底使用现有匹配逻辑 */
    private static final CompositeMatcher sMatcher = new CompositeMatcher();

    public static final String TAG_GM_CMP = GmConstants.TAG_GM_CMP;

    /** 将 app 模块 ViewRule 转换为 engine 模块 ViewRule（通过 FieldMapper 按字段名拷贝） */
    private static com.kaisar.xposed.godmode.engine.rule.ViewRule toEngine(ViewRule appRule) {
        com.kaisar.xposed.godmode.engine.rule.ViewRule engineRule =
                new com.kaisar.xposed.godmode.engine.rule.ViewRule();
        FieldMapper.copyFields(appRule, engineRule);
        return engineRule;
    }

    public static View findViewBestMatch(Activity activity, ViewRule rule) {
        if (activity == null || activity.getWindow() == null) return null;
        ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();

        // 优先尝试 engine 组合匹配器
        try {
            View matched = sMatcher.matchView(decorView, toEngine(rule));
            if (matched != null) return matched;
        } catch (Exception e) {
            Logger.w(TAG, "[ViewHelper] engine matcher failed, falling back to legacy: " + e.getMessage());
        }

        // 兜底：原有匹配逻辑
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

    /** @deprecated 临时公开，待移至 ViewFinder */
    @Deprecated
    public static View matchView(View view, ViewRule rule, boolean strictMode) {
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

    /** @deprecated 委托至 {@link ViewUtils#findTopParentViewByChildView} */
    @Deprecated
    public static View findTopParentViewByChildView(View v) {
        return ViewUtils.findTopParentViewByChildView(v);
    }

    /** @deprecated 委托至 {@link ViewUtils#findViewRootImplByChildView} */
    @Deprecated
    public static Object findViewRootImplByChildView(ViewParent parent) {
        return ViewUtils.findViewRootImplByChildView(parent);
    }

    /** @deprecated 委托至 {@link ViewUtils#getViewHierarchyDepth} */
    @Deprecated
    public static int[] getViewHierarchyDepth(View view) {
        return ViewUtils.getViewHierarchyDepth(view);
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

    /** @deprecated 临时公开，待移至 ViewFinder */
    @Deprecated
    public static View findViewByItemPath(View root, String[] path, int index) {
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

    /** @deprecated 临时公开，待移至 ViewRuleFactory */
    @Deprecated
    public static void populateRepeatableInfo(View v, ViewRule rule) {
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

    /** @deprecated 委托至 {@link ViewRuleFactory#makeRule} */
    @Deprecated
    public static ViewRule makeRule(View v) throws PackageManager.NameNotFoundException {
        return ViewRuleFactory.makeRule(v);
    }

    /** @deprecated 委托至 {@link ViewRuleFactory#makeRemoveRule} */
    @Deprecated
    public static ViewRule makeRemoveRule(View v) throws PackageManager.NameNotFoundException {
        return ViewRuleFactory.makeRemoveRule(v);
    }

    /** @deprecated 委托至 {@link ViewRuleFactory#makeModifyRule} */
    @Deprecated
    public static ViewRule makeModifyRule(View view) {
        return ViewRuleFactory.makeModifyRule(view);
    }

    /** @deprecated 委托至 {@link ViewRuleFactory#fillCoordinates} */
    @Deprecated
    public static void fillCoordinates(ViewRule rule, View v) {
        ViewRuleFactory.fillCoordinates(rule, v);
    }

    /** @deprecated 委托至 {@link ViewUtils#getAttachedActivityFromView} */
    @Deprecated
    public static Activity getAttachedActivityFromView(View view) {
        return ViewUtils.getAttachedActivityFromView(view);
    }

    /** @deprecated 委托至 {@link BitmapUtils#snapshotView} */
    @Deprecated
    public static Bitmap snapshotView(View view) {
        return BitmapUtils.snapshotView(view);
    }

    /** @deprecated 委托至 {@link BitmapUtils#drawRuleMask} */
    @Deprecated
    public static void drawRuleMask(Bitmap bitmap, ViewRule rule) {
        BitmapUtils.drawRuleMask(bitmap, rule);
    }

    /** @deprecated 委托至 {@link BitmapUtils#cloneViewAsBitmap} */
    @Deprecated
    public static Bitmap cloneViewAsBitmap(View view) {
        return BitmapUtils.cloneViewAsBitmap(view);
    }

    /** @deprecated 委托至 {@link ViewUtils#buildViewNodes} */
    @Deprecated
    public static List<WeakReference<View>> buildViewNodes(View view) {
        return ViewUtils.buildViewNodes(view);
    }

    /** @deprecated 委托至 {@link ViewUtils#getLocationInWindow} */
    @Deprecated
    public static Rect getLocationInWindow(View v) {
        return ViewUtils.getLocationInWindow(v);
    }

    /** @deprecated 委托至 {@link ViewUtils#getViewKey} */
    @Deprecated
    public static String getViewKey(View view) {
        return ViewUtils.getViewKey(view);
    }

}
