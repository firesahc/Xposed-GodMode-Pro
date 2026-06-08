package com.kaisar.xposed.godmode.injection.editor.action;

import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.editor.RuleRecordFactory;
import com.kaisar.xposed.godmode.injection.editor.overlay.ParticleView;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 閻忕偛绻楅弬鈧柨娑樼墢浜涢梻鍕╁€х槐姘跺箼瀹ュ嫮绋婂璺哄閹﹪宕?闁?缂侇喗甯掗悺娆撳礉閵娧勬毎闁圭虎鍘介弬?+ IPC 閻熸瑥瀚崹顖炲礃濞嗗繐寮抽柕?
 * <p>
 * 濞?{@code KeyInterceptor.performBlock()} 闁圭粯鍔曡ぐ鍥晬瀹€鍐ф崓閻犳劧绲藉畷鐔哥▔閳ь剟鏁?
 * <ul>
 *   <li>闁告帗绋戠紓?{@link ParticleView} 妤犵偠鍩栭幐閬嶅绩閸撗冪€婚柣鎰憸閻垹鈧稒鍔曟慨鈺呮偨?/li>
 *   <li>闁告柣鍔庨弫鎯ь嚕閳ь剚鎱ㄧ€ｎ偅顦ч幖瀛樻⒒閺併倗绮旀繝姘彑閻熸瑥瀚崹顖炴晬閸х捶link ViewController#applyRule}闁?/li>
 *   <li>闁告柣鍔庨弫鍓х磼閹惧瓨灏嗛柡鍐ㄥ缁垶宕氶幆閭︽綈闁告帗鐟╂导鍕磾閳瑰簱鍋撴笟鈧埀顒佷亢缁?IPC 闁告劖鐟ラ崣鍡欐喆閸曨偄鐏熼柡鍌氭矗濞?/li>
 * </ul>
 * <p>
 * 閻犲鍟伴弫銈夊棘绾懐顦伴悹鎰剁秬椤宕堕幑鎰闁告瑦鐗撻埀顑跨劍閻楀孩顨ョ仦鐑╁亾娴ｇ懓鐒婚柛銉﹀劤瀵兘妫冮姀鈩冪凡闁汇垻鍠庨幊锟犲川閵婏附鍩傞柛銉у仩閻ㄧ喖濡?
 */
public final class BlockHandler {

    private BlockHandler() {}

    /**
     * 閻忕偛绻楅弬鈧柟鍨С缂嶆梻鈧懓鏈崹?濠㈡儼绮剧憴锕傚炊閻愬墎娈堕柕?
     */
    public interface OnBlockListener {
        /** 缂侇喗甯掗悺娆撳礉閵娧勬毎缂備焦鎸诲顐﹀Υ娴ｇ瓔娼愰柛鎺撶懃閸熸捇宕楅妷銉ュ殥闁圭粯鍔掑锕傚触鎼淬倗娈堕柣顫妸閳?*/
        void onAnimationEnd(int blockedViewIndex);
        /** 闁瑰灝绉崇紞鏃€娼婚崶鈹炬煠濞戞搩鍘艰ぐ鍌炴偨閻旈纾介悽顖涙偠閳?*/
        void onError(String message);
    }

    /**
     * 闁圭瑳鍡╂斀閻忕偛绻楅弬鈧柟鍨С缂嶆棃鏁嶅杈╃厛閻庢稒鍔曟慨鈺呮偨?+ IPC 闁告劖鐟ラ崣鍡涘Υ?
     *
     * @param activity         鐟滅増鎸告晶?Activity
     * @param view             閻炴凹鍋勯惈鍡涙嫊閻ｅ本鐣遍柣鈺婂枟閻栵絿鎲撮崱妤佺
     * @param container        DecorView 閻庡湱鎳撳▍鎺楁晬閸︻櫑rticleView 闂傚嫬瀚鍐儎椤旂晫鍨奸柨?
     * @param snapshot         閻忕偛绻楅弬鈧柛鎾崇Т閸忛亶宕欓埀顒勫箣椤忓嫭绂堥柨娑樼墕閸戯繝姊鹃幇顖涱棏 GM 閻熸洖妫涘ú濠勪沪閸屾碍鍊甸柟鎼簻瑜板洭鏁?
     * @param blockedViewIndex 閻炴凹鍋勯惈鍡涙嫊閸婄噥娼掗柛銉﹀劤濠€顏堟嚍閸屾粌浠柛鎺擃殙閵嗗啯绋夐鐘崇暠缂佷究鍨圭槐?
     * @param listener         闁搞儳鍋犻惃?
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
