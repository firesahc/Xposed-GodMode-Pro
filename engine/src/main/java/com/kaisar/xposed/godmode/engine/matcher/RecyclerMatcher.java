package com.kaisar.xposed.godmode.engine.matcher;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView 閫傞厤鍣ㄥ尮閰?鈥?澶勭悊鍒楄〃涓噸澶嶅嚭鐜扮殑鍚岀被鍏冪礌銆?
 * 閫氳繃 itemRootClass + itemPath 鍦?RecyclerView 鐨勬瘡涓?item 涓畾浣嶇洰鏍囪鍥俱€?
 */
final class RecyclerMatcher implements MatchStrategy {

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public int computeScore(View view, RuleMatchSpec rule) {
        if (!rule.isRepeatable()) return 0;
        if (ViewTraversal.isInRecyclerView(view)) {
            return 50; // RecyclerView 涓婁笅鏂囧尮閰?
        }
        return 0;
    }

    /**
     * 鍦?RecyclerView 涓煡鎵炬墍鏈夊尮閰嶅垪琛ㄩ」涓殑鐩爣瑙嗗浘銆?
     */
    static List<View> findViewsInRecycler(View root, RuleMatchSpec rule,
            ViewGroup recyclerView) {
        List<View> results = new ArrayList<>();
        if (rule.itemPath == null || rule.itemPath.length == 0) return results;
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View itemRoot = recyclerView.getChildAt(i);
            if (itemRoot != null
                    && itemRoot.getClass().getName().equals(rule.itemRootClass)) {
                View found = findViewByItemPath(itemRoot, rule.itemPath, 0);
                if (found != null) results.add(found);
            }
        }
        return results;
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
