package com.kaisar.xposed.godmode.injection.hook;

import java.util.Optional;

public final class DisplayPropertiesHook extends BaseDebugLayoutHook {

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (mDebugLayout) {
            param.setResult(Optional.of(true));
        }
    }
}
