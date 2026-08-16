package com.kaisar.xposed.godmode.rule;

import androidx.annotation.Keep;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.RuleEffect;

import java.lang.reflect.Type;

/** Bridges the internal component model to the unchanged flat v6.9 JSON wire format. */
@Keep
public final class RuleRecordTypeAdapter
        implements JsonSerializer<RuleRecord>, JsonDeserializer<RuleRecord> {

    @Override
    public JsonElement serialize(RuleRecord rule, Type type, JsonSerializationContext context) {
        JsonObject object = new JsonObject();
        RuleEffect.WireValues wire = rule.getEffect().toWireValues();
        MatchSpec match = rule.getMatchSpec();
        add(object, "rule_tag", wire.getRuleTag());
        add(object, "label", rule.label);
        add(object, "package_name", rule.packageName);
        add(object, "match_version_name", rule.matchVersionName);
        add(object, "match_version_code", rule.matchVersionCode);
        add(object, "version_code", rule.versionCode);
        add(object, "img_path", rule.imagePath);
        add(object, "alias", rule.alias);
        add(object, "x", rule.x);
        add(object, "y", rule.y);
        add(object, "width", rule.width);
        add(object, "height", rule.height);
        add(object, "depth", context.serialize(match.getDepth()));
        add(object, "act_class", match.getActivityClass());
        add(object, "view_class", match.getViewClass());
        add(object, "res_name", match.getResourceName());
        add(object, "item_path", context.serialize(match.getItemPath()));
        add(object, "item_root_class", match.getItemRootClass());
        add(object, "parent_class", match.getParentClass());
        add(object, "repeatable", match.isRepeatable());
        add(object, "text", match.getText());
        add(object, "description", match.getDescription());
        add(object, "match_mode", match.getMatchMode() != null ? match.getMatchMode().name() : null);
        add(object, "view_type", match.getInfoFlowViewType());
        add(object, "target_level", match.getTargetLevel() != null ? match.getTargetLevel().name() : null);
        add(object, "visibility", wire.getVisibility());
        add(object, "timestamp", rule.timestamp);
        add(object, "mod_width", wire.getModWidth());
        add(object, "mod_height", wire.getModHeight());
        add(object, "mod_alpha", wire.getModAlpha());
        add(object, "mod_x_offset", wire.getModXOffset());
        add(object, "mod_y_offset", wire.getModYOffset());
        add(object, "mod_text", wire.getModText());
        add(object, "mod_img_path", wire.getModImagePath());
        add(object, "orig_width", rule.origWidth);
        add(object, "orig_height", rule.origHeight);
        add(object, "orig_alpha", rule.origAlpha);
        add(object, "orig_text", rule.origText);
        add(object, "orig_left_margin", wire.getOrigLeftMargin());
        add(object, "orig_top_margin", wire.getOrigTopMargin());
        return object;
    }

    @Override
    public RuleRecord deserialize(JsonElement element, Type type,
                                  JsonDeserializationContext context) throws JsonParseException {
        if (element == null || element.isJsonNull()) return null;
        JsonObject object = element.getAsJsonObject();
        MatchSpec match = new MatchSpec.Builder()
                .depth(context.deserialize(value(object, "depth"), int[].class))
                .activityClass(string(object, "act_class"))
                .viewClass(string(object, "view_class"))
                .resourceName(string(object, "res_name"))
                .itemPath(context.deserialize(value(object, "item_path"), String[].class))
                .itemRootClass(string(object, "item_root_class"))
                .parentClass(string(object, "parent_class"))
                .repeatable(bool(object, "repeatable", false))
                .text(string(object, "text"))
                .description(string(object, "description"))
                .matchMode(enumValue(string(object, "match_mode"), MatchMode.class))
                .viewType(integer(object, object.has("view_type") ? "view_type" : "match_threshold", 0))
                .targetLevel(enumValue(string(object, "target_level"), TargetLevel.class))
                .build();
        RuleEffect.WireValues wire = new RuleEffect.WireValues.Builder()
                .ruleTag(string(object, "rule_tag"))
                .visibility(integer(object, "visibility", 0))
                .modWidth(integer(object, "mod_width", -1))
                .modHeight(integer(object, "mod_height", -1))
                .modAlpha(floating(object, "mod_alpha", -1f))
                .modXOffset(integer(object, "mod_x_offset", 0))
                .modYOffset(integer(object, "mod_y_offset", 0))
                .modText(string(object, "mod_text"))
                .modImagePath(string(object, "mod_img_path"))
                .origLeftMargin(integer(object, "orig_left_margin", 0))
                .origTopMargin(integer(object, "orig_top_margin", 0))
                .build();
        return new RuleRecord(string(object, "label"), string(object, "package_name"),
                string(object, "match_version_name"), integer(object, "match_version_code", 0),
                integer(object, "version_code", 0), string(object, "img_path"), string(object, "alias"),
                integer(object, "x", 0), integer(object, "y", 0), integer(object, "width", 0),
                integer(object, "height", 0), longValue(object, "timestamp", 0L),
                integer(object, "orig_width", 0), integer(object, "orig_height", 0),
                floating(object, "orig_alpha", 1f), string(object, "orig_text"), match,
                RuleEffect.fromWireValues(wire));
    }

    private static JsonElement value(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null ? value : JsonNull.INSTANCE;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = value(object, key);
        return value.isJsonNull() ? null : value.getAsString();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = value(object, key);
        return value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        JsonElement value = value(object, key);
        return value.isJsonNull() ? fallback : value.getAsLong();
    }

    private static float floating(JsonObject object, String key, float fallback) {
        JsonElement value = value(object, key);
        return value.isJsonNull() ? fallback : value.getAsFloat();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = value(object, key);
        return value.isJsonNull() ? fallback : value.getAsBoolean();
    }

    private static <T extends Enum<T>> T enumValue(String value, Class<T> type) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            // Gson's previous enum adapter treated unrecognised values as absent.
            return null;
        }
    }

    private static void add(JsonObject object, String name, String value) {
        if (value != null) object.add(name, new JsonPrimitive(value));
    }

    private static void add(JsonObject object, String name, Number value) {
        object.add(name, new JsonPrimitive(value));
    }

    private static void add(JsonObject object, String name, boolean value) {
        object.add(name, new JsonPrimitive(value));
    }

    private static void add(JsonObject object, String name, JsonElement value) {
        if (value != null && !value.isJsonNull()) object.add(name, value);
    }
}
