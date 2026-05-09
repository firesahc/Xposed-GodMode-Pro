package com.kaisar.xposed.godmode.injection.hook;

public final class SystemPropertiesStringHook extends BaseDebugLayoutHook {

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (mDebugLayout && "debug.layout".equals(param.args[0])) {
            param.setResult("true");
        }
    }
}
