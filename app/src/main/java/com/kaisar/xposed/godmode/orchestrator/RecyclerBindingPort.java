package com.kaisar.xposed.godmode.orchestrator;

import android.app.Activity;

/**
 * RecyclerView 绑定端口契约 — 解耦运行时与静态钩子工具的直接依赖。
 * <p>
 * 与 P0 {@link RepeatableRuleGate} 同构：Runtime（{@link RuleLifecycleManager}）
 * 只持本接口，inject 侧（{@link RecyclerBindingSource} 薄适配器，由
 * {@code AppInjector} 装配）负责实现并转发 {@link RecyclerAdapterHook} 静态方法。
 * 依赖方向只允许 orchestrator {@code ->} 接口、inject 装配 {@code ->} 接口实现，
 * 不允许 {@link RuleLifecycleManager} 直接引用 {@link RecyclerAdapterHook} 静态方法。
 * <p>
 * 方法构成 = 运行时回调三方法（{@code invalidateMatcherCaches}/
 * {@code getViewController}/{@code scheduleReapplyForActivities}，取代已删除的
 * {@code RecyclerAdapterHook.Delegate}，由 {@link RuleLifecycleManager} 实现）
 * + 装配/诊断/清理三方法（{@code ensureInstalled}/{@code isItemBindingActive}/
 * {@code invalidateActivity}，对应 {@link RecyclerAdapterHook} 现有
 * {@code install}/{@code isItemHooksEnabled}/{@code invalidateActivity} 静态语义）。
 * <p>
 * P2-1 Commit 2 后 token/epoch/匹配/应用逻辑归属
 * {@link RecyclerBindingCoordinator}，{@link RecyclerAdapterHook} 仅做拦截转译。
 */
public interface RecyclerBindingPort {

    /** 失效所有 Activity 的匹配定位缓存，保留 applier baseline。 */
    void invalidateMatcherCaches();

    /** 返回 item 所属 Activity 的状态所有者。 */
    ViewController getViewController(Activity activity);

    /** 对所有存活 Activity 调度防抖重应用 */
    void scheduleReapplyForActivities();

    /**
     * 确保绑定钩子已安装；对应 {@link RecyclerAdapterHook#install} 幂等语义。
     * 空参由实现方忽略，不抛给宿主。
     */
    void ensureInstalled(Activity activity);

    /** 诊断：bind/recycle 对是否完整且业务门控开启；对应 {@code isItemHooksEnabled}。 */
    boolean isItemBindingActive();

    /**
     * 清理指定 Activity 拥有的绑定 token；对应
     * {@link RecyclerAdapterHook#invalidateActivity} 语义。
     */
    void invalidateActivity(Activity activity, ViewController controller);
}
