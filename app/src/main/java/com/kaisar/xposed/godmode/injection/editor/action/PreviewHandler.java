package com.kaisar.xposed.godmode.injection.editor.action;

import android.graphics.Rect;
import android.view.View;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.editor.RuleRecordFactory;
import com.kaisar.xposed.godmode.injection.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 妫板嫯顫嶉幙宥勭稊婢跺嫮鎮婇崳?閳?閸︺劎鈥樼拋銈囆╅梽銈呭娑撳瓨妞傞梾鎰鐟欏棗娴橀妴?
 * <p>
 * 缁狅紕鎮?{@code KeyInterceptor} 娑擃厾娈戞０鍕潔閻樿埖鈧緤绱檓PreviewView閵嗕沟PreviewRule閵嗕沟IsPreviewing閿涘绱?
 * 鐏忎浇顥?{@link #startPreview} / {@link #restorePreview} 闁槒绶妴?
 * <p>
 * 鐠嬪啰鏁ら弬纭呯鐠愶絾瀵滈柦顔惧Ц閹焦娲块弬甯礄{@code updatePreviewButton}閿涘寮?
 * {@link MaskView} 娑?{@code NodeSelectorPanel} 閻ㄥ嫪姘︽禍鎺嬧偓?
 */
public final class PreviewHandler {

    private View mPreviewView;
    private RuleRecord mPreviewRule;
    private boolean mIsPreviewing;

    /** 瑜版挸澧犻弰顖氭儊婢跺嫪绨０鍕潔閻樿埖鈧降鈧?*/
    public boolean isPreviewing() {
        return mIsPreviewing;
    }

    /**
     * 瀵偓婵顣╃憴鍫窗娑撴椽鈧鑵戠憴鍡楁禈閸掓稑缂撶粔濠氭珟鐟欏嫬鍨獮璺虹安閻㈩煉绱檝isibility = GONE閿涘鈧?
     *
     * @param view          鐞氼偊鈧鑵戦惃鍕窗閺嶅洩顫嬮崶?
     * @param maskView      MaskView閿涘牏鏁ゆ禍搴㈢闂勩倝鐝禍顔跨珶閻ｅ矉绱?
     * @param onStateChanged 閻樿埖鈧礁褰夐弴鎾偓姘辩叀閿涘牐鐨熼悽銊︽煙閻劋绨弴瀛樻煀閹稿鎸?UI閿?
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
     * 閹垹顦叉０鍕潔閿涙碍鎸欓柨鈧粔濠氭珟鐟欏嫬鍨敍鍧磇sibility = VISIBLE閿涘绱濋弴瀛樻煀 MaskView 妤傛ü瀵掗妴?
     *
     * @param maskView       MaskView閿涘牏鏁ゆ禍搴划婢跺秴鎮楅弴瀛樻煀妤傛ü瀵掓潏鍦櫕閿?
     * @param selectedView   瑜版挸澧犻柅澶夎厬閻ㄥ嫯顫嬮崶鎾呯礄閻劋绨幁銏狀槻閸氬孩娲块弬浼寸彯娴滎喛绔熼悾宀嬬幢閸欘垵鍏樻稉?null閿?
     * @param onStateChanged 閻樿埖鈧礁褰夐弴鎾偓姘辩叀閿涘牐鐨熼悽銊︽煙閻劋绨弴瀛樻煀閹稿鎸?UI閿?
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
