package com.kaisar.xposed.godmode.editor;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * 编辑器交互端口 — inject 层与 editor 层的交互边界。
 * <p>
 * 归属约定：
 * <ul>
 *   <li>inject 层只持有本端口（不过问实现类），Hook 只做过滤与透传；</li>
 *   <li>Editor（{@link EditorOrchestrator}）实现本端口，拥有交互政策；</li>
 *   <li>音量键过滤（是否为音量键、门控、参数合法性）归 Hook；</li>
 *   <li>时机与导航政策（ACTION_UP 双击 350ms 开关、ACTION_DOWN 选中态导航、消费返回语义）归 Editor。</li>
 * </ul>
 */
public interface EditorInteractionPort {

    /**
     * 触摸事件入口，由 TouchHook 过滤透传。
     *
     * @param view  触发触摸的视图
     * @param event 触摸事件
     * @return true 表示已消费（Hook 应 setResult(true)），false 表示放行宿主行为
     */
    boolean onTouchEvent(View view, MotionEvent event);

    /**
     * 音量键事件入口，由 KeyHook 做音量键过滤后透传。
     * <p>
     * Editor 承接时机与导航政策：ACTION_UP 双击判定与开关切换、
     * ACTION_DOWN 选中态导航；消费时返回 true。
     *
     * @param activity 当前 Activity
     * @param event    按键事件（Hook 已过滤为音量键）
     * @return true 表示已消费（Hook 应 setResult(true)），false 表示放行宿主行为
     */
    boolean onVolumeKeyEvent(Activity activity, KeyEvent event);
}
