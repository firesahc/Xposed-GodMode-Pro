package com.kaisar.xposed.godmode.engine.rule;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;

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
}
