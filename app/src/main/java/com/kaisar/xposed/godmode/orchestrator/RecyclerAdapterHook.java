package com.kaisar.xposed.godmode.orchestrator;

import android.app.Activity;
import android.view.View;

import com.kaisar.xposed.godmode.engine.core.PlatformCapabilities;
import com.kaisar.xposed.godmode.engine.util.Logger;

import java.util.Map;
import java.util.WeakHashMap;

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
 * 通过 {@link RecyclerBindingPort} 端口回调，调用方提供缓存清理和重应用调度能力。
 */
public final class RecyclerAdapterHook {

    private static final String TAG = "RecyclerAdapterHook";

    /** Physical hook state is isolated by target ClassLoader. */
    private static final Map<ClassLoader, HookFamilyState> sHookStates = new WeakHashMap<>();
    /** Aggregate flags retained for the existing public diagnostics API. */
    private static volatile boolean sHooksInstalled;
    /** Per-item execution is enabled only when bind and recycle are both hooked. */
    private static volatile boolean sItemHooksEnabled;

    /** Repeatable 业务门控 — 由 AppInjector 装配，空时默认关闭，不抛异常。 */
    private static volatile RepeatableRuleGate sRepeatableGate;

    /** 装配 repeatable 门控实现；空参忽略并保持宿主默认行为。 */
    public static void setRepeatableRuleGate(RepeatableRuleGate gate) {
        if (gate == null) {
            Logger.w(TAG, "setRepeatableRuleGate ignored: null gate");
            return;
        }
        sRepeatableGate = gate;
    }

    private static boolean isRepeatableGateEnabled() {
        RepeatableRuleGate gate = sRepeatableGate;
        if (gate == null) return false;
        try {
            return gate.isRepeatableRulesEnabled();
        } catch (Throwable failure) {
            Logger.w(TAG, "repeatable gate check failed", failure);
            return false;
        }
    }

    /** 绑定协调器 — 由 AppInjector 装配；P2-1 Commit 2 后全部 token/匹配逻辑归属其所有。 */
    private static volatile RecyclerBindingCoordinator sCoordinator;

    /** 装配绑定协调器；空参忽略并保持宿主默认行为。 */
    public static void setBindingCoordinator(RecyclerBindingCoordinator coordinator) {
        if (coordinator == null) {
            Logger.w(TAG, "setBindingCoordinator ignored: null coordinator");
            return;
        }
        sCoordinator = coordinator;
    }

    /** 当前 ViewHolder 绑定逻辑持有者；未装配时 Hook 回调跳过，不抛宿主。 */
    private static RecyclerBindingCoordinator coordinatorOrNull() {
        return sCoordinator;
    }

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
        return sItemHooksEnabled && isRepeatableGateEnabled();
    }

    private static boolean isItemHooksEnabled(HookFamilyState family) {
        return family != null && family.itemHooksEnabled()
                && isRepeatableGateEnabled();
    }

    private static synchronized void recomputeAggregateState() {
        boolean installed = false;
        boolean itemEnabled = false;
        for (HookFamilyState family : sHookStates.values()) {
            if (family == null) continue;
            installed |= family.itemHooksEnabled();
            itemEnabled |= family.itemHooksEnabled();
        }
        sHooksInstalled = installed;
        sItemHooksEnabled = itemEnabled;
    }

    /** Changes the repeatable-rule gate without installing or removing hooks. */
    public static void setRepeatableRulesEnabled(boolean enabled) {
        RepeatableRuleGate gate = sRepeatableGate;
        if (gate == null) {
            Logger.w(TAG, "setRepeatableRulesEnabled ignored: gate not installed");
            return;
        }
        try {
            gate.setRepeatableRulesEnabled(enabled);
        } catch (Throwable failure) {
            Logger.w(TAG, "setRepeatableRulesEnabled failed", failure);
        }
    }

    /**
     * 安装 RecyclerView.Adapter 的三条钩子。
     * <p>
     * 安全可重入：仅首次调用生效，同一进程后续调用直接返回。
     *
     * @param activity 用于获取 ClassLoader 的 Activity 实例
     * @param delegate 端口回调（签名兼容保留，实际运行时回调由协调器持有）
     * @param gate repeatable 门控实例；非空时先装配再安装，空时保持现有装配
     */
    public static synchronized void install(Activity activity, RecyclerBindingPort delegate,
            RepeatableRuleGate gate) {
        if (gate != null) {
            setRepeatableRuleGate(gate);
        }
        install(activity, delegate);
    }

    /**
     * 安装 RecyclerView.Adapter 的三条钩子。
     * <p>
     * 安全可重入：仅首次调用生效，同一进程后续调用直接返回。
     * <p>
     * 绑定运行时逻辑归属 {@link RecyclerBindingCoordinator}（须经
     * {@link #setBindingCoordinator} 预装配，否则 Hook 回调跳过）；
     * {@code delegate} 参数保留以兼容既有签名，实际运行时回调由协调器持有
     * （AppInjector 将二者装配为同一 RLM 单例）。
     *
     * @param activity 用于获取 ClassLoader 的 Activity 实例
     * @param delegate 端口回调（签名兼容保留，实际由协调器持有）
     */
    public static synchronized void install(Activity activity, RecyclerBindingPort delegate) {
        if (activity == null || delegate == null) return;
        if (!PlatformCapabilities.supportsRecyclerViewHook()) return;
        if (coordinatorOrNull() == null) {
            Logger.w(TAG, "install continuing without coordinator: hook callbacks will skip");
        }

        ClassLoader cl = activity.getClassLoader();
        if (cl == null) return;
        HookFamilyState family = sHookStates.get(cl);
        if (family == null) {
            family = new HookFamilyState();
            sHookStates.put(cl, family);
        }
        final HookFamilyState hookFamily = family;
        Class<?> adapterClass;
        Class<?> viewHolderClass = null;
        try {
            adapterClass = family.adapterClass != null ? family.adapterClass
                    : XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$Adapter", cl);
            family.adapterClass = adapterClass;
            try {
                viewHolderClass = family.viewHolderClass != null ? family.viewHolderClass
                        : XposedHelpers.findClass(
                        "androidx.recyclerview.widget.RecyclerView$ViewHolder", cl);
                family.viewHolderClass = viewHolderClass;
            } catch (Throwable missingViewHolder) {
                Logger.d(TAG, "RecyclerView ViewHolder class unavailable", missingViewHolder);
            }

            // Install each hook independently. A ROM may expose only part of the
            // adapter API; successful hooks must not be installed twice on retry.
            if (!hookFamily.notifyInstalled) {
                try {
                    XposedHelpers.findAndHookMethod(adapterClass, "notifyDataSetChanged",
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        RecyclerBindingCoordinator coordinator = coordinatorOrNull();
                                        if (coordinator == null) return;
                                        coordinator.onNotifyChanged();
                                    } catch (Throwable failure) {
                                        Logger.w(TAG, "notifyDataSetChanged hook failed", failure);
                                    }
                                }
                            });
                    hookFamily.notifyInstalled = true;
                } catch (Throwable failure) {
                    Logger.d(TAG, "notifyDataSetChanged hook unavailable", failure);
                }
            }

            if (viewHolderClass != null && !hookFamily.bindInstalled) {
                try {
                    XposedHelpers.findAndHookMethod(adapterClass, "bindViewHolder",
                            viewHolderClass, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!isItemHooksEnabled(hookFamily)) return;
                                RecyclerBindingCoordinator coordinator = coordinatorOrNull();
                                if (coordinator == null) return;
                                Object holder = param.args[0];
                                View itemView = getItemView(holder);
                                if (holder == null || itemView == null) return;
                                coordinator.onBeforeBind(param.thisObject, holder, itemView,
                                        resolveViewType(param.thisObject, (Integer) param.args[1]));
                            } catch (Throwable failure) {
                                Logger.w(TAG, "bindViewHolder before hook failed", failure);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!isItemHooksEnabled(hookFamily)) return;
                                RecyclerBindingCoordinator coordinator = coordinatorOrNull();
                                if (coordinator == null) return;
                                Object holder = param.args[0];
                                View itemView = getItemView(holder);
                                if (holder == null || itemView == null) return;
                                coordinator.onAfterBind(param.thisObject, holder, itemView,
                                        resolveHolderViewType(holder));
                            } catch (Throwable failure) {
                                Logger.w(TAG, "bindViewHolder after hook failed", failure);
                            }
                        }
                    });
                    hookFamily.bindInstalled = true;
                } catch (Throwable failure) {
                    Logger.d(TAG, "bindViewHolder hook unavailable", failure);
                }
            }

            if (viewHolderClass != null && !hookFamily.recycleInstalled) {
                try {
                    // Hook 3: onViewRecycled → 递归撤销 itemView 子树中所有已应用的规则
                    XposedHelpers.findAndHookMethod(adapterClass, "onViewRecycled",
                            viewHolderClass, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // Recycle remains active after the logical gate closes so
                                // effects from the previous binding are still released.
                                if (!hookFamily.itemHooksEnabled()) return;
                                RecyclerBindingCoordinator coordinator = coordinatorOrNull();
                                if (coordinator == null) return;
                                Object holder = param.args[0];
                                View itemView = getItemView(holder);
                                if (holder == null || itemView == null) return;
                                coordinator.onRecycled(param.thisObject, holder, itemView,
                                        resolveHolderViewType(holder));
                            } catch (Throwable failure) {
                                Logger.w(TAG, "onViewRecycled hook failed", failure);
                            }
                        }
                    });
                    hookFamily.recycleInstalled = true;
                } catch (Throwable failure) {
                    Logger.d(TAG, "onViewRecycled hook unavailable", failure);
                }
            }

            // notifyDataSetChanged is an optional cache-invalidation aid. The
            // per-item path is safe only when bind and recycle are paired.
            recomputeAggregateState();
            Logger.i(TAG, hookFamily.itemHooksEnabled()
                    ? (hookFamily.notifyInstalled
                    ? "RecyclerView adapter hooks installed"
                    : "RecyclerView bind/recycle hooks installed; notify hook unavailable")
                    : "RecyclerView adapter hooks partially installed");
        } catch (Throwable t) {
            Logger.d(TAG, "RecyclerView hook skipped", t);
        }
    }

    /**
     * {@link RecyclerBindingPort#ensureInstalled} 的静态转发入口。
     * <p>
     * 纯转发 {@link #install(Activity, RecyclerBindingPort)}；调用方经端口传入。
     */
    public static void ensureInstalled(Activity activity, RecyclerBindingPort delegate) {
        install(activity, delegate);
    }

    // =========================================================================
    // 绑定运行时逻辑（token/epoch/匹配/应用）已搬迁至 RecyclerBindingCoordinator；
    // 本类仅保留拦截转译（参数提取 + 协调器回调）与宿主反射提取 helper。
    // 反射 helper 留在此处的原因：它们经 XposedHelpers 操作宿主类，属于注入适配
    // 手段；协调器接收已提取的 View/viewType 纯数据，从而保持零 de.robv import。
    // =========================================================================

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
            // 反射失败（ROM 无此方法）即视为未知类型返回 -1；bind 热路径不记日志防刷屏。
            return -1;
        }
    }

    private static int resolveHolderViewType(Object holder) {
        if (holder == null) return -1;
        try {
            Object value = XposedHelpers.callMethod(holder, "getItemViewType");
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable ignored) {
            // 同上：未知 holder 类型返回 -1，bind 热路径不记日志。
            return -1;
        }
    }

    /**
     * Invalidates tokens owned by an Activity before its controller is cleared.
     * <p>
     * 兼容转发：实际逻辑归属 {@link RecyclerBindingCoordinator}；未装配时记日志跳过。
     */
    public static void invalidateActivity(Activity activity, ViewController controller) {
        RecyclerBindingCoordinator coordinator = coordinatorOrNull();
        if (coordinator == null) {
            Logger.w(TAG, "invalidateActivity skipped: coordinator not installed");
            return;
        }
        try {
            coordinator.invalidateActivity(activity, controller);
        } catch (Throwable failure) {
            Logger.w(TAG, "invalidateActivity failed", failure);
        }
    }

    /** Hook family state for one target ClassLoader. */
    private static final class HookFamilyState {
        Class<?> adapterClass;
        Class<?> viewHolderClass;
        boolean notifyInstalled;
        boolean bindInstalled;
        boolean recycleInstalled;

        boolean itemHooksEnabled() {
            return bindInstalled && recycleInstalled;
        }
    }
}
