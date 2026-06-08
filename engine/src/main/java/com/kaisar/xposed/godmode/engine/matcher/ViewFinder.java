package com.kaisar.xposed.godmode.engine.matcher;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 鐟欏棗娴橀弻銉﹀閸?閳?娴ｈ法鏁?engine 閻?RuleMatchSpec 鏉╂稖顢戠憴鍡楁禈閸栧綊鍘?閹兼粎鍌ㄩ妴?
 * <p>
 * 閼卞矁鐭楅敍姘矤瑜版挸澧?Activity 閻ㄥ嫯顫嬮崶鐐埐娑擃厽鐗撮幑?RuleMatchSpec 鐎规矮缍呴崠褰掑帳閻ㄥ嫯顫嬮崶淇扁偓?
 * 閸氬本妞傞弨顖涘瘮 {@link CompositeMatcher}閿涘潒ngine 缂佸嫬鎮庨崠褰掑帳閿涘鎷版导鐘电埠濞ｅ崬瀹?閺傚洦婀?鐠у嫭绨崥宥呭爱闁板秲鈧?
 * <p>
 * 閺囧じ鍞?{@code com.kaisar.xposed.godmode.injection.ViewHelper} 娑擃厾娈戠憴鍡楁禈閹兼粎鍌ㄩ懕宀冪煑閵?
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
    // 閸忣剙绱?API
    // =========================================================================

    /**
     * 閺嶈宓佺憴鍕灟閸栧綊鍘ょ憴鍡楁禈 閳?娴兼ê鍘涙担璺ㄦ暏 {@link CompositeMatcher}閿涘苯銇戠拹銉︽閸ョ偤鈧偓閸掗绱剁紒鐔峰爱闁板秲鈧?
     *
     * @param decorView   瑜版挸澧?Activity 閻?DecorView
     * @param rule        engine RuleMatchSpec
     * @param pm          PackageManager閿涘牏鏁ゆ禍?strict mode 濡偓閺屻儻绱?
     * @param packageName 閻╊喗鐖ｉ崠鍛倳
     * @return 閸栧綊鍘ら惃鍕潒閸ユ拝绱濋幋?null
     */
    public static View findViewBestMatch(ViewGroup decorView, RuleMatchSpec rule,
                                          PackageManager pm, String packageName) {
        // 娴兼ê鍘涚亸婵婄槸 engine 缂佸嫬鎮庨崠褰掑帳閸?
        try {
            View matched = sMatcher.matchView(decorView, rule);
            if (matched != null) return matched;
        } catch (Exception e) {
            Logger.w(TAG, "engine matcher failed, falling back to legacy: " + e.getMessage());
        }

        // 閸忔粌绨抽敍姘炊缂佺喎灏柊?
        boolean strictMode = checkStrictMode(pm, packageName, rule);

        if (rule.depth != null && rule.depth.length > 0) {
            Logger.d(TAG, "match view by depth (primary anchor)");
            View viewByDepth = ViewTraversal.findViewByDepth(decorView, rule.depth);
            if (viewByDepth != null) {
                if (isDepthMatch(viewByDepth, rule, strictMode))
                    return viewByDepth;
                View anchored = matchByAnchoredStrategy(rule, strictMode, viewByDepth);
                if (anchored != null) return anchored;
            }
        }

        // 閸楁洖鍘撶槐鐘衬佸蹇ョ窗娴犲懍淇婃禒?depth 闁挎艾鐣?
        if (!rule.isRepeatable()) {
            if (rule.depth != null && rule.depth.length > 0) {
                View view = ViewTraversal.findViewByDepth(decorView, rule.depth);
                if (view != null && verifySingleElement(view, rule)) return view;
            }
            return null;
        }

        if (!TextUtils.isEmpty(rule.resourceName)) {
            Logger.d(TAG, "match view by resource name (primary anchor)");
            View viewByRes = decorView.findViewById(getViewId(rule, decorView.getResources()));
            if (viewByRes != null) {
                View matched = matchView(viewByRes, rule, strictMode);
                if (matched != null) return matched;
            }
        }

        if (!TextUtils.isEmpty(rule.text)) {
            Logger.d(TAG, "match view by text (auxiliary)");
            View viewByText = findViewByText(decorView, rule.text);
            if (viewByText != null) {
                View matched = matchView(viewByText, rule, strictMode);
                if (matched != null) return matched;
            }
        }
        if (!TextUtils.isEmpty(rule.description)) {
            Logger.d(TAG, "match view by description (auxiliary)");
            View viewByDesc = findViewByDescription(decorView, rule.description);
            if (viewByDesc != null) {
                View matched = matchView(viewByDesc, rule, strictMode);
                if (matched != null) return matched;
            }
        }

        // 閺堚偓缂佸牆鍘规惔鏇窗娴犲懏瀵?depth
        if (rule.depth != null && rule.depth.length > 0) {
            View view = ViewTraversal.findViewByDepth(decorView, rule.depth);
            if (view != null) return matchView(view, rule, false);
        }
        return null;
    }

    /**
     * 閺屻儲澹橀幍鈧張澶婂爱闁板秶娈戠憴鍡楁禈 閳?repeatable 鐟欏嫬鍨导妯哄帥閹兼粎鍌?RecyclerView閵?
     *
     * @param decorView   瑜版挸澧?Activity 閻?DecorView
     * @param rule        engine RuleMatchSpec
     * @param pm          PackageManager閿涘牏鏁ゆ禍?strict mode 濡偓閺屻儻绱?
     * @param packageName 閻╊喗鐖ｉ崠鍛倳
     * @return 閸栧綊鍘ら惃鍕潒閸ユ儳鍨悰?
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
     * 濡偓濞村顫嬮崶鐐Ц閸氾箑婀?RecyclerView 娑擃厹鈧?
     */
    public static boolean isInRecyclerView(View v) {
        return ViewTraversal.isInRecyclerView(v);
    }

    /**
     * 閺屻儲澹橀張鈧潻?RecyclerView 缁佹牕鍘涢妴?
     */
    public static ViewGroup findRecyclerViewAncestor(View v) {
        return ViewTraversal.findRecyclerViewAncestor(v);
    }

    /**
     * 閼惧嘲褰囩憴鍡楁禈閸?RecyclerView 娑擃厾娈?itemPath閵?
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
     * 閹?itemPath 閺屻儲澹樼憴鍡楁禈閵?
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
     * 鐠囧嫬鍨庨崠褰掑帳鐟欏棗娴橀敍鍫濐啍閺夐箖妲囬崐?30閿涘奔寮楅弽鍏寄佸?80閿涘鈧?
     */
    public static View matchView(View view, RuleMatchSpec rule, boolean strictMode) {
        try {
            if (view == null || rule == null) return null;
            int score = computeMatchScore(view, rule);
            int threshold = strictMode ? 80 : 30;
            return score >= threshold ? view : null;
        } catch (Exception e) {
            Logger.w(TAG, "matchView: exception during matching", e);
        }
        return null;
    }

    /**
     * 婵夘偄鍘栭崣顖炲櫢婢跺秷顫夐崚娆庝繆閹?閳?濡偓濞村鎮撴稉鈧?itemRootClass 閸?RecyclerView 娑擃厼鍤悳?2+ 濞嗏剝妞傞弽鍥唶娑?repeatable閵?
     *
     * @param v              闁鑵戦惃鍕窗閺嶅洩顫嬮崶?
     * @param rule           瀵板懎锝為崗鍛畱鐟欏嫬鍨?
     * @param isInfoFlowMode 閺勵垰鎯佹径鍕艾娣団剝浼呭ù浣鼓佸蹇ョ礄閻㈣精鐨熼悽銊︽煙閹绘劒绶甸敍?
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
            Logger.w(TAG, "populateRepeatableInfo: failed (non-fatal)", e);
        }
    }

    // =========================================================================
    // RecyclerView 閸栧綊鍘?
    // =========================================================================

    /**
     * 閸?DecorView 娑擃厽瀵?RecyclerView 閸栧綊鍘?repeatable 鐟欏嫬鍨妴?
     *
     * @param decorView 瑜版挸澧?Activity 閻?DecorView
     * @param rule      engine RuleMatchSpec
     * @return 閸栧綊鍘ら惃鍕潒閸ユ儳鍨悰?
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
    // 娴肩姷绮洪崠褰掑帳閿涘潤egacy fallback閿?
    // =========================================================================

    private static boolean checkStrictMode(PackageManager pm, String packageName, RuleMatchSpec rule) {
        try {
            PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
            return packageInfo.versionCode == rule.matchVersionCode;
        } catch (PackageManager.NameNotFoundException e) {
            Logger.w(TAG, "Failed to get package info for strict mode check", e);
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
                    // view 閺?resource id 閳?娑撳秴灏柊?
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

    // ===== 閸栧綊鍘ょ拠鍕瀻鐢悂鍣?=====
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
                // view 閺?resource name 閳?score 娣囨繃瀵旀稉宥咁杻閸?
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
    // 閺傚洦婀?閹诲繗鍫?閺屻儲澹?
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
            Logger.w(TAG, "findViewByCondition: traversal error", e);
        }
        return null;
    }

    private interface ViewPredicate {
        boolean test(View view);
    }

    // =========================================================================
    // 瀹搞儱鍙?
    // =========================================================================

    /**
     * 閺嶈宓佺挧鍕爱閸氬秷骞忛崣鏍カ濠?ID閿涘牆鍚嬬€?engines 娓氀勬￥ R 缁崵娈戦崷鐑樻珯閿涘鈧?
     */
    private static int getViewId(RuleMatchSpec rule, Resources resources) {
        if (rule.resourceName == null || resources == null) return View.NO_ID;
        return resources.getIdentifier(rule.resourceName, "id", rule.packageName);
    }
}
