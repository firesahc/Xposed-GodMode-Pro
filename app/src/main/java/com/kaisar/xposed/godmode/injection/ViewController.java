package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.util.Pair;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Created by jrsen on 17-10-15.
 */

public final class ViewController {

    private final static SparseArray<Pair<WeakReference<View>, ViewProperty>> blockedViewCache = new SparseArray<>();

    public static void applyRuleBatch(Activity activity, List<ViewRule> rules) {
        for (ViewRule rule : rules) {
            try {
                int ruleHashCode = rule.hashCode();
                Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(ruleHashCode);
                View view = viewInfo != null ? viewInfo.first.get() : null;
                if (view == null || !view.isAttachedToWindow()) {
                    blockedViewCache.delete(ruleHashCode);
                    view = ViewHelper.findViewBestMatch(activity, rule);
                    Preconditions.checkNotNull(view, "apply rule fail not match any view");
                }
                boolean blocked = applyRule(view, rule);
                if (!blocked) {
                    Logger.d(TAG, "[Skipped] " + activity + "#" + view + " already be blocked");
                }
            } catch (NullPointerException e) {
                Logger.w(TAG, "[Failed] " + activity + "#" + rule.viewClass + " block failed: " + e.getMessage());
            }
        }
    }

    public static boolean applyRule(View v, ViewRule viewRule) {
        int ruleHashCode = viewRule.hashCode();
        Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(ruleHashCode);
        View blockedView = viewInfo != null ? viewInfo.first.get() : null;
        if (blockedView == v && v.getVisibility() == viewRule.visibility) {
            return false;
        }
        ViewProperty viewProperty = blockedView == v ? viewInfo.second : ViewProperty.create(v);
        v.setAlpha(0f);
        v.setClickable(false);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            switch (viewRule.visibility) {
                case View.GONE:
                    lp.width = 0;
                    lp.height = 0;
                    break;
                case View.INVISIBLE:
                    lp.width = viewProperty.layout_params_width;
                    lp.height = viewProperty.layout_params_height;
                    break;
            }
            v.requestLayout();
        }
        ViewCompat.setVisibility(v, viewRule.visibility);
        blockedViewCache.put(ruleHashCode, Pair.create(new WeakReference<>(v), viewProperty));
        return true;
    }

    public static void revokeRuleBatch(Activity activity, List<ViewRule> rules) {
        for (ViewRule rule : rules) {
            try {
                int ruleHashCode = rule.hashCode();
                Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(ruleHashCode);
                View view = viewInfo != null ? viewInfo.first.get() : null;
                if (view == null || !view.isAttachedToWindow()) {
                    blockedViewCache.delete(ruleHashCode);
                    view = ViewHelper.findViewBestMatch(activity, rule);
                    Preconditions.checkNotNull(view, "revoke rule fail can't found block view");
                }
                revokeRule(view, rule);
            } catch (NullPointerException e) {
                Logger.w(TAG, "###revoke rule fail [Act]:" + activity + " [Reason]:" + e.getMessage());
            }
        }
    }

    public static void revokeRule(View v, ViewRule viewRule) {
        int ruleHashCode = viewRule.hashCode();
        Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(ruleHashCode);
        if (viewInfo != null && viewInfo.first.get() == v) {
            ViewProperty viewProperty = viewInfo.second;
            v.setAlpha(viewProperty.alpha);
            v.setClickable(viewProperty.clickable);
            ViewCompat.setVisibility(v, viewProperty.visibility);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                lp.width = viewProperty.layout_params_width;
                lp.height = viewProperty.layout_params_height;
                v.requestLayout();
            }
            blockedViewCache.delete(viewRule.hashCode());
        } else {
            Logger.w(TAG, "view cache missing during revoke, falling back to setAlpha");
            v.setAlpha(1f);
            ViewCompat.setVisibility(v, viewRule.visibility);
        }
    }

    private static final class ViewProperty {

        final float alpha;
        final boolean clickable;
        final int visibility;
        final int layout_params_width;
        final int layout_params_height;

        public ViewProperty(float alpha, boolean clickable, int visibility, int layout_params_width, int layout_params_height) {
            this.alpha = alpha;
            this.clickable = clickable;
            this.visibility = visibility;
            this.layout_params_width = layout_params_width;
            this.layout_params_height = layout_params_height;
        }

        @NonNull
        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("ViewProperty{");
            sb.append("alpha=").append(alpha);
            sb.append(", clickable=").append(clickable);
            sb.append(", visibility=").append(visibility);
            sb.append(", layout_params_width=").append(layout_params_width);
            sb.append(", layout_params_height=").append(layout_params_height);
            sb.append('}');
            return sb.toString();
        }

        public static ViewProperty create(View view) {
            float alpha = view.getAlpha();
            boolean clickable = view.isClickable();
            int visibility = view.getVisibility();
            ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            int width = layoutParams != null ? layoutParams.width : 0;
            int height = layoutParams != null ? layoutParams.height : 0;
            return new ViewProperty(alpha, clickable, visibility, width, height);
        }
    }

}
