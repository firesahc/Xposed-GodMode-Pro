package com.kaisar.xposed.godmode.editor.action;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.editor.IRuleEditor;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.inject.ModuleBootstrap;
import com.kaisar.xposed.godmode.orchestrator.ViewController;
import com.kaisar.xposed.godmode.rule.RuleRecordFactory;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 屏蔽管理器 — 执行视图屏蔽操作：创建规则 + 粒子动画 + IPC 持久化。
 * <p>
 * 实际动画和 IPC 流程委托给 {@link ParticleEffectHelper} 统一处理。
 */
public final class BlockHandler {

    private static final String TAG = "BlockHandler";

    private BlockHandler() {}

    /**
     * 屏蔽操作回调接口。
     */
    public interface OnBlockListener {
        void onAnimationEnd(int blockedViewIndex);
        void onError(String message);
    }

    /**
     * 执行屏蔽操作：创建移除规则 → 粒子动画 → IPC 持久化。
     *
     * @param activity         当前 Activity
     * @param view             要屏蔽的目标视图
     * @param container        DecorView 容器
     * @param snapshot         屏蔽前的截图快照
     * @param blockedViewIndex 被屏蔽视图在列表中的索引
     * @param listener         操作回调
     */
    public static void execute(final Activity activity, final View view,
            final ViewGroup container, final Bitmap snapshot,
            final int blockedViewIndex, final OnBlockListener listener,
            final IRuleEditor ruleEditor) {
        Logger.i(TAG, "execute: blocking package=" + activity.getPackageName()
                + " viewClass=" + (view == null ? "null" : view.getClass().getName())
                + " viewId=" + (view == null ? -1 : view.getId())
                + " index=" + blockedViewIndex);
        try {
            final RuleRecord viewRule = RuleRecordFactory.makeRemoveRule(view, ModuleBootstrap.getEditorOrchestrator().isInfoFlowMode());
            ParticleEffectHelper.execute(activity, view, container, viewRule, snapshot,
                    activity.getPackageName(), /* maskView */ null,
                    ruleEditor, /* onComplete */ () -> {
                        if (listener != null) {
                            listener.onAnimationEnd(blockedViewIndex);
                        }
                    });
        } catch (Exception e) {
            Logger.e(TAG, "execute fail", e);
            if (listener != null) {
                listener.onError(e.getMessage() != null ? e.getMessage() : "block fail");
            }
        }
    }
}
