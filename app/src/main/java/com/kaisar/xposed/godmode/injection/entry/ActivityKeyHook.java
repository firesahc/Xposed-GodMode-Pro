package com.kaisar.xposed.godmode.injection.entry;

import android.app.Activity;
import android.view.KeyEvent;

import com.kaisar.xposed.godmode.injection.HookLauncher;
import com.kaisar.xposed.godmode.injection.editor.EditorOrchestrator;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 薄 Hook — 拦截 {@link Activity#dispatchKeyEvent} 处理音量键。
 * <p>
 * 将音量键事件转发给 {@link EditorOrchestrator} 进行面板切换与导航。
 */
public final class ActivityKeyHook extends XC_MethodHook {
    private final EditorOrchestrator mOrchestrator;

    public ActivityKeyHook(EditorOrchestrator orchestrator) {
        this.mOrchestrator = orchestrator;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        if (!HookLauncher.switchProp.get() || mOrchestrator.isDragging()) return;
        Activity activity = (Activity) param.thisObject;
        KeyEvent event = (KeyEvent) param.args[0];
        int action = event.getAction();
        int keyCode = event.getKeyCode();

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_UP) {
                mOrchestrator.onVolumeKeyToggle(activity);
            } else if (action == KeyEvent.ACTION_DOWN && mOrchestrator.isKeySelecting()) {
                mOrchestrator.onVolumeKeyNavigate(keyCode);
            }
            param.setResult(true);
        }
    }
}
