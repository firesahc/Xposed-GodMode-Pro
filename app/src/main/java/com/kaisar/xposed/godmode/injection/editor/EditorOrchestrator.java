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
import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.editor.action.BlockHandler;
import com.kaisar.xposed.godmode.injection.editor.action.PreviewHandler;
import com.kaisar.xposed.godmode.injection.editor.gesture.GestureDispatcher;
import com.kaisar.xposed.godmode.injection.editor.gesture.ModifyGestureHandler;
import com.kaisar.xposed.godmode.injection.editor.gesture.RemoveGestureHandler;
import com.kaisar.xposed.godmode.injection.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.injection.editor.panel.NodeSelectorPanel;
import com.kaisar.xposed.godmode.injection.editor.panel.PropertyEditorPanel;
import com.kaisar.xposed.godmode.injection.editor.panel.SeekBarHandler;
import com.kaisar.xposed.godmode.injection.editor.toolbar.ToolbarVisibilityController;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.util.GmResources;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;

/**
 * 缂傚倸鍊搁崐褰掓偋閻愬灚顐芥い鎰剁畱闂傤垶鏌曟繛褍鎳忛妵婵嬫⒑閸︻厼鍔嬮悽顖涘笒閳?闂?闂備焦宕橀褏绮旈幘顔肩畺?KeyInterceptor 濠?TouchInterceptor 闂備焦鐪归崝宀€鈧凹鍘介弲璺侯吋婢跺﹤鐝樻繝銏ｅ煐閸旀牗鎱?Hook 濠电偞鍨堕幐濠氭嚌閻愵剚鍙忛柣鏂垮悑閻掑鏌￠崟顐ょ閻㈩垰妫濋弻? * <p>
 * 缂傚倷鑳舵刊瀵告閺囥垹绠栧┑鐘叉处閸ゅ秹鏌涚仦鍓х煀濞寸媭鍨跺娲敃閵忊晜效闁诲孩鍝庨崝鎴澪涢崘顔碱潊闁斥晛鍟伴崙锟犳⒑闂堟稒顥欑紒鈧笟鈧幆鍫濐潨閳ь剟鐛埀顒傛喐韫囨洘顫曟い蹇撴噺缂嶅洭鏌熺€涙绠栭柣婵勫€濋弻鏇㈠幢閺囩喓銈伴悶姘箞閺岀喖鎸婃径瀣殘婵犫拃鍕煉鐎规洘绻堝畷褰掝敊閼测斁鍋撴繝姘厱婵炲棙鍔曢悘鐔兼煃瑜滈崗娑氭濮樿埖鍋傞柛顐ｆ礃閻撳倿鐓崶銊р姇闁哄棙鐩幃妤佹媴閸愵煈妫堥梺绯曟櫅閹虫ɑ淇婄€涙ɑ濯撮柛婵勫劜缂嶅嫰姊?濠碘槅鍋呭妯尖偓姘煎灦閿濈偛顓兼径濠勵啌閻庡箍鍎辩€氼喚绮欐繝鍥ㄧ叆? * 濠电偛顕慨浼村磿閸楃儐鍤曞瀣捣绾句粙鏌″搴″閻㈩垰妫欐穱濠囶敍濡炶浜剧€规洖娲ㄩ、鍛存⒑濮瑰洤濡奸悗姘煎櫍瀵偊骞樼拠鎻掔獩闂佽姤锚椤︿即宕抽鐐寸厸闁割偁鍨归弸鍌炴煃瑜滈崜娆撳磹婵犳艾鏋?{@link com.kaisar.xposed.godmode.injection.entry.ActivityKeyHook}
 * 闂?{@link com.kaisar.xposed.godmode.injection.entry.TouchHook} 闂佽崵濮撮鍛村疮娴兼潙鏋侀柕鍫濐槸杩? */
public final class EditorOrchestrator implements Property.OnPropertyChangeListener<Boolean> {

    // =========================================================================
    // 闂佹眹鍩勯崹閬嶅箖閸岀偛闂?    // =========================================================================

    private static final int OVERLAY_COLOR = Color.argb(150, 255, 0, 0);
    @SuppressWarnings("unused")
    private static final int OVERLAY_COLOR_REPEATABLE = Color.argb(150, 255, 165, 0);
    private static final int LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout();

    // =========================================================================
    // 濠电偛鐡ㄩ崵搴ㄥ磹閹炬儼濮抽柡澶庮嚦閻旂厧鐏崇€规洖娲ㄩ、鍛存⒑閹稿海鈽夐柣妤€妫涢弫?KeyInterceptor闂?    // =========================================================================

    private int mInteractionMode = EditorInteractionMode.INITIAL;
    private boolean mInfoFlowMode = false;

    // =========================================================================
    // 濠碘槅鍋呭妯尖偓姘煎灦閿濈偛顓兼径瀣汗闂佺厧鎽滈。浠嬪磻閹惧鐟瑰┑鐘插椤︹晠姊?KeyInterceptor闂?    // =========================================================================

    final PreviewHandler mPreviewHandler = new PreviewHandler();

    // =========================================================================
    // 闂佽瀛╃粙鎺楀礉閺嶎偅鏆滈柛顐ｆ礀缁€鍡涙偣閾忕懓鍔嬮柣婵勫€濋弻銊モ槈濡厧顣洪柣?KeyInterceptor闂?    // =========================================================================

    private final NodeSelectorPanel mNodePanel = new NodeSelectorPanel();
    final PropertyEditorPanel mPropertyEditor = new PropertyEditorPanel();
    private final SeekBarHandler mSeekBarHandler = new SeekBarHandler(mNodePanel, mPropertyEditor);
    private WeakReference<Activity> mCurrentActivityRef = new WeakReference<>(null);

    // =========================================================================
    // 闂備胶鍘ч幖顐﹀磹婵犳艾纾婚柨婵嗩槹閻掕顭跨捄渚剰妞ゅ繈鍎甸弻娑㈡晲閸愩劌顫囧┑鐐烘？閸楀啿顕ｉ崼鏇炲瀭妞ゆ梻鏅ˇ銉╂煟閻樺樊鍎忛柛鐘崇〒濡叉劕鈻庨幘鍐插亶?KeyInterceptor.NodeSelectorPanel.Callbacks闂?    // =========================================================================

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
    // 闂佽崵鍠愰悷閬嶆⒔閸曨垰绠犳繝濠傜墛閸嬫劙鏌ら崫銉毌闁稿鎸荤粭鐔哥節閸曨収妲遍梻?TouchInterceptor闂?    // =========================================================================

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
    // 闂佽绻掗崑鐔煎磻閻愬搫鐒垫い鎴墮閸熸壆绮氶崸妤佺厽?    // =========================================================================

    private final Property<Boolean> mSwitchProp;

    // =========================================================================
    // 闂備礁鎲￠悷銉х矓閹绢喗鍎嶉柣妯兼暩绾句粙鏌熼幆褏锛嶉柟鐣屽█閺屻劌鈽夊Ο鐓庮暫闁?TouchInterceptor闂?    // =========================================================================

    private static Field sWindowAttributesField;

    // =========================================================================
    // 闂備礁鎼鍛偓姘嵆閸┾偓妞ゆ帒鍊稿瓭濠?    // =========================================================================

    public EditorOrchestrator(Property<Boolean> switchProp) {
        this.mSwitchProp = switchProp;
    }

    // =========================================================================
    // 闂備胶顭堝ù姘跺礈濞嗘挸鐭楅柨娑樺婵ジ鏌涜椤ㄥ棝鏁嶉弽顓熺厱?    // =========================================================================

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
    // 闂傚倸鍊搁敃銉︾箾婵犲洤闂繛宸簼閻撱儲銇勯弮鍌楁嫛婵℃煡浜堕弻锝夋倷閸欏绱ㄧ紓浣介哺閻燂妇绮?ActivityKeyHook 闂佽崵濮撮鍛村疮娴兼潙鏋侀柕鍫濐槹閺?    // =========================================================================

    /**
     * 濠电姰鍨煎▔娑氣偓姘煎櫍楠炲啯绻濋崶顭戞闂佽鍎煎Λ鍕枍閵堝鈷戞繛鍡樺劤閺嬬喖鎮楅崹顐ょ煁缂佸顦遍埀顒婄到婢у海绮堥崜鍣奼gle/闂佽绨肩徊缁樼珶閸儱鏄ラ柛娑樼摠閺咁剙顭块懜鐢点€掔紒鈧径鎰厽?ActivityKeyHook 闂備線娼荤拹鐔煎礉瀹ュ鏋佺憸鐗堝笒缁€鍡椕归悡搴殶闁告瑩绠栧娲箵閹烘梻顔掗梺杞扮贰閸樼晫绮嬪鍥ｅ亾閿濆倹娅嗘い鎰矙閺岋繝宕橀埡浣稿Ц闂佹眹鍔庨崰鏍箖娴犲惟闁挎繂鍊告禍?     */
    public void onVolumeKeyToggle(Activity activity) {
        if (!mNodePanel.isKeySelecting() && activity != null) {
            showNodeSelectPanel(activity);
        } else if (mNodePanel.isKeySelecting()) {
            dismissNodeSelectPanel();
        }
    }

    /**
     * 闂傚倸鍊搁敃銉︾箾婵犲洤闂繛宸簼閻撱儲銇勯弮鍌楁嫛闁搞們鍊濋弻銈夊礈闊厽鍊ｇ紓浣介哺缁诲牓骞?ActivityKeyHook 闂備線娼荤拹鐔煎礉閹达箑鐒垫い鎺嗗亾妞わ富鍣ｉ幊娆撳箣閿旇棄浜归梺鐓庢憸椤ｄ粙宕戦幘瀛樺濡炲娴烽幉顕€鏌ｉ悩宸剰闁哥喍鍗冲顐﹀Χ婢跺á?     */
    public void onVolumeKeyNavigate(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            navigatePrevious();
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            navigateNext();
        }
    }

    // =========================================================================
    // Activity 闂備焦鐪归崹濠氬窗鎼淬劌绠犻柨鐔哄Т瀹告繈鏌曟繝蹇涙闁糕晛鍊块弻銊モ槈濡厧鈪遍梺?GodModeInjector 闂佽崵濮撮鍛村疮娴兼潙鏋侀柕鍫濐槹閺?    // =========================================================================

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
    // 闂佽崵鍠愰悷銉ノ涘Δ鈧湁婵せ鍋撻柡灞芥噺瀵板嫮鈧綆浜舵禒鎾⒑閹稿海鈽夐柣妤€妫涢弫?KeyInterceptor闂?    // =========================================================================

    /** 闂傚倷绶￠崑鍛┍閾忚宕查柛鎰靛枟閸婄兘鏌ｉ悢鍛婄凡婵絽锕ら湁婵犲﹤鍠氶崕搴㈢箾閸℃劕鐏查柡灞芥噺瀵板嫭寰勬惔鈥崇畱闂佽崵鍠愰悷銉ノ涘Δ鈧湁婵せ鍋撻柡浣哥Ч瀹曠厧顭ㄩ崨顖滃幘闂佽崵鍠愰悷閬嶆⒔閸曨垰绠犳繝濠傜墕缁犮儵鎮楅敐搴濈盎闁崇鍨介弻娑㈠箳閹寸儐妫為悷婊勬緲閸婂骞忛锕€绀冩い蹇撴噺濞堛垽姊?*/
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

    /** 闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉簵娴滃綊鏌熼幆褍鏆辨い銈呮嚇濮婃椽顢曢姀鈺傂ラ梺鐓庣仛閸ㄥ潡骞嗛崘顔肩妞ゆ洖鎳忕紞濠囨⒑閹肩偛鍔滈柟閿嬬箘濡叉劕鈻庨幘鏉戜缓闂佸憡鐟﹁摫闁告瑥绻橀弻锟犲焵椤掑倹鍠嗛柛鏇楀亾濞达絽澹婇崵鏇㈢叓閸ャ劍鐓ラ柣婵囩洴閺岀喎鐣￠幍鍐蹭壕闁绘梻鍎ら宥夋⒑濮瑰洤鐒洪柣鎾愁槺濡?*/
    public View getSelectedView() {
        return mNodePanel.getSelectedView();
    }

    /** 闂備礁鎲＄敮鍥磹閺嶎厼钃?candidate 闂備礁鎼€氱兘宕规导鏉戠畾濞撴埃鍋撶€?tapped 闂備焦鐪归崝宀€鈧凹鍓熼獮鍐箻閸撲焦锛忛悷婊冮叄瀹曞綊濡搁敂閿灃婵犵數濮寸€氫即鎮伴幘缁樺仭婵炲棗绻戦ˉ婊勭箾?*/
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
    // 闂佽崵鍠愰悷銉ノ涘Δ鈧湁婵☆垰鐨烽崑鎾存媴閸愵煈妫堥梺绯曟櫅閹虫﹢寮澶婇唶婵犻潧妫欓弸?KeyInterceptor闂?    // =========================================================================

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
    // 闂備胶鍘ч幖顐﹀磹婵犳艾纾婚柨婵嗩槹閻掕顭跨捄渚剰妞ゅ繈鍎甸弻娑㈡晲閸愩劌顫囧┑鐐烘？閸楀啿顕ｉ崼鏇炵闁挎稑瀚ˇ鈺呮⒑?KeyInterceptor闂?    // =========================================================================

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
    // 缂傚倷绀侀ˇ顖炩€﹀畡鎵虫瀺閹兼番鍔嶉弲顒勬煕椤愶絿绠樻繛鐓庣焸閺岋箓宕熼浣轰紕缂備浇椴稿畝鎼佺嵁濞嗗浚鍚嬮柛鏇ㄥ幘閳绘洟姊洪幐搴ｂ槈闁绘妫涢弫?KeyInterceptor闂?    // =========================================================================

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

    /** 濠电偞鍨堕幐鎼佹偤閵娿儺娓婚柛宀€鍋為埛鏇㈡煙闁箑鐏℃い銉﹀灴閺岀喓鈧稒顭囨晶顒佷繆椤栨凹妲虹紒顔肩墦閹粌螣娓氼垱娈归梻?GodMode 闂佽崵鍠愬ú鏍涘☉妯忕儤绻濋崟顏呭媰闂佺鏈换宥咁焽閹烘鐓?*/
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
    // 濠碘槅鍋呭妯尖偓姘煎灦閿濈偛顓兼径瀣珫闂佸壊鍋呯换鍌炲棘?KeyInterceptor闂?    // =========================================================================

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
    // 闂佽崵鍠愰悷閬嶆⒔閸曨垰绠犳繝濠傚椤╂煡鎮楅敐鍌涙珕妞ゆ劒绮欓弻娑㈠箳閹寸儐妫為悷婊勬緲閸婂潡寮澶婇唶婵犻潧妫欓弸?TouchInterceptor 闂備焦鐪归崝宀€鈧凹鍙冮幃褏鈧湱濮烽悿鈧梺鍛婂姌濞夋洜绮?    // =========================================================================

    /**
     * 缂傚倸鍊搁崐褰掓偋閻愬灚顐芥い鎰ㄦ嚒閻旂厧鐏崇€规洖娲ㄩ、鍛箾閹寸偞灏い鎴濇搐閳绘捇骞嬮悩鍐叉瀭闂佹寧妫佹慨銈夊吹鐎ｎ€㈢懓顭ㄩ崘鎯у壆濠电偛妫庨崹鍝勵嚗閸曨垰绀嬫い鎾跺枎閳ь剛鍋ら弻娑滅疀鐎ｎ亜濮庨悷婊呭閻╊垶寮鍛殕闁逞屽墴瀵?TouchHook 闂佽崵濮撮鍛村疮娴兼潙鏋侀柕鍫濐槸杩?     * 闂佸搫顦弲婊堝蓟閵娿儍?true 闂佽崵鍋炵粙蹇涘礉鎼淬劌桅婵﹩鍘鹃々鏌ユ倵閿濆倹娅嗘い鎰櫕閳ь剝顫夐悺鏇犱焊椤忓牞缍栭柨鏇楀亾闁宠棄顦靛畷濂告晲閸涱垪鍋撻銏＄厪?     */
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
    // 缂傚倷绀侀ˇ顖炩€﹀畡鎵虫瀺閹肩补鍨鹃悢鐓庣伋鐎规洖娲ㄩ、鍛存煟閻斿摜鎳曢梻鍕楠炲﹤顭ㄩ崟顐ょ獮闂佸憡娲﹂崢浠嬪磹閻愮儤鐓ユ繛鎴烆焽婢ф洟鎮?TouchInterceptor闂?    // =========================================================================

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
    // 濠电儑绲藉ù鍌炲窗濡ゅ懎鏋侀柣鎾冲閻旂厧鐏崇€规洖娲ㄩ、鍛存煟閻斿摜鎳曢梻鍕楠炲﹤顭ㄩ崟顐ょ獮闂佸憡娲﹂崢浠嬪磹閻愮儤鐓ユ繛鎴烆焽婢ф洟鎮?TouchInterceptor闂?    // =========================================================================

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
    // 闂備胶顭堢换鎰亹婢舵劖顥婇柍鍝勫暞閸犲棝鏌ㄩ弮鍥撻柛銈咁儑閳ь剚顔栭崰鎺楀磻閹炬枼鏀?缂傚倸鍊烽悞锕傚箰鐠囧樊鐒芥い鎰剁畱濡炵晫鈧厜鍋撻柛鏇ㄥ墯閻︼綁姊洪崷顓х劸妞わ缚鍗宠棢闁告侗鍔堕崷顓涘亾閿濆懎顣崇紒鈧崟顖涚厱?TouchInterceptor闂?    // =========================================================================

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

    /** 闂傚倸鍊甸崑鎾绘煙缁嬪潡顎楅柣锕€顭烽幃宄扳枎濞嗘垹鏆犻悷婊勬緲閸婂潡寮鍜佹僵妞ゆ挾濮撮ˉ鎴︽⒑绾拋鍤冮柛鐘愁殙閸燁垶鎮楀▓鍨灈缂佺粯鍔欓獮鍡樻償閵娿儳顦梺闈涱煬閻忔繈鍩￠崨顔规寖闂侀潧鐗嗗Λ妤呮倶濡も偓鑿愰柛銉ㄦ硾閺嬬喖鏌℃担鎼炲仮妤犵偛绻樺浠嬪Ω閵夘喕鑲?*/
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
    // 闂佽绻掗崑鐔煎磻閻愬搫鐒垫い鎴墮閸燁偆绱炴笟鈧弻锟犲炊椤忓懎娑х紓浣介哺閻熲晛鐣?KeyInterceptor + TouchInterceptor 闂備礁鎲￠懝楣冩偋閸℃稒鍤愰柣鏂垮悑閺?    // =========================================================================

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
