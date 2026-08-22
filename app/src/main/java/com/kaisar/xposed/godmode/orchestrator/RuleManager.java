package com.kaisar.xposed.godmode.orchestrator;

import android.os.Handler;
import android.os.Looper;

import com.kaisar.xposed.godmode.engine.event.EventBus;
import com.kaisar.xposed.godmode.engine.event.RulesChangedEvent;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** 当前服务快照状态；不可用不等同于合法空规则。 */
    public enum LoadState { UNAVAILABLE, READY_EMPTY, READY_WITH_RULES }

    private static final long[] RETRY_DELAYS_MS = {250L, 1000L, 3000L};

    private static volatile RuleManager sInstance;

    private final ActRules mActRules = new ActRules();
    private final Logger mLogger = Logger.getLogger(TAG);

    private final String mPackageName;
    private volatile boolean mInitialized;
    private Source mLastSource = Source.PROCESS;
    private volatile LoadState mLoadState = LoadState.UNAVAILABLE;
    private Handler mRetryHandler;
    private RuleServiceClient mServiceClient;
    private int mRetryAttempt;
    private final Runnable mRetryTask = this::retryLoad;
    private final Runnable mBinderDeathListener = this::onBinderDeath;

    // =========================================================================
    // 单例管理
    // =========================================================================

    /**
     * 包名经构造参数注入并声明为 final：实例在发布到 {@code sInstance} 之前
     * 完成全部字段初始化（safe publication），消除 {@link #get()} 在
     * init 序列中间观察到未初始化状态的窗口。
     */
    private RuleManager(String packageName) {
        this.mPackageName = packageName;
    }

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

        sInstance = new RuleManager(packageName);

        RuleServiceClient ipcClient = RuleServiceClient.getDefault();
        sInstance.mServiceClient = ipcClient;
        ipcClient.addBinderDeathListener(sInstance.mBinderDeathListener);

        // 尝试通过 Binder 获取规则
        sInstance.mInitialized = true;
        sInstance.loadFromService(ipcClient, true);

        sInstance.mLogger.i("RuleManager initialized for " + packageName
                + " (source=" + sInstance.mLastSource + ", state=" + sInstance.mLoadState
                + ", rules=" + sInstance.mActRules.size() + ")");
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
        ActRules ownedCopy = copyRules(newRules);
        mActRules.clear();
        mActRules.putAll(ownedCopy);
    }

    // =========================================================================
    // 规则状态查询
    // =========================================================================

    /** 获取当前规则（防御性副本） */
    public synchronized ActRules getRules() {
        return copyRules(mActRules);
    }

    /**
     * 弱一致只读视图 — 供引擎热路径（bind / onGlobalLayout 全量扫描）
     * 零拷贝遍历。
     * <p>
     * 返回内部容器活引用：内容随 Binder 快照弱一致可见（与原防御性拷贝的
     * 一致性级别持平——拷贝本身即弱一致遍历源容器的产物）。
     * <p>
     * 契约：调用方<b>只读</b>、栈内短命使用；不得修改返回结构，不得长期持有。
     * 需要隔离快照或跨线程持有的场景请使用 {@link #getRules()}。
     */
    public ActRules viewRules() {
        return mActRules;
    }

    /** Runtime equality excludes presentation-only metadata such as label and alias. */
    static boolean runtimeContentEquals(RuleRecord left, RuleRecord right) {
        return RuntimeRuleComparator.contentEquals(left, right);
    }

    /** Copies the complete mutable graph owned by an {@link ActRules} value. */
    static ActRules copyRules(ActRules source) {
        ActRules copy = new ActRules(source != null ? source.size() : 0);
        if (source == null) return copy;

        for (Map.Entry<String, List<RuleRecord>> entry : source.entrySet()) {
            String activity = entry.getKey();
            List<RuleRecord> rules = entry.getValue();
            if (activity == null || rules == null) continue;

            List<RuleRecord> ruleCopies = new ArrayList<>(rules.size());
            for (RuleRecord rule : rules) {
                ruleCopies.add(rule != null ? rule.clone() : null);
            }
            copy.put(activity, ruleCopies);
        }
        return copy;
    }

    /** 获取规则最后成功来源 */
    public Source getLastSource() {
        return mLastSource;
    }

    /** 当前规则快照的服务状态。 */
    public LoadState getLoadState() {
        return mLoadState;
    }

    /** 规则是否为空 */
    public boolean hasRules() {
        return !mActRules.isEmpty();
    }

    /**
     * 资源清理。
     */
    public void shutdown() {
        if (mRetryHandler != null) {
            mRetryHandler.removeCallbacks(mRetryTask);
        }
        if (mServiceClient != null) {
            mServiceClient.removeBinderDeathListener(mBinderDeathListener);
        }
        mInitialized = false;
        mLogger.d("RuleManager shut down");
    }

    private void loadFromService(RuleServiceClient client, boolean scheduleRetry) {
        try {
            ActRules binderRules = client.getRules(mPackageName);
            if (client.isConnected() && binderRules != null) {
                acceptServiceSnapshot(binderRules);
                return;
            }
        } catch (Exception e) {
            mLogger.w("Binder getRules failed package=" + mPackageName, e);
        }

        suspendRuntimeForUnavailableService();
        mLoadState = LoadState.UNAVAILABLE;
        mLogger.w("Binder unavailable for " + mPackageName
                + " — retaining last valid rules (" + mActRules.size() + " activities)");
        if (scheduleRetry) scheduleRetry();
    }

    /** Accepts both the initial Binder read and observer ready snapshots. */
    public synchronized void acceptServiceSnapshot(ActRules serviceRules) {
        if (serviceRules == null) {
            mLogger.w("null Binder snapshot for " + mPackageName
                    + ", suspending runtime");
            suspendRuntimeForUnavailableService();
            mLoadState = LoadState.UNAVAILABLE;
            scheduleRetry();
            return;
        }
        mLastSource = Source.BINDER;
        mLoadState = serviceRules.isEmpty()
                ? LoadState.READY_EMPTY : LoadState.READY_WITH_RULES;
        mRetryAttempt = 0;
        if (mRetryHandler != null) mRetryHandler.removeCallbacks(mRetryTask);

        // Publish before replacing the manager snapshot. RuleLifecycleManager
        // must diff against the old runtime rules in order to revoke/apply.
        publishRulesChanged(copyRules(serviceRules));
        // EventBus dispatch is synchronous. Keep a defensive fallback for
        // processes where no lifecycle subscriber is installed (for example,
        // a headless test process) without changing the ordering above.
        replaceRules(serviceRules);
        mLogger.i("rules accepted from Binder for " + mPackageName
                + " (" + serviceRules.size() + " activities, state=" + mLoadState + ")");
    }

    /** JVM-only seam for state tests; production callers must use the event path above. */
    void acceptServiceSnapshotForTest(ActRules serviceRules) {
        if (serviceRules == null) {
            mLoadState = LoadState.UNAVAILABLE;
            return;
        }
        mLastSource = Source.BINDER;
        mLoadState = serviceRules.isEmpty()
                ? LoadState.READY_EMPTY : LoadState.READY_WITH_RULES;
        replaceRules(serviceRules);
    }

    private void scheduleRetry() {
        if (mRetryAttempt >= RETRY_DELAYS_MS.length) return;
        long delay = RETRY_DELAYS_MS[mRetryAttempt++];
        Handler handler = getRetryHandler();
        handler.removeCallbacks(mRetryTask);
        handler.postDelayed(mRetryTask, delay);
    }

    private void retryLoad() {
        if (!mInitialized || mPackageName == null) return;
        loadFromService(RuleServiceClient.getDefault(), true);
    }

    private void onBinderDeath() {
        if (!mInitialized) return;
        suspendRuntimeForUnavailableService();
        mLoadState = LoadState.UNAVAILABLE;
        mLogger.w("Binder died for " + mPackageName
                + " — revoked module-owned effects and scheduling reload");
        mRetryAttempt = 0;
        scheduleRetry();
    }

    private synchronized void suspendRuntimeForUnavailableService() {
        if (mActRules.isEmpty()) return;
        publishRulesChanged(new ActRules());
        replaceRules(new ActRules());
    }

    /**
     * 发布规则变更事件 — 与 {@link EventBus#getDefault()} 单例直连，
     * 不经 inject 层全局容器转发，保持 orchestrator 包对入口层的零依赖。
     */
    private static void publishRulesChanged(ActRules actRules) {
        if (actRules == null) return;
        EventBus.getDefault().post(new RulesChangedEvent(
                sInstance.mPackageName != null ? sInstance.mPackageName : "", actRules));
    }

    private Handler getRetryHandler() {
        if (mRetryHandler == null) {
            mRetryHandler = new Handler(Looper.getMainLooper());
        }
        return mRetryHandler;
    }
}
