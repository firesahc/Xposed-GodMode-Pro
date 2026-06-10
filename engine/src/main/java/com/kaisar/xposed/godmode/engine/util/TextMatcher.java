package com.kaisar.xposed.godmode.engine.util;

import android.text.TextUtils;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;

/**
 * 文本匹配工具类 — 从 {@link com.kaisar.xposed.godmode.engine.matcher.ResourceMatcher} 迁入，
 * 取代策略评分体系中的 matchText 功能。
 * <p>
 * 提供按 {@link MatchMode} 比较两个字符串的静态方法。
 */
public final class TextMatcher {

    private TextMatcher() {
    }

    /**
     * 按 matchMode 比较两个字符串。
     *
     * @param target 待匹配的目标字符串
     * @param value  匹配值
     * @param mode   匹配模式，null 等价于 EXACT
     * @return 是否匹配
     */
    public static boolean matchText(String target, String value, MatchMode mode) {
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
