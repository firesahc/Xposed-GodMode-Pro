package com.kaisar.xposed.godmode.rule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.ModifyEffect;

import org.junit.Test;

/** RuleDraft is a disposable effect buffer, not a second mutable rule model. */
public final class RuleDraftTest {

    @Test
    public void discardedDraftLeavesBaseRuleUntouched() {
        RuleRecord base = modifyRule();
        RuleDraft.from(base).modWidth(200).modText("draft");

        assertEquals(80, base.getModWidth());
        assertEquals("original", base.getModText());
    }

    @Test
    public void repeatedBuildsCreateIndependentEffectSnapshots() {
        RuleDraft draft = RuleDraft.from(modifyRule()).modWidth(200).modText("first");
        RuleRecord first = draft.build();
        RuleRecord second = draft.modText("second").modImagePath("pending").build();

        assertEquals(200, first.getModWidth());
        assertEquals("first", first.getModText());
        assertFalse(first.isImageModified());
        assertEquals("second", second.getModText());
        assertEquals("pending", second.getModImagePath());
        assertTrue(second.isModifyRule());
    }

    private static RuleRecord modifyRule() {
        MatchSpec match = new MatchSpec.Builder().depth(new int[] {1})
                .activityClass("Activity").viewClass("TextView").build();
        ModifyEffect effect = new ModifyEffect.Builder().ruleTag("modify")
                .modWidth(80).modText("original").build();
        return new RuleRecord("label", "com.example", "1", 1, 69, null, "alias",
                0, 0, 10, 10, 1L, 10, 10, 1f, "host", match, effect);
    }
}
