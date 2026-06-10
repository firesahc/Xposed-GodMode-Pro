package com.kaisar.xposed.godmode.injection.editor.gesture;

import android.graphics.Bitmap;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.util.CommonUtils;
import com.kaisar.xposed.godmode.injection.bridge.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.RuleRecordFactory;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 淇敼鎵嬪娍澶勭悊鍣?鈥?闀挎寜鎷栨嫿淇敼瑙嗗浘浣嶇疆 + 缃戞牸/杈圭紭鍚搁檮 + IPC 鎸佷箙鍖栥€?
 * 浠?EventHandlerHook 鎻愬彇鐨勪慨鏀规ā寮忎氦浜掗€昏緫銆?
 */
public final class ModifyGestureHandler {

    private static final int GRID_SIZE_DP = 16;
    private static final int EDGE_SNAP_THRESHOLD_DP = 12;

    private ModifyGestureHandler() {}

    /** 寮€濮嬫嫋鎷藉綋鍓嶉€変腑鐨勮鍥?*/
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
        target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        return state;
    }

    /** 绉诲姩鎷栨嫿鐩爣锛屽簲鐢ㄧ綉鏍?鍏勫紵杈圭紭鍚搁檮 */
    public static void moveTarget(ModifyState state, float dx, float dy) {
        if (state == null || state.dragTarget == null) return;

        int newMarginX = state.startMarginX + (int) dx;
        int newMarginY = state.startMarginY + (int) dy;

        newMarginX = Math.round(newMarginX / (float) state.gridSizePx) * state.gridSizePx;
        newMarginY = Math.round(newMarginY / (float) state.gridSizePx) * state.gridSizePx;

        int[] snapped = ViewSnapper.snapToSiblings(state.dragTarget,
                newMarginX, newMarginY, state.snapThresholdPx);
        newMarginX = snapped[0];
        newMarginY = snapped[1];

        ViewGroup.LayoutParams lp = state.dragTarget.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            mlp.leftMargin = newMarginX;
            mlp.topMargin = newMarginY;
            state.dragTarget.setLayoutParams(mlp);
        }
    }

    /** 灏嗘渶缁堟嫋鎷戒綅缃寔涔呭寲涓轰慨鏀硅鍒?*/
    public static void finalizeDrag(ModifyState state, String packageName) {
        if (state == null || state.dragTarget == null) return;
        ViewGroup.LayoutParams lp = state.dragTarget.getLayoutParams();
        int finalMarginX = 0, finalMarginY = 0;
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            finalMarginX = mlp.leftMargin;
            finalMarginY = mlp.topMargin;
        }
        int deltaX = finalMarginX - state.startMarginX;
        int deltaY = finalMarginY - state.startMarginY;

        if (deltaX != 0 || deltaY != 0) {
            RuleRecord rule = RuleRecordFactory.makeModifyRule(state.dragTarget);
            rule.origLeftMargin = state.startMarginX;
            rule.origTopMargin = state.startMarginY;
            rule.modXOffset = deltaX;
            rule.modYOffset = deltaY;
            RuleRecordFactory.fillCoordinates(rule, state.dragTarget);
            Bitmap snapshot = BitmapUtils.snapshotView(
                    ViewUtils.findTopParentViewByChildView(state.dragTarget));
            BitmapUtils.drawRuleMask(snapshot, rule);
            RuleServiceClient.getDefault().writeRule(packageName, rule, snapshot);
            CommonUtils.recycleNullableBitmap(snapshot);
        }
    }

    /** 淇敼妯″紡鐘舵€佸鍣?*/
    public static final class ModifyState {
        public View dragTarget;
        public int startMarginX, startMarginY;
        public int gridSizePx, snapThresholdPx;
    }
}
