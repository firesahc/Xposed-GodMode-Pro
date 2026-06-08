package com.kaisar.xposed.godmode.injection.editor;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;
import static com.kaisar.xposed.godmode.engine.util.GmConstants.TAG_GM_CMP;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.TooltipCompat;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.EditorInteractionMode;
import com.kaisar.xposed.godmode.engine.traversal.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.Property;
import com.kaisar.xposed.godmode.injection.editor.action.BlockHandler;
import com.kaisar.xposed.godmode.injection.editor.action.PreviewHandler;
import com.kaisar.xposed.godmode.injection.editor.gesture.GestureDispatcher;
import com.kaisar.xposed.godmode.injection.editor.gesture.ModifyGestureHandler;
import com.kaisar.xposed.godmode.injection.editor.gesture.RemoveGestureHandler;
import com.kaisar.xposed.godmode.injection.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.injection.editor.panel.NodeSelectorPanel;
import com.kaisar.xposed.godmode.injection.editor.panel.PropertyEditorPanel;
import com.kaisar.xposed.godmode.injection.editor.panel.SeekBarHandler;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.util.GmResources;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.injection.util.ToolbarVisibilityController;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;

/**
 * 编辑器编排器 — 融合 KeyInterceptor 与 TouchInterceptor 的所有非 Hook 业务逻辑。
 * <p>
 * 管理节点选择面板、属性编辑器、触摸手势分发、音量键导航、屏蔽/预览操作，
 * 以及编辑模式的生命周期。由 {@link com.kaisar.xposed.godmode.injection.entry.ActivityKeyHook}
 * 和 {@link com.kaisar.xposed.godmode.injection.entry.TouchHook} 调用。
 */
public final class EditorOrchestrator implements Property.OnPropertyChangeListener<Boolean> {

    // =========================================================================
    // 常量
    // =========================================================================

    private static final int OVERLAY_COLOR = Color.argb(150, 255, 0, 0);
    @SuppressWarnings("unused")
    private static final int OVERLAY_COLOR_REPEATABLE = Color.argb(150, 255, 165, 0);
    private static final int LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout();

    // =========================================================================
    // 交互模式（原 KeyInterceptor）
    // =========================================================================

    private int mInteractionMode = EditorInteractionMode.INITIAL;
    private boolean mInfoFlowMode = false;

    // =========================================================================
    // 预览状态（原 KeyInterceptor）
    // =========================================================================

    final PreviewHandler mPreviewHandler = new PreviewHandler();

    // =========================================================================
    // 子控制器（原 KeyInterceptor）
    // =========================================================================

    private final NodeSelectorPanel mNodePanel = new NodeSelectorPanel();
    final PropertyEditorPanel mPropertyEditor = new PropertyEditorPanel();
    private final SeekBarHandler mSeekBarHandler = new SeekBarHandler(mNodePanel, mPropertyEditor);
    private WeakReference<Activity> mCurrentActivityRef = new WeakReference<>(null);

    // =========================================================================
    // 节点选择器面板回调（原 KeyInterceptor.NodeSelectorPanel.Callbacks）
    // =========================================================================

    private final NodeSelectorPanel.Callbacks mNodePanelCallbacks =
            new NodeSelectorPanel.Callbacks() {
                @Override
                public void onBlockRequested(Activity activity, ViewGroup container) {
                    performBlock(activity, container);
                }

                @Override
                public void onPreviewRequested(Activity activity) {
                    togglePreview(activity);
                }

                @Override
                public void onModifyRequested(View selectedView, Activity activity, ViewGroup container) {
                    mPropertyEditor.show(selectedView, activity, container);
                }

                @Override
                public void onSaveModifyRequested(Activity activity) {
                    mPropertyEditor.saveAll(activity, mNodePanel.getPanelView(),
                            mNodePanel.getMaskView(), mPropertyEditor.getPanelView());
                }

                @Override
                public void onModeChanged(int mode) {
                    mInteractionMode = mode;
                }

                @Override
                public void onInfoFlowRequested() {
                    toggleInfoFlowMode();
                }
            };

    // =========================================================================
    // 触摸状态（原 TouchInterceptor）
    // =========================================================================

    private boolean mIsInEditMode;
    private boolean mMultiPointLock;
    private boolean mDragging;
    private boolean mLongClick;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mHasBlockEvent;

    private RemoveGestureHandler.RemoveState mRemoveState;
    private ModifyGestureHandler.ModifyState mModifyState;
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private float mDeltaX, mDeltaY;
    private float mDragStartRawX, mDragStartRawY;

    // =========================================================================
    // 属性引用
    // =========================================================================

    private final Property<Boolean> mSwitchProp;

    // =========================================================================
    // 反射缓存（原 TouchInterceptor）
    // =========================================================================

    private static Field sWindowAttributesField;

    // =========================================================================
    // 构造器
    // =========================================================================

    public EditorOrchestrator(Property<Boolean> switchProp) {
        this.mSwitchProp = switchProp;
    }

    // =========================================================================
    // 公共访问器
    // =========================================================================

    public int getInteractionMode() {
        return mInteractionMode;
    }

    public boolean isKeySelecting() {
        return mNodePanel.isKeySelecting();
    }

    public boolean isInfoFlowMode() {
        return mInfoFlowMode;
    }

    public boolean isDragging() {
        return mDragging;
    }

    // =========================================================================
    // 音量键处理（供 ActivityKeyHook 调用）
    // =========================================================================

    /**
     * 处理音量键按下（toggle/导航），由 ActivityKeyHook 在收到音量键事件时调用。
     */
    public void onVolumeKeyToggle(Activity activity) {
        if (!mNodePanel.isKeySelecting() && activity != null) {
            showNodeSelectPanel(activity);
        } else if (mNodePanel.isKeySelecting()) {
            dismissNodeSelectPanel();
        }
    }

    /**
     * 音量键导航，由 ActivityKeyHook 在选中状态下调用。
     */
    public void onVolumeKeyNavigate(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            navigatePrevious();
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            navigateNext();
        }
    }

    // =========================================================================
    // Activity 生命周期（由 GodModeInjector 调用）
    // =========================================================================

    public void setActivity(final Activity a) {
        Activity current = mCurrentActivityRef.get();
        if (current != null && current != a && mNodePanel.isKeySelecting()) {
            dismissNodeSelectPanel();
        }
        mCurrentActivityRef = new WeakReference<>(a);
    }

    public void setDisplay(Boolean display) {
        Activity act = mCurrentActivityRef.get();
        if (act == null) return;
        if (display == null) return;
        if (display && !mSwitchProp.get()) return;
        if (display) {
            if (!mNodePanel.isKeySelecting()) {
                showNodeSelectPanel(act);
            }
        } else {
            dismissNodeSelectPanel();
        }
    }

    // =========================================================================
    // 视图选择（原 KeyInterceptor）
    // =========================================================================

    /** 通过点击事件选中视图（从触摸手势分发调用） */
    public void selectViewByTap(View tappedView) {
        if (!mNodePanel.isKeySelecting() || mPropertyEditor.isShowing()) return;
        List<WeakReference<View>> nodes = mNodePanel.getViewNodes();
        if (nodes == null) return;
        for (int i = nodes.size() - 1; i >= 0; i--) {
            View v = nodes.get(i).get();
            if (v != null && isViewMatch(v, tappedView)) {
                mNodePanel.setCurrentIndex(i);
                mNodePanel.setHasUserSelection(true);
                return;
            }
        }
    }

    /** 获取当前选中的视图（由修改模式拖拽调用） */
    public View getSelectedView() {
        return mNodePanel.getSelectedView();
    }

    /** 判断 candidate 是否是 tapped 的同级或上级视图 */
    private static boolean isViewMatch(View candidate, View tapped) {
        if (candidate == tapped) return true;
        ViewParent parent = tapped.getParent();
        while (parent instanceof View) {
            if (parent == candidate) return true;
            parent = parent.getParent();
        }
        return false;
    }

    // =========================================================================
    // 视图导航（原 KeyInterceptor）
    // =========================================================================

    private void toggleInfoFlowMode() {
        mInfoFlowMode = !mInfoFlowMode;
        updateInfoFlowModeButton();
        Activity act = mCurrentActivityRef.get();
        if (act != null) {
            Toast.makeText(act, GmResources.getString(mInfoFlowMode
                            ? R.string.accessibility_info_flow_on : R.string.accessibility_info_flow_off),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void updateInfoFlowModeButton() {
        View panelView = mNodePanel.getPanelView();
        if (panelView == null) return;
        TextView btn = panelView.findViewById(R.id.info_flow_mode_btn);
        if (btn == null) return;
        if (mInfoFlowMode) {
            btn.setText(GmResources.getText(R.string.mode_info_flow_on));
            btn.setTextColor(android.graphics.Color.parseColor("#FFA500"));
        } else {
            btn.setText(GmResources.getText(R.string.mode_info_flow_off));
            btn.setTextColor(android.graphics.Color.GRAY);
        }
    }

    private void navigate(int delta) {
        if (mPropertyEditor.isShowing()) return;
        mNodePanel.navigate(delta);
    }

    private void navigateNext() {
        navigate(+1);
    }

    private void navigatePrevious() {
        navigate(-1);
    }

    // =========================================================================
    // 节点选择器面板（原 KeyInterceptor）
    // =========================================================================

    private void showNodeSelectPanel(final Activity activity) {
        Logger.i(TAG, "[KeyEventHook] showNodeSelectPanel for " + activity.getPackageName());
        List<WeakReference<View>> viewNodes = ViewTraversal.buildViewNodes(
                activity.getWindow().getDecorView());
        final ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
        mNodePanel.show(viewNodes, activity, container, OVERLAY_COLOR, mSeekBarHandler);
        if (!mNodePanel.isKeySelecting()) return;
        ToolbarVisibilityController.apply(mNodePanel.getPanelView());
        mNodePanel.wireButtons(activity, container, mNodePanelCallbacks);
        updateInfoFlowModeButton();
    }

    private void dismissNodeSelectPanel() {
        Logger.i(TAG, "[KeyEventHook] dismissNodeSelectPanel");
        mPropertyEditor.cancel();
        mPreviewHandler.restorePreview(null, null, null);
        mInteractionMode = EditorInteractionMode.INITIAL;
        mNodePanel.dismiss();
    }

    // =========================================================================
    // 移除（屏蔽）操作（原 KeyInterceptor）
    // =========================================================================

    private void performBlock(final Activity activity, final ViewGroup container) {
        try {
            List<WeakReference<View>> viewNodes = mNodePanel.getViewNodes();
            if (viewNodes == null || viewNodes.isEmpty()) return;
            if (mPreviewHandler.isPreviewing()) {
                mPreviewHandler.restorePreview(mNodePanel.getMaskView(),
                        mNodePanel.getSelectedView(), () -> updatePreviewButton(false));
            }
            final View view = mNodePanel.getSelectedView();
            Logger.d(TAG, "[KeyEventHook] block view = " + view);
            if (view == null) return;
            MaskView maskView = mNodePanel.getMaskView();
            if (maskView != null) maskView.updateOverlayBounds(new Rect());

            final int blockedViewIndex = mNodePanel.getCurrentIndex();
            hideGmOverlays(View.INVISIBLE);
            final Bitmap snapshot = BitmapUtils.snapshotView(
                    ViewUtils.findTopParentViewByChildView(view));
            hideGmOverlays(View.VISIBLE);

            BlockHandler.execute(activity, view, container, snapshot, blockedViewIndex,
                    new BlockHandler.OnBlockListener() {
                        @Override
                        public void onAnimationEnd(int index) {
                            restorePanelAlpha();
                            mNodePanel.updateAfterRemove(index);
                        }

                        @Override
                        public void onError(String message) {
                            restorePanelAlpha();
                            Toast.makeText(activity,
                                    GmResources.getString(R.string.block_fail, message),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
        } catch (Exception e) {
            Logger.e(TAG, "[KeyEventHook] block fail", e);
            restorePanelAlpha();
            Toast.makeText(activity, GmResources.getString(R.string.block_fail, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 临时隐藏或显示所有 GodMode 覆盖层视图 */
    private void hideGmOverlays(int visibility) {
        View panelView = mNodePanel.getPanelView();
        if (panelView != null) panelView.setVisibility(visibility);
        View modifyPanel = mPropertyEditor.getPanelView();
        if (modifyPanel != null) modifyPanel.setVisibility(visibility);
        MaskView maskView = mNodePanel.getMaskView();
        if (maskView != null) maskView.setVisibility(visibility);
    }

    private void restorePanelAlpha() {
        View panelView = mNodePanel.getPanelView();
        if (panelView != null) {
            panelView.animate().alpha(1.0f)
                    .setInterpolator(new DecelerateInterpolator(1.0f))
                    .setDuration(300).start();
        }
    }

    // =========================================================================
    // 预览（原 KeyInterceptor）
    // =========================================================================

    private void togglePreview(final Activity activity) {
        if (mPreviewHandler.isPreviewing()) {
            MaskView maskView = mNodePanel.getMaskView();
            View selectedView = mNodePanel.getSelectedView();
            mPreviewHandler.restorePreview(maskView, selectedView,
                    () -> updatePreviewButton(false));
        } else {
            List<WeakReference<View>> viewNodes = mNodePanel.getViewNodes();
            if (viewNodes == null || viewNodes.isEmpty()) return;
            View view = mNodePanel.getSelectedView();
            if (view == null) return;
            MaskView maskView = mNodePanel.getMaskView();
            mPreviewHandler.startPreview(view, maskView,
                    () -> updatePreviewButton(true));
        }
    }

    private void updatePreviewButton(boolean inPreview) {
        View panelView = mNodePanel.getPanelView();
        View btnPreview = panelView != null ? panelView.findViewById(R.id.preview) : null;
        if (btnPreview instanceof android.widget.ImageButton) {
            ((android.widget.ImageButton) btnPreview).setImageResource(
                    inPreview ? android.R.drawable.ic_menu_close_clear_cancel
                            : android.R.drawable.ic_menu_view);
        }
        if (btnPreview != null) {
            TooltipCompat.setTooltipText(btnPreview,
                    GmResources.getText(inPreview
                            ? R.string.accessibility_preview_exit : R.string.accessibility_preview));
        }
    }

    // =========================================================================
    // 触摸事件分发（原 TouchInterceptor 的核心）
    // =========================================================================

    /**
     * 编辑模式下的触摸事件处理入口，由 TouchHook 调用。
     * 返回 true 表示事件已被消费。
     */
    public boolean onTouchEvent(View view, MotionEvent event) {
        if (!mIsInEditMode) return false;
        if (TAG_GM_CMP.equals(view.getTag())) return false;
        if (!isEditableWindow(view)) return false;
        return dispatchTouchEvent(view, event);
    }

    private boolean isEditableWindow(View v) {
        WindowManager.LayoutParams wl = getWindowLayoutParams(v);
        if (wl == null) return false;
        int type = wl.type;
        if (type < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) return true;
        return type > WindowManager.LayoutParams.LAST_SYSTEM_WINDOW;
    }

    private WindowManager.LayoutParams getWindowLayoutParams(View v) {
        Object viewRootImpl = ViewUtils.findViewRootImplByChildView(v.getParent());
        if (viewRootImpl == null) return null;
        try {
            if (sWindowAttributesField == null) {
                sWindowAttributesField = viewRootImpl.getClass().getDeclaredField("mWindowAttributes");
                sWindowAttributesField.setAccessible(true);
            }
            return (WindowManager.LayoutParams) sWindowAttributesField.get(viewRootImpl);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean dispatchTouchEvent(View v, MotionEvent event) {
        int mode = mInteractionMode;
        int action = event.getActionMasked();

        if (mode == EditorInteractionMode.INITIAL) {
            return true;
        }
        if (mode == EditorInteractionMode.MODIFY) {
            return handleModifyTouch(v, event);
        }
        return handleRemoveTouch(v, event, action);
    }

    // =========================================================================
    // 移除模式触摸处理（原 TouchInterceptor）
    // =========================================================================

    private boolean handleRemoveTouch(View v, MotionEvent event, int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            if (!beginTouch(v, false)) return false;
            Rect bounds = ViewUtils.getLocationInWindow(v);
            mDeltaX = event.getRawX() - bounds.left;
            mDeltaY = event.getRawY() - bounds.top;

        } else if (action == MotionEvent.ACTION_MOVE) {
            if (mLongClick && mRemoveState != null && mRemoveState.maskView != null) {
                mRemoveState.maskView.updateOverlayBounds(
                        (int) (event.getRawX() - mDeltaX), (int) (event.getRawY() - mDeltaY),
                        v.getWidth(), v.getHeight());
                mRemoveState.maskView.setMarked(
                        mRemoveState.cancelView.getRealBounds().intersect(
                                mRemoveState.maskView.getRealBounds()));
            }

        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (mLongClick && mRemoveState != null) {
                RemoveGestureHandler.finishDrag(v, mRemoveState);
                RemoveGestureHandler.clearState(mRemoveState);
                mRemoveState = null;
            } else if (action == MotionEvent.ACTION_UP && isKeySelecting()) {
                selectViewByTap(v);
            }
            endTouch(v);
        }
        return true;
    }

    // =========================================================================
    // 修改模式触摸处理（原 TouchInterceptor）
    // =========================================================================

    private boolean handleModifyTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            if (!beginTouch(v, true)) return false;
            mDragStartRawX = event.getRawX();
            mDragStartRawY = event.getRawY();

        } else if (action == MotionEvent.ACTION_MOVE) {
            if (mLongClick && mModifyState != null) {
                float dx = event.getRawX() - mDragStartRawX;
                float dy = event.getRawY() - mDragStartRawY;
                ModifyGestureHandler.moveTarget(mModifyState, dx, dy);
            }

        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (mLongClick && mModifyState != null) {
                ModifyGestureHandler.finalizeDrag(mModifyState,
                        v.getContext().getPackageName());
                mModifyState = null;
            } else if (action == MotionEvent.ACTION_UP && !mLongClick) {
                selectViewByTap(v);
            }
            endTouch(v);
        }
        return true;
    }

    // =========================================================================
    // 共享触摸开始/结束和长按检测（原 TouchInterceptor）
    // =========================================================================

    private boolean beginTouch(View v, boolean isModifyMode) {
        boolean[] draggingRef = new boolean[1];
        if (!GestureDispatcher.tryBeginTouch(v, isModifyMode,
                mMultiPointLock, new boolean[]{mHasBlockEvent},
                this::getWindowLayoutParams, draggingRef)) {
            return false;
        }
        mDragging = draggingRef[0];
        mMultiPointLock = true;
        mHandler.postDelayed(() -> onLongPress(v, isModifyMode), LONG_PRESS_TIMEOUT);
        return true;
    }

    private void endTouch(View v) {
        ViewParent parent = v.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        mHandler.removeCallbacksAndMessages(null);
        mLongClick = false;
        mHasBlockEvent = false;
        mMultiPointLock = false;
        mDragging = false;
    }

    /** 长按触发：根据模式启动移除或修改拖拽 */
    private void onLongPress(View v, boolean isModifyMode) {
        if (isModifyMode) {
            View target = getSelectedView();
            if (target != null) {
                mModifyState = ModifyGestureHandler.startDrag(target);
            }
        } else {
            mRemoveState = RemoveGestureHandler.startDrag(v);
        }
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        mLongClick = true;
    }

    // =========================================================================
    // 属性变更（原 KeyInterceptor + TouchInterceptor 合并）
    // =========================================================================

    @Override
    public void onPropertyChange(Boolean enable) {
        if (enable == null) return;
        mIsInEditMode = enable;
        Logger.d(TAG, "[EditorOrchestrator] edit mode: " + enable);
        if (!enable) {
            mInteractionMode = EditorInteractionMode.INITIAL;
            mHandler.removeCallbacksAndMessages(null);
            mLongClick = false;
            mMultiPointLock = false;
            mDragging = false;
            RemoveGestureHandler.clearState(mRemoveState);
            mRemoveState = null;
            mModifyState = null;
        }
    }
}
