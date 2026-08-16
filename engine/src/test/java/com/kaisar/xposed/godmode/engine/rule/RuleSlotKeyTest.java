package com.kaisar.xposed.godmode.engine.rule;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuleSlotKeyTest {

    @Test
    public void packageScopeAndTargetKindArePartOfIdentity() {
        MatchSpec direct = new MatchSpec.Builder()
                .activityClass("Activity")
                .viewClass("TextView")
                .depth(new int[] {0, 2})
                .build();
        RuleSlotKey first = RuleSlotKey.from("pkg.one", direct);
        RuleSlotKey same = RuleSlotKey.from("pkg.one", direct.clone());
        RuleSlotKey otherPackage = RuleSlotKey.from("pkg.two", direct);

        assertEquals(first, same);
        assertTrue(!first.equals(otherPackage));
        assertEquals(RuleSlotKey.TargetKind.DIRECT, first.getTargetKind());
        assertArrayEquals(new int[] {0, 2}, first.getDepth());
    }

    @Test
    public void malformedRepeatableRemainsRepeatableAndIsDiagnosable() {
        MatchSpec malformed = new MatchSpec.Builder()
                .activityClass("Activity")
                .viewClass("Card")
                .repeatable(true)
                .itemPath(new String[0])
                .build();
        RuleSlotKey key = RuleSlotKey.from("pkg", malformed);

        assertEquals(RuleSlotKey.TargetKind.REPEATABLE, key.getTargetKind());
        assertTrue(key.hasMissingRepeatableLocator());
        assertEquals(null, key.getDepth());
        assertArrayEquals(new String[0], key.getItemPath());
    }
}
