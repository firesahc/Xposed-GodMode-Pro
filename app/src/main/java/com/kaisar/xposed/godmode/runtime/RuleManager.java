package com.kaisar.xposed.godmode.runtime;

import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.kaisar.xposed.godmode.data.DataBusConstants;
import com.kaisar.xposed.godmode.data.RuleSnapshotStore;
import com.kaisar.xposed.godmode.engine.rule.RuleSnapshot;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.inject.ModuleBootstrap;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.List;
import java.util.Map;

/**
 * 规则运行管理器 — 目标 App 进程内的规则状态中枢。
 * <p>
 * 职责：
 * <ul>
 *   <li>维护当前规则内存缓存（{@link ActRules}）</li>
 *   <li>三层初始化降级：Binder 即时 → 文件快照 → 空规则</li>
 *   <li>通过 FileObserver 监控信号文件，接收 system_server 的规则更新通知</li>
 *   <li>信号文件触发时将快照内容通过 {@link RulesChangedEvent}
 *       发布到 EventBus，由 {@link com.kaisar.xposed.godmode.runtime.RuleLifecycleManager}
 *       消费并执行实际撤销/应用</li>
 * </ul>
 * <p>
 * 使用 {@link #init(String)} 初始化单例，{@link #get()} 获取实例。
 * 初始化必须在应用进程启动后尽早调用（在 Activity 创建之前）。
 */
public final class RuleManager {

    private static final String TAG = "RuleManager";

    /** 规则加载来源枚举 */
    public enum Source { BINDER, FILE_SNAPSHOT, PROCESS }

    private static volatile RuleManager sInstance;

    private final ActRules mActRules = new ActRules();
    private final Logger mLogger = Logger.getLogger(TAG);

    private String mPackageName;
    private volatile boolean mInitialized;
    private Source mLastSource = Source.PROCESS;

    /** 主线程 Handler — 用于将 EventBus post 切换到主线程（View 操作必须） */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** 信号文件观察者（Binder 断连时的降级触发通道） */
    private FileObserver mSignalObserver;

    // =========================================================================
    // 单例管理
    // =========================================================================

    private RuleManager() {}

    /**
     * 初始化 RuleManager 单例。
     * <p>
     * 三层降级策略：
     * <ol>
     *   <li>尝试通过 Binder 从 system_server 获取规则</li>
     *   <li>Binder 失败或返回空规则 → 文件快照降级</li>
     *   <li>快照不存在或损坏 → 保留空规则，记录日志</li>
     * </ol>
     * <p>
     * 此方法仅设置规则缓存，不触发实际 UI 操作。
     * {@link RuleLifecycleManager} 在 Activity resume 时通过 {@link #getRules()} 获取规则并应用。
     * 后续信号文件通知也会通过 {@link #relaySnapshotToEventBus()} 触发重新应用。
     */
    public static synchronized void init(String packageName) {
        if (sInstance != null) {
            Logger.d(TAG, "RuleManager already initialized for " + sInstance.mPackageName);
            return;
        }

        sInstance = new RuleManager();
        sInstance.mPackageName = packageName;

        // Step 1: 尝试通过 Binder 获取规则
        try {
            ActRules binderRules = RuleServiceClient.getDefault().getRules(packageName);
            if (binderRules != null && !binderRules.isEmpty()) {
                sInstance.replaceRules(binderRules);
                sInstance.mLastSource = Source.BINDER;
                sInstance.mLogger.i("rules loaded from Binder for " + packageName
                        + " (" + binderRules.size() + " activities)");
            } else {
                // Binder 返回空规则 — 合法的无规则状态，继续尝试快照
                sInstance.mLogger.i("Binder returned empty rules for " + packageName
                        + " — falling back to file snapshot");
            }
        } catch (Exception e) {
            sInstance.mLogger.w("Binder getRules failed: " + e.getMessage()
                    + ", falling back to file snapshot");
        }

        // Step 2: Binder 无规则 → 文件快照降级
        if (sInstance.mActRules.isEmpty()) {
            sInstance.refreshFromSnapshot();
        }

        // Step 3: 注册 FileObserver 监控信号文件
        sInstance.installSignalObserver(packageName);

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
    // 文件快照降级
    // =========================================================================

    /**
     * 从文件快照刷新规则（用于初始化路径）。
     * <p>
     * 通过 {@link RuleSnapshotStore} 读取磁盘上的最新快照并更新到缓存。
     * 该方法仅在 {@link #init(String)} 中调用，只更新缓存不触发 UI 操作。
     * 后续的信号文件变更通过 {@link #relaySnapshotToEventBus()} 触发完整重新应用。
     */
    private void refreshFromSnapshot() {
        ActRules rules = readSnapshotAsActRules();
        if (rules != null) {
            replaceRules(rules);
            mLastSource = Source.FILE_SNAPSHOT;
            mLogger.i("rules refreshed from file snapshot for " + mPackageName
                    + " (" + mActRules.size() + " activities)");
        }
    }

    /**
     * 读取文件快照并转为 {@link ActRules}。
     *
     * @return 快照中的规则集，或 {@code null}（快照不存在/损坏）
     */
    @SuppressWarnings("unchecked")
    private ActRules readSnapshotAsActRules() {
        try {
            RuleSnapshot snapshot = RuleSnapshotStore.getDefault().readLatest(mPackageName);
            if (snapshot == null || snapshot.payload == null) {
                mLogger.w("no file snapshot available for " + mPackageName);
                return null;
            }
            ActRules rules = new ActRules();
            for (Map.Entry<String, ?> entry : snapshot.payload.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key != null && value != null) {
                    List<RuleRecord> ruleList = (List<RuleRecord>) value;
                    rules.put(key, ruleList);
                }
            }
            return rules;
        } catch (Exception e) {
            mLogger.w("readSnapshotAsActRules failed for " + mPackageName, e);
            return null;
        }
    }

    /**
     * 将文件快照内容通过 EventBus 发布为 {@link RulesChangedEvent}。
     * <p>
     * 在信号文件触发时调用，由 {@link RuleLifecycleManager} 消费事件，
     * 执行完整的规则差集计算、撤销和应用流程。
     * 直接更新缓存而不发布事件会导致规则变更永远不会应用到 View。
     * <p>
     * 注意：不在此处做缓存对比过滤 — 让 {@link RuleLifecycleManager#onRulesChanged}
     * 通过 {@code equals()}/{@code contentEquals()} 双检测来自行决定是否需要实际应用。
     */
    private void relaySnapshotToEventBus() {
        if (!mInitialized) {
            mLogger.d("relaySnapshotToEventBus: RuleManager not initialized, deferring");
            return;
        }
        ActRules newRules = readSnapshotAsActRules();
        if (newRules == null) return;

        mLogger.d("relaying snapshot to EventBus for " + mPackageName
                + " (" + newRules.size() + " activities)");
        // 发布事件到主线程（不更新缓存 — RuleLifecycleManager.onRulesChanged()
        // 会读取当前缓存作为 oldRules，计算差集后通过 replaceRules() 更新缓存）
        // FileObserver 回调在后台线程，但规则撤销/应用必须在主线程执行 View 操作
        final ActRules rulesToPost = newRules;
        mMainHandler.post(() -> ModuleBootstrap.notifyViewRulesChanged(rulesToPost));
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
    // 信号文件监控
    // =========================================================================

    private void installSignalObserver(String packageName) {
        try {
            String signalPath = DataBusConstants.SIGNAL_DIR;
            mSignalObserver = new FileObserver(signalPath, FileObserver.CLOSE_WRITE) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null && path.startsWith(
                            DataBusConstants.RULE_CHANGED_PREFIX + packageName)) {
                        mLogger.d("signal file changed, relaying to EventBus");
                        // 使用转发到 EventBus 而非直接更新缓存：
                        // RuleLifecycleManager 会完成差集计算、撤销旧规则、应用新规则的完整流程
                        relaySnapshotToEventBus();
                    }
                }
            };
            mSignalObserver.startWatching();
            mLogger.d("FileObserver started for " + signalPath);
        } catch (Exception e) {
            mLogger.w("Failed to install FileObserver", e);
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
     * 停止 FileObserver 监控（资源清理）。
     */
    public void shutdown() {
        if (mSignalObserver != null) {
            try {
                mSignalObserver.stopWatching();
            } catch (Exception e) {
                mLogger.w("Failed to stop FileObserver", e);
            }
            mSignalObserver = null;
        }
        mInitialized = false;
        mLogger.d("RuleManager shut down");
    }
}
