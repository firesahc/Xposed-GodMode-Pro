package com.kaisar.xposed.godmode.hook;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;
import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.TooltipCompat;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.injection.GodModeInjector;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.ViewHelper;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.util.GmResources;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.injection.util.Property;
import com.kaisar.xposed.godmode.injection.util.ToolbarVisibilityController;
import com.kaisar.xposed.godmode.injection.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.injection.editor.overlay.ParticleView;
import com.kaisar.xposed.godmode.injection.editor.panel.NodeSelectorPanel;
import com.kaisar.xposed.godmode.injection.editor.panel.PropertyEditorPanel;
import com.kaisar.xposed.godmode.rule.ViewRule;

import java.lang.ref.WeakReference;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook {@link Activity#dispatchKeyEvent}，提供浮动的检查器面板，
 * 用于在目标应用中选择、移除和修改视图。
 * <p>
 * 协调的子控制器：
 * <ul>
 *   <li>{@link PropertyEditorPanel} – 逐视图属性编辑面板</li>
 * </ul>
 */
public final class KeyInterceptor extends XC_MethodHook
        implements Property.OnPropertyChangeListener<Boolean>, SeekBar.OnSeekBarChangeListener {

    // =========================================================================
    // 常量与交互模式
    // =========================================================================

    private static final int OVERLAY_COLOR = Color.argb(150, 255, 0, 0);
    private static final int OVERLAY_COLOR_REPEATABLE = Color.argb(150, 255, 165, 0);
    private static KeyInterceptor sInstance;

    public static final int MODE_INITIAL = 0;
    public static final int MODE_REMOVE = 1;
    public static final int MODE_MODIFY = 2;
    private static volatile int sInteractionMode = MODE_INITIAL;

    public static int getInteractionMode() { return sInteractionMode; }
    public static boolean isKeySelecting() { return sInstance != null && sInstance.mNodePanel.isKeySelecting(); }

    private static volatile boolean sInfoFlowMode = false;
    public static boolean isInfoFlowMode() { return sInfoFlowMode; }

    // =========================================================================
    // 预览状态（在确认移除前临时隐藏视图）
    // =========================================================================

    private View mPreviewView;
    private ViewRule mPreviewRule;
    private boolean mIsPreviewing;

    // =========================================================================
    // 子控制器
    // =========================================================================

    private final NodeSelectorPanel mNodePanel = new NodeSelectorPanel();
    final PropertyEditorPanel mModifyController = new PropertyEditorPanel();
    private Activity mCurrentActivity;

    // =========================================================================
    // 构造器 — 注册音量键 Hook
    // =========================================================================

    public KeyInterceptor() {
        sInstance = this;
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent", KeyEvent.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!GodModeInjector.switchProp.get() || TouchInterceptor.mDragging) return;
                KeyEvent event = (KeyEvent) param.args[0];
                int action = event.getAction();
                int keyCode = event.getKeyCode();
                if (action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                    Activity currentActivity = sInstance.mCurrentActivity;
                    if (!sInstance.mNodePanel.isKeySelecting() && currentActivity != null) {
                        sInstance.showNodeSelectPanel(currentActivity);
                    } else if (sInstance.mNodePanel.isKeySelecting()) {
                        sInstance.dismissNodeSelectPanel();
                    }
                    param.setResult(true);
                } else if (sInstance.mNodePanel.isKeySelecting() && action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        sInstance.navigatePrevious();
                        param.setResult(true);
                    } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        sInstance.navigateNext();
                        param.setResult(true);
                    }
                }
            }
        });
    }

    // =========================================================================
    // Activity 生命周期（由 GodModeInjector 在 Activity.onCreate 中调用）
    // =========================================================================

    public void setActivity(final Activity a) {
        if (mCurrentActivity != null && mCurrentActivity != a && mNodePanel.isKeySelecting()) {
            dismissNodeSelectPanel();
        }
        mCurrentActivity = a;
    }

    public void setdisplay(Boolean display) {
        if (mCurrentActivity == null) return;
        if (display == null) return;
        if (display && !GodModeInjector.switchProp.get()) return;
        if (display) {
            if (!mNodePanel.isKeySelecting()) {
                showNodeSelectPanel(mCurrentActivity);
            }
        } else {
            dismissNodeSelectPanel();
        }
    }

    // =========================================================================
    // 视图选择 — 点击选中、获取选中视图、视图匹配
    // =========================================================================

    /** 通过点击事件选中视图（从 TouchInterceptor 调用） */
    public static void selectViewByTap(View tappedView) {
        KeyInterceptor instance = sInstance;
        if (instance == null || !instance.mNodePanel.isKeySelecting()
                || instance.mModifyController.isShowing()) return;

        List<WeakReference<View>> nodes = instance.mNodePanel.getViewNodes();
        if (nodes == null) return;
        for (int i = nodes.size() - 1; i >= 0; i--) {
            View v = nodes.get(i).get();
            if (v != null && isViewMatch(v, tappedView)) {
                instance.mNodePanel.setCurrentIndex(i);
                instance.mNodePanel.setHasUserSelection(true);
                return;
            }
        }
    }

    /** 获取当前选中的视图（由 TouchInterceptor 的修改模式拖拽调用） */
    public static View getSelectedView() {
        KeyInterceptor instance = sInstance;
        if (instance == null) return null;
        return instance.mNodePanel.getSelectedView();
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
    // 视图导航 — SeekBar 按钮和音量键步进
    // =========================================================================

    private void toggleInfoFlowMode() {
        sInfoFlowMode = !sInfoFlowMode;
        updateInfoFlowModeButton();
        Toast.makeText(mCurrentActivity, GmResources.getString(sInfoFlowMode ? R.string.accessibility_info_flow_on : R.string.accessibility_info_flow_off), Toast.LENGTH_SHORT).show();
    }
    private void updateInfoFlowModeButton() {
        View panelView = mNodePanel.getPanelView();
        if (panelView == null) return;
        TextView btn = panelView.findViewById(R.id.info_flow_mode_btn);
        if (btn == null) return;
        if (sInfoFlowMode) {
            btn.setText(GmResources.getText(R.string.mode_info_flow_on));
            btn.setTextColor(android.graphics.Color.parseColor("#FFA500"));
        } else {
            btn.setText(GmResources.getText(R.string.mode_info_flow_off));
            btn.setTextColor(android.graphics.Color.GRAY);
        }
    }

    private void navigate(int delta) {
        if (mModifyController.isShowing()) return;
        mNodePanel.navigate(delta);
    }

    private void navigateNext() { navigate(+1); }

    private void navigatePrevious() { navigate(-1); }

    // =========================================================================
    // 节点选择器面板 — 显示、关闭、按钮绑定
    // =========================================================================

    private void showNodeSelectPanel(final Activity activity) {
        Logger.i(TAG, "[KeyEventHook] showNodeSelectPanel for " + activity.getPackageName());
        List<WeakReference<View>> viewNodes = ViewHelper.buildViewNodes(activity.getWindow().getDecorView());
        final ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
        mNodePanel.show(viewNodes, activity, container, this);
        if (!mNodePanel.isKeySelecting()) return; // show() failed
        MaskView mask = mNodePanel.getMaskView();
        if (mask != null) {
            mask.setMaskOverlay(OVERLAY_COLOR);
        }
        ToolbarVisibilityController.apply(mNodePanel.getPanelView());
        wireNodeSelectorButtons(activity, container);
    }

    /** 绑定节点选择器面板所有按钮的点击监听 */
    private void wireNodeSelectorButtons(final Activity activity, final ViewGroup container) {
        View panelView = mNodePanel.getPanelView();
        if (panelView == null) return;
        View removeMenu = panelView.findViewById(R.id.remove_menu);
        View modifyMenu = panelView.findViewById(R.id.modify_menu);

        // 移除按钮
        View btnBlock = panelView.findViewById(R.id.block);
        TooltipCompat.setTooltipText(btnBlock, GmResources.getText(R.string.accessibility_block));
        btnBlock.setOnClickListener(v -> performBlock(activity, container));

        // 预览按钮
        View btnPreview = panelView.findViewById(R.id.preview);
        TooltipCompat.setTooltipText(btnPreview, GmResources.getText(R.string.accessibility_preview));
        btnPreview.setOnClickListener(v -> togglePreview(activity));

        // 修改按钮 — 打开属性编辑面板
        View btnModify = panelView.findViewById(R.id.modify);
        btnModify.setOnClickListener(v -> {
            if (!mNodePanel.hasUserSelection()) return;
            View selectedView = mNodePanel.getSelectedView();
            if (selectedView != null) {
                mModifyController.show(selectedView, activity, container);
            }
        });

        // 保存修改按钮
        View btnSaveModify = panelView.findViewById(R.id.save_modify);
        btnSaveModify.setOnClickListener(v -> mModifyController.saveAll(
                activity, mNodePanel.getPanelView(), mNodePanel.getMaskView(),
                mModifyController.getPanelView()));

        // 模式切换：移除模式
        View removeModeBtn = panelView.findViewById(R.id.remove_mode_btn);
        View modifyModeBtn = panelView.findViewById(R.id.modify_mode_btn);

        removeModeBtn.setOnClickListener(v -> {
            boolean wasVisible = removeMenu.getVisibility() == View.VISIBLE;
            removeMenu.setVisibility(wasVisible ? View.GONE : View.VISIBLE);
            modifyMenu.setVisibility(View.GONE);
            modifyModeBtn.setEnabled(wasVisible);
            sInteractionMode = wasVisible ? MODE_INITIAL : MODE_REMOVE;
        });

        // 模式切换：修改模式
        modifyModeBtn.setOnClickListener(v -> {
            boolean wasVisible = modifyMenu.getVisibility() == View.VISIBLE;
            modifyMenu.setVisibility(wasVisible ? View.GONE : View.VISIBLE);
            removeMenu.setVisibility(View.GONE);
            removeModeBtn.setEnabled(wasVisible);
            sInteractionMode = wasVisible ? MODE_INITIAL : MODE_MODIFY;
        });

        // 面板位置切换按钮
        View exchangeBtn = panelView.findViewById(R.id.exchange);
        View topContent = panelView.findViewById(R.id.topcentent);
        exchangeBtn.setOnClickListener(v -> {
            Display display = activity.getWindowManager().getDefaultDisplay();
            int width = display.getWidth();
            int targetWidth = width - (width / 6);
            topContent.setPadding(4, 4,
                    topContent.getPaddingRight() == targetWidth ? 12 : targetWidth, 4);
        });

        TextView infoFlowBtn = panelView.findViewById(R.id.info_flow_mode_btn);
        if (infoFlowBtn != null) {
            infoFlowBtn.setOnClickListener(v -> toggleInfoFlowMode());
            updateInfoFlowModeButton();
        }

        // 上/下导航按钮
        panelView.findViewById(R.id.Up).setOnClickListener(v -> navigatePrevious());
        panelView.findViewById(R.id.Down).setOnClickListener(v -> navigateNext());
    }

    private void dismissNodeSelectPanel() {
        Logger.i(TAG, "[KeyEventHook] dismissNodeSelectPanel");
        mModifyController.cancel();
        restorePreview();
        sInteractionMode = MODE_INITIAL;
        mNodePanel.dismiss();
    }

    // =========================================================================
    // 移除（屏蔽）操作
    // =========================================================================

    private void performBlock(final Activity activity, final ViewGroup container) {
        try {
            List<WeakReference<View>> viewNodes = mNodePanel.getViewNodes();
            if (viewNodes == null || viewNodes.isEmpty()) return;
            if (mIsPreviewing) restorePreview();
            final View view = mNodePanel.getSelectedView();
            Logger.d(TAG, "[KeyEventHook] block view = " + view);
            if (view == null) return;
            MaskView maskView = mNodePanel.getMaskView();
            if (maskView != null) maskView.updateOverlayBounds(new Rect());

            final int blockedViewIndex = mNodePanel.getCurrentIndex();

            // 隐藏 GM 覆盖层以获取干净截图
            hideGmOverlays(View.INVISIBLE);
            final Bitmap snapshot = ViewHelper.snapshotView(ViewHelper.findTopParentViewByChildView(view));
            hideGmOverlays(View.VISIBLE);

            final ViewRule viewRule = ViewHelper.makeRemoveRule(view);
            final ParticleView particleView = new ParticleView(activity);
            particleView.setDuration(1000);
            particleView.attachToContainer(container);
            particleView.setOnAnimationListener(new ParticleView.OnAnimationListener() {
                @Override
                public void onAnimationStart(View animView, Animator animation) {
                    viewRule.visibility = View.GONE;
                    ViewController.applyRule(view, viewRule);
                }

                @Override
                public void onAnimationEnd(View animView, Animator animation) {
                    try {
                        ViewHelper.drawRuleMask(snapshot, viewRule);
                        particleView.detachFromContainer();
                    } catch (Exception e) { Logger.e(TAG, "[KeyEventHook] write rule fail", e); }
                    restorePanelAlpha();
                    mNodePanel.updateAfterRemove(blockedViewIndex);
                    new Thread(() -> {
                        try { GodModeManager.getDefault().writeRule(activity.getPackageName(), viewRule, snapshot); }
                        catch (Exception e) { Logger.e(TAG, "[KeyEventHook] write rule fail", e); }
                        recycleNullableBitmap(snapshot);
                    }, "gm-write").start();
                }
            });
            particleView.boom(view);
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
        View modifyPanel = mModifyController.getPanelView();
        if (modifyPanel != null) modifyPanel.setVisibility(visibility);
        MaskView maskView = mNodePanel.getMaskView();
        if (maskView != null) maskView.setVisibility(visibility);
    }

    /** 移除视图后更新节点列表 */
    private void updateViewNodesAfterRemove(int removedIndex) {
        mNodePanel.updateAfterRemove(removedIndex);
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
    // 预览（在确认移除前临时隐藏视图）
    // =========================================================================

    private void togglePreview(final Activity activity) {
        if (mIsPreviewing) {
            restorePreview();
        } else {
            startPreview();
        }
    }

    private void startPreview() {
        List<WeakReference<View>> viewNodes = mNodePanel.getViewNodes();
        if (viewNodes == null || viewNodes.isEmpty()) return;
        View view = mNodePanel.getSelectedView();
        if (view == null) return;
        try {
            mPreviewRule = ViewHelper.makeRemoveRule(view);
            mPreviewRule.visibility = View.GONE;
            ViewController.applyRule(view, mPreviewRule);
            mPreviewView = view;
            mIsPreviewing = true;
            updatePreviewButton(true);
            MaskView maskView = mNodePanel.getMaskView();
            if (maskView != null) maskView.updateOverlayBounds(new Rect());
        } catch (Exception e) {
            Logger.e(TAG, "[KeyEventHook] preview fail", e);
        }
    }

    private void restorePreview() {
        if (mPreviewView != null && mPreviewRule != null) {
            mPreviewRule.visibility = View.VISIBLE;
            ViewController.revokeRule(mPreviewView, mPreviewRule);
            mPreviewView = null;
            mPreviewRule = null;
        }
        mIsPreviewing = false;
        updatePreviewButton(false);
        MaskView maskView = mNodePanel.getMaskView();
        List<WeakReference<View>> viewNodes = mNodePanel.getViewNodes();
        if (maskView != null && viewNodes != null && !viewNodes.isEmpty()) {
            View currentView = mNodePanel.getSelectedView();
            if (currentView != null) {
                maskView.updateOverlayBounds(ViewHelper.getLocationInWindow(currentView));
            }
        }
    }

    private void updatePreviewButton(boolean inPreview) {
        View panelView = mNodePanel.getPanelView();
        View btnPreview = panelView != null ? panelView.findViewById(R.id.preview) : null;
        if (btnPreview instanceof android.widget.ImageButton) {
            ((android.widget.ImageButton) btnPreview).setImageResource(
                    inPreview ? android.R.drawable.ic_menu_close_clear_cancel : android.R.drawable.ic_menu_view);
        }
        if (btnPreview != null) {
            TooltipCompat.setTooltipText(btnPreview,
                    GmResources.getText(inPreview ? R.string.accessibility_preview_exit : R.string.accessibility_preview));
        }
    }

    // =========================================================================
    // 属性变更 & SeekBar 回调
    // =========================================================================

    @Override
    public void onPropertyChange(Boolean enable) {
        if (enable == null) return;
        if (!enable) {
            sInteractionMode = MODE_INITIAL;
            // dismissNodeSelectPanel 不再在此调用——notifyEditModeChanged(false)
            // 已通过 setdisplay(false) 执行完整的 dismiss 流程，此处是冗余路径。
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (mModifyController.isShowing()) return;
        List<WeakReference<View>> viewNodes = mNodePanel.getViewNodes();
        MaskView maskView = mNodePanel.getMaskView();
        if (viewNodes != null && progress < viewNodes.size()) {
            mNodePanel.setCurrentIndexSilent(progress);
            if (fromUser) mNodePanel.setHasUserSelection(true);
            View view = viewNodes.get(mNodePanel.getCurrentIndex()).get();
            if (view != null && maskView != null) {
                if (ViewHelper.isInRecyclerView(view)) {
                    maskView.setMaskOverlay(OVERLAY_COLOR_REPEATABLE);
                } else {
                    maskView.setMaskOverlay(OVERLAY_COLOR);
                }
                maskView.updateOverlayBounds(ViewHelper.getLocationInWindow(view));
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        View panelView = mNodePanel.getPanelView();
        if (panelView != null) panelView.setAlpha(0.2f);
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        View panelView = mNodePanel.getPanelView();
        if (panelView != null) panelView.setAlpha(1f);
    }
}
