package com.kaisar.xposed.godmode.engine.rule;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;

import org.junit.Test;

public final class MatchSpecTest {

    @Test
    public void builderAndGettersDefensivelyCopyArrays() {
        int[] depth = {1, 2, 3};
        String[] itemPath = {"card", "child"};
        MatchSpec spec = new MatchSpec.Builder()
                .depth(depth)
                .itemPath(itemPath)
                .repeatable(true)
                .build();

        depth[0] = 99;
        itemPath[0] = "changed";
        int[] returnedDepth = spec.getDepth();
        String[] returnedItemPath = spec.getItemPath();
        returnedDepth[1] = 88;
        returnedItemPath[1] = "mutated";

        assertArrayEquals(new int[] {1, 2, 3}, spec.getDepth());
        assertArrayEquals(new String[] {"card", "child"}, spec.getItemPath());
        assertNotSame(returnedDepth, spec.getDepth());
        assertNotSame(returnedItemPath, spec.getItemPath());
    }

    @Test
    public void rawEqualityKeepsRepeatableTextAndAllMatcherOptions() {
        MatchSpec first = new MatchSpec.Builder()
                .depth(new int[] {1})
                .itemPath(new String[] {"title"})
                .repeatable(true)
                .text("first")
                .description("description")
                .matchMode(MatchMode.EXACT)
                .viewType(2)
                .targetLevel(TargetLevel.ELEMENT)
                .build();
        MatchSpec changedText = new MatchSpec.Builder()
                .depth(new int[] {1})
                .itemPath(new String[] {"title"})
                .repeatable(true)
                .text("second")
                .description("description")
                .matchMode(MatchMode.EXACT)
                .viewType(2)
                .targetLevel(TargetLevel.ELEMENT)
                .build();

        assertNotEquals(first, changedText);
        assertTrue(first.hasSameRuntimeSemantics(changedText));
        assertTrue(first.hasSameRuntimeSemantics(new MatchSpec.Builder()
                .depth(new int[] {1})
                .itemPath(new String[] {"title"})
                .repeatable(true)
                .text("second")
                .description("other")
                .matchMode(MatchMode.EXACT)
                .viewType(2)
                .targetLevel(TargetLevel.ELEMENT)
                .build()));
    }

    @Test
    public void runtimeSemanticsIncludesModeViewTypeAndTargetLevel() {
        MatchSpec base = new MatchSpec.Builder()
                .activityClass("A")
                .viewClass("V")
                .text("text")
                .build();

        assertTrue(base.hasSameRuntimeSemantics(new MatchSpec.Builder()
                .activityClass("A").viewClass("V").text("text")
                .matchMode(MatchMode.EXACT).targetLevel(TargetLevel.ELEMENT).build()));
        assertFalse(base.hasSameRuntimeSemantics(new MatchSpec.Builder()
                .activityClass("A").viewClass("V").text("text")
                .matchMode(MatchMode.CONTAINS).build()));
        assertFalse(base.hasSameRuntimeSemantics(new MatchSpec.Builder()
                .activityClass("A").viewClass("V").text("text").viewType(4).build()));
        assertFalse(base.hasSameRuntimeSemantics(new MatchSpec.Builder()
                .activityClass("A").viewClass("V").text("text")
                .targetLevel(TargetLevel.CARD).build()));
        assertNotEquals(base, new MatchSpec.Builder()
                .activityClass("A").viewClass("V").text("text")
                .targetLevel(TargetLevel.ELEMENT).build());
    }

    @Test
    public void fromPreservesRawNullableValues() {
        MatchSpec source = new MatchSpec.Builder()
                .repeatable(true)
                .itemPath(new String[] {"child"})
                .text("raw")
                .matchMode(null)
                .targetLevel(null)
                .build();

        MatchSpec copy = MatchSpec.from(source);
        assertEquals("raw", copy.getText());
        assertEquals(null, copy.getMatchMode());
        assertEquals(null, copy.getTargetLevel());
    }
}
