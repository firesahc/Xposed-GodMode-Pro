package com.kaisar.xposed.godmode.rule;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.ModifyEffect;

import org.junit.Test;

/** Golden tests for the v6.9 flat JSON representation of physically split records. */
public final class RuleRecordTypeAdapterTest {

    private final Gson gson = new Gson();

    @Test
    public void splitComponentsRoundTripThroughFlatLegacyJson() {
        RuleRecord original = fullRecord();

        String json = gson.toJson(original);
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        RuleRecord restored = gson.fromJson(json, RuleRecord.class);

        assertFalse(object.has("matchSpec"));
        assertFalse(object.has("effect"));
        assertTrue(object.has("act_class"));
        assertTrue(object.has("mod_img_path"));
        assertEquals("modify", object.get("rule_tag").getAsString());
        assertEquals("ExampleActivity", restored.getActivityClass());
        assertArrayEquals(new int[] {1, 2}, restored.getDepth());
        assertArrayEquals(new String[] {"row", "title"}, restored.getItemPath());
        assertEquals(MatchMode.CONTAINS, restored.getMatchMode());
        assertEquals(TargetLevel.CARD, restored.getTargetLevel());
        assertEquals("replacement.png", restored.getModImagePath());
        assertEquals(17, restored.getOrigLeftMargin());
        assertEquals(18, restored.getOrigTopMargin());
        assertEquals(original.getEffect().toWireValues(), restored.getEffect().toWireValues());
    }

    @Test
    public void legacyMatchThresholdAliasAndRawRepeatableTextRemainCompatible() {
        String oldJson = "{"
                + "'rule_tag':'modify','label':'L','package_name':'com.example',"
                + "'match_version_name':'1','match_version_code':1,'version_code':69,"
                + "'img_path':'preview.png','alias':'A','x':1,'y':2,'width':3,'height':4,"
                + "'depth':[1,2],'act_class':'Activity','view_class':'TextView',"
                + "'res_name':'com.example:id/title','item_path':['row','title'],"
                + "'item_root_class':'FrameLayout','parent_class':'LinearLayout',"
                + "'repeatable':true,'text':'stored text','description':'stored description',"
                + "'match_mode':null,'match_threshold':7,'target_level':null,'visibility':4,"
                + "'timestamp':11,'mod_width':90,'mod_height':-1,'mod_alpha':.5,"
                + "'mod_x_offset':3,'mod_y_offset':4,'mod_text':'changed',"
                + "'mod_img_path':'replace.png','orig_width':30,'orig_height':40,"
                + "'orig_alpha':.7,'orig_text':'original','orig_left_margin':5,'orig_top_margin':6}"
                .replace('\'', '"');

        RuleRecord record = gson.fromJson(oldJson, RuleRecord.class);
        JsonObject encoded = JsonParser.parseString(gson.toJson(record)).getAsJsonObject();

        assertEquals("stored text", record.getText());
        assertEquals("stored description", record.getDescription());
        assertNull(record.getMatchMode());
        assertNull(record.getTargetLevel());
        assertEquals(7, record.getInfoFlowViewType());
        assertTrue(record.getMatchSpec().hasRepeatableLocator());
        assertEquals("stored text", encoded.get("text").getAsString());
        assertEquals("stored description", encoded.get("description").getAsString());
        assertEquals(7, encoded.get("view_type").getAsInt());
    }

    @Test
    public void unknownLegacyEnumAndExtraFieldFollowThePreviousTolerantReadPolicy() {
        String json = "{'package_name':'com.example','depth':[1],'act_class':'Activity',"
                + "'view_class':'TextView','rule_tag':null,'match_mode':'FUTURE_MODE',"
                + "'target_level':'FUTURE_LEVEL','new_future_field':42}"
                .replace('\'', '"');

        RuleRecord record = gson.fromJson(json, RuleRecord.class);

        assertNull(record.getMatchMode());
        assertNull(record.getTargetLevel());
        assertEquals("Activity", record.getActivityClass());
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
