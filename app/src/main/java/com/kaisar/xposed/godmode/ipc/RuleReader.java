package com.kaisar.xposed.godmode.ipc;

import android.os.SharedMemory;
import android.os.RemoteException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.contract.RuleSnapshotParcel;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B5 读门面：规则快照读全家（getAllRules/getRules 双签名 + 世代下限重试）
 * 与快照解码（readSnapshot/closeSnapshotMemory/sha256 校验）及工具栏读
 * 的唯一归属，构造注入 {@link ServiceConnection}。
 *
 * <p>行为零差搬迁自 RuleServiceClient（555-629 区读循环、readSnapshot 长度/
 * 校验/异常语义、toolbar 1180-1189 读分支逐行保留）。世代计数是读链路的
 * 单调水位，由本类唯一持有；观察者失效校验经
 * {@link ObserverCenter#acceptRuleGeneration} 读写本水位，保证单源。
 */
public final class RuleReader {
    private static final String TAG = "RuleServiceClient";

    private final ServiceConnection mServiceConnection;
    private final Gson mGson = new GsonBuilder().create();
    private final AtomicLong mRuleGeneration = new AtomicLong();

    public RuleReader(ServiceConnection serviceConnection) {
        if (serviceConnection == null) throw new IllegalArgumentException("serviceConnection is required");
        mServiceConnection = serviceConnection;
    }

    /** 职责门面直调入口：真单例，直连 {@link ServiceConnection#getDefault()}。 */
    private static volatile RuleReader sInstance;

    public static RuleReader getDefault() {
        RuleReader result = sInstance;
        if (result == null) {
            synchronized (RuleReader.class) {
                result = sInstance;
                if (result == null) {
                    result = new RuleReader(ServiceConnection.getDefault());
                    sInstance = result;
                }
            }
        }
        return result;
    }

    /** 当前读水位，供写入对账（RuleEditorClient.Host）与观察者校验共用。 */
    public long ruleGeneration() {
        return mRuleGeneration.get();
    }

    /** 观察者失效事件的世代准入（原 Client.acceptRuleGeneration 逐行语义）。 */
    boolean acceptRuleGeneration(long epoch, long generation) {
        if (!isCurrentEpoch(epoch)) return false;
        return generation >= mRuleGeneration.get() && isCurrentEpoch(epoch);
    }

    void resetGeneration() {
        mRuleGeneration.set(0L);
    }

    private boolean isCurrentEpoch(long epoch) {
        return mServiceConnection.isCurrentEpoch(epoch);
    }

    /** 连接只读转发（读调用方的可用性判断与读同门面，不经 Client 中转）。 */
    public boolean isConnected() {
        return mServiceConnection.isConnected();
    }

    public boolean hasReadyConnection() {
        return mServiceConnection.hasReadyConnection();
    }

    public AppRules getAllRules() {
        return getAllRulesAtLeast(0L);
    }

    public AppRules getAllRulesAtLeast(long minimumGeneration) {
        for (int attempt = 0; attempt < 3; attempt++) {
            ServiceConnection.Connection c = mServiceConnection.ensureConnection(); if (c == null) return null;
            try {
                RuleSnapshotParcel snapshot = c.service.getAllRulesSnapshot();
                if (snapshot == null || snapshot.status == RuleServiceContract.SNAPSHOT_UNAVAILABLE) {
                    Logger.d(TAG, "getAllRules snapshot unavailable attempt=" + (attempt + 1));
                    closeSnapshotMemory(snapshot);
                    return null;
                }
                if (snapshot.generation < minimumGeneration) {
                    Logger.d(TAG, "getAllRules snapshot stale generation=" + snapshot.generation
                            + " minimum=" + minimumGeneration);
                    closeSnapshotMemory(snapshot);
                    continue;
                }
                AppRules rules = readSnapshot(snapshot, AppRules.class);
                if (rules == null) {
                    Logger.w(TAG, "getAllRules snapshot decoded null generation="
                            + snapshot.generation);
                    return null;
                }
                mRuleGeneration.accumulateAndGet(snapshot.generation, Math::max);
                return rules;
            } catch (RemoteException | RuntimeException e) {
                mServiceConnection.logError("getAllRules", c, ServiceConnection.asRemote(e)); return null;
            }
        }
        Logger.w(TAG, "getAllRules could not satisfy minimum generation=" + minimumGeneration);
        return null;
    }

    public ActRules getRules(String packageName) { return getRulesAtLeast(packageName, 0L); }

    public ActRules getRulesAtLeast(String packageName, long minimumGeneration) {
        for (int attempt = 0; attempt < 3; attempt++) {
            ServiceConnection.Connection c = mServiceConnection.ensureConnection(); if (c == null) return null;
            try {
                RuleSnapshotParcel snapshot = c.service.getRulesSnapshot(packageName);
                if (snapshot == null || snapshot.status == RuleServiceContract.SNAPSHOT_UNAVAILABLE) {
                    Logger.d(TAG, "getRules snapshot unavailable package=" + packageName
                            + " attempt=" + (attempt + 1));
                    closeSnapshotMemory(snapshot);
                    return null;
                }
                if (snapshot.generation < minimumGeneration) {
                    Logger.d(TAG, "getRules snapshot stale package=" + packageName
                            + " generation=" + snapshot.generation
                            + " minimum=" + minimumGeneration);
                    closeSnapshotMemory(snapshot);
                    continue;
                }
                ActRules rules = readSnapshot(snapshot, ActRules.class);
                if (rules == null) {
                    Logger.w(TAG, "getRules snapshot decoded null package=" + packageName
                            + " generation=" + snapshot.generation);
                    return null;
                }
                mRuleGeneration.accumulateAndGet(snapshot.generation, Math::max);
                return rules;
            } catch (RemoteException | RuntimeException e) {
                mServiceConnection.logError("getRules", c, ServiceConnection.asRemote(e));
                return null;
            }
        }
        Logger.w(TAG, "getRules could not satisfy package=" + packageName
                + " minimumGeneration=" + minimumGeneration);
        return null;
    }

    private <T> T readSnapshot(RuleSnapshotParcel snapshot, Class<T> type) {
        if (snapshot == null) {
            Logger.w(TAG, "snapshot read rejected reason=null_snapshot");
            return null;
        }
        if (snapshot.memory == null) {
            Logger.w(TAG, "snapshot read rejected scope=" + snapshot.packageName
                    + " generation=" + snapshot.generation + " reason=no_memory");
            return null;
        }
        ByteBuffer buffer = null;
        try {
            if (snapshot.payloadLength < 0 || snapshot.payloadLength > 8 * 1024 * 1024) {
                Logger.w(TAG, "snapshot read rejected scope=" + snapshot.packageName
                        + " generation=" + snapshot.generation + " reason=invalid_length");
                return null;
            }
            buffer = snapshot.memory.mapReadOnly();
            if (snapshot.payloadLength > buffer.remaining()) {
                Logger.w(TAG, "snapshot read rejected scope=" + snapshot.packageName
                        + " generation=" + snapshot.generation + " reason=short_buffer");
                return null;
            }
            byte[] bytes = new byte[snapshot.payloadLength];
            buffer.get(bytes);
            if (!sha256(bytes).equalsIgnoreCase(snapshot.sha256)) {
                Logger.w(TAG, "snapshot read rejected scope=" + snapshot.packageName
                        + " generation=" + snapshot.generation + " reason=checksum_mismatch");
                return null;
            }
            return mGson.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            Logger.w(TAG, "snapshot read failed scope=" + snapshot.packageName
                    + " generation=" + snapshot.generation, e);
            throw new IllegalStateException(DiagnosticMessages.SNAPSHOT_READ_FAILED_EXCEPTION, e);
        } finally {
            if (buffer != null) SharedMemory.unmap(buffer);
            closeSnapshotMemory(snapshot);
        }
    }

    private void closeSnapshotMemory(RuleSnapshotParcel snapshot) {
        if (snapshot == null || snapshot.memory == null) return;
        try {
            snapshot.memory.close();
        } catch (Exception e) {
            Logger.w(TAG, "snapshot memory close failed scope=" + snapshot.packageName
                    + " generation=" + snapshot.generation, e);
        }
    }

    /** 工具栏读归本门面（写经 RuleEditorClient.mutate，见 B3）。 */
    public String getToolbarHiddenItems(String packageName) {
        ServiceConnection.Connection c = mServiceConnection.ensureConnection(); if (c == null) return null;
        try { return c.service.getToolbarHiddenItems(packageName); }
        catch (RemoteException e) { mServiceConnection.logError("getToolbarHiddenItems", c, e); return null; }
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }
}
