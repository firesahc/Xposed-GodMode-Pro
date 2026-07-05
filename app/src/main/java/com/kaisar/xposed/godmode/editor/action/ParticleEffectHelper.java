package com.kaisar.xposed.godmode.editor.action;

import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.runtime.ViewController;
import com.kaisar.xposed.godmode.editor.IRuleEditor;
import com.kaisar.xposed.godmode.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.editor.overlay.ParticleView;
import com.kaisar.xposed.godmode.util.BitmapUtils;
import com.kaisar.xposed.godmode.util.TaskExecutor;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 粒子动画辅助工具 — 统一 BlockHandler 和 RemoveGestureHandler 中共用的
 * 粒子破碎动画 + IPC 持久化流程。
 * <p>
 * 流程：创建 ParticleView → 启动动画 → onAnimationStart 中应用规则 +
 * 截图遮罩 + 分离遮罩 → onAnimationEnd 中异步 IPC 写入 + Bitmap 回收。
 */
public final class ParticleEffectHelper {

    private static final String TAG = "ParticleEffectHelper";

    private ParticleEffectHelper() {}

    /**
     * 执行粒子动画 + IPC 持久化的完整流程。
     *
     * @param activity     当前 Activity
     * @param targetView   粒子动画的锚点视图（用于 boom() 方法定位）
     * @param container    DecorView 容器
     * @param viewRule     已创建的移除规则记录
     * @param snapshot     屏蔽前的截图快照
     * @param packageName  目标应用包名
     * @param maskView     遮罩视图（动画结束后分离），可为 null
     * @param onComplete   动画 + IPC 全部完成后的回调（在主线程执行），可为 null
     */
    public static void execute(final Activity activity,
            final View targetView,
            final ViewGroup container,
            final RuleRecord viewRule,
            final Bitmap snapshot,
            final String packageName,
            final MaskView maskView,
            final IRuleEditor ruleEditor,
            final Runnable onComplete) {
        Logger.d(TAG, "execute: starting particle animation for " + packageName);

        final ParticleView particleView = new ParticleView(activity);
        particleView.setDuration(GmConstants.PARTICLE_ANIM_DURATION_MS);
        particleView.attachToContainer(container);
        particleView.setOnAnimationListener(new ParticleView.OnAnimationListener() {
            @Override
            public void onAnimationStart(View animView, Animator animation) {
                try {
                    viewRule.visibility = View.GONE;
                    ViewController.getDefault().applyRule(targetView, viewRule);
                    if (snapshot != null) {
                        BitmapUtils.drawRectMask(snapshot, viewRule.x, viewRule.y, viewRule.width, viewRule.height);
                    }
                } catch (Exception e) {
                    Logger.e(TAG, "applyRule/drawRuleMask on animation start fail", e);
                }
                if (maskView != null) {
                    maskView.detachFromContainer();
                }
            }

            @Override
            public void onAnimationEnd(View animView, Animator animation) {
                try {
                    particleView.detachFromContainer();
                } catch (Exception e) {
                    Logger.e(TAG, "detachFromContainer fail", e);
                }
                // 异步 IO 线程执行 IPC 写入
                TaskExecutor.executeIo(() -> {
                    try {
                        ruleEditor.writeRule(packageName, viewRule, snapshot);
                    } catch (Exception e) {
                        Logger.e(TAG, "writeRule fail: " + packageName, e);
                    }
                    recycleNullableBitmap(snapshot);
                });
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
        particleView.boom(targetView);
    }
}
