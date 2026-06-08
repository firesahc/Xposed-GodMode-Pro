package com.kaisar.xposed.godmode.engine.matcher;

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
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.GmConstants;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 瑙嗗浘鏌ユ壘鍣?鈥?浣跨敤 engine 鐨?RuleMatchSpec 杩涜瑙嗗浘鍖归厤/鎼滅储銆?
 * <p>
 * 鑱岃矗锛氫粠褰撳墠 Activity 鐨勮鍥炬爲涓牴鎹?RuleMatchSpec 瀹氫綅鍖归厤鐨勮鍥俱€?
 * 鍚屾椂鏀寔 {@link CompositeMatcher}锛坋ngine 缁勫悎鍖归厤锛夊拰浼犵粺娣卞害/鏂囨湰/璧勬簮鍚嶅尮閰嶃€?
 * <p>
 * 鏇夸唬 {@code com.kaisar.xposed.godmode.injection.ViewHelper} 涓殑瑙嗗浘鎼滅储鑱岃矗銆?
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
    // 鍏紑 API
    // =========================================================================

    /**
     * 鏍规嵁瑙勫垯鍖归厤瑙嗗浘 鈥?浼樺厛浣跨敤 {@link CompositeMatcher}锛屽け璐ユ椂鍥為€€鍒颁紶缁熷尮閰嶃€?
     *
     * @param decorView   褰撳墠 Activity 鐨?DecorView
     * @param rule        engine RuleMatchSpec
     * @param pm          PackageManager锛堢敤浜?strict mode 妫€鏌ワ級
     * @param packageName 鐩爣鍖呭悕
     * @return 鍖归厤鐨勮鍥撅紝鎴?null
     */
    public static View findViewBestMatch(ViewGroup decorView, RuleMatchSpec rule,
                                          PackageManager pm, String packageName) {
        // 浼樺厛灏濊瘯 engine 缁勫悎鍖归厤鍣?
        try {
            View matched = sMatcher.matchView(decorView, rule);
            if (matched != null) return matched;
        } catch (Exception e) {
            Log.w(TAG, "engine matcher failed, falling back to legacy: " + e.getMessage());
        }

        // 鍏滃簳锛氫紶缁熷尮閰?
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

        // 鍗曞厓绱犳ā寮忥細浠呬俊浠?depth 閿氬畾
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

        // 鏈€缁堝厹搴曪細浠呮寜 depth
        if (rule.depth != null && rule.depth.length > 0) {
            View view = ViewTraversal.findViewByDepth(decorView, rule.depth);
            if (view != null) return matchView(view, rule, false);
        }
        return null;
    }

    /**
     * 鏌ユ壘鎵€鏈夊尮閰嶇殑瑙嗗浘 鈥?repeatable 瑙勫垯浼樺厛鎼滅储 RecyclerView銆?
     *
     * @param decorView   褰撳墠 Activity 鐨?DecorView
     * @param rule        engine RuleMatchSpec
     * @param pm          PackageManager锛堢敤浜?strict mode 妫€鏌ワ級
     * @param packageName 鐩爣鍖呭悕
     * @return 鍖归厤鐨勮鍥惧垪琛?
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
     * 妫€娴嬭鍥炬槸鍚﹀湪 RecyclerView 涓€?
     */
    public static boolean isInRecyclerView(View v) {
        return ViewTraversal.isInRecyclerView(v);
    }

    /**
     * 鏌ユ壘鏈€杩?RecyclerView 绁栧厛銆?
     */
    public static ViewGroup findRecyclerViewAncestor(View v) {
        return ViewTraversal.findRecyclerViewAncestor(v);
    }

    /**
     * 鑾峰彇瑙嗗浘鍦?RecyclerView 涓殑 itemPath銆?
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
     * 鎸?itemPath 鏌ユ壘瑙嗗浘銆?
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
     * 璇勫垎鍖归厤瑙嗗浘锛堝鏉鹃槇鍊?30锛屼弗鏍兼ā寮?80锛夈€?
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
     * 濉厖鍙噸澶嶈鍒欎俊鎭?鈥?妫€娴嬪悓涓€ itemRootClass 鍦?RecyclerView 涓嚭鐜?2+ 娆℃椂鏍囪涓?repeatable銆?
     *
     * @param v              閫変腑鐨勭洰鏍囪鍥?
     * @param rule           寰呭～鍏呯殑瑙勫垯
     * @param isInfoFlowMode 鏄惁澶勪簬淇℃伅娴佹ā寮忥紙鐢辫皟鐢ㄦ柟鎻愪緵锛?
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
    // RecyclerView 鍖归厤
    // =========================================================================

    /**
     * 鍦?DecorView 涓寜 RecyclerView 鍖归厤 repeatable 瑙勫垯銆?
     *
     * @param decorView 褰撳墠 Activity 鐨?DecorView
     * @param rule      engine RuleMatchSpec
     * @return 鍖归厤鐨勮鍥惧垪琛?
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
    // 浼犵粺鍖归厤锛坙egacy fallback锛?
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
                } catch (Resources.NotFoundException e) {
                    // view 鏃?resource id 鈥?涓嶅尮閰?
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

    // ===== 鍖归厤璇勫垎甯搁噺 =====
    private static final int MATCH_CLASS = 30;
    private static final int MATCH_RESOURCE = 25;
    private static final int MATCH_TEXT = 20;
    private static final int MATCH_DESC = 15;
    private static final int MATCH_PARENT = 10;
    private static final int MATCH_THRESHOLD = 80;

    private static int computeMatchScore(View view, RuleMatchSpec rule) {
        int score = 0;
        if (view.getClass().getName().equals(rule.viewClass)) score += MATCH_CLASS;
        if (!TextUtils.isEmpty(rule.resourceName)) {
            try {
                String resName = view.getResources().getResourceName(view.getId());
                if (TextUtils.equals(resName, rule.resourceName)) score += MATCH_RESOURCE;
            } catch (Resources.NotFoundException e) {
                // view 鏃?resource name 鈥?score 淇濇寔涓嶅鍔?
            }
        }
        if (!TextUtils.isEmpty(rule.text) && view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            if (t != null && TextUtils.equals(t.toString(), rule.text)) score += MATCH_TEXT;
        }
        if (!TextUtils.isEmpty(rule.description)) {
            CharSequence desc = view.getContentDescription();
            if (desc != null && TextUtils.equals(desc.toString(), rule.description)) score += MATCH_DESC;
        }
        if (!TextUtils.isEmpty(rule.parentClass)) {
            ViewParent parent = view.getParent();
            if (parent != null && parent.getClass().getName().equals(rule.parentClass)) score += MATCH_PARENT;
        }
        return score;
    }

    private static boolean verifySingleElement(View view, RuleMatchSpec rule) {
        return computeMatchScore(view, rule) >= MATCH_THRESHOLD;
    }

    // =========================================================================
    // 鏂囨湰/鎻忚堪 鏌ユ壘
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
    // 宸ュ叿
    // =========================================================================

    /**
     * 鏍规嵁璧勬簮鍚嶈幏鍙栬祫婧?ID锛堝吋瀹?engines 渚ф棤 R 绫荤殑鍦烘櫙锛夈€?
     */
    private static int getViewId(RuleMatchSpec rule, Resources resources) {
        if (rule.resourceName == null || resources == null) return View.NO_ID;
        return resources.getIdentifier(rule.resourceName, "id", rule.packageName);
    }
}
