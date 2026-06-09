package com.kaisar.xposed.godmode.engine.matcher;

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
        // depth 路径本身已是最强锚定信号，基础分 60
        // viewClass 评分由 CompositeMatcher.computeScore 统一处理，避免重复计分
        return 60;
    }
}
