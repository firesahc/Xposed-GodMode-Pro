package com.kaisar.xposed.godmode.engine.applier;

import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.rule.ActionSpec;

import java.util.WeakHashMap;

/**
 * 移除规则应用器 — 将视图设置为 GONE/INVISIBLE 或恢复原始状态。
 */
public final class RemoveApplier implements RuleApplier {

    // 改 WeakHashMap 替代 SparseArray<identityHashCode>——消除哈希碰撞风险，
    // 由 JVM 保证 View 对象唯一性，与 ModifyApplier.mAppliedViews 风格统一
    private final WeakHashMap<View, ViewProperty> mBlockedViewCache = new WeakHashMap<>();

    /** 当前 Activity 类名，用于实现 Activity 级缓存隔离 */
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
        ViewProperty cached = mBlockedViewCache.get(view);
        if (cached != null && view.getVisibility() == spec.visibility) {
            return false; // 已应用相同规则，跳过
        }
        ViewProperty vp = cached != null ? cached : ViewProperty.create(view);
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
                    lp.width = vp.layoutParamsWidth;
                    lp.height = vp.layoutParamsHeight;
                    break;
            }
        }
        ViewCompat.setVisibility(view, spec.visibility);
        mBlockedViewCache.put(view, vp);
        return true;
    }

    @Override
    public boolean revoke(View view, ActionSpec spec) {
        if (view == null || spec == null) return false;
        ViewProperty vp = mBlockedViewCache.remove(view);
        if (vp == null) return false;
        view.setAlpha(vp.alpha);
        view.setClickable(vp.clickable);
        ViewCompat.setVisibility(view, vp.visibility);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.width = vp.layoutParamsWidth;
            lp.height = vp.layoutParamsHeight;
            view.requestLayout();
        }
        return true;
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
        ViewProperty vp = mBlockedViewCache.remove(view);
        if (vp == null) return false;
        view.setAlpha(vp.alpha);
        view.setClickable(vp.clickable);
        ViewCompat.setVisibility(view, vp.visibility);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.width = vp.layoutParamsWidth;
            lp.height = vp.layoutParamsHeight;
            view.requestLayout();
        }
        return true;
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
