package com.kaisar.xposed.godmode.injection.entry;

import android.view.MotionEvent;
import android.view.View;

import com.kaisar.xposed.godmode.injection.editor.EditorOrchestrator;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 薄 Hook — 拦截 {@link View#dispatchTouchEvent} 将触摸事件转发给
 * {@link EditorOrchestrator} 进行编辑模式手势处理。
 */
public final class TouchHook extends XC_MethodHook {
    private final EditorOrchestrator mOrchestrator;

    public TouchHook(EditorOrchestrator orchestrator) {
        this.mOrchestrator = orchestrator;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        View view = (View) param.thisObject;
        MotionEvent event = (MotionEvent) param.args[0];
        boolean handled = mOrchestrator.onTouchEvent(view, event);
        if (handled) param.setResult(true);
    }
}
