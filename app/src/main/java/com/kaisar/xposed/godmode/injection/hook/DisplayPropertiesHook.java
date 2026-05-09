package com.kaisar.xposed.godmode.injection.hook;

import android.os.Build;

import androidx.annotation.RequiresApi;

import java.util.Optional;

public final class DisplayPropertiesHook extends BaseDebugLayoutHook {

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (mDebugLayout) {
            param.setResult(Optional.of(true));
        }
    }
}
