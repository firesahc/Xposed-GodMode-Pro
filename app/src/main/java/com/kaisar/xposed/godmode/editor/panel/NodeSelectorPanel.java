package com.kaisar.xposed.godmode.editor.panel;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.EditorInteractionMode;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.util.ModuleResources;
import com.kaisar.xposed.godmode.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.util.GmResources;
import com.kaisar.xposed.godmode.util.ViewUtils;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 节点选择面板 — 视图树导航 + 选中/预览/移除/修改操作。
 * <p>
 * 按钮点击通过 {@link Callbacks} 接口转发给外界处理器，
 * 面板自身的 UI 操作（导航、位置切换）在内部完成。
 */
public class NodeSelectorPanel {

    private static final String TAG = "NodeSelectorPanel";

    // 交互模式（复用 EditorInteractionMode 常量）

    /**
     * 节点选择器面板的按钮回调接口。
     * <p>
     * 由 {@link #wireButtons} 在绑定按钮时调用，实现方负责处理具体业务逻辑。
     */
    public interface Callbacks {
        void onBlockRequested(Activity activity, ViewGroup container);
        void onPreviewRequested(Activity activity);
        void onModifyRequested(View selectedView, Activity activity, ViewGroup container);
        void onModifyPreviewRequested(Activity activity);
        void onUndoRequested(Activity activity);
        void onModeChanged(int mode);
        void onInfoFlowRequested();
    }

    private View mPanelView;
    private int mCurrentIndex;
    /** Monotonic revision of explicit user selection changes in this panel. */
    private long mSelectionRevision;
    private List<WeakReference<View>> mViewNodes;
    private SeekBar mSeekBar;
    private MaskView mMaskView;
    private boolean mHasUserSelection;
    private boolean mKeySelecting;
    private boolean mModifySessionLocked;
    private boolean mModifyPreviewing;
    private boolean mUndoAvailable;
    /** 面板导航区是否处于展开（向左让位）状态，由 exchange 按钮切换。 */
    private boolean mNavigationPaneExpanded;

    // 面板导航区边距常量（dp），与布局初始 padding 保持一致
    /** 基线边距，对应布局 paddingLeft/Top/Bottom。 */
    private static final int PANEL_PADDING_DP = 4;
    /** 收起态末端留白，对应布局初始 paddingRight。 */
    private static final int END_PADDING_COLLAPSED_DP = 8;
    /** 展开态末端留白占屏宽比例（右侧空出约 1/6 屏宽）。 */
    private static final float END_PADDING_EXPANDED_FRACTION = 5f / 6f;

    /**
     * 显示节点选择面板。
     * @param viewNodes    视图树节点列表
     * @param activity     当前 Activity
     * @param container    DecorView 容器
     * @param overlayColor MaskView 遮罩颜色
     * @param seekBarListener SeekBar 变化回调
     */
    public void show(List<WeakReference<View>> viewNodes, Activity activity,
            ViewGroup container, int overlayColor,
            SeekBar.OnSeekBarChangeListener seekBarListener) {
        mViewNodes = viewNodes;
        mCurrentIndex = 0;
        mSelectionRevision = 0L;
        mHasUserSelection = false;
        mNavigationPaneExpanded = false;
        try {
            mMaskView = MaskView.makeMaskView(activity);
            mMaskView.setMaskOverlay(overlayColor);
            mMaskView.attachToContainer(container);
            // 尝试注入模块资源，记录是否成功
            boolean moduleResInjected = ModuleResources.injectInto(activity.getResources());
            LayoutInflater inflater = LayoutInflater.from(activity);
            mPanelView = inflater.inflate(
                    GmResources.getLayout(R.layout.panel_node_selector), container, false);
            GmResources.markAsGmComponent(mPanelView);
            // 如果注入失败（某些 APP 可能无法通过 addAssetPath 加载模块 APK），
            // 通过 GmResources（模块自己的 Resources）回退设置模块资源，
            // 否则 toolbar 的 ImageButton 无图、TextView 无字、背景透明
            if (!moduleResInjected) {
                patchModuleResources(mPanelView);
            }
            mSeekBar = mPanelView.findViewById(R.id.slider);
            mSeekBar.setMax(Math.max(viewNodes.size() - 1, 0));
            mSeekBar.setOnSeekBarChangeListener(seekBarListener);
            updateUndoButton();
            container.addView(mPanelView);
            mPanelView.setAlpha(0);
            mPanelView.post(() -> {
                mPanelView.setTranslationX(mPanelView.getWidth() / 2.0f);
                mPanelView.animate().alpha(1).translationX(0)
                        .setDuration(300).setInterpolator(new DecelerateInterpolator(1.0f)).start();
            });
            mKeySelecting = true;
        } catch (Exception e) {
            Logger.e(TAG, "show: failed to attach node selector panel", e);
            if (mMaskView != null) { mMaskView.detachFromContainer(); mMaskView = null; }
            mKeySelecting = false;
        }
    }

    /** 关闭面板，带动画 */
    public void dismiss() {
        mKeySelecting = false;
        mModifySessionLocked = false;
        mModifyPreviewing = false;
        if (mMaskView != null) { mMaskView.detachFromContainer(); mMaskView = null; }
        if (mPanelView != null) {
            View panel = mPanelView;
            mPanelView = null;
            panel.animate().alpha(0).setDuration(200).withEndAction(() -> {
                ViewGroup parent = (ViewGroup) panel.getParent();
                if (parent != null) parent.removeView(panel);
            }).start();
        }
        mViewNodes = null;
    }

    /**
     * 绑定面板所有按钮的点击监听。
     * <p>
     * 面板自身的 UI 操作（导航、位置切换）在内部处理；
     * 需要业务逻辑的操作通过 {@code callbacks} 转发。
     *
     * @param activity  当前 Activity
     * @param container DecorView 容器
     * @param callbacks 业务回调
     */
    public void wireButtons(final Activity activity, final ViewGroup container,
            final Callbacks callbacks) {
        if (mPanelView == null) return;
        View removeMenu = mPanelView.findViewById(R.id.remove_menu);
        View modifyMenu = mPanelView.findViewById(R.id.modify_menu);

        // 移除按钮
        View btnBlock = mPanelView.findViewById(R.id.block);
        TooltipCompat.setTooltipText(btnBlock, GmResources.getText(R.string.accessibility_block));
        btnBlock.setOnClickListener(v -> {
            if (!mModifySessionLocked && mHasUserSelection && getSelectedView() != null) {
                callbacks.onBlockRequested(activity, container);
            }
        });

        // 预览按钮
        View btnPreview = mPanelView.findViewById(R.id.preview);
        TooltipCompat.setTooltipText(btnPreview, GmResources.getText(R.string.accessibility_preview));
        btnPreview.setOnClickListener(v -> {
            if (!mModifySessionLocked && mHasUserSelection && getSelectedView() != null) {
                callbacks.onPreviewRequested(activity);
            }
        });

        // 修改按钮 — 打开属性编辑面板
        View btnModify = mPanelView.findViewById(R.id.modify);
        btnModify.setOnClickListener(v -> {
            if (mModifySessionLocked || !mHasUserSelection) return;
            View selectedView = getSelectedView();
            if (selectedView != null) {
                callbacks.onModifyRequested(selectedView, activity, container);
            }
        });

        // 修改预览按钮
        View btnModifyPreview = mPanelView.findViewById(R.id.modify_preview);
        btnModifyPreview.setEnabled(false);
        btnModifyPreview.setOnClickListener(v -> {
            if (mModifySessionLocked) callbacks.onModifyPreviewRequested(activity);
        });

        View undoButton = mPanelView.findViewById(R.id.undo);
        CharSequence undoDescription = GmResources.getText(R.string.accessibility_undo);
        undoButton.setContentDescription(undoDescription);
        TooltipCompat.setTooltipText(undoButton, undoDescription);
        undoButton.setOnClickListener(v -> {
            if (mUndoAvailable) callbacks.onUndoRequested(activity);
        });
        updateUndoButton();

        // 模式切换
        View removeModeBtn = mPanelView.findViewById(R.id.remove_mode_btn);
        View modifyModeBtn = mPanelView.findViewById(R.id.modify_mode_btn);

        removeModeBtn.setOnClickListener(v -> {
            if (mModifySessionLocked) return;
            boolean wasVisible = removeMenu.getVisibility() == View.VISIBLE;
            removeMenu.setVisibility(wasVisible ? View.GONE : View.VISIBLE);
            modifyMenu.setVisibility(View.GONE);
            syncModeControls();
            callbacks.onModeChanged(wasVisible ? EditorInteractionMode.INITIAL : EditorInteractionMode.REMOVE);
        });

        modifyModeBtn.setOnClickListener(v -> {
            if (mModifySessionLocked) return;
            boolean wasVisible = modifyMenu.getVisibility() == View.VISIBLE;
            modifyMenu.setVisibility(wasVisible ? View.GONE : View.VISIBLE);
            removeMenu.setVisibility(View.GONE);
            syncModeControls();
            callbacks.onModeChanged(wasVisible ? EditorInteractionMode.INITIAL : EditorInteractionMode.MODIFY);
        });

        syncModeControls();

        // 面板位置切换 — 在贴边与向左让位（右侧空出约 1/6 屏宽）两种状态间切换
        View exchangeBtn = mPanelView.findViewById(R.id.exchange);
        ViewGroup topContent = mPanelView.findViewById(R.id.top_content);
        exchangeBtn.setOnClickListener(v -> {
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int basePx = dpToPx(metrics, PANEL_PADDING_DP);
            int collapsedPx = dpToPx(metrics, END_PADDING_COLLAPSED_DP);
            int expandedPx = Math.round(metrics.widthPixels * END_PADDING_EXPANDED_FRACTION);
            int endPx = mNavigationPaneExpanded ? collapsedPx : expandedPx;
            mNavigationPaneExpanded = !mNavigationPaneExpanded;
            topContent.setPadding(basePx, basePx, endPx, basePx);
        });

        // 信息流模式
        TextView infoFlowBtn = mPanelView.findViewById(R.id.info_flow_mode_btn);
        if (infoFlowBtn != null) {
            infoFlowBtn.setOnClickListener(v -> callbacks.onInfoFlowRequested());
        }

        // 上/下导航
        mPanelView.findViewById(R.id.Up).setOnClickListener(v -> navigatePrevious());
        mPanelView.findViewById(R.id.Down).setOnClickListener(v -> navigateNext());
    }

    // ---- 访问器 ----

    public View getPanelView() { return mPanelView; }
    public SeekBar getSeekBar() { return mSeekBar; }
    public MaskView getMaskView() { return mMaskView; }
    public boolean isKeySelecting() { return mKeySelecting; }

    public void setHasUserSelection(boolean v) { mHasUserSelection = v; }
    public boolean hasUserSelection() { return mHasUserSelection; }

    public View getSelectedView() {
        if (mViewNodes != null && mCurrentIndex < mViewNodes.size()) {
            WeakReference<View> ref = mViewNodes.get(mCurrentIndex);
            return ref != null ? ref.get() : null;
        }
        return null;
    }

    public void setCurrentIndex(int index) {
        if (mViewNodes != null && index >= 0 && index < mViewNodes.size()) {
            if (mCurrentIndex != index) mSelectionRevision++;
            mCurrentIndex = index;
            mSeekBar.setProgress(index);
        }
    }

    /** 供 SeekBar.onProgressChanged 内部使用，不触发 setProgress 循环。 */
    public void setCurrentIndexSilent(int index) {
        if (mViewNodes != null && index >= 0 && index < mViewNodes.size()) {
            mCurrentIndex = index;
        }
    }

    public int getCurrentIndex() { return mCurrentIndex; }
    public long getSelectionRevision() { return mSelectionRevision; }
    public boolean isShowing() { return mPanelView != null; }

    /** Lock target navigation and non-edit actions while a property edit is active. */
    public void setModifySessionLocked(boolean locked) {
        mModifySessionLocked = locked;
        if (!locked) mModifyPreviewing = false;
        if (mPanelView == null) return;
        setEnabled(R.id.block, !locked);
        setEnabled(R.id.preview, !locked);
        setEnabled(R.id.modify, !locked);
        setEnabled(R.id.remove_mode_btn, !locked);
        setEnabled(R.id.modify_mode_btn, !locked);
        setEnabled(R.id.Up, !locked);
        setEnabled(R.id.Down, !locked);
        setEnabled(R.id.modify_preview, locked);
        mSeekBar.setEnabled(!locked);
        setEnabled(R.id.info_flow_mode_btn, !locked);
        syncModeControls();
    }

    /**
     * Restores the modify-mode toolbar after the property editor session ends.
     * The global editor mode and orchestrator state are owned by EditorOrchestrator;
     * this method only restores this panel's menu and control presentation to MODIFY
     * (modify expanded, remove collapsed) so save/cancel keeps the modify mode.
     */
    public void restoreModifyMode() {
        mModifyPreviewing = false;
        if (mPanelView == null) return;
        View removeMenu = mPanelView.findViewById(R.id.remove_menu);
        View modifyMenu = mPanelView.findViewById(R.id.modify_menu);
        if (removeMenu != null) removeMenu.setVisibility(View.GONE);
        if (modifyMenu != null) modifyMenu.setVisibility(View.VISIBLE);
        syncModeControls();
    }

    /** Re-highlights the currently selected view. No-op when detached. */
    public void refreshMaskToSelection() {
        if (mPanelView == null || mMaskView == null) return;
        View selected = getSelectedView();
        if (selected == null || !selected.isAttachedToWindow()) {
            mMaskView.updateOverlayBounds(new Rect());
            return;
        }
        mMaskView.updateOverlayBounds(ViewUtils.getLocationInWindow(selected));
    }

    public boolean isModifySessionLocked() { return mModifySessionLocked; }

    /** Update the toolbar state for the edit-panel preview toggle. */
    public void setModifyPreviewing(boolean previewing) {
        mModifyPreviewing = previewing;
        if (mPanelView == null) return;
        View button = mPanelView.findViewById(R.id.modify_preview);
        if (button instanceof ImageButton) {
            ((ImageButton) button).setImageResource(previewing
                    ? android.R.drawable.ic_menu_close_clear_cancel
                    : android.R.drawable.ic_menu_view);
        }
        try {
            TooltipCompat.setTooltipText(button, GmResources.getText(previewing
                    ? R.string.accessibility_modify_preview_exit
                    : R.string.accessibility_modify_preview));
        } catch (Exception ignored) {
            // Resource fallback is best-effort and must not break the editor toolbar.
        }
    }

    public boolean isModifyPreviewing() { return mModifyPreviewing; }

    /** Applies the authoritative undo projection and in-flight state to the toolbar button. */
    public void setUndoAvailable(boolean available) {
        mUndoAvailable = available;
        updateUndoButton();
    }

    public void setModifyPreviewEnabled(boolean enabled) {
        if (mPanelView == null) return;
        View preview = mPanelView.findViewById(R.id.modify_preview);
        if (preview != null) preview.setEnabled(enabled && mModifySessionLocked);
    }

    public List<WeakReference<View>> getViewNodes() { return mViewNodes; }

    // ---- 导航 ----

    /** 按 delta 步进导航（+1 或 -1），更新 SeekBar，不做越界。 */
    public void navigate(int delta) {
        if (mModifySessionLocked) return;
        if (mViewNodes == null || mSeekBar == null) return;
        int next = mCurrentIndex + delta;
        if (next < 0 || next >= mViewNodes.size()) return;
        if (mCurrentIndex != next) mSelectionRevision++;
        mCurrentIndex = next;
        mSeekBar.setProgress(next);
    }

    public void navigateNext() { navigate(+1); }
    public void navigatePrevious() { navigate(-1); }

    /** Records a user-driven SeekBar selection after the silent index update. */
    public void markUserSelectionChanged() {
        mSelectionRevision++;
    }

    /**
     * Projects a committed removal onto the node list without overwriting a newer user
     * selection made while the animation/IPC transaction was in flight.
     */
    public void applyRemoveProjection(View removedView, int fallbackIndex,
            long expectedSelectionRevision) {
        if (mModifySessionLocked) return;
        if (mViewNodes == null || mSeekBar == null) return;

        View selectedBefore = getSelectedView();
        int removedIndex = findViewIndex(removedView);
        if (removedIndex < 0 && removedView == null
                && fallbackIndex >= 0 && fallbackIndex < mViewNodes.size()) {
            removedIndex = fallbackIndex;
        }
        if (removedIndex >= 0) {
            mViewNodes.remove(removedIndex);
        }
        mSeekBar.setMax(Math.max(mViewNodes.size() - 1, 0));

        if (mViewNodes.isEmpty()) {
            mCurrentIndex = 0;
            return;
        }

        boolean shouldMoveSelection = removedIndex >= 0
                && (selectedBefore == removedView
                || mSelectionRevision == expectedSelectionRevision);
        if (shouldMoveSelection) {
            int nextIndex = removedIndex >= 0
                    ? Math.min(removedIndex, mViewNodes.size() - 1)
                    : Math.min(mCurrentIndex, mViewNodes.size() - 1);
            mCurrentIndex = Math.max(nextIndex, 0);
            mSeekBar.setProgress(mCurrentIndex);
            return;
        }

        // Preserve a newer user selection by its object identity. Never reuse the stale index.
        int preservedIndex = findViewIndex(selectedBefore);
        if (preservedIndex >= 0) {
            mCurrentIndex = preservedIndex;
            // Keep the SeekBar value in sync without generating a redundant mask update.
            if (mSeekBar.getProgress() != preservedIndex) {
                mSeekBar.setProgress(preservedIndex);
            }
        } else {
            // The selected View was already detached/collected. Do not guess a replacement.
            mCurrentIndex = Math.min(mCurrentIndex, mViewNodes.size() - 1);
        }
    }

    private int findViewIndex(View target) {
        if (target == null || mViewNodes == null) return -1;
        for (int i = 0; i < mViewNodes.size(); i++) {
            WeakReference<View> ref = mViewNodes.get(i);
            if (ref != null && ref.get() == target) return i;
        }
        return -1;
    }

    private void setEnabled(int id, boolean enabled) {
        View view = mPanelView.findViewById(id);
        if (view != null) view.setEnabled(enabled);
    }

    /** Keeps operation-mode buttons consistent with menu visibility and session lock. */
    private void syncModeControls() {
        if (mPanelView == null) return;
        View removeMenu = mPanelView.findViewById(R.id.remove_menu);
        View modifyMenu = mPanelView.findViewById(R.id.modify_menu);
        View removeModeBtn = mPanelView.findViewById(R.id.remove_mode_btn);
        View modifyModeBtn = mPanelView.findViewById(R.id.modify_mode_btn);
        if (removeModeBtn == null || modifyModeBtn == null) return;
        if (mModifySessionLocked) {
            removeModeBtn.setEnabled(false);
            modifyModeBtn.setEnabled(false);
            return;
        }
        boolean removeVisible = removeMenu != null && removeMenu.getVisibility() == View.VISIBLE;
        boolean modifyVisible = modifyMenu != null && modifyMenu.getVisibility() == View.VISIBLE;
        removeModeBtn.setEnabled(!modifyVisible);
        modifyModeBtn.setEnabled(!removeVisible);
    }

    private static int dpToPx(DisplayMetrics metrics, int dp) {
        return Math.round(dp * metrics.density);
    }

    private void updateUndoButton() {
        if (mPanelView == null) return;
        View undo = mPanelView.findViewById(R.id.undo);
        if (undo != null) undo.setEnabled(mUndoAvailable);
    }

    // =========================================================================
    // 模块资源回退补丁 — 当 ModuleResources.injectInto() 失败时,
    // 使用 GmResources (模块自己的 Resources 实例) 手动设置 drawable/string,
    // 避免 toolbar 元素因资源无法解析而呈现空白
    // =========================================================================

    /**
     * 当模块资源注入失败时,回退设置所有依赖模块资源的 view 属性。
     * 仅在 {@link ModuleResources#injectInto} 返回 false 时调用。
     */
    private static void patchModuleResources(View panelView) {
        if (panelView == null) return;
        try {
            // ── 主工具栏背景 rounded_bg_full ──
            // 布局: panel_view(FrameLayout) > top_content(LinearLayout) > toolbar_column(LinearLayout 64dp)
            View topContent = panelView.findViewById(R.id.top_content);
            if (topContent instanceof ViewGroup) {
                ViewGroup tc = (ViewGroup) topContent;
                if (tc.getChildCount() > 0) {
                    View toolbarColumn = tc.getChildAt(0);
                    if (toolbarColumn != null && toolbarColumn.getBackground() == null) {
                        try {
                            toolbarColumn.setBackground(GmResources.getDrawable(R.drawable.rounded_bg_full));
                        } catch (Exception e) {
                            Logger.d(TAG, "toolbar resource fallback failed", e);
                        }
                    }
                }
            }

            // ── 按钮背景 ripple_drawable_20dp (所有交互按钮) ──
            Drawable rippleBg = null;
            try { rippleBg = GmResources.getDrawable(R.drawable.ripple_drawable_20dp); } catch (Exception e) {
                Logger.d(TAG, "toolbar resource fallback failed", e);
            }
            if (rippleBg != null) {
                int[] rippleViewIds = {
                        R.id.exchange, R.id.info_flow_mode_btn,
                        R.id.remove_mode_btn, R.id.modify_mode_btn,
                        R.id.block, R.id.preview,
                        R.id.modify, R.id.modify_preview, R.id.undo,
                        R.id.Up, R.id.Down
                };
                for (int id : rippleViewIds) {
                    View v = panelView.findViewById(id);
                    if (v != null && v.getBackground() == null) {
                        v.setBackground(rippleBg);
                    }
                }
            }

            // ── ImageButton src drawable ──
            patchImageButtonSrc(panelView, R.id.exchange, R.drawable.exchange);
            patchImageButtonSrc(panelView, R.id.block, R.drawable.ic_block);
            patchImageButtonSrc(panelView, R.id.undo, R.drawable.ic_undo);
            patchImageButtonSrc(panelView, R.id.Up, R.drawable.up);
            patchImageButtonSrc(panelView, R.id.Down, R.drawable.down);
            patchImageButtonSrc(panelView, R.id.modify, R.drawable.ic_modify);

            // ── TextView text ──
            patchTextViewText(panelView, R.id.remove_mode_btn, R.string.mode_remove);
            patchTextViewText(panelView, R.id.modify_mode_btn, R.string.mode_modify);
            patchTextViewText(panelView, R.id.info_flow_mode_btn, R.string.mode_info_flow_off);

            // ── Tooltip (accessibility descriptions) ──
            try {
                View previewBtn = panelView.findViewById(R.id.preview);
                if (previewBtn != null) TooltipCompat.setTooltipText(previewBtn,
                        GmResources.getText(R.string.accessibility_preview));
                View modifyBtn = panelView.findViewById(R.id.modify);
                if (modifyBtn != null) TooltipCompat.setTooltipText(modifyBtn,
                        GmResources.getText(R.string.accessibility_modify));
                View modifyPreviewBtn = panelView.findViewById(R.id.modify_preview);
                if (modifyPreviewBtn != null) TooltipCompat.setTooltipText(modifyPreviewBtn,
                        GmResources.getText(R.string.accessibility_modify_preview));
                View undoBtn = panelView.findViewById(R.id.undo);
                if (undoBtn != null) {
                    CharSequence undoDescription = GmResources.getText(
                            R.string.accessibility_undo);
                    undoBtn.setContentDescription(undoDescription);
                    TooltipCompat.setTooltipText(undoBtn, undoDescription);
                }
            } catch (Exception e) {
                Logger.d(TAG, "toolbar resource fallback failed", e);
            }
        } catch (Exception e) {
            // 回退设置失败不应阻止 toolbar 显示,静默处理
        }
    }

    private static void patchImageButtonSrc(View panelView, int viewId, int drawableResId) {
        View v = panelView.findViewById(viewId);
        if (v instanceof ImageButton) {
            ImageButton ib = (ImageButton) v;
            if (ib.getDrawable() == null) {
                try {
                    Drawable d = GmResources.getDrawable(drawableResId);
                    if (d != null) ib.setImageDrawable(d);
                } catch (Exception e) {
                    Logger.d(TAG, "toolbar resource fallback failed", e);
                }
            }
        }
    }

    private static void patchTextViewText(View panelView, int viewId, int stringResId) {
        View v = panelView.findViewById(viewId);
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (tv.length() == 0) {
                try {
                    CharSequence text = GmResources.getText(stringResId);
                    if (text != null) tv.setText(text);
                } catch (Exception e) {
                    Logger.d(TAG, "toolbar resource fallback failed", e);
                }
            }
        }
    }
}
