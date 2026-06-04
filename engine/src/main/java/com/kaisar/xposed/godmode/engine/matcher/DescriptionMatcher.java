package com.kaisar.xposed.godmode.engine.matcher;

import android.text.TextUtils;
import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.ViewRule;

/**
 * 按 contentDescription 匹配（无障碍描述）。
 */
final class DescriptionMatcher implements MatchStrategy {

    @Override
    public int priority() {
        return 70;
    }

    @Override
    public int computeScore(View view, ViewRule rule) {
        if (TextUtils.isEmpty(rule.description)) return 0;
        CharSequence desc = view.getContentDescription();
        if (desc != null && TextUtils.equals(desc.toString(), rule.description)) return 15;
        return 0;
    }
}
