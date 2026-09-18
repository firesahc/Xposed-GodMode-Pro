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
 * 向各职责真单例的公开签名转发，保证既有调用方兼容（R2 再删）。
 *
 * <p>按职责门面直调对照：
 * <ul>
 *   <li>连接核/诊断读 → {@link ServiceConnection#getDefault()}</li>
 *   <li>读/快照/toolbar 读 → {@link RuleReader#getDefault()}</li>
 *   <li>观察者/epoch/编辑投影 → {@link ObserverCenter#getDefault()}</li>
 *   <li>维护租约 → {@link LeaseHub#getDefault()}；写入 → {@link RuleEditorClient#getInstance()}（B3）</li>
 *   <li>图片 → {@link ImageStore#getDefault()}；日志 → {@link LogBridge#getDefault()}</li>
 * </ul>
 */
@Deprecated
public final class RuleServiceClient {
    private static volatile RuleServiceClient instance;

    private RuleServiceClient() {
    }

    /** Installs the process-side durable sink for Logger and XServiceManager diagnostics. */
    public void installProcessLogging(String packageName) {
        LogBridge.getDefault().installProcessLogging(packageName);
    }

    public LogBridge getLogBridge() {
        return LogBridge.getDefault();
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
        return RuleReader.getDefault();
    }

    public ObserverCenter getObserverCenter() {
        return ObserverCenter.getDefault();
    }

    public LeaseHub getLeaseHub() {
        return LeaseHub.getDefault();
    }

    public ServiceConnection getServiceConnection() {
        return ServiceConnection.getDefault();
    }

    public String getLastError() { return ServiceConnection.getDefault().getLastError(); }
    public ServiceDiagnostic getServiceDiagnostic() { return ServiceConnection.getDefault().getServiceDiagnostic(); }
    public String getServiceFailureMessage() { return ServiceConnection.getDefault().getServiceFailureMessage(); }
    public int getServiceState() { return ServiceConnection.getDefault().getServiceState(); }
    public boolean isReady() { return ServiceConnection.getDefault().isReady(); }
    public boolean isConnected() { return ServiceConnection.getDefault().isConnected(); }
    public boolean hasReadyConnection() { return ServiceConnection.getDefault().hasReadyConnection(); }
    public boolean awaitReady(long timeoutMs) { return ServiceConnection.getDefault().awaitReady(timeoutMs); }

    public void addBinderDeathListener(Runnable listener) {
        ServiceConnection.getDefault().addBinderDeathListener(listener);
    }
    public void removeBinderDeathListener(Runnable listener) {
        ServiceConnection.getDefault().removeBinderDeathListener(listener);
    }

    public boolean hasLight() { return ServiceConnection.getDefault().hasLight(); }

    public boolean setEditMode(boolean enable) {
        return ObserverCenter.getDefault().setEditMode(enable);
    }

    public boolean isEditModeEnabled() {
        return ObserverCenter.getDefault().isEditModeEnabled();
    }

    public boolean isEditStateKnown() {
        return ObserverCenter.getDefault().isEditStateKnown();
    }

    public boolean isEditModeClosing() {
        return ObserverCenter.getDefault().isEditModeClosing();
    }

    public void addObserver(String packageName, ObserverCallback observer) {
        ObserverCenter.getDefault().addObserver(packageName, observer);
    }

    public void removeObserver(String packageName, ObserverCallback observer) {
        ObserverCenter.getDefault().removeObserver(packageName, observer);
    }

    public boolean isCurrentEditEvent(long epoch, long revision) {
        return ObserverCenter.getDefault().isCurrentEditEvent(epoch, revision);
    }

    public boolean isCurrentRuleEvent(long epoch, long generation) {
        return ObserverCenter.getDefault().isCurrentRuleEvent(epoch, generation);
    }

    public AppRules getAllRules() {
        return RuleReader.getDefault().getAllRules();
    }

    public AppRules getAllRulesAtLeast(long minimumGeneration) {
        return RuleReader.getDefault().getAllRulesAtLeast(minimumGeneration);
    }

    public ActRules getRules(String packageName) { return RuleReader.getDefault().getRules(packageName); }

    public ActRules getRulesAtLeast(String packageName, long minimumGeneration) {
        return RuleReader.getDefault().getRulesAtLeast(packageName, minimumGeneration);
    }

    /** B3：写入 owner 已收归 {@link RuleEditorClient}，本类只保留公开签名转发。 */
    public RuleEditorClient getRuleEditor() {
        return RuleEditorClient.getInstance();
    }

    public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot) {
        return RuleEditorClient.getInstance().writeRule(packageName, rule, snapshot);
    }

    public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot, Bitmap modifiedSnapshot) {
        return RuleEditorClient.getInstance().writeRule(packageName, rule, snapshot, modifiedSnapshot);
    }

    public RuleMutationResult writeUndoableRule(String packageName, RuleRecord rule,
                                                Bitmap snapshot, Bitmap modifiedSnapshot) {
        return RuleEditorClient.getInstance().writeUndoableRule(packageName, rule, snapshot, modifiedSnapshot);
    }
    public boolean updateRule(String packageName, RuleRecord rule) {
        return RuleEditorClient.getInstance().updateRule(packageName, rule);
    }
    public boolean deleteRule(String packageName, RuleRecord rule) {
        return RuleEditorClient.getInstance().deleteRule(packageName, rule);
    }
    public boolean deleteRules(String packageName) {
        return RuleEditorClient.getInstance().deleteRules(packageName);
    }

    public int getLastMutationStatus() {
        return RuleEditorClient.getInstance().getLastMutationStatus();
    }

    public UndoStateParcel getUndoState(String packageName) {
        return RuleEditorClient.getInstance().getUndoState(packageName);
    }

    public UndoResultParcel undoLatest(String packageName, UndoStateParcel expected) {
        return RuleEditorClient.getInstance().undoLatest(packageName, expected);
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
        return ImageStore.getDefault();
    }

    public ParcelFileDescriptor openImageFileDescriptor(String path) {
        return ImageStore.getDefault().openImageFileDescriptor(path);
    }

    /** 工具栏读归 {@link RuleReader}，本类只保留公开签名转发。 */
    public String getToolbarHiddenItems(String packageName) {
        return RuleReader.getDefault().getToolbarHiddenItems(packageName);
    }

    /** 工具栏写归 {@link RuleEditorClient}（set 经 mutate），本类只保留转发。 */
    public boolean setToolbarHiddenItems(String items) {
        return RuleEditorClient.getInstance().setToolbarHiddenItems(items);
    }

    public void forwardLog(int level, String tag, String msg, long timestamp) {
        LogBridge.getDefault().forwardLog(level, tag, msg, timestamp);
    }
    public void forwardLog(String packageName, int level, String tag, String msg, long timestamp) {
        LogBridge.getDefault().forwardLog(packageName, level, tag, msg, timestamp);
    }

    public boolean beginRestore() {
        return LeaseHub.getDefault().beginRestore();
    }
    public boolean beginBackup() {
        return LeaseHub.getDefault().beginBackup();
    }
    public void endRestore() {
        LeaseHub.getDefault().endRestore();
    }
    public void endBackup() {
        LeaseHub.getDefault().endBackup();
    }

    /** 观察者回调 canonical 归属见 {@link ObserverCenter.ObserverCallback}；本接口仅为兼容别名。 */
    public interface ObserverCallback extends ObserverCenter.ObserverCallback {
    }
}
