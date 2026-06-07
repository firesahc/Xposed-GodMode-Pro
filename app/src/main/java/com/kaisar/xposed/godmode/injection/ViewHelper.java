package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.kaisar.xposed.godmode.engine.matcher.ViewFinder;
import com.kaisar.xposed.godmode.engine.traversal.ViewTraversal;
import com.kaisar.xposed.godmode.engine.util.FieldMapper;
import com.kaisar.xposed.godmode.injection.editor.BitmapUtils;
import com.kaisar.xposed.godmode.injection.editor.ViewRuleFactory;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * Created by jrsen on 17-10-13.
 *
 * @deprecated 此类正在拆解中，新代码请直接调用具体的工具类：
 * <ul>
 *   <li>{@link BitmapUtils} — 位图操作</li>
 * </ul>
 */
@Deprecated
public final class ViewHelper {

    public static final String TAG_GM_CMP = "gm_cmp";

    /** 将 app 模块 ViewRule 转换为 engine 模块 ViewRule */
    private static com.kaisar.xposed.godmode.engine.rule.ViewRule toEngine(ViewRule appRule) {
        com.kaisar.xposed.godmode.engine.rule.ViewRule engineRule =
                new com.kaisar.xposed.godmode.engine.rule.ViewRule();
        FieldMapper.copyFields(appRule, engineRule);
        return engineRule;
    }

    /** @deprecated 委托至 {@link ViewFinder#findViewBestMatch} */
    @Deprecated
    public static View findViewBestMatch(Activity activity, ViewRule rule) {
        return ViewFinder.findViewBestMatch(activity, toEngine(rule));
    }

    /** @deprecated 委托至 {@link ViewFinder#findAllViewsBestMatch} */
    @Deprecated
    public static List<View> findAllViewsBestMatch(Activity activity, ViewRule rule) {
        return ViewFinder.findAllViewsBestMatch(activity, toEngine(rule));
    }

    /** @deprecated 委托至 {@link ViewFinder#matchView} */
    @Deprecated
    public static View matchView(View view, ViewRule rule, boolean strictMode) {
        return ViewFinder.matchView(view, toEngine(rule), strictMode);
    }

    /** @deprecated 委托至 {@link ViewTraversal#findViewByDepth} */
    @Deprecated
    public static View findViewByDepth(Activity activity, int[] depths) {
        if (activity == null || activity.getWindow() == null || depths == null) return null;
        return ViewTraversal.findViewByDepth(activity.getWindow().getDecorView(), depths);
    }

    /** @deprecated 委托至 {@link ViewFinder#findRecyclerViewAncestor} */
    @Deprecated
    public static ViewGroup findRecyclerViewAncestor(View view) {
        return ViewFinder.findRecyclerViewAncestor(view);
    }

    /** @deprecated 委托至 {@link ViewFinder#isInRecyclerView} */
    @Deprecated
    public static boolean isInRecyclerView(View v) {
        return ViewFinder.isInRecyclerView(v);
    }

    /** @deprecated 委托至 {@link ViewFinder#getItemPath} */
    @Deprecated
    public static String[] getItemPath(View v, ViewGroup recyclerView) {
        return ViewFinder.getItemPath(v, recyclerView);
    }

    /** @deprecated 委托至 {@link ViewFinder#findViewByItemPath} */
    @Deprecated
    public static View findViewByItemPath(View root, String[] path, int index) {
        return ViewFinder.findViewByItemPath(root, path, index);
    }

    /** @deprecated 委托至 {@link ViewFinder#findViewsInRecyclers} */
    @Deprecated
    public static List<View> findViewsInRecyclers(Activity activity, ViewRule rule) {
        return ViewFinder.findViewsInRecyclers(activity, toEngine(rule));
    }

    /** @deprecated 委托至 {@link ViewFinder#populateRepeatableInfo}，并将 engine 规则变更同步回 app 规则 */
    @Deprecated
    public static void populateRepeatableInfo(View v, ViewRule rule) {
        boolean isInfoFlowMode = com.kaisar.xposed.godmode.hook.KeyInterceptor.isInfoFlowMode();
        com.kaisar.xposed.godmode.engine.rule.ViewRule engineRule = toEngine(rule);
        ViewFinder.populateRepeatableInfo(v, engineRule, isInfoFlowMode);
        if (engineRule.repeatable) {
            rule.itemPath = engineRule.itemPath;
            rule.itemRootClass = engineRule.itemRootClass;
            rule.parentClass = engineRule.parentClass;
            rule.repeatable = true;
        }
    }

    /** @deprecated 委托至 {@link ViewRuleFactory#makeRule} */
    @Deprecated
    public static ViewRule makeRule(View v) throws PackageManager.NameNotFoundException {
        return ViewRuleFactory.makeRule(v);
    }

    /** @deprecated 委托至 {@link ViewRuleFactory#makeRemoveRule} */
    @Deprecated
    public static ViewRule makeRemoveRule(View v) throws PackageManager.NameNotFoundException {
        return ViewRuleFactory.makeRemoveRule(v);
    }

    /** @deprecated 委托至 {@link ViewRuleFactory#makeModifyRule} */
    @Deprecated
    public static ViewRule makeModifyRule(View view) {
        return ViewRuleFactory.makeModifyRule(view);
    }

    /** @deprecated 委托至 {@link ViewRuleFactory#fillCoordinates} */
    @Deprecated
    public static void fillCoordinates(ViewRule rule, View v) {
        ViewRuleFactory.fillCoordinates(rule, v);
    }

    /** @deprecated 委托至 {@link ViewUtils#findTopParentViewByChildView} */
    @Deprecated
    public static View findTopParentViewByChildView(View v) {
        return ViewUtils.findTopParentViewByChildView(v);
    }

    /** @deprecated 委托至 {@link ViewUtils#findViewRootImplByChildView} */
    @Deprecated
    public static Object findViewRootImplByChildView(ViewParent parent) {
        return ViewUtils.findViewRootImplByChildView(parent);
    }

    /** @deprecated 委托至 {@link ViewUtils#getViewHierarchyDepth} */
    @Deprecated
    public static int[] getViewHierarchyDepth(View view) {
        return ViewUtils.getViewHierarchyDepth(view);
    }

    /** @deprecated 委托至 {@link ViewUtils#getAttachedActivityFromView} */
    @Deprecated
    public static Activity getAttachedActivityFromView(View view) {
        return ViewUtils.getAttachedActivityFromView(view);
    }

    /** @deprecated 委托至 {@link BitmapUtils#snapshotView} */
    @Deprecated
    public static Bitmap snapshotView(View view) {
        return BitmapUtils.snapshotView(view);
    }

    /** @deprecated 委托至 {@link BitmapUtils#drawRuleMask} */
    @Deprecated
    public static void drawRuleMask(Bitmap bitmap, ViewRule rule) {
        BitmapUtils.drawRuleMask(bitmap, rule);
    }

    /** @deprecated 委托至 {@link BitmapUtils#cloneViewAsBitmap} */
    @Deprecated
    public static Bitmap cloneViewAsBitmap(View view) {
        return BitmapUtils.cloneViewAsBitmap(view);
    }

    /** @deprecated 委托至 {@link ViewUtils#buildViewNodes} */
    @Deprecated
    public static List<WeakReference<View>> buildViewNodes(View view) {
        return ViewUtils.buildViewNodes(view);
    }

    /** @deprecated 委托至 {@link ViewUtils#getLocationInWindow} */
    @Deprecated
    public static Rect getLocationInWindow(View v) {
        return ViewUtils.getLocationInWindow(v);
    }

    /** @deprecated 委托至 {@link ViewUtils#getViewKey} */
    @Deprecated
    public static String getViewKey(View view) {
        return ViewUtils.getViewKey(view);
    }

}
