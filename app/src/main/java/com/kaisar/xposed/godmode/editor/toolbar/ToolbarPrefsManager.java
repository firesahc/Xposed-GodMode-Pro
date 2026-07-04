package com.kaisar.xposed.godmode.editor.toolbar;

import android.text.TextUtils;

import com.kaisar.xposed.godmode.engine.util.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Toolbar preference parser.
 */
public final class ToolbarPrefsManager {

    private static final String TAG = "ToolbarPrefsManager";

    private ToolbarPrefsManager() {
    }

    /**
     * Parse the set of hidden toolbar items from RuleServiceServer.
     * Returns an empty set if all items are visible.
     */
    public static Set<String> parseHiddenItems(String value) {
        try {
            if (!TextUtils.isEmpty(value)) {
                return parseCommaSeparated(value);
            }
        } catch (Throwable e) {
            Logger.w(TAG, "parse hidden items failed", e);
        }
        return new HashSet<>();
    }

    private static Set<String> parseCommaSeparated(String value) {
        Set<String> result = new HashSet<>();
        if (!TextUtils.isEmpty(value)) {
            for (String s : value.split(",")) {
                if (!TextUtils.isEmpty(s)) {
                    result.add(s);
                }
            }
        }
        return result;
    }
}
