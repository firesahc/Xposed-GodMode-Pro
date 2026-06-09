package com.kaisar.xposed.godmode.engine.matcher;

import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.rule.MatchSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView 适配器匹配 — 处理列表中重复出现的同类元素。
 * 通过 itemRootClass + itemPath 在 RecyclerView 的每个 item 中定位目标视图。
 */
final class RecyclerMatcher implements MatchStrategy {

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public int computeScore(View view, MatchSpec spec) {
        if (!spec.repeatable) return 0;
        if (ViewTraversal.isInRecyclerView(view)) {
            return 50;
        }
        return 0;
    }

    static List<View> findViewsInRecycler(View root, MatchSpec spec,
            ViewGroup recyclerView) {
        List<View> results = new ArrayList<>();
        if (spec.itemPath == null || spec.itemPath.length == 0) return results;
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View itemRoot = recyclerView.getChildAt(i);
            if (itemRoot != null
                    && itemRoot.getClass().getName().equals(spec.itemRootClass)) {
                View found = findViewByItemPath(itemRoot, spec.itemPath, 0);
                if (found != null) results.add(found);
            }
        }
        return results;
    }

    /**
     * 旧版 — 委托给 MatchSpec 版本。
     */
    static List<View> findViewsInRecycler(View root,
            com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec rule,
            ViewGroup recyclerView) {
        return findViewsInRecycler(root,
                rule != null ? rule.getMatchSpec() : null, recyclerView);
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
}
