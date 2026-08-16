package com.kaisar.xposed.godmode.rule;

import com.kaisar.xposed.godmode.engine.rule.RuleEffect;

import java.util.Objects;

/**
 * A short-lived mutable edit buffer for one record's effect values.
 * It deliberately does not own Editor UI lifecycle, generations, or bitmaps.
 */
public final class RuleDraft {

    private final RuleRecord base;
    private final RuleEffect.WireValues.Builder effect;

    private RuleDraft(RuleRecord base) {
        this.base = Objects.requireNonNull(base, "base must not be null");
        this.effect = base.getEffect().toWireValues().toBuilder();
    }

    public static RuleDraft from(RuleRecord base) { return new RuleDraft(base); }

    public RuleDraft modWidth(int value) { effect.modWidth(value); return this; }
    public RuleDraft modHeight(int value) { effect.modHeight(value); return this; }
    public RuleDraft modAlpha(float value) { effect.modAlpha(value); return this; }
    public RuleDraft modXOffset(int value) { effect.modXOffset(value); return this; }
    public RuleDraft modYOffset(int value) { effect.modYOffset(value); return this; }
    public RuleDraft modText(String value) { effect.modText(value); return this; }
    public RuleDraft modImagePath(String value) { effect.modImagePath(value); return this; }

    public RuleRecord build() {
        return base.withEffect(RuleEffect.fromWireValues(effect.build()));
    }
}
