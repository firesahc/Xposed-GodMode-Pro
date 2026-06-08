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
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.engine.util.Preconditions;

import java.util.List;

/**
 * 鐟欏棗娴橀幒褍鍩楅崳?閳?娴ｈ法鏁?engine/applier 娴ｆ挾閮存惔鏃傛暏/閹俱倝鏀㈢憴鍕灟閵?
 * <p>
 * 閺嶈宓?{@link RuleRecord#ruleTag} 閼奉亜濮╃捄顖滄暠閿?
 * <ul>
 *   <li>ruleTag 娑?null 閹存牜鈹?閳?缁夊娅庣憴鍕灟閿涘苯顫欓幍?{@link RemoveApplier}</li>
 *   <li>ruleTag 闂堢偟鈹?閳?娣囶喗鏁肩憴鍕灟閿涘苯顫欓幍?{@link ModifyApplier}</li>
 * </ul>
 * <p>
 * 闁俺绻?{@link #getDefault()} 閼惧嘲褰囬崗鍙橀煩鐎圭偘绶ラ妴?
 */
public final class ViewController {

    private static volatile ViewController sInstance;

    private RuleApplier mModifyApplier;
    private RuleApplier mRemoveApplier;

    // =========================================================================
    // 閸楁洑绶ョ拋鍧楁６
    // =========================================================================

    /** 閼惧嘲褰囬崗鍙橀煩鐎圭偘绶ラ敍鍫濇鏉╃喎鍨垫慨瀣閿涘瞼鍤庣粙瀣暔閸忣煉绱氶妴?*/
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
    // Applier 閹虫帒濮炴潪?
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
    // 閸忣剙绱?API
    // =========================================================================

    /** 濞撳懐鈹栧鎻掔潌閽勫€燁潒閸ュ墽娈戠紓鎾崇摠閵?*/
    public void clearBlockedCache() {
        if (mRemoveApplier != null) mRemoveApplier.clearCache();
        if (mModifyApplier != null) mModifyApplier.clearCache();
    }

    /** 鐏?app 濡€虫健閻?RuleRecord 鏉烆剚宕叉稉?engine 閻?RuleMatchSpec閵?*/
    private static com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec toEngineRule(RuleRecord appRule) {
        return RuleMapper.toEngine(appRule);
    }

    /** 閹靛綊鍣烘惔鏃傛暏鐟欏嫬鍨妴?*/
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

    /** 鎼存梻鏁ら崡鏇熸蒋鐟欏嫬鍨妴?*/
    public boolean applyRule(View v, RuleRecord viewRule) {
        if (v == null || viewRule == null) return false;
        com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec engineRule = toEngineRule(viewRule);
        if (viewRule.isModifyRule()) {
            return getModifyApplier().apply(v, engineRule);
        } else {
            return getRemoveApplier().apply(v, engineRule);
        }
    }

    /** 閹靛綊鍣洪幘銈夋敘鐟欏嫬鍨妴?*/
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

    /** 閹俱倝鏀㈤崡鏇熸蒋鐟欏嫬鍨妴?*/
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
