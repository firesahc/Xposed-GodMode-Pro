package com.kaisar.xposed.godmode.engine.util;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link TextMatcher} 五种匹配模式的契约锁定，含无效正则降级行为。
 */
public class TextMatcherTest {

    // ===== 基线模式锁定 =====

    @Test
    public void exactMatchesOnlyIdenticalStrings() {
        assertTrue(TextMatcher.matchText("广告", "广告", MatchMode.EXACT));
        assertFalse(TextMatcher.matchText("广告位", "广告", MatchMode.EXACT));
    }

    @Test
    public void containsMatchesSubstring() {
        assertTrue(TextMatcher.matchText("打开会员页面", "会员", MatchMode.CONTAINS));
        assertFalse(TextMatcher.matchText("打开首页", "会员", MatchMode.CONTAINS));
    }

    @Test
    public void startsWithAndEndsWithMatchAffixes() {
        assertTrue(TextMatcher.matchText("com.ad.sdk", "com.", MatchMode.STARTS_WITH));
        assertFalse(TextMatcher.matchText("ad.com", "com.", MatchMode.STARTS_WITH));
        assertTrue(TextMatcher.matchText("banner_ad_view", "_view", MatchMode.ENDS_WITH));
        assertFalse(TextMatcher.matchText("banner_ad_item", "_view", MatchMode.ENDS_WITH));
    }

    /** 锁定 REGEX 走全串匹配语义（matches 而非 find）。 */
    @Test
    public void regexMatchesFullInput() {
        assertTrue(TextMatcher.matchText("广告1", "^广告\\d$", MatchMode.REGEX));
        assertFalse(TextMatcher.matchText("x广告1y", "^广告\\d$", MatchMode.REGEX));
    }

    // ===== 无效正则降级（UNMATCHABLE 替身契约）=====

    @Test
    public void invalidRegexDegradesToNoMatchWithoutThrowing() {
        assertFalse(TextMatcher.matchText("任意文本", "[unclosed", MatchMode.REGEX));
    }

    @Test
    public void invalidRegexStaysStableAcrossCalls() {
        for (int i = 0; i < 3; i++) {
            assertFalse(TextMatcher.matchText("任意文本", "(?P<named>", MatchMode.REGEX));
        }
    }

    // ===== null 契约锁定（与 MatchFields javadoc 一致）=====

    @Test
    public void nullTargetOrValueNeverMatches() {
        assertFalse(TextMatcher.matchText(null, "x", MatchMode.EXACT));
        assertFalse(TextMatcher.matchText("x", null, MatchMode.EXACT));
        assertFalse(TextMatcher.matchText(null, "x", MatchMode.REGEX));
    }

    @Test
    public void nullModeDefaultsToExact() {
        assertTrue(TextMatcher.matchText("same", "same", null));
        assertFalse(TextMatcher.matchText("diff", "same", null));
    }
}
