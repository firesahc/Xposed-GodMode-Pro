package com.kaisar.xposed.godmode.inject.hooks;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.os.SystemClock;

import com.kaisar.xposed.godmode.inject.HookRegistry;
import com.kaisar.xposed.godmode.editor.EditorOrchestrator;
import com.kaisar.xposed.godmode.engine.util.Logger;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 交互 Hook 集合 — 触摸事件和按键事件。
 * <p>
 * 合并自 {@code injection/entry/TouchHook} 和 {@code injection/entry/ActivityKeyHook}。
 * 由 {@link com.kaisar.xposed.godmode.inject.HookRegistry} 注册。
 */
public final class InteractionHooks {

    private InteractionHooks() {}

    // =========================================================================
    // TouchHook — 触摸事件拦截
    // =========================================================================

    /**
     * 拦截 {@link View#dispatchTouchEvent} 将触摸事件转发给
     * {@link EditorOrchestrator} 进行编辑模式手势处理。
     */
    public static final class TouchHook extends XC_MethodHook {
        private final EditorOrchestrator mOrchestrator;

        public TouchHook(EditorOrchestrator orchestrator) {
            this.mOrchestrator = orchestrator;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!HookRegistry.isEditorEnabled()) return;
            if (!(param.thisObject instanceof View)
                    || param.args == null || param.args.length == 0
                    || !(param.args[0] instanceof MotionEvent)
                    || mOrchestrator == null) {
                return;
            }
            try {
                View view = (View) param.thisObject;
                MotionEvent event = (MotionEvent) param.args[0];
                if (mOrchestrator.onTouchEvent(view, event)) {
                    param.setResult(true);
                }
            } catch (Throwable failure) {
                Logger.w("InteractionHooks", "touch hook failed; keeping host behavior", failure);
            }
        }
    }

    // =========================================================================
    // KeyHook — 按键事件拦截（合并原 ActivityKeyHook）
    // =========================================================================

    /**
     * 拦截 {@link Activity#dispatchKeyEvent} 处理音量键。
     * <p>
     * 将音量键事件转发给 {@link EditorOrchestrator} 进行面板切换与导航。
     * <p>
     * 按键逻辑：
     * <ul>
     *   <li>双击音量键（350ms 内两次松开）→ 开关编辑面板</li>
     *   <li>单击音量键（面板开启时）→ 逐个切换元素</li>
     *   <li>长按音量键（面板开启时）→ 利用系统按键重复机制快速切换元素</li>
     * </ul>
     */
    public static final class KeyHook extends XC_MethodHook {

        /** 双击检测间隔（毫秒） */
        private static final long DOUBLE_CLICK_INTERVAL = 350L;

        private final EditorOrchestrator mOrchestrator;
        private long mLastVolumeUpTime;
        private long mLastVolumeDownTime;

        public KeyHook(EditorOrchestrator orchestrator) {
            this.mOrchestrator = orchestrator;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!HookRegistry.isEditorEnabled()) return;
            if (!(param.thisObject instanceof Activity)
                    || param.args == null || param.args.length == 0
                    || !(param.args[0] instanceof KeyEvent)
                    || mOrchestrator == null) {
                return;
            }

            Activity activity = (Activity) param.thisObject;
            KeyEvent event = (KeyEvent) param.args[0];
            int action = event.getAction();
            int keyCode = event.getKeyCode();
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                    && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                return;
            }

            boolean handled = false;
            try {
                if (action == KeyEvent.ACTION_UP) {
                    long now = SystemClock.uptimeMillis();
                    long lastTime = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                            ? mLastVolumeUpTime : mLastVolumeDownTime;
                    if (now - lastTime > 0 && now - lastTime < DOUBLE_CLICK_INTERVAL) {
                        mOrchestrator.onVolumeKeyToggle(activity);
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                            mLastVolumeUpTime = 0L;
                        } else {
                            mLastVolumeDownTime = 0L;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        mLastVolumeUpTime = now;
                    } else {
                        mLastVolumeDownTime = now;
                    }
                    // Volume events are intentionally consumed while editor mode is on.
                    handled = true;
                } else if (action == KeyEvent.ACTION_DOWN) {
                    if (mOrchestrator.isKeySelecting()) {
                        mOrchestrator.onVolumeKeyNavigate(keyCode);
                    }
                    handled = true;
                }
            } catch (Throwable failure) {
                Logger.w("InteractionHooks", "key hook failed; keeping host behavior", failure);
                handled = false;
            }
            if (handled) param.setResult(true);
        }
    }
}
