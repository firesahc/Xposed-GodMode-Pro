package com.kaisar.xposed.godmode.injection.hook;

import com.kaisar.xposed.godmode.injection.util.Property;

import de.robv.android.xposed.XC_MethodHook;

abstract class BaseDebugLayoutHook extends XC_MethodHook implements Property.OnPropertyChangeListener<Boolean> {

    protected boolean mDebugLayout;

    @Override
    public void onPropertyChange(Boolean debugLayout) {
        mDebugLayout = debugLayout;
    }
}
