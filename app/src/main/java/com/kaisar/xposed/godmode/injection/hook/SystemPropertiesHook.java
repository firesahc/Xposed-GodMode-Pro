package com.kaisar.xposed.godmode.injection.hook;


import com.kaisar.xposed.godmode.injection.util.Property;

import de.robv.android.xposed.XC_MethodHook;

public final class SystemPropertiesHook extends XC_MethodHook implements Property.OnPropertyChangeListener<Boolean> {

    private boolean mDebugLayout;

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (mDebugLayout && "debug.layout".equals(param.args[0])) {
            param.setResult(true);
        }
    }

    @Override
    public void onPropertyChange(Boolean debugLayout) {
        mDebugLayout = debugLayout;
    }
}