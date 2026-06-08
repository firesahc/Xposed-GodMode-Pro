package com.kaisar.xposed.godmode.injection.editor.action;

import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.kaisar.xposed.godmode.injection.editor.RuleRecordFactory;
import com.kaisar.xposed.godmode.injection.editor.BitmapUtils;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.editor.overlay.ParticleView;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 屏蔽（移除）操作处理器 — 粒子动画播放 + IPC 规则写入。
 * <p>
 * 从 {@code KeyInterceptor.performBlock()} 提取，职责单一：
 * <ul>
 *   <li>创建 {@link ParticleView} 并播放爆炸粒子动画</li>
 *   <li>动画开始时应用移除规则（{@link ViewController#applyRule}）</li>
 *   <li>动画结束时绘制规则遮罩、通过 IPC 写入规则文件</li>
 * </ul>
 * <p>
 * 调用方负责视图获取、校验、截图及面板生命周期回调。
 */
public final class BlockHandler {

    private BlockHandler() {}

    /**
     * 屏蔽操作完成/失败回调。
     */
    public interface OnBlockListener {
        /** 粒子动画结束、规则写入已提交后调用。 */
        void onAnimationEnd(int blockedViewIndex);
        /** 操作过程中发生异常。 */
        void onError(String message);
    }

    /**
     * 执行屏蔽操作：粒子动画 + IPC 写入。
     *
     * @param activity         当前 Activity
     * @param view             被屏蔽的目标视图
     * @param container        DecorView 容器（ParticleView 附着目标）
     * @param snapshot         屏蔽前干净截图（已隐藏 GM 覆盖层后截取）
     * @param blockedViewIndex 被屏蔽视图在节点列表中的索引
     * @param listener         回调
     */
    public static void execute(final Activity activity, final View view,
            final ViewGroup container, final Bitmap snapshot,
            final int blockedViewIndex, final OnBlockListener listener) {
        try {
            final RuleRecord viewRule = RuleRecordFactory.makeRemoveRule(view);
            final ParticleView particleView = new ParticleView(activity);
            particleView.setDuration(1000);
            particleView.attachToContainer(container);
            particleView.setOnAnimationListener(new ParticleView.OnAnimationListener() {
                @Override
                public void onAnimationStart(View animView, Animator animation) {
                    viewRule.visibility = View.GONE;
                    ViewController.getDefault().applyRule(view, viewRule);
                }

                @Override
                public void onAnimationEnd(View animView, Animator animation) {
                    try {
                        BitmapUtils.drawRuleMask(snapshot, viewRule);
                        particleView.detachFromContainer();
                    } catch (Exception e) {
                        Logger.e("BlockHandler", "drawRuleMask / detach fail", e);
                    }
                    if (listener != null) {
                        listener.onAnimationEnd(blockedViewIndex);
                    }
                    TaskExecutor.executeIo(() -> {
                        try {
                            GodModeManager.getDefault().writeRule(
                                    activity.getPackageName(), viewRule, snapshot);
                        } catch (Exception e) {
                            Logger.e("BlockHandler", "writeRule fail", e);
                        }
                        recycleNullableBitmap(snapshot);
                    });
                }
            });
            particleView.boom(view);
        } catch (Exception e) {
            Logger.e("BlockHandler", "execute fail", e);
            if (listener != null) {
                listener.onError(e.getMessage() != null ? e.getMessage() : "block fail");
            }
        }
    }
}
