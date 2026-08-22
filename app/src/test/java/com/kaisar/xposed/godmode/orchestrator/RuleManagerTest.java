package com.kaisar.xposed.godmode.orchestrator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.ModifyEffect;
import com.kaisar.xposed.godmode.engine.rule.RuleDiff;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RuleManagerTest {

    @Test
    public void newManagerStartsUnavailableWithoutTreatingItAsReadyEmpty() throws Exception {
        assertEquals(RuleManager.LoadState.UNAVAILABLE, newManager().getLoadState());
    }

    @Test
    public void copyRulesClonesListsRecordsAndImmutableComponents() {
        RuleRecord sourceRule = rule();
        ActRules source = rulesOf(sourceRule);
        List<RuleRecord> sourceList = source.get(sourceRule.getActivityClass());

        ActRules copy = RuleManager.copyRules(source);
        RuleRecord copiedRule = copy.get(sourceRule.getActivityClass()).get(0);

        assertNotSame(source, copy);
        assertNotSame(sourceList, copy.get(sourceRule.getActivityClass()));
        assertNotSame(sourceRule, copiedRule);
        assertNotSame(sourceRule.getMatchSpec(), copiedRule.getMatchSpec());
        int[] returnedDepth = sourceRule.getDepth();
        returnedDepth[0] = 99;
        assertEquals(1, copiedRule.getDepth()[0]);

        sourceRule.alias = "mutated input";
        assertEquals("alias", copiedRule.alias);
        copiedRule.alias = "mutated output";
        assertEquals("mutated input", sourceRule.alias);
    }

    @Test
    public void replaceRulesAndGetRulesDoNotExposeOwnedState() throws Exception {
        RuleManager manager = newManager();
        RuleRecord sourceRule = rule();
        ActRules source = rulesOf(sourceRule);

        manager.replaceRules(source);
        sourceRule.alias = "mutated input";
        source.clear();

        ActRules firstRead = manager.getRules();
        RuleRecord returned = firstRead.get("ExampleActivity").get(0);
        assertEquals("alias", returned.alias);
        returned.alias = "mutated output";
        int[] returnedDepth = returned.getDepth();
        returnedDepth[0] = 99;
        firstRead.clear();

        RuleRecord secondRead = manager.getRules().get("ExampleActivity").get(0);
        assertEquals("alias", secondRead.alias);
        assertEquals(1, secondRead.getDepth()[0]);
    }

    @Test
    public void replaceRulesAcceptsNullAndEmptyLists() throws Exception {
        RuleManager manager = newManager();
        manager.replaceRules(null);
        assertTrue(manager.getRules().isEmpty());

        ActRules source = new ActRules();
        source.put("EmptyActivity", new ArrayList<>());
        manager.replaceRules(source);

        assertTrue(manager.getRules().containsKey("EmptyActivity"));
        assertTrue(manager.getRules().get("EmptyActivity").isEmpty());
    }

    @Test
    public void acceptedServiceSnapshotPublishesBeforeReplacingAndTracksReadyState()
            throws Exception {
        RuleManager manager = newManager();
        RuleRecord serviceRule = rule();
        manager.acceptServiceSnapshotForTest(rulesOf(serviceRule));

        assertEquals(RuleManager.LoadState.READY_WITH_RULES, manager.getLoadState());
        assertEquals(1, manager.getRules().get(serviceRule.getActivityClass()).size());

        manager.acceptServiceSnapshotForTest(new ActRules());
        assertEquals(RuleManager.LoadState.READY_EMPTY, manager.getLoadState());
        assertTrue(manager.getRules().isEmpty());
    }

    @Test
    public void runtimeComparatorIgnoresPresentationMetadata() {
        RuleRecord left = rule();
        RuleRecord right = left.clone();
        right.label = "new label";
        right.alias = "new alias";
        right.timestamp++;
        right.imagePath = "new-preview.png";
        right.width++;
        assertTrue(RuleManager.runtimeContentEquals(left, right));
    }

    @Test
    public void runtimeComparatorNormalizesLegacyMatcherDefaults() {
        RuleRecord left = rule();
        RuleRecord right = left.withMatchSpec(left.getMatchSpec().toBuilder()
                .matchMode(null).targetLevel(null).build());
        assertTrue(RuleManager.runtimeContentEquals(left, right));
    }

    @Test
    public void runtimeComparatorDetectsMatcherAndEffectChanges() {
        assertRuntimeChange(rule -> rule.withMatchSpec(rule.getMatchSpec().toBuilder()
                .resourceName("pkg:id/other").build()));
        assertRuntimeChange(rule -> rule.withMatchSpec(rule.getMatchSpec().toBuilder()
                .depth(new int[] {9, 2}).build()));
        assertRuntimeChange(rule -> rule.withMatchSpec(rule.getMatchSpec().toBuilder()
                .matchMode(MatchMode.CONTAINS).build()));
        assertRuntimeChange(rule -> rule.withEffect(effectWith(rule, "other replacement", 4,
                "replacement.png")));
        assertRuntimeChange(rule -> rule.withEffect(effectWith(rule, "replacement", 9,
                "replacement.png")));
        assertRuntimeChange(rule -> rule.withEffect(effectWith(rule, "replacement", 4,
                "other.png")));
    }

    @Test
    public void runtimeDiffDoesNotSkipContentChangesWithSameSlot() {
        RuleRecord oldRule = rule();
        RuleRecord newRule = oldRule.withEffect(effectWith(oldRule, "new runtime text", 4,
                "replacement.png"));

        assertTrue(oldRule.equals(newRule));
        RuleDiff diff = RuleLifecycleManager.computeRuntimeDiff(rulesOf(oldRule), rulesOf(newRule));
        assertSame(oldRule, diff.toRevoke.get(oldRule.getActivityClass()).get(0));
        assertSame(newRule, diff.toApply.get(newRule.getActivityClass()).get(0));
    }

    @Test
    public void runtimeDiffKeepsDirectAndRepeatableSlotsDistinct() {
        RuleRecord direct = rule().withMatchSpec(rule().getMatchSpec().toBuilder()
                .repeatable(false).itemPath(null).build());
        RuleRecord repeatable = rule();
        RuleDiff diff = RuleLifecycleManager.computeRuntimeDiff(rulesOf(direct), rulesOf(repeatable));
        assertEquals(1, diff.toRevoke.get(direct.getActivityClass()).size());
        assertEquals(1, diff.toApply.get(repeatable.getActivityClass()).size());
    }

    @Test
    public void runtimeDiffIgnoresPresentationOnlyChanges() {
        RuleRecord oldRule = rule();
        RuleRecord newRule = oldRule.clone();
        newRule.label = "new label";
        newRule.alias = "new alias";
        assertTrue(RuleLifecycleManager.computeRuntimeDiff(
                rulesOf(oldRule), rulesOf(newRule)).isEmpty());
    }

    private static ModifyEffect effectWith(RuleRecord rule, String text, int xOffset,
                                           String imagePath) {
        return new ModifyEffect.Builder().ruleTag("modify").visibility(rule.getVisibility())
                .modWidth(80).modHeight(90).modAlpha(.5f).modXOffset(xOffset).modYOffset(5)
                .modText(text).modImagePath(imagePath).origLeftMargin(6).origTopMargin(7).build();
    }

    private static void assertRuntimeChange(Change change) {
        RuleRecord left = rule();
        assertFalse(RuleManager.runtimeContentEquals(left, change.apply(left)));
    }

    private static RuleRecord rule() {
        MatchSpec match = new MatchSpec.Builder()
                .depth(new int[] {1, 2}).activityClass("ExampleActivity").viewClass("TextView")
                .resourceName("com.example:id/title").itemPath(new String[] {"root", "child"})
                .itemRootClass("RecyclerView").parentClass("LinearLayout").repeatable(true)
                .text("original").description("description").matchMode(MatchMode.EXACT)
                .viewType(3).targetLevel(TargetLevel.ELEMENT).build();
        ModifyEffect effect = new ModifyEffect.Builder().ruleTag("modify").visibility(0)
                .modWidth(80).modHeight(90).modAlpha(.5f).modXOffset(4).modYOffset(5)
                .modText("replacement").modImagePath("replacement.png")
                .origLeftMargin(6).origTopMargin(7).build();
        return new RuleRecord("label", "com.example", "1.0", 1, 68, "preview.png", "alias",
                10, 20, 100, 200, 123L, 100, 200, 1f, "original", match, effect);
    }

    private static ActRules rulesOf(RuleRecord rule) {
        ActRules rules = new ActRules();
        rules.put(rule.getActivityClass(), new ArrayList<>(Collections.singletonList(rule)));
        return rules;
    }

    private static RuleManager newManager() throws Exception {
        Constructor<RuleManager> constructor = RuleManager.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("com.example.test");
    }

    private interface Change {
        RuleRecord apply(RuleRecord rule);
    }
}
