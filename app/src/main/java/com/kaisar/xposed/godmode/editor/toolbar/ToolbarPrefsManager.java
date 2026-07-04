package com.kaisar.xposed.godmode.editor.toolbar;

import android.text.TextUtils;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;

import java.util.HashSet;
import java.util.Set;

/**
 * Cross-process read utility for toolbar preferences.
 * <p>
 * Reads the toolbar hidden items stored by the GodMode App via
 * RuleServiceServer (AIDL service in system_server).
 */
public final class ToolbarPrefsManager {

    private static final String TAG = "ToolbarPrefsManager";

    private ToolbarPrefsManager() {
    }

    /**
     * Load the set of hidden toolbar items from RuleServiceServer.
     * Returns an empty set if all items are visible.
     */
    public static Set<String> loadHiddenItems() {
        try {
            RuleServiceClient gmm = RuleServiceClient.getDefault();
            if (gmm == null) return new HashSet<>();
            String value = gmm.getToolbarHiddenItems();
            if (!TextUtils.isEmpty(value)) {
                return parseCommaSeparated(value);
            }
        } catch (Throwable e) {
            Logger.w(TAG, "load hidden items failed", e);
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
