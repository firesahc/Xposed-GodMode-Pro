package com.kaisar.xposed.godmode.engine.rule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuleEffectTest {

    @Test
    public void wireValuesRoundTripWithoutNormalizingLegacyTagOrVisibility() {
        RuleEffect.WireValues wire = new RuleEffect.WireValues.Builder()
                .ruleTag("legacy-custom")
                .visibility(8)
                .modWidth(320)
                .modHeight(240)
                .modAlpha(.5f)
                .modXOffset(3)
                .modYOffset(-4)
                .modText("new")
                .modImagePath("modified.png")
                .origLeftMargin(11)
                .origTopMargin(12)
                .build();

        RuleEffect effect = RuleEffect.fromWireValues(wire);
        assertTrue(effect instanceof ModifyEffect);
        assertEquals(wire, effect.toWireValues());
        assertEquals("legacy-custom", effect.getRuleTag());
        assertEquals(8, effect.toWireValues().getVisibility());
    }

    @Test
    public void removeEffectOnlyUsesVisibilitySemantically() {
        RuleEffect.WireValues first = new RuleEffect.WireValues.Builder()
                .visibility(8).modWidth(100).modImagePath("stale").build();
        RuleEffect.WireValues second = new RuleEffect.WireValues.Builder()
                .visibility(8).modWidth(999).modImagePath("other").build();

        RuleEffect a = RuleEffect.fromWireValues(first);
        RuleEffect b = RuleEffect.fromWireValues(second);
        assertTrue(a instanceof RemoveEffect);
        assertEquals(a, b);
        assertEquals(first, a.toWireValues());
    }

    @Test
    public void modifyEffectIgnoresLegacyDiscriminatorAndVisibilityForRuntimeEquality() {
        ModifyEffect first = new ModifyEffect.Builder()
                .ruleTag("modify-a")
                .modWidth(120)
                .modAlpha(.8f)
                .build();
        RuleEffect.WireValues changedWire = first.toWireValues().toBuilder()
                .ruleTag("modify-b")
                .visibility(8)
                .build();
        ModifyEffect second = (ModifyEffect) RuleEffect.fromWireValues(changedWire);

        assertEquals(first, second);
        assertEquals("modify-b", second.getRuleTag());
        assertFalse(first.isRemove());
    }
}
