package com.kaisar.xposed.godmode.editor.panel;

import android.app.Activity;
import android.graphics.Rect;
import android.util.DisplayMetrics;
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
            // 渲染上下文：资源/配置快照/主题三权收归模块，宿主仅提供窗口与容器。
            // 不再调用 injectInto（旧通路只污染宿主 AssetManager，对本面板无收益）。
            android.content.Context uiContext = GmResources.createUiContext(activity);
            mPanelView = GmResources.inflate(uiContext, activity,
                    R.layout.panel_node_selector, container, false);
            GmResources.markAsGmComponent(mPanelView);
            // 确定性样式层：布局只承载结构，背景/图标/文字/颜色统一在此回填；
            // 逐项独立失败、结束汇总，任一失败不阻断面板显示。
            patchModuleResources(activity, uiContext, mPanelView);
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
            Logger.e(TAG, "show: failed to attach node selector panel"
                    + " activity=" + (activity == null ? "null" : activity.getClass().getName()), e);
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
    // 确定性样式层 — 布局只承载结构，背景/图标/文字/颜色统一在此回填。
    // 资源一律取自本次 show 创建的 uiContext（模块资源＋模块主题＋宿主配置快照），
    // 不碰宿主 Resources，不依赖 injectInto。逐项独立失败，结束汇总报数。
    // =========================================================================

    /** 样式契约映射：涟漪背景目标（每 View 独立实例，见回填体）。 */
    static final int[] RIPPLE_VIEW_IDS = {
            R.id.exchange, R.id.info_flow_mode_btn,
            R.id.remove_mode_btn, R.id.modify_mode_btn,
            R.id.block, R.id.preview,
            R.id.modify, R.id.modify_preview, R.id.undo,
            R.id.Up, R.id.Down
    };
    /** 样式契约映射：{viewId, drawableRes}。 */
    static final int[][] SRC_BACKFILLS = {
            {R.id.exchange, R.drawable.exchange},
            {R.id.block, R.drawable.ic_block},
            {R.id.undo, R.drawable.ic_undo},
            {R.id.Up, R.drawable.up},
            {R.id.Down, R.drawable.down},
            {R.id.modify, R.drawable.ic_modify},
    };
    /** 样式契约映射：{viewId, stringRes}。 */
    static final int[][] TEXT_BACKFILLS = {
            {R.id.remove_mode_btn, R.string.mode_remove},
            {R.id.modify_mode_btn, R.string.mode_modify},
            {R.id.info_flow_mode_btn, R.string.mode_info_flow_off},
    };
    /** 样式契约映射：宿主自取自身框架图标的目标。 */
    static final int[] HOST_FRAMEWORK_SRC_IDS = {R.id.preview, R.id.modify_preview};

    /**
     * 程序化回填布局内 @null 化的背景/图标/文字/颜色，无条件调用。
     *
     * @param activity  宿主 Activity（仅用于取宿主自身框架图标与日志定位）
     * @param uiContext 本次 show 的渲染上下文（模块资源/主题/配置快照三权归属）
     * @param panelView 已 inflate 的面板根视图
     */
    private static void patchModuleResources(Activity activity,
            android.content.Context uiContext, View panelView) {
        if (panelView == null || uiContext == null) return;
        final android.content.res.Resources uiRes = uiContext.getResources();
        int ok = 0;
        int fail = 0;
        try {
            // ── 主工具栏背景 rounded_bg_full ──
            // 布局: panel_view(FrameLayout) > top_content(LinearLayout) > toolbar_column
            View topContent = panelView.findViewById(R.id.top_content);
            if (topContent instanceof ViewGroup) {
                ViewGroup tc = (ViewGroup) topContent;
                if (tc.getChildCount() > 0) {
                    View toolbarColumn = tc.getChildAt(0);
                    if (toolbarColumn != null && toolbarColumn.getBackground() == null) {
                        try {
                            toolbarColumn.setBackground(uiRes.getDrawable(
                                    R.drawable.rounded_bg_full, uiContext.getTheme()));
                            ok++;
                        } catch (Exception e) {
                            fail++;
                            Logger.d(TAG, "toolbar resource fallback failed", e);
                        }
                    }
                }
            }

            // ── 按钮背景 ripple（每 View 独立实例：RippleDrawable 有状态，
            // 共享同一实例会导致跨按钮状态串扰）──
            // 颜色权威：GmPanelTheme.colorControlHighlight（中性灰），与宿主主题无关；
            // 改涟漪色只改主题一处，勿改 XML 颜色。
            for (int id : RIPPLE_VIEW_IDS) {
                View v = panelView.findViewById(id);
                if (v == null || v.getBackground() != null) continue;
                try {
                    android.graphics.drawable.Drawable ripple =
                            uiRes.getDrawable(R.drawable.ripple_drawable_20dp,
                                    uiContext.getTheme());
                    if (ripple.getConstantState() != null) {
                        ripple = ripple.getConstantState().newDrawable(
                                uiRes, uiContext.getTheme());
                    }
                    v.setBackground(ripple);
                    ok++;
                } catch (Exception e) {
                    fail++;
                    Logger.d(TAG, "toolbar resource fallback failed", e);
                }
            }

            // ── ImageButton src drawable ──
            for (int[] entry : SRC_BACKFILLS) {
                if (patchImageButtonSrc(uiContext, panelView, entry[0], entry[1])) ok++;
            }

            // ── 框架图标 preview/modify_preview（宿主自取自身框架图，必然可用）──
            if (activity != null) {
                try {
                    android.graphics.drawable.Drawable previewIcon = activity.getResources()
                            .getDrawable(android.R.drawable.ic_menu_view, activity.getTheme());
                    for (int id : HOST_FRAMEWORK_SRC_IDS) {
                        View v = panelView.findViewById(id);
                        if (v instanceof ImageButton) {
                            ImageButton ib = (ImageButton) v;
                            if (ib.getDrawable() == null && previewIcon != null) {
                                if (previewIcon.getConstantState() != null) {
                                    ib.setImageDrawable(previewIcon.getConstantState()
                                            .newDrawable(activity.getResources(),
                                                    activity.getTheme()));
                                } else {
                                    ib.setImageDrawable(previewIcon);
                                }
                                ok++;
                            }
                        }
                    }
                } catch (Exception e) {
                    fail++;
                    Logger.d(TAG, "toolbar resource fallback failed", e);
                }
            }

            // ── TextView text（空或 "@id" 占位符才覆盖）──
            for (int[] entry : TEXT_BACKFILLS) {
                if (patchTextViewText(uiRes, panelView, entry[0], entry[1])) ok++;
            }

            // ── 信息流按钮文字颜色（布局内已摘除，失败保留主题默认）──
            try {
                View infoFlowBtn = panelView.findViewById(R.id.info_flow_mode_btn);
                if (infoFlowBtn instanceof TextView) {
                    ((TextView) infoFlowBtn).setTextColor(
                            uiRes.getColorStateList(R.color.info_flow_btn_text,
                                    uiContext.getTheme()));
                    ok++;
                }
            } catch (Exception e) {
                fail++;
                Logger.d(TAG, "toolbar resource fallback failed", e);
            }

            // ── Tooltip (accessibility descriptions) ──
            try {
                View previewBtn = panelView.findViewById(R.id.preview);
                if (previewBtn != null) TooltipCompat.setTooltipText(previewBtn,
                        uiRes.getText(R.string.accessibility_preview));
                View modifyBtn = panelView.findViewById(R.id.modify);
                if (modifyBtn != null) TooltipCompat.setTooltipText(modifyBtn,
                        uiRes.getText(R.string.accessibility_modify));
                View modifyPreviewBtn = panelView.findViewById(R.id.modify_preview);
                if (modifyPreviewBtn != null) TooltipCompat.setTooltipText(modifyPreviewBtn,
                        uiRes.getText(R.string.accessibility_modify_preview));
                View undoBtn = panelView.findViewById(R.id.undo);
                if (undoBtn != null) {
                    CharSequence undoDescription = uiRes.getText(R.string.accessibility_undo);
                    undoBtn.setContentDescription(undoDescription);
                    TooltipCompat.setTooltipText(undoBtn, undoDescription);
                }
                ok++;
            } catch (Exception e) {
                fail++;
                Logger.d(TAG, "toolbar resource fallback failed", e);
            }
        } catch (Exception e) {
            // 回退设置失败不应阻止 toolbar 显示,静默处理
            fail++;
        } finally {
            if (fail > 0) {
                Logger.w(TAG, "toolbar style done ok=" + ok + " fail=" + fail);
            }
        }
    }

    /** 成功回填返回 true；已具值或失败返回 false（计入汇总由调用方处理）。 */
    private static boolean patchImageButtonSrc(android.content.Context uiContext,
            View panelView, int viewId, int drawableResId) {
        View v = panelView.findViewById(viewId);
        if (v instanceof ImageButton) {
            ImageButton ib = (ImageButton) v;
            if (ib.getDrawable() == null) {
                try {
                    android.graphics.drawable.Drawable d = uiContext.getResources()
                            .getDrawable(drawableResId, uiContext.getTheme());
                    if (d != null) {
                        ib.setImageDrawable(d);
                        return true;
                    }
                } catch (Exception e) {
                    Logger.d(TAG, "toolbar resource fallback failed", e);
                }
            }
        }
        return false;
    }

    /** 空或 "@id" 占位符（MIUI 解析失败产物）才覆盖；成功返回 true。 */
    private static boolean patchTextViewText(android.content.res.Resources uiRes,
            View panelView, int viewId, int stringResId) {
        View v = panelView.findViewById(viewId);
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (isMissingText(tv.getText())) {
                try {
                    CharSequence text = uiRes.getText(stringResId);
                    if (text != null) {
                        tv.setText(text);
                        return true;
                    }
                } catch (Exception e) {
                    Logger.d(TAG, "toolbar resource fallback failed", e);
                }
            }
        }
        return false;
    }

    /**
     * MIUI 下解析失败的字符串不会留空，而是被填成 "@&lt;resId&gt;" 占位符
     *（如移除模式按钮显示 "@-1793982322"），同样视为缺失予以覆盖。
     */
    static boolean isMissingText(CharSequence text) {
        if (text == null || text.length() == 0) return true;
        CharSequence raw = text;
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '@') return false;
        try {
            Long.parseLong(raw.subSequence(1, raw.length()).toString());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
