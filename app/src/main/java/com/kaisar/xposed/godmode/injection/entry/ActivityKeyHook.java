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
 * <p>
 * 按键逻辑：
 * <ul>
 *   <li>双击音量键（350ms 内两次松开）→ 开关编辑面板</li>
 *   <li>单击音量键（面板开启时）→ 逐个切换元素</li>
 *   <li>长按音量键（面板开启时）→ 利用系统按键重复机制快速切换元素</li>
 * </ul>
 */
public final class ActivityKeyHook extends XC_MethodHook {

    /** 双击检测间隔（毫秒），两次松开在此时间内视为双击 */
    private static final long DOUBLE_CLICK_INTERVAL = 350L;

    private final EditorOrchestrator mOrchestrator;
    /** 上次音量键松开的时间戳，用于双击检测 */
    private long mLastVolumeUpTime;

    public ActivityKeyHook(EditorOrchestrator orchestrator) {
        this.mOrchestrator = orchestrator;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        if (!HookLauncher.isSwitchEnabled() || mOrchestrator.isDragging()) return;
        Activity activity = (Activity) param.thisObject;
        KeyEvent event = (KeyEvent) param.args[0];
        int action = event.getAction();
        int keyCode = event.getKeyCode();

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_UP) {
                // 双击检测：两次松开在 DOUBLE_CLICK_INTERVAL 内 → 开关面板
                long now = System.currentTimeMillis();
                long lastTime = (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                        ? mLastVolumeUpTime : mLastVolumeDownTime;

                if (now - lastTime > 0 && now - lastTime < DOUBLE_CLICK_INTERVAL) {
                    mOrchestrator.onVolumeKeyToggle(activity);
                    // 重置时间戳，避免三击被误判为两次双击
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        mLastVolumeUpTime = 0;
                    } else {
                        mLastVolumeDownTime = 0;
                    }
                } else {
                    // 记录本次松开时间，供下次双击检测
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        mLastVolumeUpTime = now;
                    } else {
                        mLastVolumeDownTime = now;
                    }
                }
            } else if (action == KeyEvent.ACTION_DOWN && mOrchestrator.isKeySelecting()) {
                // 面板开启时，按下即导航；系统按键重复机制自然实现长按快速切换
                mOrchestrator.onVolumeKeyNavigate(keyCode);
            }
            param.setResult(true);
        }
    }

    /** 上次音量减键松开的时间戳，用于双击检测 */
    private long mLastVolumeDownTime;
}
