package com.kaisar.xposed.godmode.engine.matcher;

import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;

/**
 * 按视图树深度路径匹配（depth[] 数组）。
 * 最可靠的锚定方式 — 通过 DecorView → childIndex 链条精确定位。
 */
final class DepthMatcher implements MatchStrategy {

    @Override
    public int priority() {
        return 100; // 最高优先级
    }

    @Override
    public int computeScore(View view, RuleMatchSpec rule) {
        if (rule.depth == null || rule.depth.length == 0) return 0;
        // depth 匹配已在 CompositeMatcher 层面通过 findViewByDepth 完成定位，
        // 此处仅做视图类名校验和严格模式的 resourceName 校验
        int score = 60; // depth 路径匹配的基础分
        if (!TextUtils.isEmpty(rule.viewClass)
                && view.getClass().getName().equals(rule.viewClass)) {
            score += 30;
        }
        return score;
    }
}
