package com.kaisar.xposed.godmode;

import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;

interface IGodModeManager {

    boolean hasLight();

    void setEditMode(boolean enable);

    boolean isInEditMode();

    void addObserver(String packageName, in IObserver observer);

    void removeObserver(String packageName, in IObserver observer);

    AppRules getAllRules();

    ActRules getRules(String packageName);

    boolean writeRule(String packageName, in RuleRecord viewRule, in Bitmap bitmap);

    boolean updateRule(String packageName, in RuleRecord viewRule);

    boolean deleteRule(String packageName, in RuleRecord viewRule);

    boolean deleteRules(String packageName);

    ParcelFileDescriptor openImageFileDescriptor(String filePath);

    String saveImageFile(String packageName, in Bitmap bitmap);

    String getToolbarHiddenItems();

    void setToolbarHiddenItems(String items);

    /**
     * 应用进程通过 IPC 向 system_server 转发日志。
     * system_server 会将日志统一写入 godmodepro.log，
     * 格式为 "[packageName] level/tag: msg"。
     *
     * @param level       日志级别（参见 android.util.Log: DEBUG=3, INFO=4, WARN=5, ERROR=6）
     * @param packageName 日志来源应用包名
     * @param timestamp   日志产生时间戳（毫秒）
     * @param tag         日志标签
     * @param msg         日志消息
     */
    void log(int level, String packageName, long timestamp, String tag, String msg);
}
