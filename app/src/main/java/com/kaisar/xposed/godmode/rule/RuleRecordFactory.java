package com.kaisar.xposed.godmode.rule;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.HookLauncher;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.Objects;

/**
 * 视图规则构造工厂 — 从视图创建屏蔽/修改规则。
 * <p>
 * 从 {@code ViewHelper} 拆分，职责单一。
 */
public final class RuleRecordFactory {

    private static final String TAG = "RuleRecordFactory";

    private RuleRecordFactory() {}

    /**
     * 从视图创建屏蔽规则（通用）。
     *
     * @param v 目标视图
     * @return 构造完成的 RuleRecord
     * @throws PackageManager.NameNotFoundException 无法获取包信息时抛出
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
     * 创建移除规则（ruleTag 留空以兼容旧 JSON 格式）。
     *
     * @param v 目标视图
     * @return 构造完成的 RuleRecord
     * @throws PackageManager.NameNotFoundException 无法获取包信息时抛出
     */
    public static RuleRecord makeRemoveRule(View v) throws PackageManager.NameNotFoundException {
        return makeRule(v);
    }

    /**
     * 创建修改规则（使用预先捕获的原始状态快照）。
     * <p>
     * 结构信息（depth/viewClass/resourceName 等）从视图捕获（安全，修改不影响结构），
     * 匹配字段（text）和原始值（orig*）从快照读取（保证为编辑前的原始值）。
     * 解决了旧方案在视图被修改后调用时
     * 捕获错误值的问题。
     *
     * @param view     目标视图
     * @param snapshot 视图未修改时预先捕获的原始状态快照
     * @return 构造完成的 RuleRecord（ruleTag="modify"），所有原始数据正确
     */
    public static RuleRecord makeModifyRule(View view, ViewSnapshot snapshot) {
        try {
            RuleRecord rule = makeRule(view);
            rule.ruleTag = "modify";

            // 匹配字段：来自快照（原始值），非视图当前（可能被修改）值
            rule.text = snapshot.text;
            rule.alias = !TextUtils.isEmpty(rule.text) ? rule.text : rule.description;

            // 原始值字段：来自快照（原始值），非视图当前（可能被修改）值
            rule.origWidth = snapshot.origWidth;
            rule.origHeight = snapshot.origHeight;
            rule.origAlpha = snapshot.origAlpha;
            rule.origText = snapshot.origText;
            rule.origLeftMargin = snapshot.origLeftMargin;
            rule.origTopMargin = snapshot.origTopMargin;

            // x/y/width/height 纯展示用途，使用当前视图值可接受
            fillCoordinates(rule, view);
            return rule;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Failed to create modify rule", e);
        }
    }

    /**
     * 将视图的当前位置/尺寸写入规则。
     *
     * @param rule 目标规则
     * @param v    当前视图
     */
    public static void fillCoordinates(RuleRecord rule, View v) {
        int[] out = new int[2];
        v.getLocationInWindow(out);
        rule.x = out[0];
        rule.y = out[1];
        rule.width = v.getWidth();
        rule.height = v.getHeight();
    }

    /** 填充可重复规则信息（itemPath、itemRootClass、parentClass）*/
    private static void populateRepeatableInfo(View v, RuleRecord rule) {
        if (!HookLauncher.getEditorOrchestrator().isInfoFlowMode()) return;
        try {
            ViewGroup rv = ViewTraversal.findRecyclerViewAncestor(v);
            if (rv == null) return;

            String[] itemPath = ViewTraversal.getItemPath(v, rv);

            View current = v;
            ViewParent p = current.getParent();
            while (p != rv && p instanceof ViewGroup) {
                current = (View) p;
                p = p.getParent();
            }
            String itemRootClass = current.getClass().getName();

            // itemPath + itemRootClass 有效即标记 repeatable（不再要求 matchCount >= 2）
            if (itemPath != null && itemPath.length > 0 && itemRootClass != null) {
                rule.itemPath = itemPath;
                rule.itemRootClass = itemRootClass;
                rule.parentClass = v.getParent() != null ? v.getParent().getClass().getName() : null;
                rule.repeatable = true;

                // 捕获 viewType（复用 matchThreshold 字段）
                int viewType = -1;
                try {
                    if (rv instanceof androidx.recyclerview.widget.RecyclerView) {
                        androidx.recyclerview.widget.RecyclerView recyclerView =
                                (androidx.recyclerview.widget.RecyclerView) rv;
                        androidx.recyclerview.widget.RecyclerView.Adapter<?> adapter =
                                recyclerView.getAdapter();
                        if (adapter != null) {
                            int pos = recyclerView.getChildAdapterPosition(current);
                            if (pos >= 0) viewType = adapter.getItemViewType(pos);
                        }
                    }
                } catch (Exception ignored) {
                }
                if (viewType >= 0) rule.matchThreshold = viewType;
            }
        } catch (Exception e) {
            Logger.w(TAG, "[RuleRecordFactory] populateRepeatableInfo failed", e);
        }
    }
}
