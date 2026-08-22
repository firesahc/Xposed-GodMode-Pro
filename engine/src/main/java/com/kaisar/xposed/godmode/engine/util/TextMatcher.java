package com.kaisar.xposed.godmode.engine.util;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文本匹配工具类 — 从 {@link com.kaisar.xposed.godmode.engine.matcher.ResourceMatcher} 迁入，
 * 取代策略评分体系中的 matchText 功能。
 * <p>
 * 提供按 {@link MatchMode} 比较两个字符串的静态方法。
 */
public final class TextMatcher {

    private static final String TAG = "TextMatcher";

    /**
     * 无效正则的替身 — 负向预查在任何输入上恒为假。
     * 使损坏的正则规则退化为"永不匹配"，而非向上传播 PatternSyntaxException。
     */
    private static final Pattern UNMATCHABLE = Pattern.compile("(?!x)x");

    /**
     * 正则编译缓存 — key 为规则中的 pattern 字符串。
     * 数量与已存规则集同阶（数十级），无需淘汰策略。
     */
    private static final ConcurrentHashMap<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();

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
                return regexMatches(target, value);
            case EXACT:
            default:
                // 入参恒为 String（调用方以 TextView.getText().toString() 传入），
                // 与原 TextUtils.equals 在非空域内语义等价；避免 android.text 依赖以保持 JVM 可测。
                return target.equals(value);
        }
    }

    /**
     * REGEX 分支 — 编译缓存 + 无效正则降级。
     * computeIfAbsent 保证并发同 key 只编译一次，失败告警天然去重。
     */
    private static boolean regexMatches(String target, String pattern) {
        Pattern compiled = REGEX_CACHE.computeIfAbsent(pattern, TextMatcher::compileSafe);
        return compiled.matcher(target).matches();
    }

    private static Pattern compileSafe(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            Logger.w(TAG, "Invalid regex degraded to unmatchable: " + regex, e);
            return UNMATCHABLE;
        }
    }
}
