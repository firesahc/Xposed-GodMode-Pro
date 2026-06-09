package com.kaisar.xposed.godmode.engine.matcher;

import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.util.GmConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 复合匹配器 — IMatcher 的默认实现。
 * <p>
 * 持有 MatchStrategy 策略链（Resource/Text/Description），按优先级排序后依次评分。
 * DepthMatcher 和 RecyclerMatcher 不作为全局策略注册，避免场景加分污染全树搜索。
 * 支持运行时注册/注销策略（注册表模式），线程安全。
 * <p>
 * 默认阈值：宽松模式 30，严格模式 80。
 * 规则可通过 {@link MatchSpec#matchThreshold} 覆盖默认阈值。
 */
public final class CompositeMatcher implements IMatcher, MatchStrategy {

    /** 默认严格阈值 */
    public static final int DEFAULT_STRICT_THRESHOLD = 80;
    /** 默认宽松阈值 */
    public static final int DEFAULT_LOOSE_THRESHOLD = 30;
    /** depth 锚定加分 — 仅在 matchView depth 分支内生效 */
    private static final int SCORE_DEPTH_ANCHOR = 60;

    private final List<MatchStrategy> mStrategies;
    private int mStrictThreshold = DEFAULT_STRICT_THRESHOLD;
    private int mLooseThreshold = DEFAULT_LOOSE_THRESHOLD;

    /**
     * 使用内置默认策略链构造。
     * <p>
     * 注意：DepthMatcher 和 RecyclerMatcher 不作为全局策略注册。
     * DepthMatcher 的 +60 锚定加分仅在 matchView depth 分支内直接添加；
     * RecyclerMatcher 的 +50 场景加分同理，RecyclerView item 定位
     * 由 findViewsInRecycler 静态方法 + itemPath 导航完成。
     * 两者不注册为全局策略，避免给 matchAllViews 全树搜索带入场景加分。
     */
    public CompositeMatcher() {
        List<MatchStrategy> defaults = new ArrayList<>(4);
        defaults.add(new ResourceMatcher());
        defaults.add(new TextMatcher());
        defaults.add(new DescriptionMatcher());
        defaults.sort(Comparator.comparingInt(MatchStrategy::priority).reversed());
        mStrategies = new CopyOnWriteArrayList<>(defaults);
    }

    /**
     * 使用自定义策略列表构造。
     * 策略将按 priority() 自动排序。
     */
    public CompositeMatcher(List<MatchStrategy> strategies) {
        List<MatchStrategy> sorted = new ArrayList<>(strategies);
        sorted.sort(Comparator.comparingInt(MatchStrategy::priority).reversed());
        mStrategies = new CopyOnWriteArrayList<>(sorted);
    }

    // =========================================================================
    // 注册表模式 — 运行时管理策略
    // =========================================================================

    /**
     * 注册一个匹配策略。相同类型的策略不会被去重（允许多实例）。
     * 策略链会按 priority() 自动重新排序。
     */
    public void registerStrategy(MatchStrategy strategy) {
        if (strategy == null) return;
        mStrategies.add(strategy);
        // CopyOnWriteArrayList 不支持原地 sort，重新构建
        List<MatchStrategy> reordered = new ArrayList<>(mStrategies);
        reordered.sort(Comparator.comparingInt(MatchStrategy::priority).reversed());
        mStrategies.clear();
        mStrategies.addAll(reordered);
    }

    /**
     * 注销指定的匹配策略实例。
     *
     * @return 如果策略存在并被移除返回 true
     */
    public boolean unregisterStrategy(MatchStrategy strategy) {
        return mStrategies.remove(strategy);
    }

    /**
     * 按类型注销所有匹配策略。
     *
     * @param strategyClass 要注销的策略类型
     * @return 被移除的策略数量
     */
    public int unregisterStrategy(Class<? extends MatchStrategy> strategyClass) {
        List<MatchStrategy> toRemove = new ArrayList<>();
        for (MatchStrategy s : mStrategies) {
            if (s.getClass() == strategyClass) {
                toRemove.add(s);
            }
        }
        if (!toRemove.isEmpty()) {
            mStrategies.removeAll(toRemove);
        }
        return toRemove.size();
    }

    /** 返回当前策略链的只读视图 */
    public List<MatchStrategy> getStrategies() {
        return Collections.unmodifiableList(mStrategies);
    }

    // =========================================================================
    // 阈值配置
    // =========================================================================

    /** 设置全局严格阈值 */
    public void setStrictThreshold(int threshold) {
        mStrictThreshold = threshold;
    }

    /** 设置全局宽松阈值 */
    public void setLooseThreshold(int threshold) {
        mLooseThreshold = threshold;
    }

    /** 获取当前严格阈值 */
    public int getStrictThreshold() {
        return mStrictThreshold;
    }

    /** 获取当前宽松阈值 */
    public int getLooseThreshold() {
        return mLooseThreshold;
    }

    /**
     * 获取生效的阈值：优先使用规格中配置的 matchThreshold，
     * 其次根据 strictMode 使用全局默认值。
     */
    private int resolveThreshold(MatchSpec spec, boolean strictMode) {
        if (spec.matchThreshold > 0) {
            return spec.matchThreshold;
        }
        return strictMode ? mStrictThreshold : mLooseThreshold;
    }

    // ---- IMatcher 实现（新 API：MatchSpec） ----

    @Override
    public View matchView(View root, MatchSpec spec) {
        if (root == null || spec == null) return null;

        // =====================================================================
        // 策略链：按锚定可靠性降序尝试，第一个验证通过的返回
        // =====================================================================

        // ── 策略 1: resourceId 锚定 ──
        // 最可靠：view.getResources().getIdentifier(resourceName) 是框架级稳定 ID
        if (!TextUtils.isEmpty(spec.resourceName)) {
            View viewById = findByResourceId(root, spec);
            if (viewById != null) return viewById;
        }

        // ── 策略 2: depth 路径锚定 + sibling 兜底 ──
        // 可靠，但对布局变化敏感
        // 注意：depth 锚定加分（SCORE_DEPTH_ANCHOR=60）在此分支内直接添加，
        // 不经过策略链，避免 matchAllViews 全树搜索时错误加分。
        int threshold = resolveThreshold(spec, false);
        if (spec.depth != null && spec.depth.length > 0) {
            View depthView = ViewTraversal.findViewByDepth(root, spec.depth);
            if (depthView != null && isVisibleView(depthView)) {
                int score = computeScore(depthView, spec) + SCORE_DEPTH_ANCHOR;
                if (score >= threshold) return depthView;
            }
            if (depthView != null) {
                // 锚定视图的兄弟节点搜索 — 取最高分（跳过不可见/GM 标签）
                ViewParent parent = depthView.getParent();
                if (parent instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) parent;
                    View bestSibling = null;
                    int bestScore = 0;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child != null && child != depthView && isVisibleView(child)) {
                            int s = computeScore(child, spec) + SCORE_DEPTH_ANCHOR;
                            if (s > bestScore) {
                                bestScore = s;
                                bestSibling = child;
                            }
                        }
                    }
                    // sibling 是 depth 锚定失败后的降级策略，需更严格阈值
                    int siblingThreshold = resolveThreshold(spec, true);
                    if (bestSibling != null && bestScore >= siblingThreshold) {
                        return bestSibling;
                    }
                }
            }
        }

        // ── 策略 3: 重复规则 — RecyclerView itemPath 精确匹配 ──
        if (spec.repeatable && spec.itemPath != null && spec.itemPath.length > 0
                && spec.itemRootClass != null) {
            List<View> rvResults = new ArrayList<>();
            collectRecyclerMatches(root, spec, rvResults);
            if (!rvResults.isEmpty()) {
                View best = null;
                int bestScore = 0;
                for (View v : rvResults) {
                    int s = computeScore(v, spec);
                    if (s > bestScore) {
                        bestScore = s;
                        best = v;
                    }
                }
                if (bestScore >= threshold) return best;
            }
        }

        // ── 策略 4: matchAllViews 兜底 ──
        // 与 matchAllViews 共享完全相同的搜索路径和阈值，保证行为一致
        List<View> allMatches = matchAllViews(root, spec);
        if (!allMatches.isEmpty()) {
            // matchAllViews 默认按遍历顺序，首个即最佳候选
            return allMatches.get(0);
        }

        return null;
    }

    @Override
    public List<View> matchAllViews(View root, MatchSpec spec) {
        List<View> results = new ArrayList<>();
        if (root == null || spec == null) return results;
        int threshold = resolveThreshold(spec, false);
        collectMatches(root, spec, results, threshold);
        return results;
    }

    // ---- 内部辅助方法 ----

    /**
     * 策略 1：按 resourceName 锚定。
     * 解析 resourceName 为 int ID，通过 findViewById 精确查找，
     * 再用严格阈值评分验证防止误匹配。
     */
    private View findByResourceId(View root, MatchSpec spec) {
        if (root == null || TextUtils.isEmpty(spec.resourceName)) return null;
        try {
            int id = root.getResources().getIdentifier(spec.resourceName, "id", null);
            if (id == 0 || id == View.NO_ID) return null;
            View found = root.findViewById(id);
            if (found == null || !isVisibleView(found)) return null;
            // resourceId 锚定最可靠（Android 框架级 ID），阈值可适当放宽
            if (computeScore(found, spec) >= resolveThreshold(spec, false)) {
                return found;
            }
        } catch (Resources.NotFoundException e) {
            // resource 不属于当前 context — 跳过此策略
        }
        return null;
    }

    /**
     * 递归遍历视图树，收集 RecyclerView 中按 itemPath 匹配的视图。
     * 仅用于 repeatable 规则的精确匹配。
     */
    private static void collectRecyclerMatches(View view, MatchSpec spec, List<View> results) {
        if (results.size() >= GmConstants.MAX_REPEATABLE_RESULTS) return;
        if (view.getClass().getName().contains("RecyclerView")
                && view instanceof ViewGroup) {
            List<View> matched = RecyclerMatcher.findViewsInRecycler(view, spec, (ViewGroup) view);
            results.addAll(matched);
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount()
                    && results.size() < GmConstants.MAX_REPEATABLE_RESULTS; i++) {
                collectRecyclerMatches(vg.getChildAt(i), spec, results);
            }
        }
    }

    /** 判断视图是否可见且非 GM 自身组件 */
    private static boolean isVisibleView(View view) {
        return view.getVisibility() == View.VISIBLE
                && !GmConstants.TAG_GM_CMP.equals(view.getTag());
    }

    private void collectMatches(View view, MatchSpec spec, List<View> results, int threshold) {
        if (results.size() >= GmConstants.MAX_REPEATABLE_RESULTS) return;
        if (!isVisibleView(view)) return;

        int score = computeScore(view, spec);
        if (score >= threshold) {
            results.add(view);
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount()
                    && results.size() < GmConstants.MAX_REPEATABLE_RESULTS; i++) {
                collectMatches(vg.getChildAt(i), spec, results, threshold);
            }
        }
    }

    // ===== 匹配评分常量 =====
    private static final int SCORE_CLASS = 30;
    private static final int SCORE_PARENT = 10;

    // ---- MatchStrategy 实现 — 聚合所有子策略评分 ----

    /**
     * 聚合 viewClass/parentClass 匹配及各子策略得分。
     */
    @Override
    public int computeScore(View view, MatchSpec spec) {
        if (view == null || spec == null) return 0;
        int total = 0;
        // 视图类名匹配 — 最基础条件
        if (spec.viewClass != null && view.getClass().getName().equals(spec.viewClass)) {
            total += SCORE_CLASS;
        }
        // 父视图类名匹配
        if (spec.parentClass != null) {
            ViewParent parent = view.getParent();
            if (parent != null && parent.getClass().getName().equals(spec.parentClass)) {
                total += SCORE_PARENT;
            }
        }
        // 收集各子策略得分
        for (MatchStrategy s : mStrategies) {
            total += s.computeScore(view, spec);
        }
        return total;
    }
}
