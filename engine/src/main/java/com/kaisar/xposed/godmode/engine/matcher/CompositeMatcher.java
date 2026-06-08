package com.kaisar.xposed.godmode.engine.matcher;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;
import com.kaisar.xposed.godmode.engine.traversal.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.GmConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 组合匹配器 — IMatcher 的默认实现。
 * 持有所有 MatchStrategy 子策略，按优先级排序后依次评分，选取得分最高且超过阈值的匹配。
 * <p>
 * 同时实现 MatchStrategy 接口，自身也可作为策略参与组合。
 */
public final class CompositeMatcher implements IMatcher, MatchStrategy {

    private static final int STRICT_THRESHOLD = 80;
    private static final int LOOSE_THRESHOLD = 30;

    private final List<MatchStrategy> mStrategies;

    public CompositeMatcher() {
        // 按优先级排序的策略链
        MatchStrategy[] strategies = {
                new DepthMatcher(),
                new ResourceMatcher(),
                new TextMatcher(),
                new DescriptionMatcher(),
                new RecyclerMatcher(),
        };
        Arrays.sort(strategies, Comparator.comparingInt(MatchStrategy::priority).reversed());
        mStrategies = Arrays.asList(strategies);
    }

    // ---- IMatcher 实现 ----

    @Override
    public View matchView(View root, RuleMatchSpec rule) {
        if (root == null || rule == null) return null;

        boolean strictMode = false; // 由调用方通过外部检测设置，此处使用宽松模式
        int threshold = strictMode ? STRICT_THRESHOLD : LOOSE_THRESHOLD;

        // 1. 优先按 depth 路径精确定位
        if (rule.depth != null && rule.depth.length > 0) {
            View depthView = ViewTraversal.findViewByDepth(root, rule.depth);
            if (depthView != null) {
                int score = computeScore(depthView, rule);
                if (score >= threshold) return depthView;
                // 锚定深度视图的兄弟节点搜索
                ViewParent parent = depthView.getParent();
                if (parent instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) parent;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child != null && computeScore(child, rule) >= threshold) {
                            return child;
                        }
                    }
                }
            }
        }

        // 2. 非 repeatable 模式下，depth 是唯一锚定 — 不回退到 text/desc
        if (!rule.isRepeatable()) {
            return null;
        }

        // 3. repeatable 规则：按 itemPath 在 RecyclerView 中精确定位
        // 遍历整树找到 RecyclerView，对每个调用 RecyclerMatcher 按 itemPath 匹配，
        // 避免全树模糊搜索误匹配非目标视图。
        if (rule.itemPath != null && rule.itemPath.length > 0
                && rule.itemRootClass != null) {
            List<View> rvResults = new ArrayList<>();
            collectRecyclerMatches(root, rule, rvResults);
            if (!rvResults.isEmpty()) {
                View best = null;
                int bestScore = 0;
                for (View v : rvResults) {
                    int s = computeScore(v, rule);
                    if (s > bestScore) {
                        bestScore = s;
                        best = v;
                    }
                }
                if (bestScore >= threshold) return best;
            }
        }

        // 4. 无可靠匹配 — 返回 null，由调用方处理兜底
        return null;
    }

    @Override
    public List<View> matchAllViews(View root, RuleMatchSpec rule) {
        List<View> results = new ArrayList<>();
        if (root == null || rule == null) return results;
        collectMatches(root, rule, results, 0);
        return results;
    }

    /**
     * 递归遍历视图树，收集所有 RecyclerView 中按 itemPath 匹配的视图。
     * 仅用于 repeatable 规则的精确匹配，不进行模糊评分搜索。
     */
    private static void collectRecyclerMatches(View view, RuleMatchSpec rule, List<View> results) {
        if (results.size() >= GmConstants.MAX_REPEATABLE_RESULTS) return;
        if (view.getClass().getName().contains("RecyclerView")
                && view instanceof ViewGroup) {
            List<View> matched = RecyclerMatcher.findViewsInRecycler(view, rule, (ViewGroup) view);
            results.addAll(matched);
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount()
                    && results.size() < GmConstants.MAX_REPEATABLE_RESULTS; i++) {
                collectRecyclerMatches(vg.getChildAt(i), rule, results);
            }
        }
    }

    private void collectMatches(View view, RuleMatchSpec rule, List<View> results, int depth) {
        if (results.size() >= GmConstants.MAX_REPEATABLE_RESULTS) return;
        if (view.getVisibility() != View.VISIBLE
                || GmConstants.TAG_GM_CMP.equals(view.getTag())) return;

        int score = computeScore(view, rule);
        if (score >= LOOSE_THRESHOLD) {
            results.add(view);
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount()
                    && results.size() < GmConstants.MAX_REPEATABLE_RESULTS; i++) {
                collectMatches(vg.getChildAt(i), rule, results, depth + 1);
            }
        }
    }

    // ===== 匹配评分常量 =====
    private static final int SCORE_CLASS = 30;
    private static final int SCORE_PARENT = 10;

    // ---- MatchStrategy 实现 — 聚合所有子策略得分 ----

    @Override
    public int computeScore(View view, RuleMatchSpec rule) {
        int total = 0;
        // 视图类名匹配 — 最基础条件
        if (view.getClass().getName().equals(rule.viewClass)) {
            total += SCORE_CLASS;
        }
        // 父视图类名匹配
        if (rule.parentClass != null) {
            ViewParent parent = view.getParent();
            if (parent != null && parent.getClass().getName().equals(rule.parentClass)) {
                total += SCORE_PARENT;
            }
        }
        // 收集各子策略得分
        for (MatchStrategy s : mStrategies) {
            total += s.computeScore(view, rule);
        }
        return total;
    }
}
