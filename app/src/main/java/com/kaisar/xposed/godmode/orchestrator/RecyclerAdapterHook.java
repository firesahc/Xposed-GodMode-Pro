package com.kaisar.xposed.godmode.orchestrator;

import android.app.Activity;
import android.view.View;

import com.kaisar.xposed.godmode.engine.core.PlatformCapabilities;
import com.kaisar.xposed.godmode.engine.matcher.CompositeMatcher;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.rule.MatchFields;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 * 通过 {@link Delegate} 接口回调，调用方提供缓存清理和重应用调度能力。
 */
public final class RecyclerAdapterHook {

    private static final String TAG = "RecyclerAdapterHook";

    /** 确保每个进程只安装一次钩子 */
    private static volatile boolean sHooksInstalled;
    private static volatile boolean sNotifyDataSetChangedHookInstalled;
    private static volatile boolean sBindViewHolderHookInstalled;
    private static volatile boolean sRecycleHookInstalled;
    /** Per-item execution is enabled only when bind and recycle are both hooked. */
    private static volatile boolean sItemHooksEnabled;

    /** 当前 ViewHolder 绑定 token；弱键避免持有已回收 holder。 */
    private static final Map<Object, BindingToken> sBindings = new WeakHashMap<>();
    private static final AtomicLong sBindingEpoch = new AtomicLong();

    private RecyclerAdapterHook() {
        // 工具类不可实例化
    }

    /**
     * 当前进程 RecyclerView Hook 是否已安装。
     */
    public static boolean isInstalled() {
        return sHooksInstalled;
    }

    /** Whether the bind/recycle pair is complete enough for per-item rules. */
    public static boolean isItemHooksEnabled() {
        return sItemHooksEnabled;
    }

    /**
     * 安装 RecyclerView.Adapter 的三条钩子。
     * <p>
     * 安全可重入：仅首次调用生效，同一进程后续调用直接返回。
     *
     * @param activity 用于获取 ClassLoader 的 Activity 实例
     * @param delegate 缓存清理和重应用调度回调（通常由 RuleLifecycleManager 实现）
     */
    public static synchronized void install(Activity activity, Delegate delegate) {
        if (sHooksInstalled) return;
        if (activity == null || delegate == null) return;
        if (!PlatformCapabilities.supportsRecyclerViewHook()) return;

        Class<?> adapterClass;
        Class<?> viewHolderClass = null;
        try {
            ClassLoader cl = activity.getClassLoader();
            adapterClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$Adapter", cl);
            try {
                viewHolderClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$ViewHolder", cl);
            } catch (Throwable missingViewHolder) {
                Logger.d(TAG, "RecyclerView ViewHolder class unavailable", missingViewHolder);
            }

            // Install each hook independently. A ROM may expose only part of the
            // adapter API; successful hooks must not be installed twice on retry.
            if (!sNotifyDataSetChangedHookInstalled) {
                try {
                    XposedHelpers.findAndHookMethod(adapterClass, "notifyDataSetChanged",
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        if (!RuleManager.isInitialized()
                                                || !RuleManager.get().hasRules()) return;
                                        delegate.invalidateMatcherCaches();
                                        delegate.scheduleReapplyForActivities();
                                    } catch (Throwable failure) {
                                        Logger.w(TAG, "notifyDataSetChanged hook failed", failure);
                                    }
                                }
                            });
                    sNotifyDataSetChangedHookInstalled = true;
                } catch (Throwable failure) {
                    Logger.d(TAG, "notifyDataSetChanged hook unavailable", failure);
                }
            }

            if (viewHolderClass != null && !sBindViewHolderHookInstalled) {
                try {
                    XposedHelpers.findAndHookMethod(adapterClass, "bindViewHolder",
                            viewHolderClass, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!sItemHooksEnabled) return;
                                Object holder = param.args[0];
                                View itemView = getItemView(holder);
                                if (holder == null || itemView == null) return;
                                Object adapter = param.thisObject;
                                int position = (Integer) param.args[1];
                                BindingToken previous = currentBinding(holder);
                                if (previous != null) {
                                    // The host adapter owns the next write. Drop the
                                    // old runtime ownership now, but do not restore
                                    // the old row values before bindViewHolder runs.
                                    deactivateForRebind(previous, delegate);
                                }
                                BindingToken token = new BindingToken(
                                        adapter, holder, itemView,
                                        resolveViewType(adapter, position),
                                        sBindingEpoch.incrementAndGet());
                                synchronized (sBindings) {
                                    sBindings.put(holder, token);
                                }
                            } catch (Throwable failure) {
                                Logger.w(TAG, "bindViewHolder before hook failed", failure);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!sItemHooksEnabled) return;
                                if (!RuleManager.isInitialized()
                                        || !RuleManager.get().hasRules()) return;
                                Object holder = param.args[0];
                                View itemView = getItemView(holder);
                                BindingToken token = currentBinding(holder);
                                if (token == null || !token.matches(adapterFor(param), holder,
                                        itemView, resolveHolderViewType(holder))) {
                                    return;
                                }
                                applyToken(token, delegate);
                            } catch (Throwable failure) {
                                Logger.w(TAG, "bindViewHolder after hook failed", failure);
                            }
                        }
                    });
                    sBindViewHolderHookInstalled = true;
                } catch (Throwable failure) {
                    Logger.d(TAG, "bindViewHolder hook unavailable", failure);
                }
            }

            if (viewHolderClass != null && !sRecycleHookInstalled) {
                try {
                    // Hook 3: onViewRecycled → 递归撤销 itemView 子树中所有已应用的规则
                    XposedHelpers.findAndHookMethod(adapterClass, "onViewRecycled",
                            viewHolderClass, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!sItemHooksEnabled) return;
                                Object holder = param.args[0];
                                View itemView = getItemView(holder);
                                if (itemView == null) return;
                                BindingToken token = currentBinding(holder);
                                if (token == null || !token.matches(adapterFor(param), holder,
                                        itemView, resolveHolderViewType(holder))) {
                                    // A stale recycle from another adapter must not touch the
                                    // current binding or its baseline.
                                    return;
                                }
                                removeBinding(holder, token);
                                token.active = false;
                                cancelRetry(token);
                                View.OnAttachStateChangeListener lifecycle = token.lifecycleListener;
                                if (lifecycle != null) {
                                    itemView.removeOnAttachStateChangeListener(lifecycle);
                                    token.lifecycleListener = null;
                                }
                                if (token.controller != null) {
                                    token.controller.revokeAllRules(itemView, token.bindingEpoch);
                                    return;
                                }
                                Activity activity = ViewUtils.getAttachedActivityFromView(itemView);
                                if (activity == null) return;
                                delegate.getViewController(activity)
                                        .revokeAllRules(itemView, token.bindingEpoch);
                            } catch (Throwable failure) {
                                Logger.w(TAG, "onViewRecycled hook failed", failure);
                            }
                        }
                    });
                    sRecycleHookInstalled = true;
                } catch (Throwable failure) {
                    Logger.d(TAG, "onViewRecycled hook unavailable", failure);
                }
            }

            // notifyDataSetChanged is an optional cache-invalidation aid. The
            // per-item path is safe only when bind and recycle are paired.
            sItemHooksEnabled = sBindViewHolderHookInstalled && sRecycleHookInstalled;
            sHooksInstalled = sItemHooksEnabled;
            Logger.i(TAG, sItemHooksEnabled
                    ? (sNotifyDataSetChangedHookInstalled
                    ? "RecyclerView adapter hooks installed"
                    : "RecyclerView bind/recycle hooks installed; notify hook unavailable")
                    : "RecyclerView adapter hooks partially installed");
        } catch (Throwable t) {
            Logger.d(TAG, "RecyclerView hook skipped", t);
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
    private static ViewController applyRepeatableRulesToBoundItem(
            View itemRoot, long bindingEpoch, Delegate delegate) {
        if (itemRoot == null || delegate == null
                || itemRoot.getVisibility() != View.VISIBLE) return null;
        Activity activity = ViewUtils.getAttachedActivityFromView(itemRoot);
        if (activity == null || activity.isFinishing()) return null;

        ActRules rules = RuleManager.get().getRules();
        List<RuleRecord> activityRules = rules.get(
                activity.getComponentName().getClassName());
        if (activityRules == null || activityRules.isEmpty()) return null;

        ViewController controller = delegate.getViewController(activity);
        boolean applied = false;
        for (RuleRecord rule : activityRules) {
            if (!rule.isRepeatable()) continue;
            try {
                MatchSpec spec = rule.getMatchSpec();
                if (!isApplicableToItem(spec, itemRoot)) continue;

                // CARD 和 ELEMENT 模式走统一的导航+验证管线
                View target = navigateAndValidate(itemRoot, spec);
                if (target != null) {
                    applied |= controller.applyRule(target, rule, bindingEpoch);
                }
            } catch (Throwable t) {
                Logger.w(TAG, "apply bound item rule failed", t);
            }
        }
        return applied ? controller : null;
    }

    private static void applyToken(BindingToken token, Delegate delegate) {
        View itemView = token.itemRoot.get();
        if (itemView == null || !isCurrent(token)) return;
        ViewController owner = applyRepeatableRulesToBoundItem(
                itemView, token.bindingEpoch, delegate);
        if (owner != null) {
            token.controller = owner;
        }
        ensureLifecycleListener(token, delegate);
        if (owner != null) return;
        if (!itemView.isAttachedToWindow() && !token.retryScheduled) {
            token.retryScheduled = true;
        }
    }

    private static void ensureLifecycleListener(BindingToken token, Delegate delegate) {
        if (token == null || token.lifecycleListener != null) return;
        View itemView = token.itemRoot.get();
        if (itemView == null) return;
        View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (!isCurrent(token) || !token.active) return;
                token.detached = false;
                token.retryScheduled = false;
                view.post(() -> {
                    if (isCurrent(token) && token.active) applyToken(token, delegate);
                });
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                if (!isCurrent(token) || !token.active) return;
                token.detached = true;
                cancelRetry(token);
                ViewController controller = token.controller;
                if (controller == null) {
                    Activity activity = token.activity.get();
                    if (activity != null) controller = delegate.getViewController(activity);
                }
                if (controller != null) {
                    // Detach is a valid revoke boundary. Epoch filtering keeps
                    // an old detach from restoring a rebound holder.
                    controller.revokeAllRules(view, token.bindingEpoch);
                }
            }
        };
        token.lifecycleListener = listener;
        itemView.addOnAttachStateChangeListener(listener);
    }

    private static void cancelRetry(BindingToken token) {
        if (token == null) return;
        View itemView = token.itemRoot.get();
        View.OnAttachStateChangeListener listener = token.attachListener;
        if (itemView != null && listener != null) {
            itemView.removeOnAttachStateChangeListener(listener);
        }
        token.attachListener = null;
        token.retryScheduled = false;
    }

    private static void deactivateForRebind(BindingToken token, Delegate delegate) {
        if (token == null) return;
        token.active = false;
        cancelRetry(token);
        View itemView = token.itemRoot.get();
        View.OnAttachStateChangeListener lifecycle = token.lifecycleListener;
        if (itemView != null && lifecycle != null) {
            itemView.removeOnAttachStateChangeListener(lifecycle);
        }
        token.lifecycleListener = null;
        ViewController controller = token.controller;
        if (controller == null && itemView != null) {
            Activity activity = token.activity.get();
            if (activity != null) controller = delegate.getViewController(activity);
        }
        if (controller != null && itemView != null) {
            controller.discardBindingEffects(itemView, token.bindingEpoch);
        }
    }

    private static BindingToken currentBinding(Object holder) {
        if (holder == null) return null;
        synchronized (sBindings) {
            return sBindings.get(holder);
        }
    }

    private static void removeBinding(Object holder, BindingToken token) {
        synchronized (sBindings) {
            if (sBindings.get(holder) == token) sBindings.remove(holder);
        }
    }

    private static boolean isCurrent(BindingToken token) {
        if (token == null) return false;
        Object holder = token.holder.get();
        synchronized (sBindings) {
            return holder != null && sBindings.get(holder) == token;
        }
    }

    private static Object adapterFor(XC_MethodHook.MethodHookParam param) {
        return param.thisObject;
    }

    private static View getItemView(Object holder) {
        if (holder == null) return null;
        try {
            Object value = XposedHelpers.getObjectField(holder, "itemView");
            return value instanceof View ? (View) value : null;
        } catch (Throwable t) {
            Logger.w(TAG, "unable to read ViewHolder.itemView", t);
            return null;
        }
    }

    private static int resolveViewType(Object adapter, int position) {
        if (adapter == null || position < 0) return -1;
        try {
            Object value = XposedHelpers.callMethod(adapter, "getItemViewType", position);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int resolveHolderViewType(Object holder) {
        if (holder == null) return -1;
        try {
            Object value = XposedHelpers.callMethod(holder, "getItemViewType");
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Invalidates tokens owned by an Activity before its controller is cleared. */
    public static void invalidateActivity(Activity activity, ViewController controller) {
        if (activity == null && controller == null) return;
        synchronized (sBindings) {
            java.util.Iterator<Map.Entry<Object, BindingToken>> iterator =
                    sBindings.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Object, BindingToken> entry = iterator.next();
                BindingToken token = entry.getValue();
                View root = token != null ? token.itemRoot.get() : null;
                boolean owned = token != null && (controller != null && token.controller == controller);
                if (!owned && activity != null) {
                    owned = token != null && token.activity.get() == activity;
                    if (!owned && root != null) {
                        owned = ViewUtils.getAttachedActivityFromView(root) == activity;
                    }
                }
                if (owned) {
                    token.active = false;
                    cancelRetry(token);
                    if (root != null && token.lifecycleListener != null) {
                        root.removeOnAttachStateChangeListener(token.lifecycleListener);
                        token.lifecycleListener = null;
                    }
                    if (root != null && controller != null) {
                        controller.discardBindingEffects(root, token.bindingEpoch);
                    }
                    iterator.remove();
                }
            }
        }
    }

    private static final class BindingToken {
        final WeakReference<Object> adapter;
        final WeakReference<Object> holder;
        final WeakReference<View> itemRoot;
        final WeakReference<Activity> activity;
        final int viewType;
        final long bindingEpoch;
        volatile ViewController controller;
        volatile boolean retryScheduled;
        volatile boolean active = true;
        volatile boolean detached;
        volatile View.OnAttachStateChangeListener attachListener;
        volatile View.OnAttachStateChangeListener lifecycleListener;

        BindingToken(Object adapter, Object holder, View itemRoot,
                int viewType, long bindingEpoch) {
            this.adapter = new WeakReference<>(adapter);
            this.holder = new WeakReference<>(holder);
            this.itemRoot = new WeakReference<>(itemRoot);
            this.activity = new WeakReference<>(ViewUtils.getAttachedActivityFromView(itemRoot));
            this.viewType = viewType;
            this.bindingEpoch = bindingEpoch;
        }

        boolean matches(Object currentAdapter, Object currentHolder, View currentItemRoot,
                int currentViewType) {
            return adapter.get() == currentAdapter
                    && holder.get() == currentHolder
                    && itemRoot.get() == currentItemRoot
                    && viewType == currentViewType
                    && active;
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
     * RuleLifecycleManager 实现的回调接口，提供 RecyclerView 钩子所需的
     * 缓存清理和重应用调度能力。
     */
    public interface Delegate {
        /** 失效所有 Activity 的匹配定位缓存，保留 applier baseline。 */
        void invalidateMatcherCaches();

        /** 返回 item 所属 Activity 的状态所有者。 */
        ViewController getViewController(Activity activity);

        /** 对所有存活 Activity 调度防抖重应用 */
        void scheduleReapplyForActivities();
    }
}
