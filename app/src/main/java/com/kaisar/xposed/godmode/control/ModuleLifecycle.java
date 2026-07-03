package com.kaisar.xposed.godmode.control;

import com.kaisar.xposed.godmode.engine.util.Logger;

import java.util.EnumMap;

/**
 * 模块生命周期 — 真正编排模块的整体健康状态，而非 CRUD 调度。
 * <p>
 * 状态机：
 * <pre>
 *   INIT → LOADING → READY
 *                    ↓ (观察者断连)
 *                    DEGRADED → (观察者重连) → READY
 *                    ↓ (严重故障)
 *                    ERROR
 * </pre>
 * <p>
 * 集成点：{@link RuleServiceServer} 构造时创建 ModuleLifecycle，
 * 数据加载完成后调用 {@link #markHealthy(Layer)}。
 */
public final class ModuleLifecycle {

    private static final String TAG = "ModuleLifecycle";

    /** 模块状态 */
    public enum State {
        INIT, LOADING, READY, DEGRADED, ERROR
    }

    /** 分层健康标签 */
    public enum Layer {
        CONTROL, DATA, INJECTION, RUNTIME
    }

    /** 分层健康状态 */
    enum Health {
        HEALTHY, DEGRADED, ERROR, UNKNOWN
    }

    private volatile State mState = State.INIT;
    private final EnumMap<Layer, Health> mHealth = new EnumMap<>(Layer.class);

    public ModuleLifecycle() {
        for (Layer layer : Layer.values()) {
            mHealth.put(layer, Health.UNKNOWN);
        }
    }

    // ===== 状态转换 =====

    /**
     * 直接转换到指定状态。
     */
    public void transition(State newState) {
        State old = mState;
        mState = newState;
        Logger.i(TAG, "state: " + old + " → " + newState);
    }

    /**
     * 标记指定层为健康。
     */
    public void markHealthy(Layer layer) {
        mHealth.put(layer, Health.HEALTHY);
        recomputeOverall();
        Logger.i(TAG, layer + " → HEALTHY (overall: " + mState + ")");
    }

    /**
     * 标记指定层为降级。
     */
    public void markDegraded(Layer layer, String reason) {
        mHealth.put(layer, Health.DEGRADED);
        Logger.w(TAG, layer + " → DEGRADED: " + reason);
        recomputeOverall();
    }

    /**
     * 标记指定层为错误。
     */
    public void markError(Layer layer, String reason) {
        mHealth.put(layer, Health.ERROR);
        Logger.e(TAG, layer + " → ERROR: " + reason);
        recomputeOverall();
    }

    // ===== 查询 =====

    /**
     * 获取当前模块整体状态。
     */
    public State getState() {
        return mState;
    }

    /**
     * 获取指定层的健康状态。
     */
    public Health getHealth(Layer layer) {
        Health h = mHealth.get(layer);
        return h != null ? h : Health.UNKNOWN;
    }

    /**
     * 检查模块是否处于可工作状态（READY 或 DEGRADED）。
     */
    public boolean isOperational() {
        return mState == State.READY || mState == State.DEGRADED;
    }

    /**
     * 获取诊断信息。
     */
    public Diagnostics getDiagnostics() {
        return new Diagnostics(mState, new EnumMap<>(mHealth));
    }

    // ===== 内部 =====

    private void recomputeOverall() {
        boolean anyError = false;
        boolean anyDegraded = false;
        boolean allHealthy = true;

        for (Health h : mHealth.values()) {
            if (h == Health.ERROR) anyError = true;
            if (h == Health.DEGRADED) anyDegraded = true;
            if (h != Health.HEALTHY) allHealthy = false;
        }

        if (anyError) {
            transition(State.ERROR);
        } else if (anyDegraded) {
            if (mState != State.INIT) {
                transition(State.DEGRADED);
            }
        } else if (allHealthy && mState == State.LOADING) {
            transition(State.READY);
        }
    }

    // ===== 诊断 DTO =====

    /**
     * 模块诊断信息 — 用于调试和健康检查端点。
     */
    public static final class Diagnostics {
        public final State state;
        public final EnumMap<Layer, Health> health;

        Diagnostics(State state, EnumMap<Layer, Health> health) {
            this.state = state;
            this.health = health;
        }

        @Override
        public String toString() {
            return "Diagnostics{state=" + state + ", health=" + health + '}';
        }
    }
}
