package com.kaisar.xposed.godmode.engine.matcher;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.GmConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 缁勫悎鍖归厤鍣?鈥?IMatcher 鐨勯粯璁ゅ疄鐜般€?
 * 鎸佹湁鎵€鏈?MatchStrategy 瀛愮瓥鐣ワ紝鎸変紭鍏堢骇鎺掑簭鍚庝緷娆¤瘎鍒嗭紝閫夊彇寰楀垎鏈€楂樹笖瓒呰繃闃堝€肩殑鍖归厤銆?
 * <p>
 * 鍚屾椂瀹炵幇 MatchStrategy 鎺ュ彛锛岃嚜韬篃鍙綔涓虹瓥鐣ュ弬涓庣粍鍚堛€?
 */
public final class CompositeMatcher implements IMatcher, MatchStrategy {

    private static final int STRICT_THRESHOLD = 80;
    private static final int LOOSE_THRESHOLD = 30;

    private final List<MatchStrategy> mStrategies;

    public CompositeMatcher() {
        // 鎸変紭鍏堢骇鎺掑簭鐨勭瓥鐣ラ摼
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

    // ---- IMatcher 瀹炵幇 ----

    @Override
    public View matchView(View root, RuleMatchSpec rule) {
        if (root == null || rule == null) return null;

        boolean strictMode = false; // 鐢辫皟鐢ㄦ柟閫氳繃澶栭儴妫€娴嬭缃紝姝ゅ浣跨敤瀹芥澗妯″紡
        int threshold = strictMode ? STRICT_THRESHOLD : LOOSE_THRESHOLD;

        // 1. 浼樺厛鎸?depth 璺緞绮剧‘瀹氫綅
        if (rule.depth != null && rule.depth.length > 0) {
            View depthView = ViewTraversal.findViewByDepth(root, rule.depth);
            if (depthView != null) {
                int score = computeScore(depthView, rule);
                if (score >= threshold) return depthView;
                // 閿氬畾娣卞害瑙嗗浘鐨勫厔寮熻妭鐐规悳绱?
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

        // 2. 闈?repeatable 妯″紡涓嬶紝depth 鏄敮涓€閿氬畾 鈥?涓嶅洖閫€鍒?text/desc
        if (!rule.isRepeatable()) {
            return null;
        }

        // 3. repeatable 瑙勫垯锛氭寜 itemPath 鍦?RecyclerView 涓簿纭畾浣?
        // 閬嶅巻鏁存爲鎵惧埌 RecyclerView锛屽姣忎釜璋冪敤 RecyclerMatcher 鎸?itemPath 鍖归厤锛?
        // 閬垮厤鍏ㄦ爲妯＄硦鎼滅储璇尮閰嶉潪鐩爣瑙嗗浘銆?
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

        // 4. 鏃犲彲闈犲尮閰?鈥?杩斿洖 null锛岀敱璋冪敤鏂瑰鐞嗗厹搴?
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
     * 閫掑綊閬嶅巻瑙嗗浘鏍戯紝鏀堕泦鎵€鏈?RecyclerView 涓寜 itemPath 鍖归厤鐨勮鍥俱€?
     * 浠呯敤浜?repeatable 瑙勫垯鐨勭簿纭尮閰嶏紝涓嶈繘琛屾ā绯婅瘎鍒嗘悳绱€?
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

    // ===== 鍖归厤璇勫垎甯搁噺 =====
    private static final int SCORE_CLASS = 30;
    private static final int SCORE_PARENT = 10;

    // ---- MatchStrategy 瀹炵幇 鈥?鑱氬悎鎵€鏈夊瓙绛栫暐寰楀垎 ----

    @Override
    public int computeScore(View view, RuleMatchSpec rule) {
        int total = 0;
        // 瑙嗗浘绫诲悕鍖归厤 鈥?鏈€鍩虹鏉′欢
        if (view.getClass().getName().equals(rule.viewClass)) {
            total += SCORE_CLASS;
        }
        // 鐖惰鍥剧被鍚嶅尮閰?
        if (rule.parentClass != null) {
            ViewParent parent = view.getParent();
            if (parent != null && parent.getClass().getName().equals(rule.parentClass)) {
                total += SCORE_PARENT;
            }
        }
        // 鏀堕泦鍚勫瓙绛栫暐寰楀垎
        for (MatchStrategy s : mStrategies) {
            total += s.computeScore(view, rule);
        }
        return total;
    }
}
