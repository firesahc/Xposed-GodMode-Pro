package com.kaisar.xposed.godmode.runtime;

import android.app.Activity;
import android.view.View;

import com.kaisar.xposed.godmode.engine.matcher.CompositeMatcher;
import com.kaisar.xposed.godmode.engine.matcher.Matcher;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.rule.MatchFields;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.RuleMapper;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * RecyclerView.Adapter 钩子 — 在 item 绑定时精确应用规则，消除组件闪现。
 * <p>
 * 三条钩子路径：
 * <ol>
 *   <li><b>notifyDataSetChanged</b> — 数据变更后清空缓存 + 触发防抖重应用</li>
 *   <li><b>bindViewHolder</b> — 每个 item 绑定时立即匹配并应用 repeatable 规则</li>
 *   <li><b>onViewRecycled</b> — item 回收时撤销所有已应用的规则，避免缓存误命中</li>
 * </ol>
 * <p>
 * 通过 {@link LifecycleObserverCompat} 接口回调，调用方提供缓存清理和重应用调度能力。
 */
public final class RecyclerAdapterHook {

    private static final String TAG = "RecyclerAdapterHook";

    /** 确保每个进程只安装一次钩子 */
    private static boolean sHooksInstalled;

    private RecyclerAdapterHook() {
        // 工具类不可实例化
    }

    /**
     * 当前进程 RecyclerView Hook 是否已安装。
     */
    public static boolean isInstalled() {
        return sHooksInstalled;
    }

    /**
     * 安装 RecyclerView.Adapter 的三条钩子。
     * <p>
     * 安全可重入：仅首次调用生效，同一进程后续调用直接返回。
     *
     * @param activity 用于获取 ClassLoader 的 Activity 实例
     * @param delegate 缓存清理和重应用调度回调（通常由 LifecycleObserver 实现）
     */
    public static void install(Activity activity, Delegate delegate) {
        if (sHooksInstalled) return;
        if (activity == null || delegate == null) return;

        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> adapterClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$Adapter", cl);

            // Hook 1: notifyDataSetChanged → 清除 Applier 缓存 + 防抖重应用
            XposedHelpers.findAndHookMethod(adapterClass, "notifyDataSetChanged",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!RuleManager.isInitialized() || !RuleManager.get().hasRules()) return;
                            if (delegate != null) {
                                delegate.clearAllViewControllersCache();
                                delegate.scheduleReapplyForActivities();
                            }
                        }
                    });

            // Hook 2: bindViewHolder → 精确应用 repeatable 规则，消除闪烁
            Class<?> viewHolderClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$ViewHolder", cl);
            XposedHelpers.findAndHookMethod(adapterClass, "bindViewHolder",
                    viewHolderClass, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!RuleManager.isInitialized() || !RuleManager.get().hasRules()) return;
                            Object holder = param.args[0];
                            if (holder == null) return;
                            View itemView = (View) XposedHelpers.getObjectField(holder, "itemView");
                            applyRepeatableRulesToBoundItem(itemView);
                        }
                    });

            // Hook 3: onViewRecycled → 递归撤销 itemView 子树中所有已应用的规则
            XposedHelpers.findAndHookMethod(adapterClass, "onViewRecycled",
                    viewHolderClass, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object holder = param.args[0];
                            if (holder == null) return;
                            View itemView = (View) XposedHelpers.getObjectField(holder, "itemView");
                            if (itemView == null) return;
                            Activity activity = ViewUtils.getAttachedActivityFromView(itemView);
                            if (activity == null) return;
                            ViewController.getDefault().revokeAllRules(itemView);
                        }
                    });

            sHooksInstalled = true;
            Logger.i(TAG, "RecyclerView adapter hooks installed");
        } catch (Throwable t) {
            Logger.d(TAG, "RecyclerView hook skipped: " + t.getMessage());
        }
    }

    // =========================================================================
    // bindViewHolder 精确规则应用（快速路径）
    // =========================================================================

    /**
     * 在 ViewHolder 绑定后立即检查并应用匹配的 repeatable 规则。
     * <p>
     * 这是消除组件闪现的关键路径：在 RecyclerView 完成 item 布局之前，
     * 优先于 onGlobalLayout 全树扫描，精确匹配目标规则并应用。
     */
    private static void applyRepeatableRulesToBoundItem(View itemRoot) {
        if (itemRoot == null || itemRoot.getVisibility() != View.VISIBLE) return;
        Activity activity = ViewUtils.getAttachedActivityFromView(itemRoot);
        if (activity == null || activity.isFinishing()) return;

        ActRules rules = RuleManager.get().getRules();
        List<RuleRecord> activityRules = rules.get(
                activity.getComponentName().getClassName());
        if (activityRules == null || activityRules.isEmpty()) return;

        for (RuleRecord rule : activityRules) {
            if (!rule.isRepeatable()) continue;
            try {
                MatchSpec spec = RuleMapper.toEngine(rule).getMatchSpec();
                if (!isApplicableToItem(spec, itemRoot)) continue;

                // CARD 和 ELEMENT 模式走统一的导航+验证管线
                View target = navigateAndValidate(itemRoot, spec);
                if (target != null) {
                    ViewController.getDefault().applyRule(target, rule);
                }
            } catch (Throwable t) {
                Logger.w(TAG, "apply bound item rule failed", t);
            }
        }
    }

    /**
     * 检查规则规格是否适用于当前 itemRoot。
     *
     * @param spec     规则匹配规格
     * @param itemRoot item 的根 View
     * @return true 如果该规则应应用于此 item
     */
    private static boolean isApplicableToItem(MatchFields spec, View itemRoot) {
        return spec.getItemPath() != null && spec.getItemPath().length > 0
                && spec.getItemRootClass() != null
                && itemRoot.getClass().getName().equals(spec.getItemRootClass());
    }

    /**
     * 通过 itemPath 导航到目标 View，并进行结构验证。
     * <p>
     * CARD 和 ELEMENT 模式使用完全相同的管线：
     * <ol>
     *   <li>精确索引 + 类名导航</li>
     *   <li>失败 → 纯类名链回退</li>
     *   <li>成功 → isStructuralMatch 验证</li>
     * </ol>
     *
     * @param itemRoot item 的根 View
     * @param spec     规则匹配规格
     * @return 验证通过的目标 View，导航失败或验证失败返回 null
     */
    private static View navigateAndValidate(View itemRoot, MatchFields spec) {
        View target = ViewTraversal.findViewByItemPath(itemRoot, spec.getItemPath(), 0);
        if (target == null) {
            target = ViewTraversal.findViewByClassChain(itemRoot, spec.getItemPath(), 0);
        }
        if (target != null && CompositeMatcher.isStructuralMatch(target, spec, false)) {
            return target;
        }
        return null;
    }

    // =========================================================================
    // 回调接口
    // =========================================================================

    /**
     * LifecycleObserver 实现的回调接口，提供 RecyclerView 钩子所需的
     * 缓存清理和重应用调度能力。
     */
    public interface Delegate {
        /** 清空所有 Activity 级 ViewController 的 Applier 缓存 */
        void clearAllViewControllersCache();

        /** 对所有存活 Activity 调度防抖重应用 */
        void scheduleReapplyForActivities();
    }
}
