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
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.Property;
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
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.injection.editor.toolbar.ToolbarVisibilityController;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;

/**
 * 缂傚倸鍊归悧鐐垫椤愶箑闂柕濞у懏銇濋梺鍦劋鐢帒鈻?闂?闂佹崘顕х粔鎾箖?KeyInterceptor 婵?TouchInterceptor 闂佹眹鍔岀€氼厽鏅跺澶婂珘濠㈣埖鍔栨慨?Hook 婵炴垶鎸婚懝鐐叏閻斿吋鐒婚柡鍕箳鐢棝鏌? * <p>
 * 缂備胶濯寸槐鏇㈠箖婵犲洦鍤嶉柛灞剧矊娴狀垶姊洪銏╂Ч閻庢哎鍔戝Λ鍐閳╁啰鍑￠梺闈涙缁€渚€鎯堝鈧獮鈧憸蹇曟椤忓懏缍囬柟瀛樼箖閻濄倝鏌曢崱鏇熺グ鐞氭繈鏌熼挊澶嬪暈濠⒀勭矒瀹曟繈宕归鑲┾偓濠氭煕濞嗘劕鐏熼柍褜鍏涚欢姘舵偂閸洘鐓傞煫鍥ㄧ⊕閺嗘盯鎮楁担鍐棈闁糕晛鎳樻俊瀛樻媴閸濄儲缍勯梺?婵☆偅婢樼€氼垶锝炲澶婄鐎广儱瀚粙濠囨煥? * 婵炲濮伴崕鍗烆嚕妞嬪海纾介柡宥庡墰鐢棙淇婇妞诲亾瀹曞洨顢呴梺姹囧妼鐎氼噣寮幘璇插窛闁芥ê顦伴崳顖炴煛閸垹鏋傞柍褜鍓欓崐濠氬极?{@link com.kaisar.xposed.godmode.injection.entry.ActivityKeyHook}
 * 闂?{@link com.kaisar.xposed.godmode.injection.entry.TouchHook} 闁荤姴顑呴崯浼村极閵堝违? */
public final class EditorOrchestrator implements Property.OnPropertyChangeListener<Boolean> {

    // =========================================================================
    // 闁汇埄鍨遍幃鍌炲闯?    // =========================================================================

    private static final int OVERLAY_COLOR = Color.argb(150, 255, 0, 0);
    @SuppressWarnings("unused")
    private static final int OVERLAY_COLOR_REPEATABLE = Color.argb(150, 255, 165, 0);
    private static final int LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout();

    // =========================================================================
    // 婵炲瓨鍤庨崐鎾惰姳閺夎鐔煎灳瀹曞洨顢呴梺鎸庣☉閻楀棛鏁?KeyInterceptor闂?    // =========================================================================

    private int mInteractionMode = EditorInteractionMode.INITIAL;
    private boolean mInfoFlowMode = false;

    // =========================================================================
    // 婵☆偅婢樼€氼垶锝炲澶嬪亹闁煎摜顣介崑鎾寸瑹婵犲嫮顦╅梺?KeyInterceptor闂?    // =========================================================================

    final PreviewHandler mPreviewHandler = new PreviewHandler();

    // =========================================================================
    // 闁诲孩绋掗崝鏍暜閸洖绀嗛悹铏瑰劋閻濄倝鏌ㄥ☉妯煎閻?KeyInterceptor闂?    // =========================================================================

    private final NodeSelectorPanel mNodePanel = new NodeSelectorPanel();
    final PropertyEditorPanel mPropertyEditor = new PropertyEditorPanel();
    private final SeekBarHandler mSeekBarHandler = new SeekBarHandler(mNodePanel, mPropertyEditor);
    private WeakReference<Activity> mCurrentActivityRef = new WeakReference<>(null);

    // =========================================================================
    // 闂佺厧鎼崐濠氬磻閿濆鐒诲璺侯儏椤忋儵鏌涢敐鍐ㄥ婵為棿鍗冲鍫曞垂椤旂晫顦ラ柣鐘差儏閸犳稓妲愬▎鎾冲偍?KeyInterceptor.NodeSelectorPanel.Callbacks闂?    // =========================================================================

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
    // 闁荤喐鐟遍梽鍕箠濠婂牊鍋愰柤鍝ヮ暯閸嬫挻绗熸繝鍕槱闂?TouchInterceptor闂?    // =========================================================================

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
    // 闁诲繒鍋熼崑鐐哄焵椤戭剙鍟扮粚鍧楁煟?    // =========================================================================

    private final Property<Boolean> mSwitchProp;

    // =========================================================================
    // 闂佸憡鐟ョ粔鎾儍閻樼數纾介柟鎯х－閹界娀鏌ㄥ☉妯煎閻?TouchInterceptor闂?    // =========================================================================

    private static Field sWindowAttributesField;

    // =========================================================================
    // 闂佸搫顑呯€氫即鍩€椤掑倸孝婵?    // =========================================================================

    public EditorOrchestrator(Property<Boolean> switchProp) {
        this.mSwitchProp = switchProp;
    }

    // =========================================================================
    // 闂佺娴氶崜娆撳矗閿涘嫭濯奸柛褎顨嗛敍鏍煕?    // =========================================================================

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
    // 闂傚倸锕ユ繛濠囧闯濞差亝鐓ユい鏃傗拡濡查亶鏌ｉ悙鍙夛紨缂佽鲸鐟︾粭?ActivityKeyHook 闁荤姴顑呴崯浼村极閵堝鏅?    // =========================================================================

    /**
     * 婵犮垼娉涚€氼噣骞冩繝鍥棅闁规儼妫勫▍銈夋⒑濞嗘儳鏋熼悗鍨矋缁嬪鈧絽澧庣粈鍓噊ggle/闁诲簼绲绘竟鍫ュ春閸涘瓨鏅鑸电〒缁€澶愭煟?ActivityKeyHook 闂侀潻璐熼崝宥夊极瑜版帒绀嗗ù鐓庮嚟閸欓箖姊洪幓鎺旂闁轰緡鍘界粋宥団偓锝傛櫆椤愪粙鏌￠崘鈺佸姸闁汇劎鍠栭幃浠嬪Ω閿濆倸浜?     */
    public void onVolumeKeyToggle(Activity activity) {
        if (!mNodePanel.isKeySelecting() && activity != null) {
            showNodeSelectPanel(activity);
        } else if (mNodePanel.isKeySelecting()) {
            dismissNodeSelectPanel();
        }
    }

    /**
     * 闂傚倸锕ユ繛濠囧闯濞差亝鐓ユい鏃傗拡閸ゃ倝鏌ら崜韫倣缂佽鲸绻堥幃?ActivityKeyHook 闂侀潻璐熼崝鎴﹀焵椤掆偓椤︻噣鎳欓幋锔藉亹闁煎摜顣介崑鎾存媴妞嬪海鎲柣鐘差儏閸熶即寮妶澶娢?     */
    public void onVolumeKeyNavigate(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            navigatePrevious();
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            navigateNext();
        }
    }

    // =========================================================================
    // Activity 闂佹眹鍨婚崰搴ㄥ箠閿熺姴宸濋柕濠忛檮閸╁倿鏌ㄥ☉妯煎ⅱ闁?GodModeInjector 闁荤姴顑呴崯浼村极閵堝鏅?    // =========================================================================

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
    // 闁荤喐鐟ュΛ妤€霉濮椻偓閺屽懏寰勭€ｎ亶浠撮梺鎸庣☉閻楀棛鏁?KeyInterceptor闂?    // =========================================================================

    /** 闂備緡鍋呮穱铏规崲閸愵喗鍊烽柣鐔告緲濮ｅ﹤霉濠婂喚鍎庢繛鍡愬灲閺屽懏寰勬径搴″箑闁荤喐鐟ュΛ妤€霉濮椻偓閺佸秹宕煎鍛厾闁荤喐鐟遍梽鍕箠濠婂牆绠ラ悗锝庝簻閳笺垽鏌涢幒鎴烆棞鐟滄澘鍊婚幏顐﹀礃椤忓懏娈㈤梺?*/
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

    /** 闂佸吋鍎抽崲鑼躲亹閸ヮ亗浜归柟鎯у暱椤ゅ懘姊洪銏╂Х闁煎灚鍨块幆鍐礋椤曞懏缍婇梺鎼炲劜閹锋繄妲愬▎鎾村仺闁告瑦蓱閸欏繘鏌￠埀顒傛喆閸曗偓娴ｅ壊鍤曢煫鍥ㄦ煥閻濇盯鏌熷畡鎵冲亾閻旂儤顔嶉梺姹囧焺閻撳妲?*/
    public View getSelectedView() {
        return mNodePanel.getSelectedView();
    }

    /** 闂佸憡甯囬崐鏍蓟?candidate 闂佸搫瀚烽崹浼村箚娓氣偓瀵?tapped 闂佹眹鍔岀€氼剟骞冮幘鍓佹／鐟滃酣宕归妸锔锯枖濠电姴瀚伴悰鎾绘偡濞嗗繑顥滄繛?*/
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
    // 闁荤喐鐟ュΛ妤€霉濡皷鍋撴担鍐棈闁糕晛鎳橀弫宥夊醇濠靛棙鏋?KeyInterceptor闂?    // =========================================================================

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
    // 闂佺厧鎼崐濠氬磻閿濆鐒诲璺侯儏椤忋儵鏌涢敐鍐ㄥ婵為棿鍗冲鍫曞礌閿涘嫮顦╅梺?KeyInterceptor闂?    // =========================================================================

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
    // 缂備礁顦…宄扳枍鎼淬劍鏅柛顐ｇ箘濞煎矂鏌﹂崟顒佺伄缂佽鲸宀搁獮娆忣吋閸曨厾鈻曢梺鎸庣☉閻楀棛鏁?KeyInterceptor闂?    // =========================================================================

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

    /** 婵炴垶鎸搁悺銊ヮ渻閸岀偞鈷曢柟閭﹀灡椤ユ垿鏌熺€涙澧俊顖氼槺缁牓鎮滃Ο渚殹闂?GodMode 闁荤喐娲栧Λ娑樏烘繝鍕勃闁稿本绻嶅鎺楁煕?*/
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
    // 婵☆偅婢樼€氼垶锝炲澶嬫櫖闁割偅绻傞弬?KeyInterceptor闂?    // =========================================================================

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
    // 闁荤喐鐟遍梽鍕箠濠婂嫮顩查悗锝傛櫆椤愪粙鏌涢幒鎴烆棞鐟滄澘鍊块弫宥夊醇濠靛棙鏋?TouchInterceptor 闂佹眹鍔岀€氼參鎮х€圭姷鐤€闁告劘娉曠粈?    // =========================================================================

    /**
     * 缂傚倸鍊归悧鐐垫椤愨懡鐔煎灳瀹曞洨顢呮繛鎴炴尭椤戝洤鈻撻幋鐘冲枂闁挎棁濮ら崵瀣瑰鍐惧剮婵炲棎鍨哄鍕礋椤撶喎鈧偤鏌涜箛瀣姎鐟滅増鐩弫宥呯暆閳ь剟寮?TouchHook 闁荤姴顑呴崯浼村极閵堝违?     * 闁哄鏅滈弻銊ッ?true 闁荤偞绋忛崝搴ㄥΦ濮橆厾顩查悗锝傛櫆椤愮晫鈧鐡曠亸顏堬綖閿曗偓閳藉宕奸敐鍛偓顓㈡煏?     */
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
    // 缂備礁顦…宄扳枍鎼粹垾鐔煎灳瀹曞洨顢呴柣鐔哥懕闂勫嫰骞婂鍕窞闁告洦鍘介崐鐐烘煥濞戞澧曢悽?TouchInterceptor闂?    // =========================================================================

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
    // 婵烇絽娴傞崰妤呭极閻撳宫鐔煎灳瀹曞洨顢呴柣鐔哥懕闂勫嫰骞婂鍕窞闁告洦鍘介崐鐐烘煥濞戞澧曢悽?TouchInterceptor闂?    // =========================================================================

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
    // 闂佺绻愯ぐ澶愭閳哄啯鍠嗛柨鏃囧Г閸ゅ鈧鍠掗崑鎾斥攽?缂傚倷鐒﹂幐璇差焽椤愶箑妞界€光偓閸曨剚鐦ｉ梺鍦焾椤︿即藟閸涱劶鍦偓锝呭缁€鍕煕?TouchInterceptor闂?    // =========================================================================

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

    /** 闂傚倵鍋撻柟绋块閻﹀鎮峰▎娆戠暠鐟滄澘鍊块弫宥咁潩椤撶姴顥戦梺纭咁嚃閸犳鍟悗娈垮枛缁绘劙骞嗘惔銊ョ闁靛鐏濋埡鍛挃闁靛牆妫楅悘妤€菐閸ヨ泛鏋熼柡浣搞偢楠炲繘寮介妸銉肌 */
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
    // 闁诲繒鍋熼崑鐐哄焵椤戭剙鍟紞渚€鏌￠崶顏呭涧缂佽鲸鐟╁畷?KeyInterceptor + TouchInterceptor 闂佸憡鑹鹃悧鍡涙嚐閻斿吋鏅?    // =========================================================================

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
