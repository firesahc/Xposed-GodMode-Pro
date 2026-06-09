package com.kaisar.xposed.godmode.engine.matcher;

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
 * 持有 MatchStrategy 策略链，按优先级排序后依次评分，选取得分最高且超过阈值的匹配。
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

    private final List<MatchStrategy> mStrategies;
    private int mStrictThreshold = DEFAULT_STRICT_THRESHOLD;
    private int mLooseThreshold = DEFAULT_LOOSE_THRESHOLD;

    /**
     * 使用内置默认策略链构造。
     * 策略按优先级降序排列：Depth > Resource > Text > Description > Recycler
     */
    public CompositeMatcher() {
        List<MatchStrategy> defaults = new ArrayList<>(5);
        defaults.add(new DepthMatcher());
        defaults.add(new ResourceMatcher());
        defaults.add(new TextMatcher());
        defaults.add(new DescriptionMatcher());
        defaults.add(new RecyclerMatcher());
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
        int removed = 0;
        for (MatchStrategy s : new ArrayList<>(mStrategies)) {
            if (s.getClass() == strategyClass) {
                mStrategies.remove(s);
                removed++;
            }
        }
        return removed;
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

        boolean strictMode = false; // 默认宽松模式
        int threshold = resolveThreshold(spec, strictMode);

        // 1. depth 路径精确锚定
        if (spec.depth != null && spec.depth.length > 0) {
            View depthView = ViewTraversal.findViewByDepth(root, spec.depth);
            if (depthView != null) {
                int score = computeScore(depthView, spec);
                if (score >= threshold) return depthView;
                // 锚定视图的兄弟节点搜索
                ViewParent parent = depthView.getParent();
                if (parent instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) parent;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child != null && computeScore(child, spec) >= threshold) {
                            return child;
                        }
                    }
                }
            }
        }

        // 2. 非 repeatable 模式：depth 是唯一锚定，不退回 text/desc
        if (!spec.repeatable) {
            return null;
        }

        // 3. repeatable 规则：在 RecyclerView 中按 itemPath 精确匹配
        if (spec.itemPath != null && spec.itemPath.length > 0
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

    private void collectMatches(View view, MatchSpec spec, List<View> results, int threshold) {
        if (results.size() >= GmConstants.MAX_REPEATABLE_RESULTS) return;
        if (view.getVisibility() != View.VISIBLE
                || GmConstants.TAG_GM_CMP.equals(view.getTag())) return;

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
