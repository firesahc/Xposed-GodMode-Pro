package com.kaisar.xposed.godmode.injection.editor.gesture;

import android.graphics.Bitmap;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.util.CommonUtils;
import com.kaisar.xposed.godmode.editor.IRuleEditor;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.rule.RuleRecordFactory;
import com.kaisar.xposed.godmode.rule.ViewSnapshot;

/**
 * 修改手势处理器：长按拖拽修改视图位置，支持网格和兄弟视图边缘吸附，并通过 IPC 持久化。
 * 从 EventHandlerHook 提取的修改模式交互逻辑。
 */
public final class ModifyGestureHandler {

    private static final int GRID_SIZE_DP = 16;
    private static final int EDGE_SNAP_THRESHOLD_DP = 12;

    private ModifyGestureHandler() {}

    /** 开始拖拽当前选中的视图 */
    public static ModifyState startDrag(View target) {
        if (target == null) return null;
        ModifyState state = new ModifyState();
        state.dragTarget = target;

        float density = target.getResources().getDisplayMetrics().density;
        state.gridSizePx = (int) (GRID_SIZE_DP * density + 0.5f);
        state.snapThresholdPx = (int) (EDGE_SNAP_THRESHOLD_DP * density + 0.5f);

        ViewGroup.LayoutParams lp = target.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            state.startMarginX = mlp.leftMargin;
            state.startMarginY = mlp.topMargin;
        }
        // 在视图位置被修改前捕获原始状态快照
        state.snapshot = ViewSnapshot.capture(target);

        target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        return state;
    }

    /**
     * 移动拖拽目标，使用 translation（渲染级变换）避免每次 MOVE 触发布局重排。
     * 网格对齐和兄弟吸附照旧计算，但通过 {@link View#setTranslationX} / {@link View#setTranslationY}
     * 应用偏移，不修改 MarginLayoutParams。最终偏移量在 {@link #finalizeDrag} 中落实为 modXOffset/modYOffset。
     */
    public static void moveTarget(ModifyState state, float dx, float dy) {
        if (state == null || state.dragTarget == null) return;

        int newMarginX = state.startMarginX + (int) dx;
        int newMarginY = state.startMarginY + (int) dy;

        newMarginX = Math.round(newMarginX / (float) state.gridSizePx) * state.gridSizePx;
        newMarginY = Math.round(newMarginY / (float) state.gridSizePx) * state.gridSizePx;

        int[] snapped = ViewSnapper.snapToSiblings(state.dragTarget,
                newMarginX, newMarginY, state.snapThresholdPx);

        int deltaX = snapped[0] - state.startMarginX;
        int deltaY = snapped[1] - state.startMarginY;

        state.dragTarget.setTranslationX(deltaX);
        state.dragTarget.setTranslationY(deltaY);
        state.totalDx = deltaX;
        state.totalDy = deltaY;
    }

    /**
     * 将最终拖拽位置持久化为修改规则。
     * 快照捕获（View.draw）在主线程执行，IPC 写入通过 {@link TaskExecutor#executeIo} 异步执行。
     */
    public static void finalizeDrag(ModifyState state, String packageName, IRuleEditor ruleEditor) {
        if (state == null || state.dragTarget == null) return;

        // 重置 translation——偏移量已记录在 totalDx/totalDy
        state.dragTarget.setTranslationX(0);
        state.dragTarget.setTranslationY(0);

        int deltaX = state.totalDx;
        int deltaY = state.totalDy;

        if (deltaX != 0 || deltaY != 0) {
            // 主线程：创建规则 + 截图（View.draw 必须在主线程）
            RuleRecord rule = RuleRecordFactory.makeModifyRule(state.dragTarget, state.snapshot);
            rule.modXOffset = deltaX;
            rule.modYOffset = deltaY;
            Bitmap snapshot = BitmapUtils.snapshotView(
                    ViewUtils.findTopParentViewByChildView(state.dragTarget));
            BitmapUtils.drawRuleMask(snapshot, rule);

            // IO 线程：仅 IPC 写入（参照 PropertyEditorPanel.saveAll 的 TaskExecutor.executeIo 模式）
            final RuleRecord finalRule = rule;
            TaskExecutor.executeIo(() -> {
                ruleEditor.writeRule(packageName, finalRule, snapshot);
                CommonUtils.recycleNullableBitmap(snapshot);
            });
        }
    }

    /** 修改模式状态容器 */
    public static final class ModifyState {
        public View dragTarget;
        public int startMarginX, startMarginY;
        public int gridSizePx, snapThresholdPx;
        /** View snapshot captured at drag start (before modification applies). */
        public ViewSnapshot snapshot;
        /** 拖拽过程中 setTranslation 的累计像素偏移（用于 finalizeDrag 计算 modXOffset/modYOffset） */
        public int totalDx;
        public int totalDy;
    }
}
