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
            view.requestLayout();
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
        // 历史上此处执行了 view.setAlpha(1f) + ViewCompat.setVisibility(view, spec.visibility)，
        // 这会在 RecyclerView 回收 View 后对不相关的视图执行破坏性操作（将无关 View 设为 GONE）。
        return false;
    }

    @Override
    public void clearCache() {
        mBlockedViewCache.clear();
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
