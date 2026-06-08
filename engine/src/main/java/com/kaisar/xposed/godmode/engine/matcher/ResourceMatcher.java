package com.kaisar.xposed.godmode.engine.matcher;

import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;

/**
 * 按 android:resourceName 匹配。
 * 通过 findViewById + 资源名匹配，适用于有固定 resource-id 的视图。
 */
final class ResourceMatcher implements MatchStrategy {

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public int computeScore(View view, RuleMatchSpec rule) {
        if (TextUtils.isEmpty(rule.resourceName)) return 0;
        try {
            String resName = view.getResources().getResourceName(view.getId());
            if (TextUtils.equals(resName, rule.resourceName)) return 25;
        } catch (Resources.NotFoundException ignored) {
        }
        return 0;
    }
}
