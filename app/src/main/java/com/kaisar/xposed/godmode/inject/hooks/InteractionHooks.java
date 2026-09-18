package com.kaisar.xposed.godmode.inject.hooks;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.kaisar.xposed.godmode.inject.HookRegistry;
import com.kaisar.xposed.godmode.editor.EditorInteractionPort;
import com.kaisar.xposed.godmode.engine.util.Logger;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 交互 Hook 集合 — 触摸事件和按键事件。
 * <p>
 * 合并自 {@code injection/entry/TouchHook} 和 {@code injection/entry/ActivityKeyHook}。
 * 由 {@link com.kaisar.xposed.godmode.inject.HookRegistry} 注册。
 * <p>
 * 职责：只做过滤与透传。音量键过滤归 Hook，时机导航政策归 Editor
 * （见 {@link EditorInteractionPort}）。
 */
public final class InteractionHooks {

    private InteractionHooks() {}

    // =========================================================================
    // TouchHook — 触摸事件拦截
    // =========================================================================

    /**
     * 拦截 {@link View#dispatchTouchEvent} 将触摸事件转发给
     * {@link EditorInteractionPort} 进行编辑模式手势处理。
     */
    public static final class TouchHook extends XC_MethodHook {
        private final EditorInteractionPort mPort;

        public TouchHook(EditorInteractionPort port) {
            this.mPort = port;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!HookRegistry.isEditorEnabled()) return;
            if (!(param.thisObject instanceof View)
                    || param.args == null || param.args.length == 0
                    || !(param.args[0] instanceof MotionEvent)
                    || mPort == null) {
                return;
            }
            try {
                View view = (View) param.thisObject;
                MotionEvent event = (MotionEvent) param.args[0];
                if (mPort.onTouchEvent(view, event)) {
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
     * 只做音量键过滤，时机与导航政策（双击 350ms 开关、选中态导航）归 Editor，
     * 经 {@link EditorInteractionPort#onVolumeKeyEvent} 分发，按返回消费。
     * <p>
     * 按键逻辑：
     * <ul>
     *   <li>双击音量键（350ms 内两次松开）→ 开关编辑面板</li>
     *   <li>单击音量键（面板开启时）→ 逐个切换元素</li>
     *   <li>长按音量键（面板开启时）→ 利用系统按键重复机制快速切换元素</li>
     * </ul>
     */
    public static final class KeyHook extends XC_MethodHook {

        private final EditorInteractionPort mPort;

        public KeyHook(EditorInteractionPort port) {
            this.mPort = port;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!HookRegistry.isEditorEnabled()) return;
            if (!(param.thisObject instanceof Activity)
                    || param.args == null || param.args.length == 0
                    || !(param.args[0] instanceof KeyEvent)
                    || mPort == null) {
                return;
            }

            Activity activity = (Activity) param.thisObject;
            KeyEvent event = (KeyEvent) param.args[0];
            int keyCode = event.getKeyCode();
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                    && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                return;
            }

            boolean handled = false;
            try {
                handled = mPort.onVolumeKeyEvent(activity, event);
            } catch (Throwable failure) {
                Logger.w("InteractionHooks", "key hook failed; keeping host behavior", failure);
                handled = false;
            }
            if (handled) param.setResult(true);
        }
    }
}
