package com.kaisar.xposed.godmode.injection.editor.panel;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.injection.ModuleResources;
import com.kaisar.xposed.godmode.injection.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.injection.util.GmResources;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 节点选择面板 — 视图树导航 + 选中/预览/移除/修改操作。
 * 从 DispatchKeyEventHook 提取核心 show/dismiss 逻辑。
 * <p>
 * 内部按钮绑定（block/preview/modify/nudge/mode切换/面板位置）仍由
 * DispatchKeyEventHook 管理，因为依赖 MODE_* 静态交互模式状态。
 */
public class NodeSelectorPanel {

    private View mPanelView;
    private int mCurrentIndex;
    private List<WeakReference<View>> mViewNodes;
    private SeekBar mSeekBar;
    private MaskView mMaskView;
    private boolean mHasUserSelection;
    private boolean mKeySelecting;

    /**
     * 显示节点选择面板。
     * @param viewNodes 视图树节点列表
     * @param activity  当前 Activity
     * @param container DecorView 容器
     * @param seekBarListener SeekBar 变化回调
     */
    public void show(List<WeakReference<View>> viewNodes, Activity activity,
            ViewGroup container, SeekBar.OnSeekBarChangeListener seekBarListener) {
        mViewNodes = viewNodes;
        mCurrentIndex = 0;
        mHasUserSelection = false;
        try {
            mMaskView = MaskView.makeMaskView(activity);
            mMaskView.setMaskOverlay(0x3A8BC34B); // OVERLAY_COLOR ARGB
            mMaskView.attachToContainer(container);
            ModuleResources.injectInto(activity.getResources());
            LayoutInflater inflater = LayoutInflater.from(activity);
            mPanelView = inflater.inflate(
                    GmResources.getLayout(R.layout.layout_node_selector), container, false);
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

    public int getCurrentIndex() { return mCurrentIndex; }
    public boolean isShowing() { return mPanelView != null; }
}
