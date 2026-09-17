package com.kaisar.xposed.godmode.orchestrator;

import android.app.Activity;
import android.view.View;

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

/**
 * RecyclerView 绑定协调器 — P2-1 Commit 2 逻辑搬迁目标。
 * <p>
 * 职责：ViewHolder 绑定生命周期（token/epoch 存储）+ repeatable 规则匹配应用
 * （导航/验证/apply/revoke）+ Activity 级 token 清理。零 {@code de.robv} import：
 * 所有 Xposed 拦截与宿主反射提取仍由 {@link RecyclerAdapterHook} 承担，本类只接收
 * 已提取的纯数据（adapter/holder/itemView/viewType），经 {@link RecyclerBindingPort}
 * 回调运行时（缓存清理、ViewController 查询、重应用调度）。依赖方向：
 * Coordinator {@code ->} 端口接口，{@link RecyclerAdapterHook} {@code ->} 本类
 * （单向持有，无循环）。
 * <p>
 * 与 P0 {@code RepeatableRuleGate} 模式同构：运行时逻辑住在本类，注入侧只做转译。
 * <p>
 * 线程：所有方法均可在任意 Hook 回调线程进入；token 存储以实例锁互斥（单例持有，
 * 与原静态 {@code sBindings} 语义等价）。热路径异常一律吞入 {@code Logger.w}，
 * 不抛给宿主。
 */
public final class RecyclerBindingCoordinator {

    private static final String TAG = "RecyclerBindingCoordinator";

    /** 当前 ViewHolder 绑定 token；弱键避免持有已回收 holder。 */
    private final Map<Object, BindingToken> mBindings = new WeakHashMap<>();
    private final AtomicLong mBindingEpoch = new AtomicLong();

    /** 运行时回调（通常为 RuleLifecycleManager 单例），空时跳过不抛宿主。 */
    private volatile RecyclerBindingPort mDelegate;

    /** Repeatable 业务门控 — 由 AppInjector 与 RecyclerAdapterHook 装配同一实例。 */
    private volatile RepeatableRuleGate mRepeatableGate;

    public RecyclerBindingCoordinator(RecyclerBindingPort delegate) {
        mDelegate = delegate;
    }

    /** 装配运行时回调；空参忽略并保持宿主默认行为。 */
    public void setDelegate(RecyclerBindingPort delegate) {
        if (delegate == null) {
            Logger.w(TAG, "setDelegate ignored: null delegate");
            return;
        }
        mDelegate = delegate;
    }

    /** 装配 repeatable 门控实现；空参忽略并保持宿主默认行为。 */
    public void setRepeatableRuleGate(RepeatableRuleGate gate) {
        if (gate == null) {
            Logger.w(TAG, "setRepeatableRuleGate ignored: null gate");
            return;
        }
        mRepeatableGate = gate;
    }

    private boolean isGateEnabled() {
        RepeatableRuleGate gate = mRepeatableGate;
        if (gate == null) return false;
        try {
            return gate.isRepeatableRulesEnabled();
        } catch (Throwable failure) {
            Logger.w(TAG, "repeatable gate check failed", failure);
            return false;
        }
    }

    // =========================================================================
    // 注入侧回调入口 — 参数均为已提取纯数据，无 Xposed 类型
    // =========================================================================

    /** 对应原 notifyDataSetChanged hook 体：门控+规则预检后清缓存并调度重应用。 */
    public void onNotifyChanged() {
        try {
            if (!isGateEnabled()
                    || !RuleManager.isInitialized()
                    || !RuleManager.get().hasRules()) return;
            RecyclerBindingPort delegate = mDelegate;
            if (delegate == null) return;
            delegate.invalidateMatcherCaches();
            delegate.scheduleReapplyForActivities();
        } catch (Throwable failure) {
            Logger.w(TAG, "onNotifyChanged failed", failure);
        }
    }

    /**
     * 对应原 bindViewHolder before hook 体。
     *
     * @param adapter  hook 的 thisObject（RecyclerView.Adapter）
     * @param holder   绑定中的 ViewHolder
     * @param itemView 已提取的 holder.itemView
     * @param positionViewType 已解析的 adapter.getItemViewType(position)，未知为 -1
     */
    public void onBeforeBind(Object adapter, Object holder, View itemView,
            int positionViewType) {
        try {
            if (!isGateEnabled()) return;
            if (holder == null || itemView == null) return;
            BindingToken previous = currentBinding(holder);
            if (previous != null) {
                // 宿主 adapter 拥有下一次写入。立即放弃旧运行时所有权，
                // 但不在 bindViewHolder 执行前恢复旧行值。
                deactivateForRebind(previous);
            }
            BindingToken token = new BindingToken(
                    adapter, holder, itemView, positionViewType,
                    mBindingEpoch.incrementAndGet());
            synchronized (mBindings) {
                mBindings.put(holder, token);
            }
        } catch (Throwable failure) {
            Logger.w(TAG, "onBeforeBind failed", failure);
        }
    }

    /**
     * 对应原 bindViewHolder after hook 体。
     *
     * @param adapter  hook 的 thisObject（RecyclerView.Adapter）
     * @param holder   绑定完成的 ViewHolder
     * @param itemView 已提取的 holder.itemView
     * @param holderViewType 已解析的 holder.getItemViewType()，未知为 -1
     */
    public void onAfterBind(Object adapter, Object holder, View itemView,
            int holderViewType) {
        try {
            if (!isGateEnabled()) return;
            if (!RuleManager.isInitialized()
                    || !RuleManager.get().hasRules()) return;
            BindingToken token = currentBinding(holder);
            if (token == null || !token.matches(adapter, holder,
                    itemView, holderViewType)) {
                return;
            }
            applyToken(token);
        } catch (Throwable failure) {
            Logger.w(TAG, "onAfterBind failed", failure);
        }
    }

    /**
     * 对应原 onViewRecycled hook 体。门控关闭后 recycle 仍保持活跃，
     * 以便释放上一次绑定遗留的效果（原 :266 注释语义保留）。
     */
    public void onRecycled(Object adapter, Object holder, View itemView,
            int holderViewType) {
        try {
            if (itemView == null) return;
            BindingToken token = currentBinding(holder);
            if (token == null || !token.matches(adapter, holder,
                    itemView, holderViewType)) {
                // 其它 adapter 的过期 recycle 不得触碰当前绑定及其 baseline。
                return;
            }
            removeBinding(holder, token);
            token.active = false;
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
            RecyclerBindingPort delegate = mDelegate;
            if (delegate == null) return;
            ViewController controller = delegate.getViewController(activity);
            if (controller == null) return;
            controller.revokeAllRules(itemView, token.bindingEpoch);
        } catch (Throwable failure) {
            Logger.w(TAG, "onRecycled failed", failure);
        }
    }

    /** Invalidates tokens owned by an Activity before its controller is cleared. */
    public void invalidateActivity(Activity activity, ViewController controller) {
        if (activity == null && controller == null) return;
        try {
            synchronized (mBindings) {
                java.util.Iterator<Map.Entry<Object, BindingToken>> iterator =
                        mBindings.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Object, BindingToken> entry = iterator.next();
                    BindingToken token = entry.getValue();
                    View root = token != null ? token.itemRoot.get() : null;
                    boolean owned = token != null
                            && (controller != null && token.controller == controller);
                    if (!owned && activity != null) {
                        owned = token != null && token.activity.get() == activity;
                        if (!owned && root != null) {
                            owned = ViewUtils.getAttachedActivityFromView(root) == activity;
                        }
                    }
                    if (owned) {
                        token.active = false;
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
        } catch (Throwable failure) {
            Logger.w(TAG, "invalidateActivity failed", failure);
        }
    }

    // =========================================================================
    // bindViewHolder 精确规则应用（快速路径）— 原 RAH :339-434 逐行平移
    // =========================================================================

    /**
     * 在 ViewHolder 绑定后立即检查并应用匹配的 repeatable 规则。
     * <p>
     * 这是消除组件闪现的关键路径：在 RecyclerView 完成 item 布局之前，
     * 优先于 onGlobalLayout 全树扫描，精确匹配目标规则并应用。
     */
    private ViewController applyRepeatableRulesToBoundItem(
            View itemRoot, long bindingEpoch, int boundViewType) {
        RecyclerBindingPort delegate = mDelegate;
        if (itemRoot == null || delegate == null
                || itemRoot.getVisibility() != View.VISIBLE) return null;
        Activity activity = ViewUtils.getAttachedActivityFromView(itemRoot);
        if (activity == null || activity.isFinishing()) return null;

        ActRules rules = RuleManager.get().viewRules();
        List<RuleRecord> activityRules = rules.get(
                activity.getComponentName().getClassName());
        if (activityRules == null || activityRules.isEmpty()) return null;

        ViewController controller = delegate.getViewController(activity);
        if (controller == null) return null;
        boolean applied = false;
        for (RuleRecord rule : activityRules) {
            if (!rule.isRepeatable()) continue;
            try {
                MatchSpec spec = rule.getMatchSpec();
                if (!isApplicableToItem(spec, itemRoot, boundViewType)) continue;

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

    private void applyToken(BindingToken token) {
        View itemView = token.itemRoot.get();
        if (itemView == null || !isCurrent(token) || !isGateEnabled()) return;
        ViewController owner = applyRepeatableRulesToBoundItem(
                itemView, token.bindingEpoch, token.viewType);
        if (owner != null) {
            token.controller = owner;
        }
        ensureLifecycleListener(token);
    }

    private void ensureLifecycleListener(BindingToken token) {
        if (token == null || token.lifecycleListener != null) return;
        View itemView = token.itemRoot.get();
        if (itemView == null) return;
        View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (!isCurrent(token) || !token.active
                        || !isGateEnabled()) return;
                // cached-view 免 rebind 复用时此处是规则唯一恢复入口。
                // 原 view.post 会先渲染一帧宿主原始内容全高可见（GONE 规则下
                // 表现为下拉回看时列表突然向上弹跳）；attach 回调先于本帧
                // child 测量执行，同步应用使塌缩直接参与本次测量布局。
                applyToken(token);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                if (!isCurrent(token) || !token.active) return;
                ViewController controller = token.controller;
                if (controller == null) {
                    Activity activity = token.activity.get();
                    RecyclerBindingPort delegate = mDelegate;
                    if (activity != null && delegate != null) {
                        try {
                            controller = delegate.getViewController(activity);
                        } catch (Throwable failure) {
                            Logger.w(TAG, "detach getViewController failed", failure);
                        }
                    }
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

    private void deactivateForRebind(BindingToken token) {
        if (token == null) return;
        token.active = false;
        View itemView = token.itemRoot.get();
        View.OnAttachStateChangeListener lifecycle = token.lifecycleListener;
        if (itemView != null && lifecycle != null) {
            itemView.removeOnAttachStateChangeListener(lifecycle);
        }
        token.lifecycleListener = null;
        ViewController controller = token.controller;
        if (controller == null && itemView != null) {
            Activity activity = token.activity.get();
            RecyclerBindingPort delegate = mDelegate;
            if (activity != null && delegate != null) {
                try {
                    controller = delegate.getViewController(activity);
                } catch (Throwable failure) {
                    Logger.w(TAG, "deactivate getViewController failed", failure);
                }
            }
        }
        if (controller != null && itemView != null) {
            controller.discardBindingEffects(itemView, token.bindingEpoch);
        }
    }

    private BindingToken currentBinding(Object holder) {
        if (holder == null) return null;
        synchronized (mBindings) {
            return mBindings.get(holder);
        }
    }

    private void removeBinding(Object holder, BindingToken token) {
        synchronized (mBindings) {
            if (mBindings.get(holder) == token) mBindings.remove(holder);
        }
    }

    private boolean isCurrent(BindingToken token) {
        if (token == null) return false;
        Object holder = token.holder.get();
        synchronized (mBindings) {
            return holder != null && mBindings.get(holder) == token;
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
        volatile boolean active = true;
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
     * <p>
     * 与批量路径（{@code CompositeMatcher.matchAllViewsBatch}）的过滤条件
     * 保持同构：itemPath 非空 + itemRootClass 类名相等 + viewType 匹配
     * （spec.viewType=0 表示不过滤，向后兼容旧规则）。
     *
     * @param spec          规则匹配规格
     * @param itemRoot      item 的根 View
     * @param boundViewType 当前绑定 holder 的实际 viewType（token 权威值）
     * @return true 如果该规则应应用于此 item
     */
    private static boolean isApplicableToItem(MatchFields spec, View itemRoot,
            int boundViewType) {
        return spec.getItemPath() != null && spec.getItemPath().length > 0
                && spec.getItemRootClass() != null
                && itemRoot.getClass().getName().equals(spec.getItemRootClass())
                && (spec.getInfoFlowViewType() <= 0
                    || spec.getInfoFlowViewType() == boundViewType);
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
}
