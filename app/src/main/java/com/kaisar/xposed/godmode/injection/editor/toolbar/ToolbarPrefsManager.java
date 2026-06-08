package com.kaisar.xposed.godmode.injection.editor.toolbar;

import android.text.TextUtils;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;

import java.util.HashSet;
import java.util.Set;

/**
 * 宸ュ叿鏍忓亸濂界殑璺ㄨ繘绋嬭鍙栧伐鍏枫€?
 * <p>
 * 閫氳繃 GodModeManagerService锛坰ystem_server 涓殑 AIDL 鏈嶅姟锛夎鍙?GodMode App 淇濆瓨鐨勫伐鍏锋爮鍋忓ソ銆?
 */
public final class ToolbarPrefsManager {

    private ToolbarPrefsManager() {
    }

    /**
     * 浠?GodModeManagerService 璇诲彇宸查殣钘忕殑椤圭洰闆嗗悎銆?
     * 杩斿洖绌洪泦鍚堣〃绀烘棤闅愯棌椤癸紙鍏ㄩ儴鏄剧ず锛夈€?
     */
    public static Set<String> loadHiddenItems() {
        try {
            GodModeManager gmm = GodModeManager.getDefault();
            if (gmm == null) return new HashSet<>();
            String value = gmm.getToolbarHiddenItems();
            if (!TextUtils.isEmpty(value)) {
                return parseCommaSeparated(value);
            }
        } catch (Throwable e) {
            Logger.w("ToolbarPrefs", "load hidden items failed", e);
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
