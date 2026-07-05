package com.kaisar.xposed.godmode.orchestrator;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.ActRules;

/**
 * 规则运行管理器 — 目标 App 进程内的规则状态中枢。
 * <p>
 * 职责：
 * <ul>
 *   <li>维护当前规则内存缓存（{@link ActRules}）</li>
 *   <li>通过 Binder 从 system_server 获取规则</li>
 * </ul>
 * <p>
 * 使用 {@link #init(String)} 初始化单例，{@link #get()} 获取实例。
 * 初始化必须在应用进程启动后尽早调用（在 Activity 创建之前）。
 */
public final class RuleManager {

    private static final String TAG = "RuleManager";

    /** 规则加载来源枚举 */
    public enum Source { BINDER, PROCESS }

    private static volatile RuleManager sInstance;

    private final ActRules mActRules = new ActRules();
    private final Logger mLogger = Logger.getLogger(TAG);

    private String mPackageName;
    private volatile boolean mInitialized;
    private Source mLastSource = Source.PROCESS;

    // =========================================================================
    // 单例管理
    // =========================================================================

    private RuleManager() {}

    /**
     * 初始化 RuleManager 单例。
     * <p>
     * 通过 Binder 从 system_server 获取规则作为主要数据源。
     * 此方法仅设置规则缓存，不触发实际 UI 操作。
     * {@link RuleLifecycleManager} 在 Activity resume 时通过 {@link #getRules()} 获取规则并应用。
     */
    public static synchronized void init(String packageName) {
        if (sInstance != null) {
            Logger.d(TAG, "RuleManager already initialized for " + sInstance.mPackageName);
            return;
        }

        sInstance = new RuleManager();
        sInstance.mPackageName = packageName;

        boolean loadedFromBinder = false;
        RuleServiceClient ipcClient = RuleServiceClient.getDefault();

        // 尝试通过 Binder 获取规则
        try {
            ActRules binderRules = ipcClient.getRules(packageName);
            if (ipcClient.isConnected() && binderRules != null) {
                sInstance.replaceRules(binderRules);
                sInstance.mLastSource = Source.BINDER;
                loadedFromBinder = true;
                sInstance.mLogger.i("rules loaded from Binder for " + packageName
                        + " (" + binderRules.size() + " activities)");
            } else {
                sInstance.mLogger.i("Binder unavailable for " + packageName
                        + " — no fallback, empty rules");
            }
        } catch (Exception e) {
            sInstance.mLogger.w("Binder getRules failed: " + e.getMessage()
                    + " — no fallback, empty rules");
        }

        sInstance.mInitialized = true;
        sInstance.mLogger.i("RuleManager initialized for " + packageName
                + " (source=" + sInstance.mLastSource + ", rules=" + sInstance.mActRules.size() + ")");
    }

    /** 获取 RuleManager 单例（必须在 {@link #init(String)} 之后调用） */
    public static RuleManager get() {
        if (sInstance == null) {
            throw new IllegalStateException("RuleManager not initialized. Call init() first.");
        }
        return sInstance;
    }

    public static boolean isInitialized() {
        return sInstance != null && sInstance.mInitialized;
    }

    // =========================================================================
    // 规则状态管理
    // =========================================================================

    /**
     * 替换当前规则集。
     * <p>
     * 仅更新内存缓存，不触发 UI 操作。
     * 规则变更的 UI 应用由 {@link RuleLifecycleManager#onRulesChanged}
     * 通过 EventBus 订阅 {@code RulesChangedEvent} 驱动。
     */
    public synchronized void replaceRules(ActRules newRules) {
        mActRules.clear();
        if (newRules != null) {
            mActRules.putAll(newRules);
        }
    }

    // =========================================================================
    // 规则状态查询
    // =========================================================================

    /** 获取当前规则（防御性副本） */
    public synchronized ActRules getRules() {
        ActRules copy = new ActRules();
        copy.putAll(mActRules);
        return copy;
    }

    /** 获取规则最后成功来源 */
    public Source getLastSource() {
        return mLastSource;
    }

    /** 规则是否为空 */
    public boolean hasRules() {
        return !mActRules.isEmpty();
    }

    /**
     * 资源清理。
     */
    public void shutdown() {
        mInitialized = false;
        mLogger.d("RuleManager shut down");
    }
}
