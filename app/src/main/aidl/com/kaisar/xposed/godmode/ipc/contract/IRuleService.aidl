package com.kaisar.xposed.godmode.ipc.contract;

import android.os.ParcelFileDescriptor;
import com.kaisar.xposed.godmode.ipc.contract.ILeaseOwner;
import com.kaisar.xposed.godmode.ipc.contract.IRuleObserver;
import com.kaisar.xposed.godmode.ipc.contract.OperationLeaseParcel;
import com.kaisar.xposed.godmode.ipc.contract.ObserverRegistrationParcel;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationRequest;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationResult;
import com.kaisar.xposed.godmode.ipc.contract.RuleSnapshotParcel;
import com.kaisar.xposed.godmode.ipc.contract.ServiceIdentityParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoRequestParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoResultParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;

/** Canonical 6.10 rule service contract. RuleRecord remains the JSON data format. */
interface IRuleService {
    ServiceIdentityParcel getServiceIdentity();

    ObserverRegistrationParcel addObserver(String packageName, in IRuleObserver observer);
    void removeObserver(String packageName, in IRuleObserver observer);

    RuleSnapshotParcel getAllRulesSnapshot();
    RuleSnapshotParcel getRulesSnapshot(String packageName);

    boolean hasLight();
    OperationLeaseParcel openOperation(int operationType, String packageName, in ILeaseOwner owner);
    OperationLeaseParcel closeOperation(String leaseToken, in ILeaseOwner owner);

    RuleMutationResult mutate(in RuleMutationRequest request, in ILeaseOwner owner);
    UndoStateParcel getUndoState(String packageName, in ILeaseOwner owner);
    UndoResultParcel undoLatest(in UndoRequestParcel request, in ILeaseOwner owner);

    ParcelFileDescriptor openImageFileDescriptor(String filePath);
    String getToolbarHiddenItems(String packageName);
    void log(int level, String packageName, long timestamp, String tag, String msg);
}
