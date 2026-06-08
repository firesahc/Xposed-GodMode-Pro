package com.kaisar.xposed.godmode.injection.editor;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.engine.matcher.ViewFinder;
import com.kaisar.xposed.godmode.engine.rule.RuleMapper;
import com.kaisar.xposed.godmode.injection.GodModeInjector;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.Objects;

/**
 * 瑙嗗浘瑙勫垯鏋勯€犲伐鍘?鈥?浠庤鍥惧垱寤哄睆钄?淇敼瑙勫垯銆?
 * <p>
 * 浠?{@code ViewHelper} 鎷嗗垎锛岃亴璐ｅ崟涓€銆?
 */
public final class RuleRecordFactory {

    private RuleRecordFactory() {}

    /**
     * 浠庤鍥惧垱寤哄睆钄借鍒欙紙閫氱敤锛夈€?
     *
     * @param v 鐩爣瑙嗗浘
     * @return 鏋勯€犲畬鎴愮殑 RuleRecord
     * @throws PackageManager.NameNotFoundException 鏃犳硶鑾峰彇鍖呬俊鎭椂鎶涘嚭
     */
    public static RuleRecord makeRule(View v) throws PackageManager.NameNotFoundException {
        Activity activity = ViewUtils.getAttachedActivityFromView(v);
        Objects.requireNonNull(activity, "Can't found attached activity");
        int[] out = new int[2];
        v.getLocationInWindow(out);
        int x = out[0];
        int y = out[1];
        int width = v.getWidth();
        int height = v.getHeight();

        int[] viewHierarchyDepth = ViewUtils.getViewHierarchyDepth(v);
        String activityClassName = activity.getComponentName().getClassName();
        String viewClassName = v.getClass().getName();
        Context context = v.getContext();
        Resources res = context.getResources();
        String resourceName = null;
        try {
            resourceName = v.getId() != View.NO_ID ? res.getResourceName(v.getId()) : null;
        } catch (Resources.NotFoundException ignore) {
            //the resource id may be declared in the plugin apk
        }
        String text = (v instanceof TextView && !TextUtils.isEmpty(((TextView) v).getText()))
                ? ((TextView) v).getText().toString() : "";
        String description = (!TextUtils.isEmpty(v.getContentDescription()))
                ? v.getContentDescription().toString() : "";
        String alias = !TextUtils.isEmpty(text) ? text : description;
        String packageName = context.getPackageName();
        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(packageName, 0);
        String label = packageInfo.applicationInfo.loadLabel(context.getPackageManager()).toString();
        String versionName = packageInfo.versionName;
        int versionCode = packageInfo.versionCode;
        RuleRecord rule = new RuleRecord(label, packageName, versionName, versionCode,
                BuildConfig.VERSION_CODE, "", alias,
                x, y, width, height, viewHierarchyDepth,
                activityClassName, viewClassName, resourceName, text, description,
                View.INVISIBLE, System.currentTimeMillis());
        populateRepeatableInfo(v, rule);
        return rule;
    }

    /**
     * 鍒涘缓绉婚櫎瑙勫垯锛坮uleTag 鐣欑┖浠ュ吋瀹规棫 JSON 鏍煎紡锛夈€?
     *
     * @param v 鐩爣瑙嗗浘
     * @return 鏋勯€犲畬鎴愮殑 RuleRecord
     * @throws PackageManager.NameNotFoundException 鏃犳硶鑾峰彇鍖呬俊鎭椂鎶涘嚭
     */
    public static RuleRecord makeRemoveRule(View v) throws PackageManager.NameNotFoundException {
        return makeRule(v);
    }

    /**
     * 鍒涘缓淇敼瑙勫垯銆?
     *
     * @param view 鐩爣瑙嗗浘
     * @return 鏋勯€犲畬鎴愮殑 RuleRecord锛坮uleTag="modify"锛?
     */
    public static RuleRecord makeModifyRule(View view) {
        Activity act = ViewUtils.getAttachedActivityFromView(view);
        RuleRecord rule = new RuleRecord("",
                act != null ? act.getPackageName() : "",
                "", 0, 0, "", "", 0, 0, 0, 0,
                ViewUtils.getViewHierarchyDepth(view),
                act != null ? act.getComponentName().getClassName() : "",
                view.getClass().getName(), "", "", "",
                View.VISIBLE, System.currentTimeMillis());
        rule.ruleTag = "modify";
        rule.captureOriginals(view);
        fillCoordinates(rule, view);
        populateRepeatableInfo(view, rule);
        return rule;
    }

    /**
     * 灏嗚鍥剧殑褰撳墠浣嶇疆/灏哄鍐欏叆瑙勫垯銆?
     *
     * @param rule 鐩爣瑙勫垯
     * @param v    褰撳墠瑙嗗浘
     */
    public static void fillCoordinates(RuleRecord rule, View v) {
        int[] out = new int[2];
        v.getLocationInWindow(out);
        rule.x = out[0];
        rule.y = out[1];
        rule.width = v.getWidth();
        rule.height = v.getHeight();
    }

    // =========================================================================
    // 浠ヤ笅鏂规硶浠?ViewHelper 鍐呰仈杩佺Щ锛圴iewHelper @Deprecated 鍗冲皢閫€褰癸級
    // =========================================================================

    /** 灏?app 妯″潡 RuleRecord 杞崲涓?engine 妯″潡 RuleMatchSpec */
    private static com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec toEngine(RuleRecord appRule) {
        return RuleMapper.toEngine(appRule);
    }

    /** 濉厖鍙噸澶嶈鍒欎俊鎭紙itemPath銆乮temRootClass銆乸arentClass锛?*/
    private static void populateRepeatableInfo(View v, RuleRecord rule) {
        boolean isInfoFlowMode = GodModeInjector.getEditorOrchestrator().isInfoFlowMode();
        com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec engineRule = toEngine(rule);
        ViewFinder.populateRepeatableInfo(v, engineRule, isInfoFlowMode);
        if (engineRule.isRepeatable()) {
            rule.itemPath = engineRule.getItemPath();
            rule.itemRootClass = engineRule.getItemRootClass();
            rule.parentClass = engineRule.getParentClass();
            rule.repeatable = true;
        }
    }
}

