package com.kaisar.xposed.godmode.orchestrator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.ModifyEffect;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import org.junit.Test;

/**
 * 反向 golden：锁定“展示字段变化不得驱动运行时 diff”的方向（S-1）。
 * <p>
 * 仅改 alias / imagePath 时，运行时比较器必须判同（无重应用），而 UI
 * {@code contentEquals} 必须判异（列表刷新）。若未来有人把 UI 相等误用于
 * 运行时链路（如 IPC 提交确认），本测试即红——这正是 S-1 的用错链路。
 */
public final class RuntimeVsDisplayEqualityTest {

    @Test
    public void displayOnlyChangeKeepsRuntimeEqualButNotUiEqual() {
        RuleRecord base = fullRecord();
        RuleRecord renamed = base.withAlias("renamed").withImagePath("other-preview.png");

        assertTrue(RuntimeRuleComparator.contentEquals(base, renamed));
        assertFalse(base.contentEquals(renamed));
    }

    @Test
    public void effectChangeBreaksRuntimeEquality() {
        RuleRecord base = fullRecord();
        RuleRecord changed = base.withEffect(new ModifyEffect.Builder()
                .ruleTag("modify").visibility(4)
                .modWidth(999).modHeight(81).modAlpha(.5f).modXOffset(3).modYOffset(4)
                .modText("replacement").modImagePath("replacement.png")
                .origLeftMargin(17).origTopMargin(18).build());

        assertFalse(RuntimeRuleComparator.contentEquals(base, changed));
    }

    private static RuleRecord fullRecord() {
        MatchSpec match = new MatchSpec.Builder()
                .depth(new int[] {1, 2}).activityClass("ExampleActivity").viewClass("TextView")
                .resourceName("com.example:id/title").itemPath(new String[] {"row", "title"})
                .itemRootClass("FrameLayout").parentClass("LinearLayout").repeatable(true)
                .text("raw text").description("raw description").matchMode(MatchMode.CONTAINS)
                .viewType(7).targetLevel(TargetLevel.CARD).build();
        ModifyEffect effect = new ModifyEffect.Builder().ruleTag("modify").visibility(4)
                .modWidth(80).modHeight(81).modAlpha(.5f).modXOffset(3).modYOffset(4)
                .modText("replacement").modImagePath("replacement.png")
                .origLeftMargin(17).origTopMargin(18).build();
        return new RuleRecord("label", "com.example", "1", 1, 69, "preview.png", "alias",
                1, 2, 3, 4, 5L, 30, 40, .7f, "original", match, effect);
    }
}
