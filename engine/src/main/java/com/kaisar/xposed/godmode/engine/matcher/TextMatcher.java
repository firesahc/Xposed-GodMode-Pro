package com.kaisar.xposed.godmode.engine.matcher;

import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.kaisar.xposed.godmode.engine.rule.ViewRule;

/**
 * 按 TextView 文本内容匹配。
 */
final class TextMatcher implements MatchStrategy {

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public int computeScore(View view, ViewRule rule) {
        if (TextUtils.isEmpty(rule.text)) return 0;
        if (view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            if (t != null && TextUtils.equals(t.toString(), rule.text)) return 20;
        }
        return 0;
    }
}
