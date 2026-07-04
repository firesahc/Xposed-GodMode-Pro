package com.kaisar.xposed.godmode.control;

import android.os.Process;

import com.kaisar.xposed.godmode.data.DataBusConstants;
import com.kaisar.xposed.godmode.data.RuleSnapshotStore;
import com.kaisar.xposed.godmode.data.SignalStore;
import com.kaisar.xposed.godmode.engine.rule.RuleSnapshot;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 快照发布器 — 双通道发布规则变更。
 * <p>
 * 通道 1: Binder（即时，但依赖进程存活）
 * 通道 2: 文件快照 + 信号（延迟可达，但独立于 Binder 存活）
 * <p>
 * 【关键约束】
 * <ul>
 *   <li>generation 由 {@link RuleRepository} 保证单调递增</li>
 *   <li>只有 {@link RuleSnapshotStore#writeSnapshot} 返回 true 后才能调用 {@link SignalStore#signal}</li>
 *   <li>Binder 通道失败不能阻断文件快照通道</li>
 *   <li>文件快照失败不能回滚已完成的权威内存缓存</li>
 * </ul>
 */
final class SnapshotPublisher {

    private static final String TAG = "SnapshotPublisher";

    private final ObserverRegistry mObserverRegistry;
    private final RuleSnapshotStore mSnapshotStore;
    private final SignalStore mSignalStore;
    private final ExecutorService mFileIoExecutor;

    SnapshotPublisher(ObserverRegistry observerRegistry,
                      RuleSnapshotStore snapshotStore,
                      SignalStore signalStore) {
        this.mObserverRegistry = observerRegistry;
        this.mSnapshotStore = snapshotStore;
        this.mSignalStore = signalStore;
        this.mFileIoExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "snapshot-publisher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 发布规则变更。
     * <p>
     * 通道 1: Binder 即时推送
     * 通道 2: 文件快照 + 信号（异步）
     *
     * @param packageName 发生变更的包名
     * @param rules       最新规则集合
     * @param generation  当前 generation 号（单调递增）
     */
    void publish(String packageName, ActRules rules, long generation) {
        // 通道 1: Binder 即时推送（同步）
        try {
            mObserverRegistry.notifyObserverRuleChanged(packageName, rules);
        } catch (Exception e) {
            Logger.w(TAG, "Binder notify failed for " + packageName
                    + " (non-fatal, file snapshot will follow)", e);
        }

        // 通道 2: 文件快照（异步，不阻塞 Binder 路径）
        final long gen = generation;
        mFileIoExecutor.execute(() -> {
            try {
                RuleSnapshot snapshot = RuleSnapshot.create(
                        packageName, rules, gen, publisherId());

                boolean written = mSnapshotStore.writeSnapshot(packageName, snapshot);
                if (written) {
                    // 只有快照写入成功后才能发信号
                    mSignalStore.signal(DataBusConstants.RULE_CHANGED_PREFIX + packageName);
                    Logger.d(TAG, "file snapshot + signal published for " + packageName
                            + " gen=" + gen);
                } else {
                    Logger.w(TAG, "file snapshot rejected (generation rollback?) for "
                            + packageName + " gen=" + gen);
                }
            } catch (IOException e) {
                Logger.w(TAG, "file snapshot failed for " + packageName, e);
            } catch (Exception e) {
                Logger.w(TAG, "unexpected error in snapshot publish for "
                        + packageName, e);
            }
        });
    }

    /**
     * 关闭，释放线程资源。
     */
    void shutdown() {
        mFileIoExecutor.shutdown();
    }

    private static String publisherId() {
        return "system_server:" + Process.myPid();
    }
}
