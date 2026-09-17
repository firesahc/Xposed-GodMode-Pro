package com.kaisar.xposed.godmode.orchestrator;

import android.app.Activity;

import com.kaisar.xposed.godmode.engine.util.Logger;

/**
 * {@link RecyclerBindingPort} 薄适配器（方案 a）。
 * <p>
 * inject 侧实现：装配/诊断二方法直接转发 {@link RecyclerAdapterHook} 静态方法，
 * 清理方法转发 {@link RecyclerBindingCoordinator}（P2-1 Commit 2 后 token 逻辑
 * 归属协调器）；端口回调三方法转发给构造时传入的运行时委托（通常为
 * {@link RuleLifecycleManager} 单例）。无任何业务状态，仅持有 final 引用；
 * 空引用守卫后跳过，保持宿主默认行为，不抛异常。
 */
public final class RecyclerBindingSource implements RecyclerBindingPort {

    private static final String TAG = "RecyclerBindingSource";

    private final RecyclerBindingPort mDelegate;
    private final RecyclerBindingCoordinator mCoordinator;

    public RecyclerBindingSource(RecyclerBindingPort delegate,
            RecyclerBindingCoordinator coordinator) {
        mDelegate = delegate;
        mCoordinator = coordinator;
    }

    @Override
    public void ensureInstalled(Activity activity) {
        if (activity == null) return;
        RecyclerBindingPort delegate = mDelegate;
        if (delegate == null) {
            Logger.w(TAG, "ensureInstalled skipped: null delegate");
            return;
        }
        try {
            RecyclerAdapterHook.ensureInstalled(activity, delegate);
        } catch (Throwable failure) {
            Logger.w(TAG, "ensureInstalled failed", failure);
        }
    }

    @Override
    public boolean isItemBindingActive() {
        try {
            return RecyclerAdapterHook.isItemHooksEnabled();
        } catch (Throwable failure) {
            Logger.w(TAG, "isItemBindingActive failed", failure);
            return false;
        }
    }

    @Override
    public void invalidateActivity(Activity activity, ViewController controller) {
        RecyclerBindingCoordinator coordinator = mCoordinator;
        if (coordinator == null) {
            Logger.w(TAG, "invalidateActivity skipped: null coordinator");
            return;
        }
        try {
            coordinator.invalidateActivity(activity, controller);
        } catch (Throwable failure) {
            Logger.w(TAG, "invalidateActivity failed", failure);
        }
    }

    @Override
    public void invalidateMatcherCaches() {
        RecyclerBindingPort delegate = mDelegate;
        if (delegate == null) {
            Logger.w(TAG, "invalidateMatcherCaches skipped: null delegate");
            return;
        }
        try {
            delegate.invalidateMatcherCaches();
        } catch (Throwable failure) {
            Logger.w(TAG, "invalidateMatcherCaches failed", failure);
        }
    }

    @Override
    public ViewController getViewController(Activity activity) {
        RecyclerBindingPort delegate = mDelegate;
        if (delegate == null) return null;
        try {
            return delegate.getViewController(activity);
        } catch (Throwable failure) {
            Logger.w(TAG, "getViewController failed", failure);
            return null;
        }
    }

    @Override
    public void scheduleReapplyForActivities() {
        RecyclerBindingPort delegate = mDelegate;
        if (delegate == null) {
            Logger.w(TAG, "scheduleReapplyForActivities skipped: null delegate");
            return;
        }
        try {
            delegate.scheduleReapplyForActivities();
        } catch (Throwable failure) {
            Logger.w(TAG, "scheduleReapplyForActivities failed", failure);
        }
    }
}
