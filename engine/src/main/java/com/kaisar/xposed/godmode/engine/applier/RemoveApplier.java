package com.kaisar.xposed.godmode.engine.applier;

import android.util.Pair;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.rule.ActionSpec;

import java.lang.ref.WeakReference;

/**
 * 移除规则应用器 — 将视图设置为 GONE/INVISIBLE 或恢复原始状态。
 */
public final class RemoveApplier implements RuleApplier {

    private final SparseArray<Pair<WeakReference<View>, ViewProperty>> mBlockedViewCache
            = new SparseArray<>();

    /** 当前 Activity 类名，用于构造 {@link IApplierCache.CacheKey} 实现 Activity 级缓存隔离 */
    private final String mActivityClassName;

    /** 进程级单例构造（向后兼容，缓存未经 Activity 隔离） */
    public RemoveApplier() {
        this.mActivityClassName = null;
    }

    /** Activity 级实例构造（缓存键包含 activityClassName，实现跨 Activity 隔离） */
    public RemoveApplier(String activityClassName) {
        this.mActivityClassName = activityClassName;
    }

    // ===== 新 API（ActionSpec） =====

    @Override
    public boolean apply(View view, ActionSpec spec) {
        if (view == null || spec == null) return false;
        int cacheKey = System.identityHashCode(view);
        Pair<WeakReference<View>, ViewProperty> viewInfo = mBlockedViewCache.get(cacheKey);
        View blockedView = viewInfo != null ? viewInfo.first.get() : null;
        if (blockedView == view && view.getVisibility() == spec.visibility) {
            return false;
        }
        ViewProperty viewProperty = blockedView == view
                ? viewInfo.second : ViewProperty.create(view);
        view.setAlpha(0f);
        view.setClickable(false);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            switch (spec.visibility) {
                case View.GONE:
                    lp.width = 0;
                    lp.height = 0;
                    break;
                case View.INVISIBLE:
                    lp.width = viewProperty.layoutParamsWidth;
                    lp.height = viewProperty.layoutParamsHeight;
                    break;
            }
        }
        ViewCompat.setVisibility(view, spec.visibility);
        mBlockedViewCache.put(cacheKey, Pair.create(
                new WeakReference<>(view), viewProperty));
        return true;
    }

    @Override
    public boolean revoke(View view, ActionSpec spec) {
        if (view == null || spec == null) return false;
        int cacheKey = System.identityHashCode(view);
        Pair<WeakReference<View>, ViewProperty> viewInfo = mBlockedViewCache.get(cacheKey);
        if (viewInfo != null && viewInfo.first.get() == view) {
            ViewProperty vp = viewInfo.second;
            view.setAlpha(vp.alpha);
            view.setClickable(vp.clickable);
            ViewCompat.setVisibility(view, vp.visibility);
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                lp.width = vp.layoutParamsWidth;
                lp.height = vp.layoutParamsHeight;
                view.requestLayout();
            }
            mBlockedViewCache.delete(cacheKey);
            return true;
        }
        // 非缓存视图：不做任何操作，静默返回 false
        return false;
    }

    /**
     * 对单个 View 进行撤销恢复操作（不依赖规则集，纯缓存操作）。
     * <p>
     * 用于 onViewRecycled 回调——当 RecyclerView 回收 View 时，
     * 确保该 View 的修改被撤销，避免后续 bindViewHolder 时缓存误命中。
     *
     * @param view 被回收的 View
     * @return true 表示该 View 曾被应用过规则并已成功撤销
     */
    public boolean revokeForView(View view) {
        if (view == null) return false;
        int cacheKey = System.identityHashCode(view);
        Pair<WeakReference<View>, ViewProperty> viewInfo = mBlockedViewCache.get(cacheKey);
        if (viewInfo != null && viewInfo.first.get() == view) {
            ViewProperty vp = viewInfo.second;
            view.setAlpha(vp.alpha);
            view.setClickable(vp.clickable);
            ViewCompat.setVisibility(view, vp.visibility);
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                lp.width = vp.layoutParamsWidth;
                lp.height = vp.layoutParamsHeight;
                view.requestLayout();
            }
            mBlockedViewCache.delete(cacheKey);
            return true;
        }
        return false;
    }

    @Override
    public void clearCache() {
        mBlockedViewCache.clear();
    }

    /**
     * 获取当前 Activity 类名（可能为 null，表示进程级单例）。
     */
    String getActivityClassName() {
        return mActivityClassName;
    }

    // ---- 内部 ViewProperty ----

    private static final class ViewProperty {
        final float alpha;
        final boolean clickable;
        final int visibility;
        final int layoutParamsWidth;
        final int layoutParamsHeight;

        ViewProperty(float alpha, boolean clickable, int visibility,
                int layoutParamsWidth, int layoutParamsHeight) {
            this.alpha = alpha;
            this.clickable = clickable;
            this.visibility = visibility;
            this.layoutParamsWidth = layoutParamsWidth;
            this.layoutParamsHeight = layoutParamsHeight;
        }

        static ViewProperty create(View view) {
            float alpha = view.getAlpha();
            boolean clickable = view.isClickable();
            int visibility = view.getVisibility();
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            int w = lp != null ? lp.width : 0;
            int h = lp != null ? lp.height : 0;
            return new ViewProperty(alpha, clickable, visibility, w, h);
        }
    }
}
