package com.kaisar.xposed.godmode.engine.rule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RuleDiffTest {

    @Test
    public void resultMapsAndListsAreImmutableSnapshots() {
        List<String> source = new ArrayList<>(Collections.singletonList("rule-A"));
        Map<String, List<String>> newRules = new HashMap<>();
        newRules.put("Activity", source);

        RuleDiff diff = RuleDiff.compute(Collections.emptyMap(), newRules);
        source.add("rule-B");

        assertEquals(Collections.singletonList("rule-A"),
                diff.toApply.get("Activity"));
        assertUnsupported(() -> diff.toApply.put(
                "Other", Collections.singletonList("rule-C")));
        assertUnsupported(() -> addRaw(diff.toApply.get("Activity"), "rule-C"));
    }

    private static void assertUnsupported(Runnable runnable) {
        try {
            runnable.run();
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addRaw(List list, Object value) {
        list.add(value);
    }
}
