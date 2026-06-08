package com.kaisar.xposed.godmode.engine.matcher;

import android.app.Activity; // kept for @Deprecated backward-compat methods only
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;
import com.kaisar.xposed.godmode.engine.traversal.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.GmConstants;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 视图查找器 — 使用 engine 的 RuleMatchSpec 进行视图匹配/搜索。
 * <p>
 * 职责：从当前 Activity 的视图树中根据 RuleMatchSpec 定位匹配的视图。
 * 同时支持 {@link CompositeMatcher}（engine 组合匹配）和传统深度/文本/资源名匹配。
 * <p>
 * 替代 {@code com.kaisar.xposed.godmode.injection.ViewHelper} 中的视图搜索职责。
 */
public final class ViewFinder {

    private static final String TAG = "ViewFinder";
    private static final int MAX_REPEATABLE_RESULTS = GmConstants.MAX_REPEATABLE_RESULTS;

    private static final Map<ViewGroup, List<WeakReference<ViewGroup>>> sRecyclerViewCache
            = new WeakHashMap<>();

    private static final CompositeMatcher sMatcher = new CompositeMatcher();

    private ViewFinder() {
    }

    // =========================================================================
    // 公开 API
    // =========================================================================

    /**
     * 根据规则匹配视图 — 优先使用 {@link CompositeMatcher}，失败时回退到传统匹配。
     *
     * @param decorView   当前 Activity 的 DecorView
     * @param rule        engine RuleMatchSpec
     * @param pm          PackageManager（用于 strict mode 检查）
     * @param packageName 目标包名
     * @return 匹配的视图，或 null
     */
    public static View findViewBestMatch(ViewGroup decorView, RuleMatchSpec rule,
                                          PackageManager pm, String packageName) {
        // 优先尝试 engine 组合匹配器
        try {
            View matched = sMatcher.matchView(decorView, rule);
            if (matched != null) return matched;
        } catch (Exception e) {
            Log.w(TAG, "engine matcher failed, falling back to legacy: " + e.getMessage());
        }

        // 兜底：传统匹配
        boolean strictMode = checkStrictMode(pm, packageName, rule);

        if (rule.depth != null && rule.depth.length > 0) {
            Log.d(TAG, "match view by depth (primary anchor)");
            View viewByDepth = ViewTraversal.findViewByDepth(decorView, rule.depth);
            if (viewByDepth != null) {
                if (isDepthMatch(viewByDepth, rule, strictMode))
                    return viewByDepth;
                View anchored = matchByAnchoredStrategy(rule, strictMode, viewByDepth);
                if (anchored != null) return anchored;
            }
        }

        // 单元素模式：仅信任 depth 锚定
        if (!rule.isRepeatable()) {
            if (rule.depth != null && rule.depth.length > 0) {
                View view = ViewTraversal.findViewByDepth(decorView, rule.depth);
                if (view != null && verifySingleElement(view, rule)) return view;
            }
            return null;
        }

        if (!TextUtils.isEmpty(rule.resourceName)) {
            Log.d(TAG, "match view by resource name (primary anchor)");
            View viewByRes = decorView.findViewById(getViewId(rule, decorView.getResources()));
            if (viewByRes != null) {
                View matched = matchView(viewByRes, rule, strictMode);
                if (matched != null) return matched;
            }
        }

        if (!TextUtils.isEmpty(rule.text)) {
            Log.d(TAG, "match view by text (auxiliary)");
            View viewByText = findViewByText(decorView, rule.text);
            if (viewByText != null) {
                View matched = matchView(viewByText, rule, strictMode);
                if (matched != null) return matched;
            }
        }
        if (!TextUtils.isEmpty(rule.description)) {
            Log.d(TAG, "match view by description (auxiliary)");
            View viewByDesc = findViewByDescription(decorView, rule.description);
            if (viewByDesc != null) {
                View matched = matchView(viewByDesc, rule, strictMode);
                if (matched != null) return matched;
            }
        }

        // 最终兜底：仅按 depth
        if (rule.depth != null && rule.depth.length > 0) {
            View view = ViewTraversal.findViewByDepth(decorView, rule.depth);
            if (view != null) return matchView(view, rule, false);
        }
        return null;
    }

    /**
     * 根据规则匹配视图 — 优先使用 {@link CompositeMatcher}，失败时回退到传统匹配。
     *
     * @deprecated 使用 {@link #findViewBestMatch(ViewGroup, RuleMatchSpec, PackageManager, String)}
     */
    @Deprecated
    public static View findViewBestMatch(Activity activity, RuleMatchSpec rule) {
        if (activity == null || activity.getWindow() == null) return null;
        return findViewBestMatch((ViewGroup) activity.getWindow().getDecorView(), rule,
                activity.getPackageManager(), activity.getPackageName());
    }

    /**
     * 查找所有匹配的视图 — repeatable 规则优先搜索 RecyclerView。
     *
     * @param decorView   当前 Activity 的 DecorView
     * @param rule        engine RuleMatchSpec
     * @param pm          PackageManager（用于 strict mode 检查）
     * @param packageName 目标包名
     * @return 匹配的视图列表
     */
    public static List<View> findAllViewsBestMatch(ViewGroup decorView, RuleMatchSpec rule,
                                                    PackageManager pm, String packageName) {
        if (rule.isRepeatable()) {
            List<View> results = findViewsInRecyclers(decorView, rule);
            if (!results.isEmpty()) return results;
        }
        View single = findViewBestMatch(decorView, rule, pm, packageName);
        if (single != null) {
            List<View> list = new ArrayList<>();
            list.add(single);
            return list;
        }
        return Collections.emptyList();
    }

    /**
     * 查找所有匹配的视图 — repeatable 规则优先搜索 RecyclerView。
     *
     * @deprecated 使用 {@link #findAllViewsBestMatch(ViewGroup, RuleMatchSpec, PackageManager, String)}
     */
    @Deprecated
    public static List<View> findAllViewsBestMatch(Activity activity, RuleMatchSpec rule) {
        if (activity == null || activity.getWindow() == null) return Collections.emptyList();
        return findAllViewsBestMatch((ViewGroup) activity.getWindow().getDecorView(), rule,
                activity.getPackageManager(), activity.getPackageName());
    }

    /**
     * 检测视图是否在 RecyclerView 中。
     */
    public static boolean isInRecyclerView(View v) {
        return ViewTraversal.isInRecyclerView(v);
    }

    /**
     * 查找最近 RecyclerView 祖先。
     */
    public static ViewGroup findRecyclerViewAncestor(View v) {
        return ViewTraversal.findRecyclerViewAncestor(v);
    }

    /**
     * 获取视图在 RecyclerView 中的 itemPath。
     */
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

    /**
     * 按 itemPath 查找视图。
     */
    public static View findViewByItemPath(View root, String[] path, int index) {
        if (index >= path.length) return root;
        String entry = path[index];
        int colonPos = entry.indexOf(':');
        if (colonPos < 0) return null;
        int childIdx;
        try {
            childIdx = Integer.parseInt(entry.substring(0, colonPos));
        } catch (NumberFormatException e) {
            return null;
        }
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

    /**
     * 评分匹配视图（宽松阈值 30，严格模式 80）。
     */
    public static View matchView(View view, RuleMatchSpec rule, boolean strictMode) {
        try {
            if (view == null || rule == null) return null;
            int score = computeMatchScore(view, rule);
            int threshold = strictMode ? 80 : 30;
            return score >= threshold ? view : null;
        } catch (Exception e) {
            Log.w(TAG, "matchView: exception during matching", e);
        }
        return null;
    }

    /**
     * 填充可重复规则信息 — 检测同一 itemRootClass 在 RecyclerView 中出现 2+ 次时标记为 repeatable。
     *
     * @param v              选中的目标视图
     * @param rule           待填充的规则
     * @param isInfoFlowMode 是否处于信息流模式（由调用方提供）
     */
    public static void populateRepeatableInfo(View v, RuleMatchSpec rule, boolean isInfoFlowMode) {
        try {
            if (!isInfoFlowMode) return;
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
            Log.w(TAG, "populateRepeatableInfo: failed (non-fatal)", e);
        }
    }

    // =========================================================================
    // RecyclerView 匹配
    // =========================================================================

    /**
     * 在 DecorView 中按 RecyclerView 匹配 repeatable 规则。
     *
     * @param decorView 当前 Activity 的 DecorView
     * @param rule      engine RuleMatchSpec
     * @return 匹配的视图列表
     */
    public static List<View> findViewsInRecyclers(ViewGroup decorView, RuleMatchSpec rule) {
        List<View> results = new ArrayList<>();
        List<WeakReference<ViewGroup>> cached = sRecyclerViewCache.get(decorView);
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
        collectRecyclerViewMatches(decorView, rule, results, foundRecyclers);
        List<WeakReference<ViewGroup>> cacheEntry = new ArrayList<>();
        for (ViewGroup rv : foundRecyclers) cacheEntry.add(new WeakReference<>(rv));
        sRecyclerViewCache.put(decorView, cacheEntry);
        return results;
    }

    /**
     * 在 Activity 中按 RecyclerView 匹配 repeatable 规则。
     *
     * @deprecated 使用 {@link #findViewsInRecyclers(ViewGroup, RuleMatchSpec)}
     */
    @Deprecated
    public static List<View> findViewsInRecyclers(Activity activity, RuleMatchSpec rule) {
        if (activity == null || activity.getWindow() == null) return Collections.emptyList();
        return findViewsInRecyclers((ViewGroup) activity.getWindow().getDecorView(), rule);
    }

    private static void collectRecyclerViewMatches(ViewGroup parent, RuleMatchSpec rule,
            List<View> results, List<ViewGroup> foundRecyclers) {
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

    private static void scanRecyclerViewItems(ViewGroup recyclerView, RuleMatchSpec rule,
            List<View> results) {
        for (int i = 0; i < recyclerView.getChildCount()
                && results.size() < MAX_REPEATABLE_RESULTS; i++) {
            View itemRoot = recyclerView.getChildAt(i);
            if (itemRoot != null && itemRoot.getClass().getName().equals(rule.itemRootClass)) {
                if (rule.itemPath != null && rule.itemPath.length > 0) {
                    collectViewsByItemPath(itemRoot, rule.itemPath, 0, rule, results);
                }
            }
        }
    }

    private static void collectViewsByItemPath(View parent, String[] itemPath, int index,
            RuleMatchSpec rule, List<View> results) {
        if (results.size() >= MAX_REPEATABLE_RESULTS) return;
        String entry = itemPath[index];
        int colonPos = entry.indexOf(':');
        if (colonPos < 0) return;
        int childIdx;
        try {
            childIdx = Integer.parseInt(entry.substring(0, colonPos));
        } catch (NumberFormatException e) {
            return;
        }
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

    // =========================================================================
    // 传统匹配（legacy fallback）
    // =========================================================================

    private static boolean checkStrictMode(PackageManager pm, String packageName, RuleMatchSpec rule) {
        try {
            PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
            return packageInfo.versionCode == rule.matchVersionCode;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to get package info for strict mode check", e);
        }
        return false;
    }

    private static boolean isDepthMatch(View view, RuleMatchSpec rule, boolean strictMode) {
        try {
            String viewClass = view.getClass().getName();
            if (!TextUtils.isEmpty(rule.viewClass)
                    && !TextUtils.equals(viewClass, rule.viewClass))
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

    private static View matchByAnchoredStrategy(RuleMatchSpec rule, boolean strictMode,
            View depthView) {
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

    private static int computeMatchScore(View view, RuleMatchSpec rule) {
        int score = 0;
        if (view.getClass().getName().equals(rule.viewClass)) score += 30;
        if (!TextUtils.isEmpty(rule.resourceName)) {
            try {
                String resName = view.getResources().getResourceName(view.getId());
                if (TextUtils.equals(resName, rule.resourceName)) score += 25;
            } catch (Resources.NotFoundException ignored) {
            }
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

    private static boolean verifySingleElement(View view, RuleMatchSpec rule) {
        return computeMatchScore(view, rule) >= 80;
    }

    // =========================================================================
    // 文本/描述 查找
    // =========================================================================

    private static View findViewByText(View view, String text) {
        return findViewByCondition(view,
                v -> v instanceof TextView && TextUtils.equals(((TextView) v).getText(), text));
    }

    private static View findViewByDescription(View view, String description) {
        return findViewByCondition(view,
                v -> TextUtils.equals(v.getContentDescription(), description));
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
            Log.w(TAG, "findViewByCondition: traversal error", e);
        }
        return null;
    }

    private interface ViewPredicate {
        boolean test(View view);
    }

    // =========================================================================
    // 工具
    // =========================================================================

    /**
     * 根据资源名获取资源 ID（兼容 engines 侧无 R 类的场景）。
     */
    private static int getViewId(RuleMatchSpec rule, Resources resources) {
        if (rule.resourceName == null || resources == null) return View.NO_ID;
        return resources.getIdentifier(rule.resourceName, "id", rule.packageName);
    }
}
