package com.kaisar.xposed.godmode.injection.editor.action;

import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.kaisar.xposed.godmode.injection.editor.RuleRecordFactory;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.editor.overlay.ParticleView;
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 鐏炲繗鏂€閿涘牏些闂勩倧绱氶幙宥勭稊婢跺嫮鎮婇崳?閳?缁帒鐡欓崝銊ф暰閹绢厽鏂?+ IPC 鐟欏嫬鍨崘娆忓弳閵?
 * <p>
 * 娴?{@code KeyInterceptor.performBlock()} 閹绘劕褰囬敍宀冧捍鐠愶絽宕熸稉鈧敍?
 * <ul>
 *   <li>閸掓稑缂?{@link ParticleView} 楠炶埖鎸遍弨鍓у瀻閻愬摜鐭戠€涙劕濮╅悽?/li>
 *   <li>閸斻劎鏁惧鈧慨瀣鎼存梻鏁ょ粔濠氭珟鐟欏嫬鍨敍鍧紷link ViewController#applyRule}閿?/li>
 *   <li>閸斻劎鏁剧紒鎾存将閺冨墎绮崚鎯邦潐閸掓瑩浼勭純鈹库偓渚€鈧俺绻?IPC 閸愭瑥鍙嗙憴鍕灟閺傚洣娆?/li>
 * </ul>
 * <p>
 * 鐠嬪啰鏁ら弬纭呯鐠愶綀顫嬮崶鎹愬箯閸欐牓鈧焦鐗庢灞烩偓浣瑰焻閸ユ儳寮烽棃銏℃緲閻㈢喎鎳￠崨銊︽埂閸ョ偠鐨熼妴?
 */
public final class BlockHandler {

    private BlockHandler() {}

    /**
     * 鐏炲繗鏂€閹垮秳缍旂€瑰本鍨?婢惰精瑙﹂崶鐐剁殶閵?
     */
    public interface OnBlockListener {
        /** 缁帒鐡欓崝銊ф暰缂佹挻娼妴浣筋潐閸掓瑥鍟撻崗銉ュ嚒閹绘劒姘﹂崥搴ょ殶閻劊鈧?*/
        void onAnimationEnd(int blockedViewIndex);
        /** 閹垮秳缍旀潻鍥┾柤娑擃厼褰傞悽鐔风磽鐢悶鈧?*/
        void onError(String message);
    }

    /**
     * 閹笛嗩攽鐏炲繗鏂€閹垮秳缍旈敍姘辩煈鐎涙劕濮╅悽?+ IPC 閸愭瑥鍙嗛妴?
     *
     * @param activity         瑜版挸澧?Activity
     * @param view             鐞氼偄鐫嗛拕鐣屾畱閻╊喗鐖ｇ憴鍡楁禈
     * @param container        DecorView 鐎圭懓娅掗敍鍦rticleView 闂勫嫮娼冮惄顔界垼閿?
     * @param snapshot         鐏炲繗鏂€閸撳秴鍏遍崙鈧幋顏勬禈閿涘牆鍑￠梾鎰 GM 鐟曞棛娲婄仦鍌氭倵閹搭亜褰囬敍?
     * @param blockedViewIndex 鐞氼偄鐫嗛拕鍊燁潒閸ユ儳婀懞鍌滃仯閸掓銆冩稉顓犳畱缁便垹绱?
     * @param listener         閸ョ偠鐨?
     */
    public static void execute(final Activity activity, final View view,
            final ViewGroup container, final Bitmap snapshot,
            final int blockedViewIndex, final OnBlockListener listener) {
        try {
            final RuleRecord viewRule = RuleRecordFactory.makeRemoveRule(view);
            final ParticleView particleView = new ParticleView(activity);
            particleView.setDuration(1000);
            particleView.attachToContainer(container);
            particleView.setOnAnimationListener(new ParticleView.OnAnimationListener() {
                @Override
                public void onAnimationStart(View animView, Animator animation) {
                    viewRule.visibility = View.GONE;
                    ViewController.getDefault().applyRule(view, viewRule);
                }

                @Override
                public void onAnimationEnd(View animView, Animator animation) {
                    try {
                        BitmapUtils.drawRuleMask(snapshot, viewRule);
                        particleView.detachFromContainer();
                    } catch (Exception e) {
                        Logger.e("BlockHandler", "drawRuleMask / detach fail", e);
                    }
                    if (listener != null) {
                        listener.onAnimationEnd(blockedViewIndex);
                    }
                    TaskExecutor.executeIo(() -> {
                        try {
                            GodModeManager.getDefault().writeRule(
                                    activity.getPackageName(), viewRule, snapshot);
                        } catch (Exception e) {
                            Logger.e("BlockHandler", "writeRule fail", e);
                        }
                        recycleNullableBitmap(snapshot);
                    });
                }
            });
            particleView.boom(view);
        } catch (Exception e) {
            Logger.e("BlockHandler", "execute fail", e);
            if (listener != null) {
                listener.onError(e.getMessage() != null ? e.getMessage() : "block fail");
            }
        }
    }
}
