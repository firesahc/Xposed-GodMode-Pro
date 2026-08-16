package com.kaisar.xposed.godmode.ui.glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.RemoveEffect;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import org.junit.Test;

public final class RulePreviewSpecTest {

    @Test
    public void previewIdentityUsesOnlyPreviewSourceAndCaptureBounds() {
        RuleRecord first = record("preview.png", 1, 2, 30, 40);
        RuleRecord samePreview = record("preview.png", 1, 2, 30, 40);
        samePreview.alias = "different display text";
        RuleRecord differentBounds = record("preview.png", 1, 2, 31, 40);

        assertEquals(RulePreviewSpec.from(first), RulePreviewSpec.from(samePreview));
        assertNotEquals(RulePreviewSpec.from(first), RulePreviewSpec.from(differentBounds));
    }

    private static RuleRecord record(String imagePath, int x, int y, int width, int height) {
        MatchSpec match = new MatchSpec.Builder().depth(new int[] {1})
                .activityClass("Activity").viewClass("TextView").build();
        return new RuleRecord("label", "com.example", "1", 1, 69, imagePath, "alias",
                x, y, width, height, 1L, width, height, 1f, "host", match,
                RemoveEffect.of(4));
    }
}
