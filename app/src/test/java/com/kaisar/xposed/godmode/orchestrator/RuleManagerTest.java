package com.kaisar.xposed.godmode.orchestrator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
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
        RuleManager manager = newManager();
        assertEquals(RuleManager.LoadState.UNAVAILABLE, manager.getLoadState());
    }

    @Test
    public void copyRulesClonesListsRecordsAndNestedArrays() {
        RuleRecord sourceRule = rule();
        List<RuleRecord> sourceList = new ArrayList<>(Collections.singletonList(sourceRule));
        ActRules source = new ActRules();
        source.put(sourceRule.activityClass, sourceList);

        ActRules copy = RuleManager.copyRules(source);
        RuleRecord copiedRule = copy.get(sourceRule.activityClass).get(0);

        assertNotSame(source, copy);
        assertNotSame(sourceList, copy.get(sourceRule.activityClass));
        assertNotSame(sourceRule, copiedRule);
        assertNotSame(sourceRule.depth, copiedRule.depth);
        assertNotSame(sourceRule.itemPath, copiedRule.itemPath);

        sourceRule.modText = "mutated input";
        sourceRule.depth[0] = 99;
        assertEquals("replacement", copiedRule.modText);
        assertEquals(1, copiedRule.depth[0]);

        copiedRule.modImagePath = "mutated output";
        copiedRule.itemPath[0] = "changed";
        assertEquals("replacement.png", sourceRule.modImagePath);
        assertEquals("root", sourceRule.itemPath[0]);
    }

    @Test
    public void replaceRulesAndGetRulesDoNotExposeOwnedState() throws Exception {
        RuleManager manager = newManager();
        RuleRecord sourceRule = rule();
        ActRules source = new ActRules();
        source.put(sourceRule.activityClass,
                new ArrayList<>(Collections.singletonList(sourceRule)));

        manager.replaceRules(source);
        sourceRule.modText = "mutated input";
        source.get(sourceRule.activityClass).clear();

        ActRules firstRead = manager.getRules();
        RuleRecord returned = firstRead.get(sourceRule.activityClass).get(0);
        assertEquals("replacement", returned.modText);
        returned.modText = "mutated output";
        returned.depth[0] = 99;
        firstRead.clear();

        RuleRecord secondRead = manager.getRules().get(sourceRule.activityClass).get(0);
        assertEquals("replacement", secondRead.modText);
        assertEquals(1, secondRead.depth[0]);
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
        ActRules snapshot = rulesOf(serviceRule);

        manager.acceptServiceSnapshotForTest(snapshot);

        assertEquals(RuleManager.LoadState.READY_WITH_RULES, manager.getLoadState());
        assertEquals(1, manager.getRules().get(serviceRule.activityClass).size());

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

        assertTrue(RuleManager.runtimeContentEquals(left, right));
    }

    @Test
    public void runtimeComparatorNormalizesLegacyMatcherDefaults() {
        RuleRecord left = rule();
        RuleRecord right = left.clone();
        left.matchMode = null;
        left.targetLevel = null;

        assertTrue(RuleManager.runtimeContentEquals(left, right));
    }

    @Test
    public void runtimeComparatorDetectsMatcherActionGeometryVisibilityAndImageChanges() {
        assertRuntimeChange(rule -> rule.resourceName = "pkg:id/other");
        assertRuntimeChange(rule -> rule.depth[0]++);
        assertRuntimeChange(rule -> rule.matchMode = MatchMode.CONTAINS);
        assertRuntimeChange(rule -> rule.modText = "other replacement");
        assertRuntimeChange(rule -> rule.width++);
        assertRuntimeChange(rule -> rule.modXOffset++);
        assertRuntimeChange(rule -> rule.visibility++);
        assertRuntimeChange(rule -> rule.imagePath = "other-preview.png");
        assertRuntimeChange(rule -> rule.modImagePath = "other.png");
    }

    @Test
    public void runtimeDiffDoesNotSkipContentChangesWithSameIdentity() {
        RuleRecord oldRule = rule();
        RuleRecord newRule = oldRule.clone();
        newRule.modText = "new runtime text";
        ActRules oldRules = rulesOf(oldRule);
        ActRules newRules = rulesOf(newRule);

        // RuleRecord.equals is intentionally identity-only; the runtime diff must
        // still revoke the old effect and apply the new one.
        assertTrue(oldRules.equals(newRules));
        RuleDiff diff = RuleLifecycleManager.computeRuntimeDiff(oldRules, newRules);

        assertSame(oldRule, diff.toRevoke.get(oldRule.activityClass).get(0));
        assertSame(newRule, diff.toApply.get(newRule.activityClass).get(0));
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

    private static void assertRuntimeChange(Change change) {
        RuleRecord left = rule();
        RuleRecord right = left.clone();
        change.apply(right);
        assertFalse(RuleManager.runtimeContentEquals(left, right));
    }

    private static RuleRecord rule() {
        RuleRecord rule = new RuleRecord(
                "label", "com.example", "1.0", 1, 68, "preview.png", "alias",
                10, 20, 100, 200, new int[]{1, 2}, "ExampleActivity", "TextView",
                "com.example:id/title", "original", "description", 0, 123L);
        rule.ruleTag = "modify";
        rule.itemPath = new String[]{"root", "child"};
        rule.itemRootClass = "RecyclerView";
        rule.parentClass = "LinearLayout";
        rule.repeatable = true;
        rule.matchMode = MatchMode.EXACT;
        rule.viewType = 3;
        rule.targetLevel = TargetLevel.ELEMENT;
        rule.modWidth = 80;
        rule.modHeight = 90;
        rule.modAlpha = 0.5f;
        rule.modXOffset = 4;
        rule.modYOffset = 5;
        rule.modText = "replacement";
        rule.modImagePath = "replacement.png";
        rule.origWidth = 100;
        rule.origHeight = 200;
        rule.origAlpha = 1f;
        rule.origText = "original";
        rule.origLeftMargin = 6;
        rule.origTopMargin = 7;
        return rule;
    }

    private static ActRules rulesOf(RuleRecord rule) {
        ActRules rules = new ActRules();
        rules.put(rule.activityClass,
                new ArrayList<>(Collections.singletonList(rule)));
        return rules;
    }

    private static RuleManager newManager() throws Exception {
        Constructor<RuleManager> constructor = RuleManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private interface Change {
        void apply(RuleRecord rule);
    }
}
