package com.kaisar.xposed.godmode.rule;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kaisar.xposed.godmode.engine.applier.ModifyApplier;
import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.ModifyEffect;
import com.kaisar.xposed.godmode.engine.rule.RuleSlotKey;

import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * 反向 golden：锁定 ADR-0001 / CONTEXT 的禁持久化边界。
 * <p>
 * {@code RuleSlotKey} 与运行时基线（{@code ModifyApplier} 内部 {@code AppliedState}）
 * 永不进入 JSON / Parcelable / ZIP 备份格式。正向 golden 只证明“需要的键都在”，
 * 本类证明“禁止的键永不出现”——两者缺一，禁令就是纸面合同。
 */
public final class WireBoundaryNegativeTest {

    private final Gson gson = new Gson();

    @Test
    public void flatJsonNeverCarriesSlotOrBaselineKeys() {
        String json = gson.toJson(fullRecord());
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();

        assertFalse(object.has("slotKey"));
        assertFalse(object.has("slot_key"));
        assertFalse(object.has("appliedState"));
        assertFalse(object.has("applied_state"));
        assertFalse(object.has("appliedTarget"));
        for (Map.Entry<String, ?> entry : object.entrySet()) {
            String key = entry.getKey().toLowerCase();
            assertFalse("wire key must not leak runtime identity: " + entry.getKey(),
                    key.contains("slot") || key.contains("applied"));
        }
    }

    @Test
    public void ruleSlotKeyIsMemoryOnlyIdentity() {
        assertFalse(Serializable.class.isAssignableFrom(RuleSlotKey.class));
        for (Field field : RuleSlotKey.class.getDeclaredFields()) {
            assertFalse("RuleSlotKey must not expose a Parcelable CREATOR",
                    field.getName().equals("CREATOR"));
        }
        assertTrue(Modifier.isFinal(RuleSlotKey.class.getModifiers()));
    }

    @Test
    public void appliedStateStaysPrivateAndNonSerializable() {
        Class<?> appliedState;
        try {
            appliedState = Class.forName(
                    ModifyApplier.class.getName() + "$AppliedState");
        } catch (ClassNotFoundException e) {
            fail("ModifyApplier$AppliedState must exist as the single modify baseline owner");
            return;
        }
        assertTrue(Modifier.isPrivate(appliedState.getModifiers()));
        assertFalse(Serializable.class.isAssignableFrom(appliedState));
    }

    private static RuleRecord fullRecord() {
        MatchSpec match = new MatchSpec.Builder()
                .depth(new int[] {1, 2}).activityClass("ExampleActivity").viewClass("TextView")
                .resourceName("com.example:id/title").itemPath(new String[] {"row", "title"})
                .itemRootClass("FrameLayout").parentClass("LinearLayout").repeatable(true)
                .text("raw text").description("raw description").matchMode(MatchMode.CONTAINS)
                .viewType(7).targetLevel(TargetLevel.CARD).build();
        ModifyEffect effect = new ModifyEffect.Builder().ruleTag("modify").visibility(4)
                .modWidth(80).modHeight(81).modAlpha(.5f).modXOffset(3).modYOffset(4)
                .modText("replacement").modImagePath("replacement.png")
                .origLeftMargin(17).origTopMargin(18).build();
        return new RuleRecord("label", "com.example", "1", 1, 69, "preview.png", "alias",
                1, 2, 3, 4, 5L, 30, 40, .7f, "original", match, effect);
    }
}
