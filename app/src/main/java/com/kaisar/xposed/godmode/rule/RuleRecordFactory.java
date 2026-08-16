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
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.ModifyEffect;
import com.kaisar.xposed.godmode.engine.rule.RemoveEffect;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.util.ViewUtils;

import java.util.Objects;

/** Creates complete records from a host view without mutating stable rule components afterward. */
public final class RuleRecordFactory {

    private static final String TAG = "RuleRecordFactory";

    private RuleRecordFactory() {}

    static RuleRecord makeRule(View view, boolean isInfoFlowMode)
            throws PackageManager.NameNotFoundException {
        Activity activity = ViewUtils.getAttachedActivityFromView(view);
        Objects.requireNonNull(activity, "Can't find attached activity");
        Context context = view.getContext();
        int[] location = new int[2];
        view.getLocationInWindow(location);
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        String text = view instanceof TextView && !TextUtils.isEmpty(((TextView) view).getText())
                ? ((TextView) view).getText().toString() : "";
        String description = !TextUtils.isEmpty(view.getContentDescription())
                ? view.getContentDescription().toString() : "";
        String alias = !TextUtils.isEmpty(text) ? text : description;
        return new RuleRecord(info.applicationInfo.loadLabel(context.getPackageManager()).toString(),
                context.getPackageName(), info.versionName, info.versionCode, BuildConfig.VERSION_CODE,
                "", alias, location[0], location[1], view.getWidth(), view.getHeight(),
                System.currentTimeMillis(), 0, 0, 1f, null,
                captureMatchSpec(view, activity, text, description, isInfoFlowMode),
                RemoveEffect.of(View.INVISIBLE));
    }

    public static RuleRecord makeRemoveRule(View view, boolean isInfoFlowMode)
            throws PackageManager.NameNotFoundException {
        return makeRule(view, isInfoFlowMode);
    }

    public static RuleRecord makeModifyRule(View view, ViewSnapshot snapshot,
                                            boolean isInfoFlowMode) {
        try {
            RuleRecord base = makeRule(view, isInfoFlowMode);
            MatchSpec match = base.getMatchSpec().toBuilder().text(snapshot.text).build();
            String alias = !TextUtils.isEmpty(snapshot.text) ? snapshot.text : match.getDescription();
            return new RuleRecord(base.label, base.packageName, base.matchVersionName,
                    base.matchVersionCode, base.versionCode, base.imagePath, alias,
                    base.x, base.y, base.width, base.height, base.timestamp,
                    snapshot.origWidth, snapshot.origHeight, snapshot.origAlpha, snapshot.origText,
                    match, new ModifyEffect.Builder().ruleTag("modify")
                            .visibility(base.getVisibility())
                            .origLeftMargin(snapshot.origLeftMargin)
                            .origTopMargin(snapshot.origTopMargin)
                            .build());
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Failed to create modify rule", e);
        }
    }

    /** Updates only residual capture coordinates; stable components are untouched. */
    public static void fillCoordinates(RuleRecord rule, View view) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        rule.x = location[0];
        rule.y = location[1];
        rule.width = view.getWidth();
        rule.height = view.getHeight();
    }

    private static MatchSpec captureMatchSpec(View view, Activity activity, String text,
                                              String description, boolean isInfoFlowMode) {
        Resources resources = view.getResources();
        String resourceName = null;
        try {
            resourceName = view.getId() != View.NO_ID ? resources.getResourceName(view.getId()) : null;
        } catch (Resources.NotFoundException e) {
            Logger.d(TAG, "resourceName not found for view id=" + view.getId(), e);
        }
        MatchSpec.Builder builder = new MatchSpec.Builder()
                .depth(ViewUtils.getViewHierarchyDepth(view))
                .activityClass(activity.getComponentName().getClassName())
                .viewClass(view.getClass().getName())
                .resourceName(resourceName)
                .text(text)
                .description(description);
        populateRepeatableInfo(view, builder, isInfoFlowMode);
        return builder.build();
    }

    private static void populateRepeatableInfo(View view, MatchSpec.Builder builder,
                                               boolean isInfoFlowMode) {
        if (!isInfoFlowMode) return;
        try {
            ViewGroup recycler = ViewTraversal.findRecyclerViewAncestor(view);
            if (recycler == null) return;
            String[] itemPath = ViewTraversal.getItemPath(view, recycler);
            View itemRoot = view;
            ViewParent parent = itemRoot.getParent();
            while (parent != recycler && parent instanceof ViewGroup) {
                itemRoot = (View) parent;
                parent = itemRoot.getParent();
            }
            if (itemPath == null || itemPath.length == 0) return;
            builder.itemPath(itemPath)
                    .itemRootClass(itemRoot.getClass().getName())
                    .parentClass(view.getParent() != null ? view.getParent().getClass().getName() : null)
                    .repeatable(true)
                    .targetLevel(TargetLevel.CARD);
            if (recycler instanceof androidx.recyclerview.widget.RecyclerView) {
                androidx.recyclerview.widget.RecyclerView recyclerView =
                        (androidx.recyclerview.widget.RecyclerView) recycler;
                androidx.recyclerview.widget.RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                int position = recyclerView.getChildAdapterPosition(itemRoot);
                if (adapter != null && position >= 0) builder.viewType(adapter.getItemViewType(position));
            }
        } catch (Exception e) {
            Logger.w(TAG, "[RuleRecordFactory] repeatable capture failed", e);
        }
    }
}
