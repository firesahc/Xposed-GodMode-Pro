package com.kaisar.xposed.godmode.editor;

import static com.kaisar.xposed.godmode.engine.util.GmConstants.TAG_GM_CMP;

import com.kaisar.xposed.godmode.engine.util.GmConstants;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Toast;

import androidx.appcompat.widget.TooltipCompat;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.editor.IRuleEditor;
import com.kaisar.xposed.godmode.engine.EditorInteractionMode;
import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.editor.action.BlockHandler;
import com.kaisar.xposed.godmode.editor.action.PreviewHandler;
import com.kaisar.xposed.godmode.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.editor.overlay.ParticleView;
import com.kaisar.xposed.godmode.editor.panel.NodeSelectorPanel;
import com.kaisar.xposed.godmode.editor.panel.PropertyEditorPanel;
import com.kaisar.xposed.godmode.editor.panel.SeekBarHandler;
import com.kaisar.xposed.godmode.editor.toolbar.ToolbarVisibilityController;
import com.kaisar.xposed.godmode.ipc.RuleServiceContract;
import com.kaisar.xposed.godmode.ipc.contract.UndoResultParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;
import com.kaisar.xposed.godmode.util.BitmapUtils;
import com.kaisar.xposed.godmode.engine.util.CommonUtils;
import com.kaisar.xposed.godmode.util.GmResources;
import com.kaisar.xposed.godmode.util.TaskExecutor;
import com.kaisar.xposed.godmode.util.ViewUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 编辑器编排器 — 管理编辑模式的核心类，连接按键拦截器（KeyInterceptor）和触摸拦截器（TouchInterceptor）。
 * 负责调度视图选择、屏蔽、预览、修改等交互操作，通过 Hook 系统与目标应用交互。
 * <p>
 * 内部管理节点选择面板、属性编辑器、预览处理器和触摸选择处理器。
 * 按键和触摸事件通过 inject/hooks 中的交互 Hook 转发到这里。
 */
public final class EditorOrchestrator implements Property.OnPropertyChangeListener<Boolean>,
        TouchEventHandler.TouchCallback, KeyEventHandler.KeyCallback {

    // =========================================================================
    // 常量定义    // =========================================================================

    private static final String TAG = "EditorOrchestrator";
    private static final String KEY_EVENT_TAG = "KeyEventHook";
    private static final int OVERLAY_COLOR = GmConstants.OVERLAY_COLOR_RED;

    // =========================================================================
    // Interaction mode and info-flow mode
// =========================================================================

    private int mInteractionMode = EditorInteractionMode.INITIAL;

    // =========================================================================
    // 预览处理器    // =========================================================================

    final PreviewHandler mPreviewHandler = new PreviewHandler();

    // =========================================================================
    // 节点选择面板与属性编辑器    // =========================================================================

    private final NodeSelectorPanel mNodePanel = new NodeSelectorPanel();
    private final EditorUndoController mUndoController = new EditorUndoController();
    final PropertyEditorPanel mPropertyEditor;
    private final SeekBarHandler mSeekBarHandler;
    private WeakReference<Activity> mCurrentActivityRef = new WeakReference<>(null);
    private long mSessionGeneration;
    /** A whole-editor close requested while the property editor is saving. */
    private boolean mNodePanelDismissPending;

    // =========================================================================
    // 节点选择面板回调（NodeSelectorPanel.Callbacks）    // =========================================================================

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
                    mPropertyEditor.show(selectedView, activity, container, isInfoFlowMode());
                }

                @Override
                public void onModifyPreviewRequested(Activity activity) {
                    mPropertyEditor.togglePreview();
                }

                @Override
                public void onUndoRequested(Activity activity) {
                    requestUndo(activity);
                }

                @Override
                public void onModeChanged(int mode) {
                    mInteractionMode = mode;
                }

                @Override
                public void onInfoFlowRequested() {
                    mKeyEventHandler.toggleInfoFlowMode();
                }
            };

    // =========================================================================
    // 编辑模式状态    // =========================================================================

    private boolean mIsInEditMode;

    // =========================================================================
    // 触摸 / 按键 子组件    // =========================================================================

    private final TouchEventHandler mTouchEventHandler;
    private final KeyEventHandler mKeyEventHandler = new KeyEventHandler(this);

    // =========================================================================
    // 编辑器规则持久化接口    // =========================================================================

    private final IRuleEditor mRuleEditor;

    // 开关属性引用    // =========================================================================

    private final Property<Boolean> mSwitchProp;

    // =========================================================================
    // 构造器    // =========================================================================

    public EditorOrchestrator(Property<Boolean> switchProp, IRuleEditor ruleEditor) {
        this.mSwitchProp = switchProp;
        this.mRuleEditor = ruleEditor;
        this.mPropertyEditor = new PropertyEditorPanel(ruleEditor,
                (active, previewing, previewToggleEnabled) -> {
                    mNodePanel.setModifySessionLocked(active);
                    if (!active) {
                        onPropertyEditorSessionClosed();
                    }
                    mNodePanel.setModifyPreviewing(previewing);
                    mNodePanel.setModifyPreviewEnabled(previewToggleEnabled);
                    if (!active) return;
                    MaskView mask = mNodePanel.getMaskView();
                    if (mask == null) return;
                    View target = getModifyTargetView();
                    if (previewing || target == null || !target.isAttachedToWindow()) {
                        mask.updateOverlayBounds(new Rect());
                    } else {
                        mask.updateOverlayBounds(ViewUtils.getLocationInWindow(target));
                    }
                }, this::captureWithoutEditorOverlays,
                new PropertyEditorPanel.MutationListener() {
                    @Override
                    public long onMutationStarted() {
                        return mUndoController.beginForwardMutation();
                    }

                    @Override
                    public void onMutationSucceeded(long mutationScope,
                            UndoStateParcel undoState) {
                        mUndoController.completeForwardMutation(mutationScope, undoState);
                    }

                    @Override
                    public void onMutationFailed(long mutationScope) {
                        mUndoController.failForwardMutation(mutationScope);
                    }
                });
        this.mTouchEventHandler = new TouchEventHandler(this);
        this.mSeekBarHandler = new SeekBarHandler(mNodePanel, mPropertyEditor);
        this.mUndoController.setListener(mNodePanel::setUndoAvailable);
    }

    private View getModifyTargetView() {
        return mPropertyEditor.getTargetView();
    }

    // =========================================================================
    // 状态查询方法    // =========================================================================

    public int getInteractionMode() {
        return mInteractionMode;
    }

    public boolean isKeySelecting() {
        return mNodePanel.isKeySelecting();
    }

    public boolean isInfoFlowMode() {
        return mKeyEventHandler.isInfoFlowMode();
    }


    // =========================================================================
    // 音量键事件处理（ActivityKeyHook 相关）    // =========================================================================

    /**
     * 音量键切换编辑面板：若未选择则显示节点选择面板，否则关闭面板。
     * 由 ActivityKeyHook 通过按键事件触发调用。
     */
    public void onVolumeKeyToggle(Activity activity) {
        if (mPropertyEditor.isShowing()) return;
        mKeyEventHandler.onVolumeKeyToggle(activity);
    }

    /**
     * Integrates with ActivityKeyHook for key event dispatch and
     * TouchHook for touch interception in edit mode.
     */
    public void onVolumeKeyNavigate(int keyCode) {
        mKeyEventHandler.onVolumeKeyNavigate(keyCode);
    }


    // =========================================================================
    // Activity 管理 — 由 LifecycleHooks 的 onResume 回调设置当前 Activity
    // =========================================================================

    public void setActivity(final Activity a) {
        Activity current = mCurrentActivityRef.get();
        if (current != a) {
            mSessionGeneration++;
        }
        if (current != null && current != a) {
            mPropertyEditor.abandon();
            dismissNodeSelectPanel();
        }
        mCurrentActivityRef = new WeakReference<>(a);
        mUndoController.bindPackage(a == null ? null : a.getPackageName());
    }

    private boolean isCurrentActivitySession(Activity activity, long sessionGeneration) {
        return activity != null
                && mCurrentActivityRef.get() == activity
                && mSessionGeneration == sessionGeneration
                && !activity.isFinishing()
                && !activity.isDestroyed();
    }

    public void setDisplay(Boolean display) {
        Activity act = mCurrentActivityRef.get();
        if (act == null) {
            Logger.w(TAG, "setDisplay(" + display + ") ignored — no current activity");
            return;
        }
        if (display == null) return;
        if (display && !mSwitchProp.get()) {
            Logger.w(TAG, "setDisplay(true) ignored — edit mode switch is off");
            return;
        }
        if (display) {
            if (!mNodePanel.isKeySelecting()) {
                showNodeSelectPanel(act);
            }
        } else {
            dismissNodeSelectPanel();
        }
    }

    /** Shows or hides the editor only when the posted callback still belongs to this Activity. */
    public void setDisplayForActivity(Activity activity, Boolean display) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Activity current = mCurrentActivityRef.get();
        if (current == null) {
            setActivity(activity);
        } else if (current != activity) {
            return;
        }
        setDisplay(display);
    }

    // =========================================================================
    // 节点选择导航 — 音量键导航选择控件（KeyInterceptor 相关）    // =========================================================================

    /** 点击视图选择控件：从节点列表中查找匹配的视图并设为当前选中 */
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

    /** 获取当前选中的视图（节点选择面板中高亮的那个） */
    public View getSelectedView() {
        return mNodePanel.getSelectedView();
    }

    /** Check whether the candidate view equals the currently tapped view. */
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
    // KeyInterceptor — intercepts key events for editor tool shortcuts
    // =========================================================================

    private void showNodeSelectPanel(final Activity activity) {
        Logger.i(KEY_EVENT_TAG, "showNodeSelectPanel for " + activity.getPackageName());
        List<WeakReference<View>> viewNodes = ViewTraversal.buildViewNodes(
                activity.getWindow().getDecorView());
        final ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
        mNodePanel.show(viewNodes, activity, container, OVERLAY_COLOR, mSeekBarHandler);
        if (!mNodePanel.isKeySelecting()) return;
        ToolbarVisibilityController.apply(mNodePanel.getPanelView());
        mNodePanel.wireButtons(activity, container, mNodePanelCallbacks);
        mKeyEventHandler.updateInfoFlowModeButton();
        refreshUndoState(activity, null);
    }

    private void dismissNodeSelectPanel() {
        Logger.i(KEY_EVENT_TAG, "dismissNodeSelectPanel");
        mPropertyEditor.cancel();
        if (mPropertyEditor.isSaving()) {
            mNodePanelDismissPending = true;
            return;
        }
        mNodePanelDismissPending = false;
        dismissNodePanelNow();
    }

    private void onPropertyEditorSessionClosed() {
        if (mNodePanelDismissPending) {
            mNodePanelDismissPending = false;
            dismissNodePanelNow();
            return;
        }
        mInteractionMode = EditorInteractionMode.MODIFY;
        mNodePanel.restoreModifyMode();
        mNodePanel.setModifySessionLocked(false);
        mNodePanel.setModifyPreviewing(false);
        mNodePanel.setModifyPreviewEnabled(false);
        mNodePanel.refreshMaskToSelection();
    }

    private void dismissNodePanelNow() {
        mPreviewHandler.restorePreview(null, null, null);
        mInteractionMode = EditorInteractionMode.INITIAL;
        mNodePanel.setModifySessionLocked(false);
        mNodePanel.dismiss();
    }

    /** Invalidates pending editor work when the tracked Activity is destroyed. */
    public void onActivityDestroyed(Activity activity) {
        if (activity != null && mCurrentActivityRef.get() == activity) {
            setActivity(null);
        }
    }

    // =========================================================================
    // 屏蔽视图 — 执行视图移除/屏蔽操作，含快照和动画（BlockHandler 相关）    // =========================================================================

    private void performBlock(final Activity activity, final ViewGroup container) {
        long startedMutationScope = EditorUndoController.INVALID_SCOPE;
        try {
            List<WeakReference<View>> viewNodes = mNodePanel.getViewNodes();
            if (viewNodes == null || viewNodes.isEmpty()) return;
            if (mPreviewHandler.isPreviewing()) {
                mPreviewHandler.restorePreview(mNodePanel.getMaskView(),
                        mNodePanel.getSelectedView(), () -> updatePreviewButton(false));
            }
            final View view = mNodePanel.getSelectedView();
            if (view == null) return;
            MaskView maskView = mNodePanel.getMaskView();
            if (maskView != null) maskView.updateOverlayBounds(new Rect());

            final int blockedViewIndex = mNodePanel.getCurrentIndex();
            final long selectionRevision = mNodePanel.getSelectionRevision();
            final Bitmap snapshot = captureWithoutEditorOverlays(view);
            if (snapshot == null) {
                throw new IllegalStateException("snapshot failed");
            }
            startedMutationScope = mUndoController.beginForwardMutation();
            if (startedMutationScope == EditorUndoController.INVALID_SCOPE) {
                CommonUtils.recycleNullableBitmap(snapshot);
                Toast.makeText(activity,
                        GmResources.getString(R.string.toast_editor_operation_busy),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            final long mutationScope = startedMutationScope;
            final long sessionGeneration = mSessionGeneration;

            BlockHandler.execute(activity, view, container, snapshot, blockedViewIndex,
                    new BlockHandler.OnBlockListener() {
                        @Override
                        public void onCommitted(int index, UndoStateParcel undoState) {
                            boolean accepted = mUndoController.completeForwardMutation(
                                    mutationScope, undoState);
                            if (accepted && isCurrentActivitySession(activity, sessionGeneration)) {
                                mNodePanel.applyRemoveProjection(view, index, selectionRevision);
                            }
                        }

                        @Override
                        public void onError(String message) {
                            if (mUndoController.failForwardMutation(mutationScope)
                                    && isCurrentActivitySession(activity, sessionGeneration)) {
                                Toast.makeText(activity,
                                        GmResources.getString(R.string.block_fail, message),
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    }, mRuleEditor, isInfoFlowMode());
        } catch (Exception e) {
            if (startedMutationScope != EditorUndoController.INVALID_SCOPE) {
                mUndoController.failForwardMutation(startedMutationScope);
            }
            Logger.e(KEY_EVENT_TAG, "block fail", e);
            Toast.makeText(activity, GmResources.getString(R.string.block_fail, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestUndo(Activity activity) {
        final long sessionGeneration = mSessionGeneration;
        refreshUndoState(activity, () -> {
            if (!isCurrentActivitySession(activity, sessionGeneration)) return;
            EditorUndoController.UndoAttempt attempt = mUndoController.beginUndo();
            if (attempt == null) return;
            final String packageName = activity.getPackageName();
            TaskExecutor.executeIo(() -> {
                UndoResultParcel result = null;
                try {
                    result = mRuleEditor.undoLatest(packageName, attempt.expected);
                } catch (Exception e) {
                    Logger.e(TAG, "undoLatest failed for " + packageName, e);
                }
                final UndoResultParcel finalResult = result;
                activity.runOnUiThread(() -> finishUndo(packageName, activity,
                        sessionGeneration, attempt.scopeGeneration, finalResult));
            });
        });
    }

    private void refreshUndoState(Activity activity, Runnable onComplete) {
        final long sessionGeneration = mSessionGeneration;
        final String packageName = activity.getPackageName();
        mUndoController.bindPackage(packageName);
        final long refreshScope = mUndoController.beginRefresh();
        if (refreshScope == EditorUndoController.INVALID_SCOPE) return;
        TaskExecutor.executeIo(() -> {
            UndoStateParcel state = null;
            try {
                state = mRuleEditor.getUndoState(packageName);
            } catch (Exception e) {
                Logger.e(TAG, "getUndoState failed for " + packageName, e);
            }
            final UndoStateParcel finalState = state;
            activity.runOnUiThread(() -> {
                boolean accepted = mUndoController.completeRefresh(refreshScope, finalState);
                if (accepted && onComplete != null
                        && isCurrentActivitySession(activity, sessionGeneration)) {
                    onComplete.run();
                }
            });
        });
    }

    private void finishUndo(String packageName, Activity activity, long sessionGeneration,
            long undoScope, UndoResultParcel result) {
        if (!mUndoController.isBoundTo(packageName)) return;
        boolean committed = result != null
                && (result.status == RuleServiceContract.RESULT_COMMITTED
                || result.status == RuleServiceContract.RESULT_ALREADY_UNDONE);
        if (committed) {
            if (!mUndoController.completeUndo(undoScope, result.undoState)) return;
            if (!isCurrentActivitySession(activity, sessionGeneration)) return;
            Toast.makeText(activity, GmResources.getString(R.string.toast_undo_succeeded),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mUndoController.failUndo(undoScope,
                result == null ? null : result.undoState)) return;
        if (!isCurrentActivitySession(activity, sessionGeneration)) return;
        String reason = result == null ? null : result.message;
        Toast.makeText(activity, GmResources.getString(R.string.toast_undo_failed_format,
                        reason == null
                                ? GmResources.getString(R.string.toast_undo_not_completed)
                                : reason),
                Toast.LENGTH_SHORT).show();
    }

    /** Updates known editor overlays for the key-handler visibility callback. */
    private void hideGmOverlays(int visibility) {
        View panelView = mNodePanel.getPanelView();
        if (panelView != null) panelView.setVisibility(visibility);
        View modifyPanel = mPropertyEditor.getPanelView();
        if (modifyPanel != null) modifyPanel.setVisibility(visibility);
        MaskView maskView = mNodePanel.getMaskView();
        if (maskView != null) maskView.setVisibility(visibility);
    }

    /** Captures the host window without editor-owned overlays. Must run on the main thread. */
    Bitmap captureWithoutEditorOverlays(View targetView) {
        return captureWithoutEditorOverlays(targetView, Arrays.asList(
                mNodePanel.getPanelView(),
                mPropertyEditor.getPanelView(),
                mNodePanel.getMaskView()));
    }

    static Bitmap captureWithoutEditorOverlays(View targetView, List<View> ownedOverlays) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Editor snapshot must run on the main thread");
        }
        View root = ViewUtils.findTopParentViewByChildView(targetView);
        if (root == null) return null;

        List<View> overlays = new ArrayList<>();
        if (ownedOverlays != null) {
            for (View overlay : ownedOverlays) {
                if (overlay != null && overlay.getParent() == root && !overlays.contains(overlay)) {
                    overlays.add(overlay);
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup rootGroup = (ViewGroup) root;
            for (int i = 0; i < rootGroup.getChildCount(); i++) {
                View child = rootGroup.getChildAt(i);
                if (TAG_GM_CMP.equals(child.getTag())
                        && !(child instanceof ParticleView)
                        && !overlays.contains(child)) {
                    overlays.add(child);
                }
            }
        }

        return captureWithHiddenOverlays(overlays, () -> BitmapUtils.snapshotView(root));
    }

    interface BitmapCapture {
        Bitmap capture();
    }

    static Bitmap captureWithHiddenOverlays(List<View> overlays, BitmapCapture capture) {
        List<OverlayVisibility> states = new ArrayList<>();
        try {
            for (View overlay : overlays) {
                states.add(new OverlayVisibility(overlay, overlay.getVisibility()));
                overlay.setVisibility(View.INVISIBLE);
            }
            return capture.capture();
        } finally {
            for (int i = states.size() - 1; i >= 0; i--) {
                OverlayVisibility state = states.get(i);
                state.view.setVisibility(state.visibility);
            }
        }
    }

    private static final class OverlayVisibility {
        final View view;
        final int visibility;

        OverlayVisibility(View view, int visibility) {
            this.view = view;
            this.visibility = visibility;
        }
    }

    // =========================================================================
    // 预览模式 — 切换控件高亮预览/还原状态（PreviewHandler 相关）    // =========================================================================

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
                    () -> updatePreviewButton(true), isInfoFlowMode());
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
    // 触摸事件处理 — 委托给 TouchEventHandler（TouchInterceptor 相关）    // =========================================================================

    /**
     * 编辑模式触摸事件入口，由 TouchHook 调用。
     * 判断是否处于编辑模式、是否为 GodMode 控件，然后委托给 TouchEventHandler。
     */
    public boolean onTouchEvent(View view, MotionEvent event) {
        if (!mIsInEditMode) return false;
        if (TAG_GM_CMP.equals(view.getTag())) return false;
        return mTouchEventHandler.onTouchEvent(view, event);
    }

    // =========================================================================
    // Property change listener — KeyInterceptor + TouchInterceptor integration
    // =========================================================================

    @Override
    public void onPropertyChange(Boolean enable) {
        if (enable == null) return;
        mIsInEditMode = enable;
        if (!enable) {
            mInteractionMode = EditorInteractionMode.INITIAL;
            mUndoController.bindPackage(null);
        }
    }

    // =========================================================================
    // TouchCallback / KeyCallback 实现
    // 注：isKeySelecting()、getSelectedView()、getInteractionMode()、selectViewByTap()
    //     均已在 EditorOrchestrator 中作为 public 方法存在，直接满足 TouchCallback 接口。
    // =========================================================================

    @Override
    public NodeSelectorPanel getNodePanel() {
        return mNodePanel;
    }

    @Override
    public PropertyEditorPanel getPropertyEditor() {
        return mPropertyEditor;
    }

    @Override
    public PreviewHandler getPreviewHandler() {
        return mPreviewHandler;
    }

    @Override
    public WeakReference<Activity> getCurrentActivityRef() {
        return mCurrentActivityRef;
    }

    @Override
    public void setInteractionMode(int mode) {
        mInteractionMode = mode;
    }

    @Override
    public int getOverlayColor() {
        return OVERLAY_COLOR;
    }

    @Override
    public void onShowNodeSelectPanel(Activity activity, int overlayColor) {
        showNodeSelectPanel(activity);
    }

    @Override
    public void onDismissNodeSelectPanel() {
        dismissNodeSelectPanel();
    }

    @Override
    public void onHideGmOverlays(int visibility) {
        hideGmOverlays(visibility);
    }
}
