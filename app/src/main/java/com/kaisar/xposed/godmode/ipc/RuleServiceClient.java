package com.kaisar.xposed.godmode.ipc;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;

import com.kaisar.xposed.godmode.editor.RuleEditorClient;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationResult;
import com.kaisar.xposed.godmode.ipc.contract.ServiceIdentityParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoResultParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * B5 收尾后的 deprecated 组装根（垫片）：不再持有任何业务实现，只负责
 * 共享同一 {@link ServiceConnection} 下各职责门面的装配与公开签名转发，
 * 保证既有 16 处调用方兼容。
 *
 * <p>按职责门面直调对照：
 * <ul>
 *   <li>连接核/诊断读 → {@link ServiceConnection}（B1，Client 现为转发，确认保留）</li>
 *   <li>读/快照/toolbar 读 → {@link RuleReader#getDefault()}</li>
 *   <li>观察者/epoch/编辑投影 → {@link ObserverCenter#getDefault()}</li>
 *   <li>维护租约 → {@link LeaseHub#getDefault()}；写入 → {@link RuleEditorClient#getInstance()}（B3）</li>
 *   <li>图片 → {@link ImageStore}（B4）；日志 → {@link LogBridge}（B2）</li>
 * </ul>
 */
@Deprecated
public final class RuleServiceClient {
    private static volatile RuleServiceClient instance;

    private final ServiceConnection mServiceConnection = new ServiceConnection();
    private final LogBridge mLogBridge;
    private final RuleReader mRuleReader;
    private final LeaseHub mLeaseHub;
    private final ObserverCenter mObserverCenter;
    private final ImageStore mImageStore;
    private final RuleEditorClient mRuleEditor;

    private RuleServiceClient() {
        mLogBridge = new LogBridge(mServiceConnection);
        mRuleReader = new RuleReader(mServiceConnection);
        mLeaseHub = new LeaseHub(mServiceConnection);
        // 编辑投影随观察者走：实例归 ObserverCenter，LeaseHub.Listener 由其自注册。
        mObserverCenter = new ObserverCenter(mServiceConnection, mLeaseHub, mRuleReader);
        mImageStore = new ImageStore(mServiceConnection);
        mRuleEditor = new RuleEditorClient(mServiceConnection, mLeaseHub, mImageStore,
                new RuleEditorClient.Host() {
                    @Override public ActRules getRules(String packageName) {
                        return mRuleReader.getRules(packageName);
                    }
                    @Override public String getToolbarHiddenItems(String packageName) {
                        return mRuleReader.getToolbarHiddenItems(packageName);
                    }
                    @Override public long ruleGeneration() { return mRuleReader.ruleGeneration(); }
                    @Override public long editRevision() { return mObserverCenter.editRevision(); }
                });
        mServiceConnection.setConnectionListener(new ServiceConnection.ConnectionListener() {
            @Override public void onConnectionCleared() {
                mLeaseHub.clearLeases();
                mObserverCenter.onConnectionCleared();
            }

            @Override public void onConnectionEstablished(ServiceConnection.Connection connection) {
                mObserverCenter.onConnectionEstablished(connection);
            }
        });
        mServiceConnection.setLogBridge(mLogBridge);
    }

    /** Installs the process-side durable sink for Logger and XServiceManager diagnostics. */
    public void installProcessLogging(String packageName) {
        mLogBridge.installProcessLogging(packageName);
    }

    public LogBridge getLogBridge() {
        return mLogBridge;
    }

    public static RuleServiceClient getDefault() {
        RuleServiceClient result = instance;
        if (result == null) {
            synchronized (RuleServiceClient.class) {
                result = instance;
                if (result == null) {
                    result = new RuleServiceClient();
                    instance = result;
                }
            }
        }
        return result;
    }

    /** 共享职责门面实例（直调入口见各门面 getDefault）。 */
    public RuleReader getRuleReader() {
        return mRuleReader;
    }

    public ObserverCenter getObserverCenter() {
        return mObserverCenter;
    }

    public LeaseHub getLeaseHub() {
        return mLeaseHub;
    }

    public ServiceConnection getServiceConnection() {
        return mServiceConnection;
    }

    public String getLastError() { return mServiceConnection.getLastError(); }
    public ServiceDiagnostic getServiceDiagnostic() { return mServiceConnection.getServiceDiagnostic(); }
    public String getServiceFailureMessage() { return mServiceConnection.getServiceFailureMessage(); }
    public int getServiceState() { return mServiceConnection.getServiceState(); }
    public boolean isReady() { return mServiceConnection.isReady(); }
    public boolean isConnected() { return mServiceConnection.isConnected(); }
    public boolean hasReadyConnection() { return mServiceConnection.hasReadyConnection(); }
    public boolean awaitReady(long timeoutMs) { return mServiceConnection.awaitReady(timeoutMs); }

    public void addBinderDeathListener(Runnable listener) {
        mServiceConnection.addBinderDeathListener(listener);
    }
    public void removeBinderDeathListener(Runnable listener) {
        mServiceConnection.removeBinderDeathListener(listener);
    }

    public boolean hasLight() { return mServiceConnection.hasLight(); }

    public boolean setEditMode(boolean enable) {
        return mObserverCenter.setEditMode(enable);
    }

    public boolean isEditModeEnabled() {
        return mObserverCenter.isEditModeEnabled();
    }

    public boolean isEditStateKnown() {
        return mObserverCenter.isEditStateKnown();
    }

    public boolean isEditModeClosing() {
        return mObserverCenter.isEditModeClosing();
    }

    public void addObserver(String packageName, ObserverCallback observer) {
        mObserverCenter.addObserver(packageName, observer);
    }

    public void removeObserver(String packageName, ObserverCallback observer) {
        mObserverCenter.removeObserver(packageName, observer);
    }

    public boolean isCurrentEditEvent(long epoch, long revision) {
        return mObserverCenter.isCurrentEditEvent(epoch, revision);
    }

    public boolean isCurrentRuleEvent(long epoch, long generation) {
        return mObserverCenter.isCurrentRuleEvent(epoch, generation);
    }

    public AppRules getAllRules() {
        return mRuleReader.getAllRules();
    }

    public AppRules getAllRulesAtLeast(long minimumGeneration) {
        return mRuleReader.getAllRulesAtLeast(minimumGeneration);
    }

    public ActRules getRules(String packageName) { return mRuleReader.getRules(packageName); }

    public ActRules getRulesAtLeast(String packageName, long minimumGeneration) {
        return mRuleReader.getRulesAtLeast(packageName, minimumGeneration);
    }

    /** B3：写入 owner 已收归 {@link RuleEditorClient}，本类只保留公开签名转发。 */
    public RuleEditorClient getRuleEditor() {
        return mRuleEditor;
    }

    public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot) {
        return mRuleEditor.writeRule(packageName, rule, snapshot);
    }

    public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot, Bitmap modifiedSnapshot) {
        return mRuleEditor.writeRule(packageName, rule, snapshot, modifiedSnapshot);
    }

    public RuleMutationResult writeUndoableRule(String packageName, RuleRecord rule,
                                                Bitmap snapshot, Bitmap modifiedSnapshot) {
        return mRuleEditor.writeUndoableRule(packageName, rule, snapshot, modifiedSnapshot);
    }
    public boolean updateRule(String packageName, RuleRecord rule) {
        return mRuleEditor.updateRule(packageName, rule);
    }
    public boolean deleteRule(String packageName, RuleRecord rule) {
        return mRuleEditor.deleteRule(packageName, rule);
    }
    public boolean deleteRules(String packageName) {
        return mRuleEditor.deleteRules(packageName);
    }

    public int getLastMutationStatus() {
        return mRuleEditor.getLastMutationStatus();
    }

    public UndoStateParcel getUndoState(String packageName) {
        return mRuleEditor.getUndoState(packageName);
    }

    public UndoResultParcel undoLatest(String packageName, UndoStateParcel expected) {
        return mRuleEditor.undoLatest(packageName, expected);
    }

    /** 身份校验已收归 ServiceConnection；测试兼容保留包内转发。 */
    static boolean isExpectedIdentity(ServiceIdentityParcel identity) {
        return ServiceConnection.isExpectedIdentity(identity);
    }

    /** 对账谓词已收归 RuleEditorClient；测试兼容保留包内转发。 */
    static boolean containsCommittedRule(ActRules rules, RuleRecord expected,
                                         boolean hadMainImage, boolean hadModifiedImage) {
        return RuleEditorClient.containsCommittedRule(rules, expected,
                hadMainImage, hadModifiedImage);
    }

    /** 对账谓词已收归 RuleEditorClient；测试兼容保留包内转发。 */
    static boolean containsSlot(ActRules rules, RuleRecord expected) {
        return RuleEditorClient.containsSlot(rules, expected);
    }

    /** B4：图片库已收归 {@link ImageStore}，本类只保留 FD 公开签名转发。 */
    public ImageStore getImageStore() {
        return mImageStore;
    }

    public ParcelFileDescriptor openImageFileDescriptor(String path) {
        return mImageStore.openImageFileDescriptor(path);
    }

    /** 工具栏读归 {@link RuleReader}，本类只保留公开签名转发。 */
    public String getToolbarHiddenItems(String packageName) {
        return mRuleReader.getToolbarHiddenItems(packageName);
    }

    /** 工具栏写归 {@link RuleEditorClient}（set 经 mutate），本类只保留转发。 */
    public boolean setToolbarHiddenItems(String items) {
        return mRuleEditor.setToolbarHiddenItems(items);
    }

    public void forwardLog(int level, String tag, String msg, long timestamp) {
        mLogBridge.forwardLog(level, tag, msg, timestamp);
    }
    public void forwardLog(String packageName, int level, String tag, String msg, long timestamp) {
        mLogBridge.forwardLog(packageName, level, tag, msg, timestamp);
    }

    public boolean beginRestore() {
        return mLeaseHub.beginRestore();
    }
    public boolean beginBackup() {
        return mLeaseHub.beginBackup();
    }
    public void endRestore() {
        mLeaseHub.endRestore();
    }
    public void endBackup() {
        mLeaseHub.endBackup();
    }

    /** 观察者回调 canonical 归属见 {@link ObserverCenter.ObserverCallback}；本接口仅为兼容别名。 */
    public interface ObserverCallback extends ObserverCenter.ObserverCallback {
    }
}
