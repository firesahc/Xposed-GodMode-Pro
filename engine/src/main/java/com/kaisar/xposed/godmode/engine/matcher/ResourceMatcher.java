package com.kaisar.xposed.godmode.engine.matcher;

import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.MatchSpec;

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
    public int computeScore(View view, MatchSpec spec) {
        if (TextUtils.isEmpty(spec.resourceName)) return 0;
        try {
            String resName = view.getResources().getResourceName(view.getId());
            if (matchText(resName, spec.resourceName, spec.matchMode)) return 25;
        } catch (Resources.NotFoundException e) {
            // view 没有 resource name — 不匹配，score 保持 0
        }
        return 0;
    }

    /**
     * 按 matchMode 比较两个字符串。
     */
    static boolean matchText(String target, String value, MatchMode mode) {
        if (target == null || value == null) return false;
        if (mode == null) mode = MatchMode.EXACT;
        switch (mode) {
            case CONTAINS:
                return target.contains(value);
            case STARTS_WITH:
                return target.startsWith(value);
            case ENDS_WITH:
                return target.endsWith(value);
            case REGEX:
                return target.matches(value);
            case EXACT:
            default:
                return TextUtils.equals(target, value);
        }
    }
}
