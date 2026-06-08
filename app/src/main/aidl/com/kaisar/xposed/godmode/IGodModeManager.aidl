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
}
