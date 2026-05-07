package com.kaisar.xposed.godmode.injection.hook;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;
import static com.kaisar.xposed.godmode.injection.util.CommonUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import android.widget.SeekBar;
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
import com.kaisar.xposed.godmode.injection.weiget.MaskView;
import com.kaisar.xposed.godmode.injection.weiget.ParticleView;
import com.kaisar.xposed.godmode.rule.ViewRule;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public final class DispatchKeyEventHook extends XC_MethodHook implements Property.OnPropertyChangeListener<Boolean>, SeekBar.OnSeekBarChangeListener {

    private static final int OVERLAY_COLOR = Color.argb(150, 255, 0, 0);
    private static DispatchKeyEventHook sInstance;
    private final List<WeakReference<View>> mViewNodes = new ArrayList<>();
    private int mCurrentViewIndex = 0;

    private MaskView mMaskView;
    private View mNodeSelectorPanel;
    private Activity activity = null;
    private SeekBar seekbar = null;
    public static volatile boolean mKeySelecting = false;

    private View mPreviewView;
    private ViewRule mPreviewRule;
    private boolean mIsPreviewing;

    public DispatchKeyEventHook() {
        sInstance = this;
    }

    public void setactivity(final Activity a) {
        if (activity != null && activity != a && mKeySelecting) {
            dismissNodeSelectPanel();
        }
        activity = a;
    }

    public void setdisplay(Boolean display) {
        if (activity == null) return;
        if (display && !GodModeInjector.switchProp.get()) return;
        if (display) {
            if (!mKeySelecting) {
                showNodeSelectPanel(activity);
            }
        } else {
            dismissNodeSelectPanel();
        }
    }

    public static void selectViewByTap(View tappedView) {
        DispatchKeyEventHook instance = sInstance;
        if (instance == null || !instance.mKeySelecting || instance.seekbar == null) return;

        for (int i = instance.mViewNodes.size() - 1; i >= 0; i--) {
            View v = instance.mViewNodes.get(i).get();
            if (v != null && isViewMatch(v, tappedView)) {
                instance.mCurrentViewIndex = i;
                instance.seekbar.setProgress(i);
                return;
            }
        }
    }

    private static boolean isViewMatch(View candidate, View tapped) {
        if (candidate == tapped) return true;
        ViewParent parent = tapped.getParent();
        while (parent instanceof View) {
            if (parent == candidate) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private void showNodeSelectPanel(final Activity activity) {
        Logger.i(TAG, "[GodMode] showNodeSelectPanel for " + activity.getPackageName());
        mViewNodes.clear();
        mCurrentViewIndex = 0;
        mViewNodes.addAll(ViewHelper.buildViewNodes(activity.getWindow().getDecorView()));
        final ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
        mMaskView = MaskView.makeMaskView(activity);
        mMaskView.setMaskOverlay(OVERLAY_COLOR);
        mMaskView.attachToContainer(container);
        try {
            GodModeInjector.injectModuleResources(activity.getResources());
            LayoutInflater layoutInflater = LayoutInflater.from(activity);
            mNodeSelectorPanel = layoutInflater.inflate(GodModeInjector.moduleRes.getLayout(R.layout.layout_node_selector), container, false);
            seekbar = mNodeSelectorPanel.findViewById(R.id.slider);
            seekbar.setMax(Math.max(mViewNodes.size() - 1, 0));
            seekbar.setOnSeekBarChangeListener(this);

            View btnBlock = mNodeSelectorPanel.findViewById(R.id.block);
            TooltipCompat.setTooltipText(btnBlock, GmResources.getText(R.string.accessibility_block));
            btnBlock.setOnClickListener(v -> performBlock(activity, container));

            View btnPreview = mNodeSelectorPanel.findViewById(R.id.preview);
            TooltipCompat.setTooltipText(btnPreview, GmResources.getText(R.string.accessibility_preview));
            btnPreview.setOnClickListener(v -> togglePreview(activity));

            View exchange = mNodeSelectorPanel.findViewById(R.id.exchange);
            View topcentent = mNodeSelectorPanel.findViewById(R.id.topcentent);
            exchange.setOnClickListener(v -> {
                Display display = activity.getWindowManager().getDefaultDisplay();
                int width = display.getWidth();
                int Targetwidth = width - (width / 6);
                if (topcentent.getPaddingRight() == Targetwidth) {
                    topcentent.setPadding(4, 4, 12, 4);
                } else {
                    topcentent.setPadding(4, 4, Targetwidth, 4);
                }
            });
            View btnUp = mNodeSelectorPanel.findViewById(R.id.Up);
            btnUp.setOnClickListener(v -> seekbaradd());
            View btnDown = mNodeSelectorPanel.findViewById(R.id.Down);
            btnDown.setOnClickListener(v -> seekbarreduce());
            container.addView(mNodeSelectorPanel);
            mNodeSelectorPanel.setAlpha(0);
            mNodeSelectorPanel.post(() -> {
                mNodeSelectorPanel.setTranslationX(mNodeSelectorPanel.getWidth() / 2.0f);
                mNodeSelectorPanel.animate()
                        .alpha(1)
                        .translationX(0)
                        .setDuration(300)
                        .setInterpolator(new DecelerateInterpolator(1.0f))
                        .start();
            });
            mKeySelecting = true;
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent", KeyEvent.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!GodModeInjector.switchProp.get() || EventHandlerHook.mDragging) return;
                    KeyEvent event = (KeyEvent) param.args[0];
                    int action = event.getAction();
                    int keyCode = event.getKeyCode();
                    if (action == KeyEvent.ACTION_UP &&
                            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                        if (!mKeySelecting && activity != null) {
                            showNodeSelectPanel(activity);
                        } else if (mKeySelecting) {
                            dismissNodeSelectPanel();
                        }
                        param.setResult(true);
                    } else if (mKeySelecting && action == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                            seekbaradd();
                        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                            seekbarreduce();
                        }
                        param.setResult(true);
                    }
                }
            });
        } catch (Exception e) {
            Logger.e(TAG, "showNodeSelectPanel fail", e);
            mKeySelecting = false;
        }
    }

    private void performBlock(final Activity activity, final ViewGroup container) {
        try {
            if (mViewNodes.isEmpty()) return;
            if (mIsPreviewing) {
                restorePreview();
            }
            final View view = mViewNodes.get(Math.max(mCurrentViewIndex, 0)).get();
            Logger.d(TAG, "block view = " + view);
            if (view == null) return;
            mMaskView.updateOverlayBounds(new Rect());
            final Bitmap snapshot = ViewHelper.snapshotView(ViewHelper.findTopParentViewByChildView(view));
            final ViewRule viewRule = ViewHelper.makeRule(view);
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
                        GodModeManager.getDefault().writeRule(activity.getPackageName(), viewRule, snapshot);
                        recycleNullableBitmap(snapshot);
                        particleView.detachFromContainer();
                    } catch (Exception e) {
                        Logger.e(TAG, "write rule fail", e);
                    }
                    restorePanelAlpha();
                    updateViewNodesAfterRemove();
                }
            });
            particleView.boom(view);
        } catch (Exception e) {
            Logger.e(TAG, "block fail", e);
            restorePanelAlpha();
            Toast.makeText(activity, GmResources.getString(R.string.block_fail, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateViewNodesAfterRemove() {
        if (mCurrentViewIndex >= mViewNodes.size()) {
            mCurrentViewIndex = mViewNodes.size() - 1;
        }
        if (mCurrentViewIndex >= 0 && mCurrentViewIndex < mViewNodes.size()) {
            mViewNodes.remove(mCurrentViewIndex);
        }
        seekbar.setMax(Math.max(mViewNodes.size() - 1, 0));
        mCurrentViewIndex = Math.min(mCurrentViewIndex, Math.max(mViewNodes.size() - 1, 0));
        if (mCurrentViewIndex >= 0 && seekbar != null) {
            seekbar.setProgress(mCurrentViewIndex);
        }
    }

    private void restorePanelAlpha() {
        if (mNodeSelectorPanel != null) {
            mNodeSelectorPanel.animate()
                    .alpha(1.0f)
                    .setInterpolator(new DecelerateInterpolator(1.0f))
                    .setDuration(300)
                    .start();
        }
    }

    private void togglePreview(final Activity activity) {
        if (mIsPreviewing) {
            restorePreview();
        } else {
            startPreview(activity);
        }
    }

    private void startPreview(final Activity activity) {
        if (mViewNodes.isEmpty()) return;
        View view = mViewNodes.get(Math.max(mCurrentViewIndex, 0)).get();
        if (view == null) return;
        try {
            mPreviewRule = ViewHelper.makeRule(view);
            mPreviewRule.visibility = View.GONE;
            ViewController.applyRule(view, mPreviewRule);
            mPreviewView = view;
            mIsPreviewing = true;
            View btnPreview = mNodeSelectorPanel.findViewById(R.id.preview);
            if (btnPreview != null) {
                TooltipCompat.setTooltipText(btnPreview, GmResources.getText(R.string.accessibility_preview_exit));
            }
            mMaskView.updateOverlayBounds(new Rect());
        } catch (Exception e) {
            Logger.e(TAG, "preview fail", e);
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
        View btnPreview = mNodeSelectorPanel != null ? mNodeSelectorPanel.findViewById(R.id.preview) : null;
        if (btnPreview != null) {
            TooltipCompat.setTooltipText(btnPreview, GmResources.getText(R.string.accessibility_preview));
        }
    }

    private void seekbaradd() {
        if (seekbar == null || seekbar.getProgress() >= seekbar.getMax()) {
            return;
        }
        seekbar.setProgress(seekbar.getProgress() + 1);
    }

    private void seekbarreduce() {
        if (seekbar == null || seekbar.getProgress() <= 0) {
            return;
        }
        seekbar.setProgress(seekbar.getProgress() - 1);
    }

    private void dismissNodeSelectPanel() {
        Logger.i(TAG, "[GodMode] dismissNodeSelectPanel");
        restorePreview();
        if (mMaskView != null) mMaskView.detachFromContainer();
        mMaskView = null;
        if (mNodeSelectorPanel != null) {
            final View nodeSelectorPanel = mNodeSelectorPanel;
            nodeSelectorPanel.post(() -> nodeSelectorPanel.animate()
                    .alpha(0)
                    .translationX(nodeSelectorPanel.getWidth() / 2.0f)
                    .setDuration(250)
                    .setInterpolator(new AccelerateInterpolator(1.0f))
                    .withEndAction(() -> {
                        ViewGroup parent = (ViewGroup) nodeSelectorPanel.getParent();
                        if (parent != null) parent.removeView(nodeSelectorPanel);
                    })
                    .start());
        }
        mNodeSelectorPanel = null;
        mViewNodes.clear();
        mCurrentViewIndex = 0;
        mKeySelecting = false;
    }

    @Override
    public void onPropertyChange(Boolean enable) {
        if (!enable && mMaskView != null) {
            dismissNodeSelectPanel();
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (progress < mViewNodes.size()) {
            mCurrentViewIndex = progress;
            View view = mViewNodes.get(mCurrentViewIndex).get();
            Logger.d(TAG, String.format(Locale.getDefault(), "progress=%d selected view=%s", progress, view));
            if (view != null && mMaskView != null) {
                mMaskView.updateOverlayBounds(ViewHelper.getLocationInWindow(view));
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        if (mNodeSelectorPanel != null) mNodeSelectorPanel.setAlpha(0.2f);
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (mNodeSelectorPanel != null) mNodeSelectorPanel.setAlpha(1f);
    }
}
