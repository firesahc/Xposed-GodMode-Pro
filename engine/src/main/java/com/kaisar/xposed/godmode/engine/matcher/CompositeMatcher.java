package com.kaisar.xposed.godmode.engine.matcher;

import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.kaisar.xposed.godmode.engine.rule.MatchFields;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.TextMatcher;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 复合匹配器 — Matcher 的默认实现。
 * <p>
 * 使用 AND 布尔匹配（{@link #isStructuralMatch}），所有非空字段必须全部匹配。
 * 已移除评分体系（computeScore / 阈值 / MatchStrategy 注册表）。
 * <p>
 * 匹配流程：
 * <ul>
 *   <li>{@link #matchView} — resourceId 锚定 → depth 路径锚定 → isStructuralMatch 验证</li>
 *   <li>{@link #matchAllViews} / {@link #matchAllViewsBatch} — 根据 targetLevel：</li>
 *   <ul>
 *     <li>{@link TargetLevel#CARD} — 跳过已隐藏的卡片根，导航 itemPath + isStructuralMatch 验证（同 ELEMENT 精度），匹配通过后返回卡片根视图</li>
 *     <li>{@link TargetLevel#ELEMENT} — itemPath 导航 + classChain 回退 + isStructuralMatch 验证</li>
 *   </ul>
 * </ul>
 */
public final class CompositeMatcher implements Matcher {

    public CompositeMatcher() {
    }

    // RecyclerView 收集缓存——同一 DecorView 在短时间内避免重复全树遍历。
    // WeakReference 持有 root，Activity 重建或 GC 后自动失效。
    private WeakReference<View> mCachedRoot;
    private final List<WeakReference<ViewGroup>> mCachedRecyclerViews = new ArrayList<>();

    // ---- Matcher 实现 ----

    @Override
    public View matchView(View root, MatchFields spec) {
        if (root == null || spec == null) return null;

        // 非 repeatable 规则必须有可靠锚定（resourceId/depth）
        // ── 策略 1: resourceId 锚定 ──
        if (!TextUtils.isEmpty(spec.getResourceName())) {
            View viewById = findByResourceId(root, spec);
            if (viewById != null && isStructuralMatch(viewById, spec, true)) {
                return viewById;
            }
        }

        // ── 策略 2: depth 路径锚定 ──
        if (spec.getDepth() != null && spec.getDepth().length > 0) {
            View depthView = ViewTraversal.findViewByDepth(root, spec.getDepth());
            if (depthView != null && isVisibleView(depthView)
                    && isStructuralMatch(depthView, spec, true)) {
                return depthView;
            }
        }

        return null;
    }

    @Override
    public List<View> matchAllViews(View root, MatchFields spec) {
        if (root == null || spec == null) return new ArrayList<>();

        // 信息流规则：itemPath 导航 + AND 验证，无全树兜底
        List<View> results = new ArrayList<>();
        if (spec.isRepeatable() && spec.getItemPath() != null && spec.getItemPath().length > 0
                && spec.getItemRootClass() != null) {
            collectRecyclerMatches(root, spec, results);
            if (!results.isEmpty()) return results;
        }

        return results;
    }

    @Override
    public Map<Integer, List<View>> matchAllViewsBatch(View root, List<? extends MatchFields> specs) {
        Map<Integer, List<View>> results = new HashMap<>();
        if (root == null || specs == null || specs.isEmpty()) return results;

        // 初始化结果映射：只包含符合条件的 repeatable 规则
        int eligibleCount = 0;
        for (int i = 0; i < specs.size(); i++) {
            MatchFields spec = specs.get(i);
            if (spec != null && spec.isRepeatable() && spec.getItemPath() != null
                    && spec.getItemPath().length > 0 && spec.getItemRootClass() != null) {
                results.put(i, new ArrayList<>());
                eligibleCount++;
            }
        }
        if (eligibleCount == 0) return results;

        // 单次视图树遍历，收集所有 RecyclerView（使用缓存避免同一 DecorView 重复遍历）
        List<ViewGroup> recyclerViews = getCachedRecyclerViews(root);

        // 对每个 RecyclerView 的子项批量匹配所有规格
        for (ViewGroup rv : recyclerViews) {
            for (int i = 0; i < rv.getChildCount(); i++) {
                View itemRoot = rv.getChildAt(i);
                if (itemRoot == null) continue;

                // 所有规格共一次 itemRoot 遍历
                for (Map.Entry<Integer, List<View>> entry : results.entrySet()) {
                    List<View> partial = entry.getValue();
                    if (partial.size() >= GmConstants.MAX_REPEATABLE_RESULTS) continue;

                    MatchFields spec = specs.get(entry.getKey());
                    if (!itemRoot.getClass().getName().equals(spec.getItemRootClass())) continue;

                    Integer expectedViewType = spec.getInfoFlowViewType() > 0
                            ? spec.getInfoFlowViewType() : null;
                    if (expectedViewType != null
                            && !checkViewType(rv, itemRoot, expectedViewType)) {
                        continue;
                    }

                    if (spec.getTargetLevel() == TargetLevel.CARD) {
                        // CARD 模式：跳过已隐藏的卡片根（防止级联重应用）
                        if (itemRoot.getVisibility() != View.VISIBLE) continue;
                    }
                    View found = matchSingleItem(itemRoot, spec);
                    if (found != null && !partial.contains(found)) {
                        partial.add(found);
                    }
                }
            }
        }

        return results;
    }

    // ---- 内部辅助方法 ----

    /**
     * 获取 DecorView 下的 RecyclerView 列表——同一 DecorView 复用缓存避免重复遍历。
     * 缓存条目为弱引用：已 detach 的 RecyclerView 由 GC 自然回收，无强引用泄漏；
     * 命中时惰性剔除已回收或已脱离窗口的实例（比等待 GC 更及时，
     * 避免 L2 扫描对 detached 视图做无效匹配）。
     */
    private List<ViewGroup> getCachedRecyclerViews(View root) {
        if (mCachedRoot != null && mCachedRoot.get() == root
                && !mCachedRecyclerViews.isEmpty()) {
            List<ViewGroup> live = new ArrayList<>(mCachedRecyclerViews.size());
            boolean stale = false;
            for (WeakReference<ViewGroup> ref : mCachedRecyclerViews) {
                ViewGroup rv = ref.get();
                if (rv == null || !rv.isAttachedToWindow()) {
                    stale = true;
                    continue;
                }
                live.add(rv);
            }
            if (stale) {
                mCachedRecyclerViews.removeIf(ref -> ref.get() == null
                        || !ref.get().isAttachedToWindow());
            }
            if (!live.isEmpty()) return live;
        }
        List<ViewGroup> result = new ArrayList<>();
        collectRecyclerViews(root, result);
        mCachedRoot = new WeakReference<>(root);
        mCachedRecyclerViews.clear();
        for (ViewGroup rv : result) {
            mCachedRecyclerViews.add(new WeakReference<>(rv));
        }
        return result;
    }

    /**
     * 清除 RecyclerView 收集缓存。
     * 应在 position 语义破坏型事件（Adapter 整体失效）时调用；
     * 普通布局事件经弱引用自然容错，无需显式失效。
     */
    public void invalidateRecyclerCache() {
        mCachedRoot = null;
        mCachedRecyclerViews.clear();
    }

    /**
     * 按 resourceName 锚定：解析为 int ID，通过 findViewById 精确查找。
     * 不包含评分验证，由调用方负责 isStructuralMatch 检查。
     */
    private static View findByResourceId(View root, MatchFields spec) {
        if (root == null || TextUtils.isEmpty(spec.getResourceName())) return null;
        try {
            int id = root.getResources().getIdentifier(spec.getResourceName(), "id", null);
            if (id == 0 || id == View.NO_ID) return null;
            View found = root.findViewById(id);
            if (found == null || !isVisibleView(found)) return null;
            return found;
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    /** 单次递归遍历，收集视图树中所有 RecyclerView（不递归进入 RecyclerView 内部） */
    private static void collectRecyclerViews(View view, List<ViewGroup> results) {
        if (view.getClass().getName().contains("RecyclerView") && view instanceof ViewGroup) {
            results.add((ViewGroup) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectRecyclerViews(vg.getChildAt(i), results);
            }
        }
    }

    /**
     * 递归遍历视图树，收集 RecyclerView 中按 itemPath 导航 + AND 验证匹配的视图。
     * 无 collectMatches 全树兜底。
     */
    private static void collectRecyclerMatches(View view, MatchFields spec, List<View> results) {
        if (results.size() >= GmConstants.MAX_REPEATABLE_RESULTS) return;
        boolean isRecyclerView = view.getClass().getName().contains("RecyclerView")
                && view instanceof ViewGroup;
        if (isRecyclerView) {
            ViewGroup rv = (ViewGroup) view;

            Integer expectedViewType = spec.getInfoFlowViewType() > 0 ? spec.getInfoFlowViewType() : null;

            for (int i = 0; i < rv.getChildCount()
                    && results.size() < GmConstants.MAX_REPEATABLE_RESULTS; i++) {
                View itemRoot = rv.getChildAt(i);
                if (itemRoot == null) continue;
                if (!itemRoot.getClass().getName().equals(spec.getItemRootClass())) continue;

                // viewType 检查（反射避免 RecyclerView 编译期依赖）
                if (expectedViewType != null
                        && !checkViewType(rv, itemRoot, expectedViewType)) {
                    continue;
                }

                if (spec.getTargetLevel() == TargetLevel.CARD) {
                    // CARD 模式：跳过已隐藏的卡片根（防止级联重应用）
                    if (itemRoot.getVisibility() != View.VISIBLE) continue;
                }
                View found = matchSingleItem(itemRoot, spec);
                if (found != null && !results.contains(found)) {
                    results.add(found);
                }
            }
            return;
        }

        // 非 RecyclerView → 继续递归
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount()
                    && results.size() < GmConstants.MAX_REPEATABLE_RESULTS; i++) {
                collectRecyclerMatches(vg.getChildAt(i), spec, results);
            }
        }
    }

    /** viewType 检查（反射避免 RecyclerView 编译期依赖）；缓存 Method 避免反复反射 */
    private static final Map<Class<?>, java.lang.reflect.Method> sGetAdapterCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, java.lang.reflect.Method> sGetChildAdapterPositionCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, java.lang.reflect.Method> sGetItemViewTypeCache = new ConcurrentHashMap<>();

    private static boolean checkViewType(ViewGroup rv, View itemRoot, int expectedViewType) {
        try {
            Class<?> rvClass = rv.getClass();
            java.lang.reflect.Method getAdapter = sGetAdapterCache.get(rvClass);
            if (getAdapter == null) {
                getAdapter = rvClass.getMethod("getAdapter");
                sGetAdapterCache.put(rvClass, getAdapter);
            }
            Object adapter = getAdapter.invoke(rv);
            if (adapter != null) {
                java.lang.reflect.Method getChildAdapterPosition = sGetChildAdapterPositionCache.get(rvClass);
                if (getChildAdapterPosition == null) {
                    getChildAdapterPosition = rvClass.getMethod("getChildAdapterPosition", View.class);
                    sGetChildAdapterPositionCache.put(rvClass, getChildAdapterPosition);
                }
                int pos = (int) getChildAdapterPosition.invoke(rv, itemRoot);
                if (pos >= 0) {
                    Class<?> adapterClass = adapter.getClass();
                    java.lang.reflect.Method getItemViewType = sGetItemViewTypeCache.get(adapterClass);
                    if (getItemViewType == null) {
                        getItemViewType = adapterClass.getMethod("getItemViewType", int.class);
                        sGetItemViewTypeCache.put(adapterClass, getItemViewType);
                    }
                    int viewType = (int) getItemViewType.invoke(adapter, pos);
                    return viewType == expectedViewType;
                }
            }
        } catch (Exception e) {
            Logger.d("CompositeMatcher", "checkViewType reflection failed", e);
        }
        return false;
    }

    /** 判断视图是否可见且非 GM 自身组件 */
    private static boolean isVisibleView(View view) {
        return view.getVisibility() == View.VISIBLE
                && !GmConstants.TAG_GM_CMP.equals(view.getTag());
    }

    // =========================================================================
    // matchSingleItem — CARD/ELEMENT 统一导航+验证管线
    // =========================================================================

    /**
     * 对单个 itemRoot 执行 itemPath 导航 + AND 布尔验证。
     * <p>
     * CARD 和 ELEMENT 模式使用完全相同的管线：
     * <ol>
     *   <li>精确索引 + 类名导航</li>
     *   <li>失败 → 纯类名链回退</li>
     *   <li>成功 → isStructuralMatch 验证</li>
     * </ol>
     * <p>
     * 调用方负责 CARD 模式的可见性检查和结果去重。
     *
     * @param itemRoot RecyclerView item 根 View
     * @param spec     匹配规格
     * @return 验证通过的目标 View，导航失败或验证失败返回 null
     */
    private static View matchSingleItem(View itemRoot, MatchFields spec) {
        View found = ViewTraversal.findViewByItemPath(itemRoot, spec.getItemPath(), 0);
        if (found == null) {
            found = ViewTraversal.findViewByClassChain(itemRoot, spec.getItemPath(), 0);
        }
        if (found != null && isStructuralMatch(found, spec, false)) {
            return found;
        }
        return null;
    }

    // =========================================================================
    // isStructuralMatch — AND 布尔匹配（最终验证）
    // =========================================================================

    /**
     * AND 布尔匹配：MatchFields 中所有非空字段必须全部匹配。
     *
     * @param strictParent true=单元素模式，parentClass 也必须匹配；
     *                     false=信息流模式，parentClass 提供但不强制
     */
    public static boolean isStructuralMatch(View view, MatchFields spec, boolean strictParent) {
        if (view == null || spec == null) return false;

        // ── viewClass ──
        if (hasContent(spec.getViewClass())
                && !view.getClass().getName().equals(spec.getViewClass())) {
            return false;
        }

        // Repeatable targets are located structurally. Their captured text is
        // retained for wire/UI compatibility but must not prevent cross-card matching.
        boolean structuralRepeatable = spec.isRepeatable() && spec.getItemPath() != null
                && spec.getItemPath().length > 0;

        // ── text ──
        if (!structuralRepeatable && hasContent(spec.getText())) {
            if (!(view instanceof TextView)) return false;
            CharSequence t = ((TextView) view).getText();
            if (t == null) return false;
            if (!TextMatcher.matchText(t.toString(), spec.getText(), spec.getMatchMode())) {
                return false;
            }
        }

        // ── description ──
        if (!structuralRepeatable && hasContent(spec.getDescription())) {
            CharSequence d = view.getContentDescription();
            if (d == null) return false;
            if (!TextMatcher.matchText(d.toString(), spec.getDescription(), spec.getMatchMode())) {
                return false;
            }
        }

        // ── parentClass（条件检） ──
        if (hasContent(spec.getParentClass()) && strictParent) {
            ViewParent p = view.getParent();
            if (!(p instanceof View)
                    || !p.getClass().getName().equals(spec.getParentClass())) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasContent(String s) {
        return s != null && !s.isEmpty();
    }

}
