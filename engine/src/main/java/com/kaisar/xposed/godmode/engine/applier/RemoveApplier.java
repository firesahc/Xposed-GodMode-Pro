package com.kaisar.xposed.godmode.engine.applier;

import android.util.Pair;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;

import java.lang.ref.WeakReference;

/**
 * 移除规则应用器 — 将视图设置为 GONE/INVISIBLE 或恢复原始状态。
 * 从 ViewController 提取的核心逻辑，通过 IMatcher 构造注入实现匹配解耦。
 */
public final class RemoveApplier implements RuleApplier {

    private final SparseArray<Pair<WeakReference<View>, ViewProperty>> mBlockedViewCache
            = new SparseArray<>();

    @Override
    public boolean apply(View view, RuleMatchSpec rule) {
        if (view == null || rule == null) return false;
        int cacheKey = rule.isRepeatable() ? identityKey(rule) : rule.hashCode();
        Pair<WeakReference<View>, ViewProperty> viewInfo = mBlockedViewCache.get(cacheKey);
        View blockedView = viewInfo != null ? viewInfo.first.get() : null;
        if (blockedView == view && view.getVisibility() == rule.visibility) {
            return false; // 已经应用
        }
        ViewProperty viewProperty = blockedView == view
                ? viewInfo.second : ViewProperty.create(view);
        view.setAlpha(0f);
        view.setClickable(false);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            switch (rule.visibility) {
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
        ViewCompat.setVisibility(view, rule.visibility);
        mBlockedViewCache.put(cacheKey, Pair.create(
                new WeakReference<>(view), viewProperty));
        return true;
    }

    @Override
    public boolean revoke(View view, RuleMatchSpec rule) {
        int cacheKey = rule.isRepeatable() ? identityKey(rule) : rule.hashCode();
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
        // 缓存缺失时的降级恢复
        view.setAlpha(1f);
        ViewCompat.setVisibility(view, rule.visibility);
        return false;
    }

    @Override
    public void clearCache() {
        mBlockedViewCache.clear();
    }

    private static int identityKey(RuleMatchSpec rule) {
        int result = rule.activityClass != null ? rule.activityClass.hashCode() : 0;
        result = 31 * result + (rule.viewClass != null ? rule.viewClass.hashCode() : 0);
        result = 31 * result + java.util.Objects.hashCode(rule.resourceName);
        result = 31 * result + java.util.Objects.hashCode(rule.text);
        return result;
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
