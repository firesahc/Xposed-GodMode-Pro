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
import com.kaisar.xposed.godmode.injection.ViewHelper;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.ViewRule;

import java.util.Objects;

/**
 * 视图规则构造工厂 — 从视图创建屏蔽/修改规则。
 * <p>
 * 从 {@code ViewHelper} 拆分，职责单一。
 */
public final class ViewRuleFactory {

    private ViewRuleFactory() {}

    /**
     * 从视图创建屏蔽规则（通用）。
     *
     * @param v 目标视图
     * @return 构造完成的 ViewRule
     * @throws PackageManager.NameNotFoundException 无法获取包信息时抛出
     */
    public static ViewRule makeRule(View v) throws PackageManager.NameNotFoundException {
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
        ViewRule rule = new ViewRule(label, packageName, versionName, versionCode,
                BuildConfig.VERSION_CODE, "", alias,
                x, y, width, height, viewHierarchyDepth,
                activityClassName, viewClassName, resourceName, text, description,
                View.INVISIBLE, System.currentTimeMillis());
        ViewHelper.populateRepeatableInfo(v, rule);
        return rule;
    }

    /**
     * 创建移除规则（ruleTag 留空以兼容旧 JSON 格式）。
     *
     * @param v 目标视图
     * @return 构造完成的 ViewRule
     * @throws PackageManager.NameNotFoundException 无法获取包信息时抛出
     */
    public static ViewRule makeRemoveRule(View v) throws PackageManager.NameNotFoundException {
        return makeRule(v);
    }

    /**
     * 创建修改规则。
     *
     * @param view 目标视图
     * @return 构造完成的 ViewRule（ruleTag="modify"）
     */
    public static ViewRule makeModifyRule(View view) {
        Activity act = ViewUtils.getAttachedActivityFromView(view);
        ViewRule rule = new ViewRule("",
                act != null ? act.getPackageName() : "",
                "", 0, 0, "", "", 0, 0, 0, 0,
                ViewUtils.getViewHierarchyDepth(view),
                act != null ? act.getComponentName().getClassName() : "",
                view.getClass().getName(), "", "", "",
                View.VISIBLE, System.currentTimeMillis());
        rule.ruleTag = "modify";
        rule.captureOriginals(view);
        fillCoordinates(rule, view);
        ViewHelper.populateRepeatableInfo(view, rule);
        return rule;
    }

    /**
     * 将视图的当前位置/尺寸写入规则。
     *
     * @param rule 目标规则
     * @param v    当前视图
     */
    public static void fillCoordinates(ViewRule rule, View v) {
        int[] out = new int[2];
        v.getLocationInWindow(out);
        rule.x = out[0];
        rule.y = out[1];
        rule.width = v.getWidth();
        rule.height = v.getHeight();
    }
}

