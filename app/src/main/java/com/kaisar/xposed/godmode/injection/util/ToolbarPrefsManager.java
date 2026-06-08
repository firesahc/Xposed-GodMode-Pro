package com.kaisar.xposed.godmode.injection.util;

import android.text.TextUtils;

import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;

import java.util.HashSet;
import java.util.Set;

/**
 * 工具栏偏好的跨进程读取工具。
 * <p>
 * 通过 GodModeManagerService（system_server 中的 AIDL 服务）读取 GodMode App 保存的工具栏偏好。
 */
public final class ToolbarPrefsManager {

    private ToolbarPrefsManager() {
    }

    /**
     * 从 GodModeManagerService 读取已隐藏的项目集合。
     * 返回空集合表示无隐藏项（全部显示）。
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
            android.util.Log.w("ToolbarPrefs", "load hidden items failed", e);
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
