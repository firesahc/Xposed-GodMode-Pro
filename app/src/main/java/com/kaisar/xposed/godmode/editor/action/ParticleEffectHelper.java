package com.kaisar.xposed.godmode.editor.action;

import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.ipc.RuleServiceContract;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationResult;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.orchestrator.RuleLifecycleManager;
import com.kaisar.xposed.godmode.orchestrator.ViewController;
import com.kaisar.xposed.godmode.editor.IRuleEditor;
import com.kaisar.xposed.godmode.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.editor.overlay.ParticleView;
import com.kaisar.xposed.godmode.util.BitmapUtils;
import com.kaisar.xposed.godmode.util.GmResources;
import com.kaisar.xposed.godmode.util.TaskExecutor;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.engine.rule.RemoveEffect;

/**
 * 粒子动画辅助工具 — 执行工具栏移除操作的粒子动画和 IPC 持久化流程。
 * <p>
 * 流程：创建 ParticleView → 启动动画 → onAnimationStart 中应用规则 +
 * 截图遮罩 + 分离遮罩 → onAnimationEnd 中异步 IPC 写入 + Bitmap 回收。
 */
public final class ParticleEffectHelper {

    private static final String TAG = "ParticleEffectHelper";

    private ParticleEffectHelper() {}

    public interface Completion {
        void onCommitted(UndoStateParcel undoState);
        void onError(String message);
    }

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
     * @param completion   权威提交完成后的主线程回调，可为 null
     */
    public static void execute(final Activity activity,
            final View targetView,
            final ViewGroup container,
            final RuleRecord viewRule,
            final Bitmap snapshot,
            final String packageName,
            final MaskView maskView,
            final IRuleEditor ruleEditor,
            final Completion completion) {
        Logger.d(TAG, "execute: starting particle animation for " + packageName);

        final RuleRecord ruleToWrite = viewRule.withEffect(RemoveEffect.of(View.GONE));
        final ViewController controller = RuleLifecycleManager.getInstance()
                .getViewController(activity);
        final boolean[] runtimeApplied = { false };
        final ParticleView particleView = new ParticleView(activity);
        particleView.setDuration(GmConstants.PARTICLE_ANIM_DURATION_MS);
        particleView.attachToContainer(container);
        particleView.setOnAnimationListener(new ParticleView.OnAnimationListener() {
            @Override
            public void onAnimationStart(View animView, Animator animation) {
                try {
                    runtimeApplied[0] = controller.applyRule(targetView, ruleToWrite);
                    if (!runtimeApplied[0]) {
                        Logger.w(TAG, "activity controller rejected optimistic block apply");
                    }
                    if (snapshot != null) {
                        BitmapUtils.drawRectMask(snapshot, ruleToWrite.x, ruleToWrite.y,
                                ruleToWrite.width, ruleToWrite.height);
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
                if (!runtimeApplied[0]) {
                    recycleNullableBitmap(snapshot);
                    if (completion != null) completion.onError(GmResources.getString(
                            R.string.toast_runtime_apply_failed));
                    return;
                }
                // 异步 IO 线程执行 IPC 写入；仅权威提交后才报告完成。
                TaskExecutor.executeIo(() -> {
                    RuleMutationResult result = null;
                    try {
                        result = ruleEditor.writeUndoableRule(packageName, ruleToWrite, snapshot,
                                null);
                    } catch (Exception e) {
                        Logger.e(TAG, "writeUndoableRule fail: " + packageName, e);
                    }
                    final RuleMutationResult finalResult = result;
                    recycleNullableBitmap(snapshot);
                    activity.runOnUiThread(() -> {
                        if (isCommitted(finalResult)) {
                            if (completion != null) completion.onCommitted(finalResult.undoState);
                            return;
                        }
                        controller.revokeRule(targetView, ruleToWrite);
                        if (completion != null) {
                            String reason = finalResult == null ? null : finalResult.message;
                            completion.onError(reason == null
                                    ? GmResources.getString(R.string.toast_rule_service_rejected)
                                    : reason);
                        }
                    });
                });
            }
        });
        particleView.boom(targetView);
    }

    private static boolean isCommitted(RuleMutationResult result) {
        return result != null && (result.status == RuleServiceContract.RESULT_COMMITTED
                || result.status == RuleServiceContract.RESULT_NO_CHANGE);
    }
}
