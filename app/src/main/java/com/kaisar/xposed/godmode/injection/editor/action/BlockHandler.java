package com.kaisar.xposed.godmode.injection.editor.action;

import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.bridge.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.RuleRecordFactory;
import com.kaisar.xposed.godmode.injection.editor.overlay.ParticleView;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 屏蔽管理器 — 执行视图屏蔽操作的完整流程：粒子动画 + IPC 持久化。
 * <p>
 * 由 {@code KeyInterceptor.performBlock()} 触发执行，包含三个步骤：
 * <ul>
 *   <li>创建 {@link ParticleView} 粒子破碎动画</li>
 *   <li>执行 {@link ViewController#applyRule} 应用移除规则</li>
 *   <li>通过 IPC 将规则和截图持久化到系统服务</li>
 * </ul>
 * <p>
 * 内部使用回调接口通知调用方动画结束或执行失败。
 */
public final class BlockHandler {

    private static final String TAG = "BlockHandler";

    private BlockHandler() {}

    /**
     * 屏蔽操作回调接口。
     */
    public interface OnBlockListener {
        /** 动画结束回调，返回被屏蔽视图的索引 */
        void onAnimationEnd(int blockedViewIndex);
        /** 操作失败回调，返回错误信息 */
        void onError(String message);
    }

    /**
     * 执行屏蔽操作：粒子动画 + IPC 持久化。
     * <p>
     * ParticleView 的 detach 由 onAnimationEnd 回调中的 finally 块保证执行。
     * 如果在 attachToContainer 与 boom 之间抛异常（如 boom 内部崩溃），
     * 外层 catch 会调用 listener.onError，但 ParticleView 无法在当前帧被
     * 安全清理——boom 通过 view.post() 异步启动动画，onAnimationEnd 最终会触发
     * detach。实际场景中 boom 不抛同步异常，此路径仅防极端崩溃。
     *
     * @param activity         当前 Activity
     * @param view             要屏蔽的目标视图
     * @param container        DecorView 容器，用于添加 ParticleView
     * @param snapshot         屏蔽前的截图快照，用于绘制屏蔽标记到 GM 存储
     * @param blockedViewIndex 被屏蔽视图在列表中的索引，用于更新 UI
     * @param listener         操作回调
     */
    public static void execute(final Activity activity, final View view,
            final ViewGroup container, final Bitmap snapshot,
            final int blockedViewIndex, final OnBlockListener listener) {
        Logger.i(TAG, "execute: blocking " + view + " in " + activity.getPackageName());
        try {
            final RuleRecord viewRule = RuleRecordFactory.makeRemoveRule(view);
            final ParticleView particleView = new ParticleView(activity);
            particleView.setDuration(GmConstants.PARTICLE_ANIM_DURATION_MS);
            particleView.attachToContainer(container);
            particleView.setOnAnimationListener(new ParticleView.OnAnimationListener() {
                @Override
                public void onAnimationStart(View animView, Animator animation) {
                    try {
                        viewRule.visibility = View.GONE;
                        ViewController.getDefault().applyRule(view, viewRule);
                    } catch (Exception e) {
                        Logger.e(TAG, "applyRule on animation start fail", e);
                    }
                }

                @Override
                public void onAnimationEnd(View animView, Animator animation) {
                    try {
                        BitmapUtils.drawRuleMask(snapshot, viewRule);
                    } catch (Exception e) {
                        Logger.e(TAG, "drawRuleMask fail", e);
                    } finally {
                        particleView.detachFromContainer();
                    }
                    if (listener != null) {
                        listener.onAnimationEnd(blockedViewIndex);
                    }
                    TaskExecutor.executeIo(() -> {
                        try {
                            RuleServiceClient.getDefault().writeRule(
                                    activity.getPackageName(), viewRule, snapshot);
                        } catch (Exception e) {
                            Logger.e(TAG, "writeRule fail", e);
                        }
                        recycleNullableBitmap(snapshot);
                    });
                }
            });
            particleView.boom(view);
            Logger.d(TAG, "execute: particle animation started for " + activity.getPackageName());
        } catch (Exception e) {
            Logger.e(TAG, "execute fail", e);
            if (listener != null) {
                listener.onError(e.getMessage() != null ? e.getMessage() : "block fail");
            }
        }
    }
}
