package com.kaisar.xposed.godmode.ipc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.ModifyEffect;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

public final class RuleMutationReconciliationTest {

    @Test
    public void confirmsCommittedRuleAfterServerAssignsImagePaths() {
        RuleRecord requested = rule("client-preview", "client-modified", "replacement");
        RuleRecord committed = requested.withImagePath("/data/main.webp")
                .withModifyImagePath("/data/modified.webp");

        assertTrue(RuleServiceClient.containsCommittedRule(rulesOf(committed), requested,
                true, true));
    }

    @Test
    public void rejectsSameSlotWithDifferentRuleContent() {
        RuleRecord requested = rule(null, null, "replacement");
        RuleRecord old = rule(null, null, "old text");

        assertFalse(RuleServiceClient.containsCommittedRule(rulesOf(old), requested,
                false, false));
        assertTrue(RuleServiceClient.containsSlot(rulesOf(old), requested));
    }

    private static RuleRecord rule(String imagePath, String modifiedImagePath, String text) {
        MatchSpec match = new MatchSpec.Builder()
                .depth(new int[] {1, 2}).activityClass("ExampleActivity")
                .viewClass("TextView").resourceName("com.example:id/title")
                .matchMode(MatchMode.EXACT).targetLevel(TargetLevel.ELEMENT).build();
        ModifyEffect effect = new ModifyEffect.Builder().ruleTag("modify").visibility(0)
                .modWidth(80).modHeight(90).modAlpha(.5f).modText(text)
                .modImagePath(modifiedImagePath).build();
        return new RuleRecord("label", "com.example", "1.0", 1, 68,
                imagePath, "alias", 10, 20, 100, 200, 123L,
                100, 200, 1f, "original", match, effect);
    }

    private static ActRules rulesOf(RuleRecord rule) {
        ActRules rules = new ActRules();
        rules.put(rule.getActivityClass(),
                new ArrayList<>(Collections.singletonList(rule)));
        return rules;
    }
}
