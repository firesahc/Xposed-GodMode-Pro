package com.kaisar.xposed.godmode.injection.editor.action;

import android.graphics.Rect;
import android.view.View;

import com.kaisar.xposed.godmode.injection.editor.RuleRecordFactory;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 棰勮鎿嶄綔澶勭悊鍣?鈥?鍦ㄧ‘璁ょЩ闄ゅ墠涓存椂闅愯棌瑙嗗浘銆?
 * <p>
 * 绠＄悊 {@code KeyInterceptor} 涓殑棰勮鐘舵€侊紙mPreviewView銆乵PreviewRule銆乵IsPreviewing锛夛紝
 * 灏佽 {@link #startPreview} / {@link #restorePreview} 閫昏緫銆?
 * <p>
 * 璋冪敤鏂硅礋璐ｆ寜閽姸鎬佹洿鏂帮紙{@code updatePreviewButton}锛夊強
 * {@link MaskView} 涓?{@code NodeSelectorPanel} 鐨勪氦浜掋€?
 */
public final class PreviewHandler {

    private View mPreviewView;
    private RuleRecord mPreviewRule;
    private boolean mIsPreviewing;

    /** 褰撳墠鏄惁澶勪簬棰勮鐘舵€併€?*/
    public boolean isPreviewing() {
        return mIsPreviewing;
    }

    /**
     * 寮€濮嬮瑙堬細涓洪€変腑瑙嗗浘鍒涘缓绉婚櫎瑙勫垯骞跺簲鐢紙visibility = GONE锛夈€?
     *
     * @param view          琚€変腑鐨勭洰鏍囪鍥?
     * @param maskView      MaskView锛堢敤浜庢竻闄ら珮浜竟鐣岋級
     * @param onStateChanged 鐘舵€佸彉鏇撮€氱煡锛堣皟鐢ㄦ柟鐢ㄤ簬鏇存柊鎸夐挳 UI锛?
     */
    public void startPreview(View view, MaskView maskView, Runnable onStateChanged) {
        if (view == null) return;
        try {
            mPreviewRule = RuleRecordFactory.makeRemoveRule(view);
            mPreviewRule.visibility = View.GONE;
            ViewController.getDefault().applyRule(view, mPreviewRule);
            mPreviewView = view;
            mIsPreviewing = true;
            if (onStateChanged != null) onStateChanged.run();
            if (maskView != null) maskView.updateOverlayBounds(new Rect());
        } catch (Exception e) {
            Logger.e("PreviewHandler", "startPreview fail", e);
        }
    }

    /**
     * 鎭㈠棰勮锛氭挙閿€绉婚櫎瑙勫垯锛坴isibility = VISIBLE锛夛紝鏇存柊 MaskView 楂樹寒銆?
     *
     * @param maskView       MaskView锛堢敤浜庢仮澶嶅悗鏇存柊楂樹寒杈圭晫锛?
     * @param selectedView   褰撳墠閫変腑鐨勮鍥撅紙鐢ㄤ簬鎭㈠鍚庢洿鏂伴珮浜竟鐣岋紱鍙兘涓?null锛?
     * @param onStateChanged 鐘舵€佸彉鏇撮€氱煡锛堣皟鐢ㄦ柟鐢ㄤ簬鏇存柊鎸夐挳 UI锛?
     */
    public void restorePreview(MaskView maskView, View selectedView, Runnable onStateChanged) {
        if (mPreviewView != null && mPreviewRule != null) {
            mPreviewRule.visibility = View.VISIBLE;
            ViewController.getDefault().revokeRule(mPreviewView, mPreviewRule);
            mPreviewView = null;
            mPreviewRule = null;
        }
        mIsPreviewing = false;
        if (onStateChanged != null) onStateChanged.run();
        if (maskView != null && selectedView != null) {
            maskView.updateOverlayBounds(ViewUtils.getLocationInWindow(selectedView));
        }
    }
}
