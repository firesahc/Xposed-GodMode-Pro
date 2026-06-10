package com.kaisar.xposed.godmode.injection.editor.panel;

import android.app.Activity;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.EditorInteractionMode;
import com.kaisar.xposed.godmode.injection.ModuleResources;
import com.kaisar.xposed.godmode.injection.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.injection.util.GmResources;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 节点选择面板 — 视图树导航 + 选中/预览/移除/修改操作。
 * <p>
 * 按钮点击通过 {@link Callbacks} 接口转发给外界处理器，
 * 面板自身的 UI 操作（导航、位置切换）在内部完成。
 */
public class NodeSelectorPanel {

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
            ModuleResources.injectInto(activity.getResources());
            LayoutInflater inflater = LayoutInflater.from(activity);
            mPanelView = inflater.inflate(
                    GmResources.getLayout(R.layout.panel_node_selector), container, false);
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
}
