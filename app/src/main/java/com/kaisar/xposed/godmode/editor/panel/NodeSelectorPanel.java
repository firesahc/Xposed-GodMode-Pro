package com.kaisar.xposed.godmode.editor.panel;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.Display;
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
        void onSaveModifyRequested(Activity activity);
        void onModeChanged(int mode);
        void onInfoFlowRequested();
    }

    private View mPanelView;
    private int mCurrentIndex;
    private List<WeakReference<View>> mViewNodes;
    private SeekBar mSeekBar;
    private MaskView mMaskView;
    private boolean mHasUserSelection;
    private boolean mKeySelecting;

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
        mHasUserSelection = false;
        try {
            mMaskView = MaskView.makeMaskView(activity);
            mMaskView.setMaskOverlay(overlayColor);
            mMaskView.attachToContainer(container);
            // 尝试注入模块资源，记录是否成功
            boolean moduleResInjected = ModuleResources.injectInto(activity.getResources());
            LayoutInflater inflater = LayoutInflater.from(activity);
            mPanelView = inflater.inflate(
                    GmResources.getLayout(R.layout.panel_node_selector), container, false);
            // 如果注入失败（某些 APP 可能无法通过 addAssetPath 加载模块 APK），
            // 通过 GmResources（模块自己的 Resources）回退设置模块资源，
            // 否则 toolbar 的 ImageButton 无图、TextView 无字、背景透明
            if (!moduleResInjected) {
                patchModuleResources(mPanelView);
            }
            mSeekBar = mPanelView.findViewById(R.id.slider);
            mSeekBar.setMax(Math.max(viewNodes.size() - 1, 0));
            mSeekBar.setOnSeekBarChangeListener(seekBarListener);
            container.addView(mPanelView);
            mPanelView.setAlpha(0);
            mPanelView.post(() -> {
                mPanelView.setTranslationX(mPanelView.getWidth() / 2.0f);
                mPanelView.animate().alpha(1).translationX(0)
                        .setDuration(300).setInterpolator(new DecelerateInterpolator(1.0f)).start();
            });
            mKeySelecting = true;
        } catch (Exception e) {
            if (mMaskView != null) { mMaskView.detachFromContainer(); mMaskView = null; }
            mKeySelecting = false;
        }
    }

    /** 关闭面板，带动画 */
    public void dismiss() {
        mKeySelecting = false;
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
        btnBlock.setOnClickListener(v -> callbacks.onBlockRequested(activity, container));

        // 预览按钮
        View btnPreview = mPanelView.findViewById(R.id.preview);
        TooltipCompat.setTooltipText(btnPreview, GmResources.getText(R.string.accessibility_preview));
        btnPreview.setOnClickListener(v -> callbacks.onPreviewRequested(activity));

        // 修改按钮 — 打开属性编辑面板
        View btnModify = mPanelView.findViewById(R.id.modify);
        btnModify.setOnClickListener(v -> {
            if (!mHasUserSelection) return;
            View selectedView = getSelectedView();
            if (selectedView != null) {
                callbacks.onModifyRequested(selectedView, activity, container);
            }
        });

        // 保存修改按钮
        View btnSaveModify = mPanelView.findViewById(R.id.save_modify);
        btnSaveModify.setOnClickListener(v -> callbacks.onSaveModifyRequested(activity));

        // 模式切换
        View removeModeBtn = mPanelView.findViewById(R.id.remove_mode_btn);
        View modifyModeBtn = mPanelView.findViewById(R.id.modify_mode_btn);

        removeModeBtn.setOnClickListener(v -> {
            boolean wasVisible = removeMenu.getVisibility() == View.VISIBLE;
            removeMenu.setVisibility(wasVisible ? View.GONE : View.VISIBLE);
            modifyMenu.setVisibility(View.GONE);
            modifyModeBtn.setEnabled(wasVisible);
            callbacks.onModeChanged(wasVisible ? EditorInteractionMode.INITIAL : EditorInteractionMode.REMOVE);
        });

        modifyModeBtn.setOnClickListener(v -> {
            boolean wasVisible = modifyMenu.getVisibility() == View.VISIBLE;
            modifyMenu.setVisibility(wasVisible ? View.GONE : View.VISIBLE);
            removeMenu.setVisibility(View.GONE);
            removeModeBtn.setEnabled(wasVisible);
            callbacks.onModeChanged(wasVisible ? EditorInteractionMode.INITIAL : EditorInteractionMode.MODIFY);
        });

        // 面板位置切换
        View exchangeBtn = mPanelView.findViewById(R.id.exchange);
        View topContent = mPanelView.findViewById(R.id.topcentent);
        exchangeBtn.setOnClickListener(v -> {
            Display display = activity.getWindowManager().getDefaultDisplay();
            int width = display.getWidth();
            int targetWidth = width - (width / 6);
            topContent.setPadding(4, 4,
                    topContent.getPaddingRight() == targetWidth ? 12 : targetWidth, 4);
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
    public boolean isShowing() { return mPanelView != null; }

    public List<WeakReference<View>> getViewNodes() { return mViewNodes; }

    // ---- 导航 ----

    /** 按 delta 步进导航（+1 或 -1），更新 SeekBar，不做越界。 */
    public void navigate(int delta) {
        if (mViewNodes == null || mSeekBar == null) return;
        int next = mCurrentIndex + delta;
        if (next < 0 || next >= mViewNodes.size()) return;
        mCurrentIndex = next;
        mSeekBar.setProgress(next);
    }

    public void navigateNext() { navigate(+1); }
    public void navigatePrevious() { navigate(-1); }

    /** 移除后更新节点列表和 SeekBar。 */
    public void updateAfterRemove(int removedIndex) {
        if (mViewNodes == null || mSeekBar == null) return;
        if (removedIndex >= 0 && removedIndex < mViewNodes.size()) {
            mViewNodes.remove(removedIndex);
        }
        mSeekBar.setMax(Math.max(mViewNodes.size() - 1, 0));
        mCurrentIndex = Math.min(removedIndex, Math.max(mViewNodes.size() - 1, 0));
        if (mCurrentIndex >= 0) {
            mSeekBar.setProgress(mCurrentIndex);
        }
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
            // 布局: panel_view(FrameLayout) > topcentent(LinearLayout) > toolbar_column(LinearLayout 64dp)
            View topContent = panelView.findViewById(R.id.topcentent);
            if (topContent instanceof ViewGroup) {
                ViewGroup tc = (ViewGroup) topContent;
                if (tc.getChildCount() > 0) {
                    View toolbarColumn = tc.getChildAt(0);
                    if (toolbarColumn != null && toolbarColumn.getBackground() == null) {
                        try {
                            toolbarColumn.setBackground(GmResources.getDrawable(R.drawable.rounded_bg_full));
                        } catch (Exception e) {
                            Logger.d(TAG, "toolbar resource fallback failed: " + e.getMessage(), e);
                        }
                    }
                }
            }

            // ── 按钮背景 ripple_drawable_20dp (所有交互按钮) ──
            Drawable rippleBg = null;
            try { rippleBg = GmResources.getDrawable(R.drawable.ripple_drawable_20dp); } catch (Exception e) {
                Logger.d(TAG, "toolbar resource fallback failed: " + e.getMessage(), e);
            }
            if (rippleBg != null) {
                int[] rippleViewIds = {
                        R.id.exchange, R.id.info_flow_mode_btn,
                        R.id.remove_mode_btn, R.id.modify_mode_btn,
                        R.id.block, R.id.preview,
                        R.id.modify, R.id.save_modify,
                        R.id.Up, R.id.Down
                };
                for (int id : rippleViewIds) {
                    View v = panelView.findViewById(id);
                    if (v != null && v.getBackground() == null) {
                        v.setBackground(rippleBg);
                    }
                }
            }

            // ── remove_menu 背景 rounded_bg_bottom_background ──
            View removeMenu = panelView.findViewById(R.id.remove_menu);
            if (removeMenu != null && removeMenu.getBackground() == null) {
                try {
                    removeMenu.setBackground(GmResources.getDrawable(R.drawable.rounded_bg_bottom_background));
                } catch (Exception e) {
                    Logger.d(TAG, "toolbar resource fallback failed: " + e.getMessage(), e);
                }
            }

            // ── ImageButton src drawable ──
            patchImageButtonSrc(panelView, R.id.exchange, R.drawable.exchange);
            patchImageButtonSrc(panelView, R.id.block, R.drawable.ic_block);
            patchImageButtonSrc(panelView, R.id.Up, R.drawable.up);
            patchImageButtonSrc(panelView, R.id.Down, R.drawable.down);

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
                View saveBtn = panelView.findViewById(R.id.save_modify);
                if (saveBtn != null) TooltipCompat.setTooltipText(saveBtn,
                        GmResources.getText(R.string.accessibility_save_modify));
            } catch (Exception e) {
                Logger.d(TAG, "toolbar resource fallback failed: " + e.getMessage(), e);
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
                    Logger.d(TAG, "toolbar resource fallback failed: " + e.getMessage(), e);
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
                    Logger.d(TAG, "toolbar resource fallback failed: " + e.getMessage(), e);
                }
            }
        }
    }
}
