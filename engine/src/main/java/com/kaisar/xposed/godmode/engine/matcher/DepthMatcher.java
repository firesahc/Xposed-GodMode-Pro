package com.kaisar.xposed.godmode.engine.matcher;

import android.text.TextUtils;
import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.MatchSpec;

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
    public int computeScore(View view, MatchSpec spec) {
        if (spec.depth == null || spec.depth.length == 0) return 0;
        int score = 60; // depth 路径匹配的基础分
        if (!TextUtils.isEmpty(spec.viewClass)
                && view.getClass().getName().equals(spec.viewClass)) {
            score += 30;
        }
        return score;
    }
}
