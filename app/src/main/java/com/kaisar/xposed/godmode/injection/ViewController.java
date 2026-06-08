package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.applier.ModifyApplier;
import com.kaisar.xposed.godmode.engine.applier.RemoveApplier;
import com.kaisar.xposed.godmode.engine.applier.RuleApplier;
import com.kaisar.xposed.godmode.engine.matcher.ViewFinder;
import com.kaisar.xposed.godmode.engine.rule.RuleMapper;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.List;

/**
 * 閻熸瑥妫楀ù姗€骞掕閸╂宕?闁?濞达綀娉曢弫?engine/applier 濞达絾鎸鹃柈瀛樻償閺冨倹鏆?闁逛勘鍊濋弨銏㈡喆閸曨偄鐏熼柕?
 * <p>
 * 闁哄秷顫夊畵?{@link RuleRecord#ruleTag} 闁煎浜滄慨鈺冩崉椤栨粍鏆犻柨?
 * <ul>
 *   <li>ruleTag 濞?null 闁瑰瓨鐗滈埞?闁?缂佸顭峰▍搴ｆ喆閸曨偄鐏熼柨娑樿嫰椤瑩骞?{@link RemoveApplier}</li>
 *   <li>ruleTag 闂傚牏鍋熼埞?闁?濞ｅ浂鍠楅弫鑲╂喆閸曨偄鐏熼柨娑樿嫰椤瑩骞?{@link ModifyApplier}</li>
 * </ul>
 * <p>
 * 闂侇偅淇虹换?{@link #getDefault()} 闁兼儳鍢茶ぐ鍥礂閸欐﹢鐓╅悗鍦仒缁躲儵濡?
 */
public final class ViewController {

    private static volatile ViewController sInstance;

    private RuleApplier mModifyApplier;
    private RuleApplier mRemoveApplier;

    // =========================================================================
    // 闁告娲戠欢銉ф媼閸ф锛?
    // =========================================================================

    /** 闁兼儳鍢茶ぐ鍥礂閸欐﹢鐓╅悗鍦仒缁躲儵鏁嶉崼婵囶偨閺夆晝鍠庨崹鍨叏鐎ｎ亜顕ч柨娑樼灱閸ゅ海绮欑€ｎ亞鏆旈柛蹇ｇ厜缁辨岸濡?*/
    public static ViewController getDefault() {
        if (sInstance == null) {
            synchronized (ViewController.class) {
                if (sInstance == null) {
                    sInstance = new ViewController();
                }
            }
        }
        return sInstance;
    }

    private ViewController() {}

    // =========================================================================
    // Applier 闁硅櫕甯掓慨鐐存姜?
    // =========================================================================

    private RuleApplier getModifyApplier() {
        if (mModifyApplier == null) {
            mModifyApplier = new ModifyApplier(
                    path -> GodModeManager.getDefault().openImageFileDescriptor(path));
        }
        return mModifyApplier;
    }

    private RuleApplier getRemoveApplier() {
        if (mRemoveApplier == null) {
            mRemoveApplier = new RemoveApplier();
        }
        return mRemoveApplier;
    }

    // =========================================================================
    // 闁稿浚鍓欑槐?API
    // =========================================================================

    /** 婵炴挸鎳愰埞鏍ь啅閹绘帞娼岄柦鍕偓鐕佹綊闁搞儱澧藉▓鎴犵磽閹惧磭鎽犻柕?*/
    public void clearBlockedCache() {
        if (mRemoveApplier != null) mRemoveApplier.clearCache();
        if (mModifyApplier != null) mModifyApplier.clearCache();
    }

    /** 閻?app 婵☆垪鈧櫕鍋ラ柣?RuleRecord 閺夌儐鍓氬畷鍙夌▔?engine 闁?RuleMatchSpec闁?*/
    private static com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec toEngineRule(RuleRecord appRule) {
        return RuleMapper.toEngine(appRule);
    }

    /** 闁归潧缍婇崳鐑樻償閺冨倹鏆忛悷娆忓閸垶濡?*/
    public void applyRuleBatch(Activity activity, List<RuleRecord> rules) {
        int appliedCount = 0;
        ViewGroup decorView = activity != null && activity.getWindow() != null
                ? (ViewGroup) activity.getWindow().getDecorView() : null;
        if (decorView == null) return;
        String packageName = activity.getPackageName();
        for (RuleRecord rule : rules) {
            try {
                com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec engineRule = toEngineRule(rule);
                if (rule.isRepeatable()) {
                    List<View> views = ViewFinder.findAllViewsBestMatch(decorView, engineRule,
                            activity.getPackageManager(), packageName);
                    if (views != null) {
                        for (View v : views) {
                            if (v != null && applyRule(v, rule)) appliedCount++;
                        }
                    }
                    continue;
                }
                View view = ViewFinder.findViewBestMatch(decorView, engineRule,
                        activity.getPackageManager(), packageName);
                if (view == null) {
                    Logger.w(TAG, "[ViewController] Failed: " + activity + "#" + rule.viewClass
                            + " block failed: not match any view");
                    continue;
                }
                if (applyRule(view, rule)) appliedCount++;
            } catch (Exception e) {
                Logger.w(TAG, "[ViewController] apply rule failed", e);
            }
        }
        if (appliedCount > 0) {
            Logger.d(TAG, "[ViewController] applied " + appliedCount + " rules for " + activity);
        }
    }

    /** 閹煎瓨姊婚弫銈夊础閺囩喐钂嬮悷娆忓閸垶濡?*/
    public boolean applyRule(View v, RuleRecord viewRule) {
        if (v == null || viewRule == null) return false;
        com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec engineRule = toEngineRule(viewRule);
        if (viewRule.isModifyRule()) {
            return getModifyApplier().apply(v, engineRule);
        } else {
            return getRemoveApplier().apply(v, engineRule);
        }
    }

    /** 闁归潧缍婇崳娲箻閵堝鏁橀悷娆忓閸垶濡?*/
    public void revokeRuleBatch(Activity activity, List<RuleRecord> rules) {
        ViewGroup decorView = activity != null && activity.getWindow() != null
                ? (ViewGroup) activity.getWindow().getDecorView() : null;
        if (decorView == null) return;
        String packageName = activity.getPackageName();
        for (RuleRecord rule : rules) {
            try {
                com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec engineRule = toEngineRule(rule);
                if (rule.isRepeatable()) {
                    List<View> views = ViewFinder.findAllViewsBestMatch(decorView, engineRule,
                            activity.getPackageManager(), packageName);
                    if (views != null) {
                        for (View v : views) {
                            if (v != null) revokeRule(v, rule);
                        }
                    }
                    continue;
                }
                View view = ViewFinder.findViewBestMatch(decorView, engineRule,
                        activity.getPackageManager(), packageName);
                if (view == null) {
                    Logger.w(TAG, "[ViewController] revoke rule fail (act=" + activity
                            + "): not match any view");
                    continue;
                }
                revokeRule(view, rule);
            } catch (Exception e) {
                Logger.w(TAG, "[ViewController] revoke rule failed", e);
            }
        }
    }

    /** 闁逛勘鍊濋弨銏ゅ础閺囩喐钂嬮悷娆忓閸垶濡?*/
    public void revokeRule(View v, RuleRecord viewRule) {
        if (v == null || viewRule == null) return;
        com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec engineRule = toEngineRule(viewRule);
        if (viewRule.isModifyRule()) {
            getModifyApplier().revoke(v, engineRule);
        } else {
            getRemoveApplier().revoke(v, engineRule);
        }
    }
}
