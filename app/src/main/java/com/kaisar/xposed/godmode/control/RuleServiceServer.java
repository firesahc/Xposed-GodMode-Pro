package com.kaisar.xposed.godmode.control;

import static com.kaisar.xposed.godmode.engine.util.GmConstants.DATA_DIR;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.engine.util.Closeables;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.RuleServiceContract;
import com.kaisar.xposed.godmode.ipc.contract.ILeaseOwner;
import com.kaisar.xposed.godmode.ipc.contract.IRuleObserver;
import com.kaisar.xposed.godmode.ipc.contract.IRuleService;
import com.kaisar.xposed.godmode.ipc.contract.ObserverRegistrationParcel;
import com.kaisar.xposed.godmode.ipc.contract.OperationLeaseParcel;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationRequest;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationResult;
import com.kaisar.xposed.godmode.ipc.contract.RuleSnapshotParcel;
import com.kaisar.xposed.godmode.ipc.contract.ServiceIdentityParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoRequestParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoResultParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** The single system_server authority for rule, asset and toolbar mutations. */
public final class RuleServiceServer extends IRuleService.Stub {
    private final PermissionEnforcer mPermissionEnforcer;
    private final RuleRepository mRepository;
    private final ObserverRegistry mObserverRegistry;
    private final OperationLeaseController mOperationLeases;
    private final EditorHistoryOwnerRegistry mHistoryOwners;
    private final ModuleLifecycle mLifecycle;
    private final Logger mLogger;
    private final IncomingImageReader mIncomingImageReader;
    private final Gson mGson = new GsonBuilder().setPrettyPrinting().create();
    private volatile boolean mStarted;
    private volatile String mToolbarHiddenItems = "";
    private volatile boolean mToolbarConfigurationPresent;

    public RuleServiceServer(Context context) {
        mLogger = Logger.getLogger("RuleServiceServer");
        Logger.setWriter((level, tag, msg, timestamp) ->
                GodModeLog.write(level, "system_server", tag, msg, timestamp));
        mIncomingImageReader = new IncomingImageReader(mLogger);
        mPermissionEnforcer = new PermissionEnforcer(context);
        mObserverRegistry = new ObserverRegistry(Logger.getLogger("ObserverRegistry"));
        mLifecycle = new ModuleLifecycle(ModuleLifecycle.Layer.CONTROL);
        mLifecycle.transition(ModuleLifecycle.State.LOADING);
        mRepository = new RuleRepository(mGson, Logger.getLogger("RuleRepository"),
                mObserverRegistry);
        BinderOwnerDeathMonitor ownerDeaths = new BinderOwnerDeathMonitor();
        mHistoryOwners = new EditorHistoryOwnerRegistry(ownerDeaths,
                new EditorHistoryOwnerRegistry.ReleaseSink() {
                    @Override public void releaseScope(RuleRepository.UndoScope scope) {
                        mRepository.releaseUndo(scope);
                    }

                    @Override public void releaseOwner(String ownerId, int callingUid) {
                        mRepository.releaseUndoOwner(ownerId, callingUid);
                        mLogger.i("editor undo history released after owner death uid="
                                + callingUid);
                    }
                });
        mOperationLeases = new OperationLeaseController(ownerDeaths,
                new OperationLeaseController.Listener() {
                    @Override public void onEditRevisionClosed(long editRevision) {
                        mHistoryOwners.closeRevision(editRevision);
                    }

                    @Override public void onEditTransition(
                            OperationLeaseController.EditTransition transition) {
                        mObserverRegistry.notifyObserverEditModeChanged(
                                transition.enabled, transition.revision);
                    }

                    @Override public void onOwnerDied(OperationLeaseController.LeaseInfo lease) {
                        mLogger.w("operation owner died type=" + operationName(lease.type)
                                + " package=" + lease.packageName
                                + " uid=" + lease.callingUid);
                    }
                });
        cleanupStaleIncomingFiles();
        mRepository.loadAll(
                () -> {
                    mLifecycle.markHealthy(ModuleLifecycle.Layer.CONTROL);
                    mObserverRegistry.notifyRulesLoaded(mRepository.getGeneration());
                },
                () -> mLifecycle.markError(ModuleLifecycle.Layer.CONTROL,
                        "load rules failed"));
        mToolbarHiddenItems = mRepository.loadToolbarHiddenItems();
        mToolbarConfigurationPresent = mRepository.hasToolbarHiddenItems();
        mStarted = true;
    }

    @Override public ServiceIdentityParcel getServiceIdentity() {
        return new ServiceIdentityParcel(RuleServiceContract.PROTOCOL_VERSION,
                BuildConfig.VERSION_CODE,
                RuleServiceContract.CONTRACT_FINGERPRINT, currentServiceState());
    }

    private int currentServiceState() {
        if (!mStarted || !mRepository.isDataLoaded()) return RuleServiceContract.STARTING;
        if (mLifecycle.getState() == ModuleLifecycle.State.ERROR) return RuleServiceContract.FAILED;
        return mLifecycle.isOperational() ? RuleServiceContract.READY
                : RuleServiceContract.STARTING;
    }

    @Override public boolean hasLight() throws RemoteException {
        mPermissionEnforcer.enforcePermission("has light fail permission denied");
        return true;
    }

    @Override public OperationLeaseParcel openOperation(int operationType, String packageName,
                                                        ILeaseOwner owner)
            throws RemoteException {
        if (!areRulesReady()) {
            mLogger.w("operation rejected type=" + operationName(operationType)
                    + " reason=service_not_ready");
            return leaseResult(operationType,
                    RuleServiceContract.RESULT_BUSY, "rule service is not ready");
        }
        if (owner == null) {
            mLogger.w("operation rejected type=" + operationName(operationType)
                    + " reason=missing_owner");
            return leaseResult(operationType,
                    RuleServiceContract.RESULT_INVALID, "operation owner is required");
        }
        int callingUid = Binder.getCallingUid();
        boolean moduleCaller = mPermissionEnforcer.isModuleUid(callingUid);
        boolean ownsPackage = PackageNameValidator.isValid(packageName)
                && mPermissionEnforcer.uidOwnsPackage(callingUid, packageName);
        OperationCoordinator.OpenResult opened = mOperationLeases.open(operationType, packageName,
                callingUid, moduleCaller, ownsPackage, owner.asBinder());
        if (opened.status != RuleServiceContract.RESULT_COMMITTED) {
            mLogger.w("operation rejected type=" + operationName(operationType)
                    + " package=" + packageName + " uid=" + callingUid
                    + " status=" + resultName(opened.status)
                    + " reason=" + opened.message);
            return leaseResult(operationType, opened.status, opened.message);
        }
        mLogger.i("operation opened type=" + operationName(operationType)
                + " package=" + packageName + " uid=" + callingUid
                + " editRevision=" + opened.editRevision);
        return new OperationLeaseParcel(opened.status, operationType, opened.token,
                opened.message);
    }

    @Override public OperationLeaseParcel closeOperation(String leaseToken, ILeaseOwner owner)
            throws RemoteException {
        if (owner == null || leaseToken == null) {
            mLogger.w("operation close rejected reason=missing_owner_or_token");
            return leaseResult(0,
                    RuleServiceContract.RESULT_INVALID, "operation owner and token are required");
        }
        OperationLeaseController.LeaseInfo lease = mOperationLeases.leaseInfo(leaseToken);
        if (lease == null) {
            mLogger.d("operation close ignored: lease already released");
            return new OperationLeaseParcel(RuleServiceContract.RESULT_NO_CHANGE, 0, null,
                    "lease already released");
        }
        int type = lease.type;
        try {
            OperationLeaseController.CloseOutcome outcome = mOperationLeases.close(leaseToken,
                    owner.asBinder(), Binder.getCallingUid(), OperationCoordinator.CLOSE_TIMEOUT_MS);
            if (!outcome.result.closed) {
                mLogger.w("operation close busy type=" + operationName(type)
                        + " package=" + lease.packageName
                        + " uid=" + lease.callingUid);
                return leaseResult(type, RuleServiceContract.RESULT_BUSY,
                        "operation is still active");
            }
            mLogger.i("operation closed type=" + operationName(type)
                    + " package=" + lease.packageName
                    + " uid=" + lease.callingUid);
            return new OperationLeaseParcel(RuleServiceContract.RESULT_COMMITTED, type, null,
                    "lease released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mLogger.w("operation close interrupted type=" + operationName(type)
                    + " package=" + lease.packageName
                    + " uid=" + lease.callingUid, e);
            return leaseResult(type, RuleServiceContract.RESULT_BUSY,
                    "interrupted while closing operation");
        }
    }

    @Override public ObserverRegistrationParcel addObserver(String packageName,
                                                            IRuleObserver observer)
            throws RemoteException {
        enforceObserverScope(packageName, "register observer");
        if (!mStarted || observer == null) {
            mLogger.w("observer registration rejected package=" + packageName
                    + " reason=service_not_ready_or_null_observer");
            return new ObserverRegistrationParcel(
                    RuleServiceContract.RESULT_BUSY, false, 0L, mRepository.getGeneration(),
                    "rule service is not ready");
        }
        boolean registered = mObserverRegistry.addObserver(packageName, observer);
        OperationCoordinator.EditState state = mOperationLeases.editState();
        mLogger.d("observer " + (registered ? "registered" : "already registered")
                + " package=" + packageName + " generation=" + mRepository.getGeneration());
        return new ObserverRegistrationParcel(registered
                ? RuleServiceContract.RESULT_COMMITTED : RuleServiceContract.RESULT_NO_CHANGE,
                state.enabled, state.revision, mRepository.getGeneration(),
                registered ? "observer registered" : "observer already registered");
    }

    @Override public void removeObserver(String packageName, IRuleObserver observer)
            throws RemoteException {
        enforceObserverScope(packageName, "unregister observer");
        if (mObserverRegistry.removeObserver(observer)) {
            mLogger.d("observer unregistered package=" + packageName);
        } else if (observer == null) {
            mLogger.w("observer unregister rejected package=" + packageName
                    + " reason=null_observer");
        }
    }

    @Override public RuleSnapshotParcel getAllRulesSnapshot() throws RemoteException {
        mPermissionEnforcer.enforcePermission("get all rules fail permission denied");
        if (!areRulesReady()) {
            mLogger.d("snapshot unavailable scope=global reason=service_not_ready");
            return unavailableSnapshot(RuleServiceContract.GLOBAL_SCOPE);
        }
        RuleRepository.RepositorySnapshot<AppRules> snapshot =
                mRepository.getAllRulesSnapshot();
        return createSnapshot("all", RuleServiceContract.GLOBAL_SCOPE, snapshot.value,
                snapshot.generation);
    }

    @Override public RuleSnapshotParcel getRulesSnapshot(String packageName)
            throws RemoteException {
        enforcePackageOrManager(packageName, "get rules");
        if (!areRulesReady()) {
            mLogger.d("snapshot unavailable scope=" + packageName
                    + " reason=service_not_ready");
            return unavailableSnapshot(packageName);
        }
        RuleRepository.RepositorySnapshot<ActRules> snapshot =
                mRepository.getRulesSnapshot(packageName);
        return createSnapshot(packageName, packageName, snapshot.value, snapshot.generation);
    }

    @Override public RuleMutationResult mutate(RuleMutationRequest request, ILeaseOwner owner) {
        RuleMutationResult result = null;
        try {
            result = mutateInternal(request, owner);
            return result;
        } finally {
            logMutationResult(request, result);
            closeInputFds(request);
        }
    }

    private void logMutationResult(RuleMutationRequest request, RuleMutationResult result) {
        if (result == null) {
            mLogger.e("mutation completed without result requestId="
                    + (request == null ? "null" : request.requestId));
            return;
        }
        String message = result.message == null ? "" : " reason=" + result.message;
        String line = "mutation complete operation="
                + mutationName(request == null ? 0 : request.operation)
                + " requestId=" + result.requestId
                + " package=" + result.packageName
                + " status=" + resultName(result.status)
                + " generation=" + result.generation + message;
        if (result.status == RuleServiceContract.RESULT_COMMITTED
                || result.status == RuleServiceContract.RESULT_NO_CHANGE) {
            mLogger.i(line);
        } else if (result.status == RuleServiceContract.RESULT_WRITE_FAILED) {
            mLogger.e(line);
        } else {
            mLogger.w(line);
        }
    }

    private RuleMutationResult mutateInternal(RuleMutationRequest request, ILeaseOwner owner) {
        if (request == null || owner == null) return mutationResult(null, null,
                RuleServiceContract.RESULT_INVALID, null, "mutation request is required");
        boolean toolbarMutation = request.operation == RuleServiceContract.MUTATION_SET_TOOLBAR;
        if (toolbarMutation) {
            if (!RuleServiceContract.GLOBAL_SCOPE.equals(request.packageName)
                    || request.mainImageFd != null || request.modifiedImageFd != null
                    || !mPermissionEnforcer.isModuleUid(Binder.getCallingUid())) {
                return mutationResult(request.requestId, request.packageName,
                        RuleServiceContract.RESULT_REJECTED, null,
                        "toolbar mutation requires manager global scope");
            }
        } else if (!PackageNameValidator.isValid(request.packageName)) {
            return mutationResult(request.requestId, request.packageName,
                    RuleServiceContract.RESULT_INVALID, null, "invalid package name");
        }

        int callingUid = Binder.getCallingUid();
        if (!toolbarMutation && !mPermissionEnforcer.isModuleUid(callingUid)
                && !mPermissionEnforcer.uidOwnsPackage(callingUid, request.packageName)) {
            return mutationResult(request.requestId, request.packageName,
                    RuleServiceContract.RESULT_REJECTED, null,
                    "caller no longer owns the mutation package");
        }

        RuleRecord rule;
        try {
            rule = request.ruleJson == null ? null
                    : mGson.fromJson(request.ruleJson, RuleRecord.class);
        } catch (RuntimeException e) {
            return mutationResult(request.requestId, request.packageName,
                    RuleServiceContract.RESULT_INVALID, null, "invalid rule JSON");
        }
        if (rule != null && !request.packageName.equals(rule.packageName)) {
            return mutationResult(request.requestId, request.packageName,
                    RuleServiceContract.RESULT_INVALID, null,
                    "rule package does not match request");
        }
        if (requiresRule(request.operation) && rule == null) {
            return mutationResult(request.requestId, request.packageName,
                    RuleServiceContract.RESULT_INVALID, null, "mutation requires a rule");
        }

        OperationLeaseController.PersistencePermit permit = mOperationLeases.acquirePersistence(
                request.leaseToken,
                owner.asBinder(), callingUid, request.packageName);
        if (permit == null) return mutationResult(request.requestId, request.packageName,
                RuleServiceContract.RESULT_BUSY, null,
                "write lease is not authorized for this mutation");
        OperationCoordinator.Access access = permit.access();

        mLogger.d("fd mutate begin requestId=" + request.requestId
                + " package=" + request.packageName + " uid=" + callingUid
                + " mainFd=" + (request.mainImageFd != null)
                + " modifiedFd=" + (request.modifiedImageFd != null));

        Bitmap mainBitmap = null;
        Bitmap modifiedBitmap = null;
        try {
            IncomingImageReader.ReadResult main = readIncomingImage(
                    request.mainImageFd, request.packageName,
                    request.requestId, "main");
            IncomingImageReader.ReadResult modified = readIncomingImage(request.modifiedImageFd,
                    request.packageName, request.requestId, "modified");
            if (!main.valid || !modified.valid) {
                return mutationResult(request.requestId, request.packageName,
                        RuleServiceContract.RESULT_WRITE_FAILED, null,
                        "input image is missing, truncated, oversized or invalid");
            }
            mainBitmap = main.bitmap;
            modifiedBitmap = modified.bitmap;
            if (toolbarMutation && (mainBitmap != null || modifiedBitmap != null)) {
                return mutationResult(request.requestId, request.packageName,
                        RuleServiceContract.RESULT_INVALID, null,
                        "toolbar mutation cannot consume rule assets");
            }
            if (request.captureUndo && (!access.editorMutation
                    || (request.operation != RuleServiceContract.MUTATION_WRITE
                    && request.operation != RuleServiceContract.MUTATION_UPDATE))) {
                return mutationResult(request.requestId, request.packageName,
                        RuleServiceContract.RESULT_REJECTED, null,
                        "undo capture requires an active target editor write");
            }
            switch (request.operation) {
                case RuleServiceContract.MUTATION_WRITE:
                    return writeRuleMutation(request, owner.asBinder(), access, rule,
                            mainBitmap, modifiedBitmap, true);
                case RuleServiceContract.MUTATION_UPDATE:
                    return writeRuleMutation(request, owner.asBinder(), access, rule,
                            mainBitmap, modifiedBitmap, false);
                case RuleServiceContract.MUTATION_DELETE:
                    return mapMutationResult(request.requestId,
                            mRepository.mutateDelete(request.packageName, rule));
                case RuleServiceContract.MUTATION_DELETE_ALL:
                    return mapMutationResult(request.requestId,
                            mRepository.mutateDeleteAll(request.packageName));
                case RuleServiceContract.MUTATION_SET_TOOLBAR:
                    String toolbarItems = request.value == null ? "" : request.value;
                    if (!mRepository.persistToolbarHiddenItems(toolbarItems)) {
                        return mutationResult(request.requestId, request.packageName,
                                RuleServiceContract.RESULT_WRITE_FAILED, null,
                                "toolbar preference persistence failed");
                    }
                    mToolbarHiddenItems = toolbarItems;
                    mToolbarConfigurationPresent = true;
                    return mutationResult(request.requestId, request.packageName,
                            RuleServiceContract.RESULT_COMMITTED, null, "committed");
                default:
                    return mutationResult(request.requestId, request.packageName,
                            RuleServiceContract.RESULT_INVALID, null, "unknown mutation");
            }
        } catch (Exception e) {
            mLogger.w("mutation failed operation=" + mutationName(request.operation)
                    + " requestId=" + request.requestId
                    + " package=" + request.packageName, e);
            return mutationResult(request.requestId, request.packageName,
                    RuleServiceContract.RESULT_WRITE_FAILED, null, e.getMessage());
        } finally {
            if (mainBitmap != null && !mainBitmap.isRecycled()) mainBitmap.recycle();
            if (modifiedBitmap != null && !modifiedBitmap.isRecycled()) modifiedBitmap.recycle();
            permit.close();
        }
    }

    @Override public UndoStateParcel getUndoState(String packageName, ILeaseOwner owner)
            throws RemoteException {
        if (owner == null || !PackageNameValidator.isValid(packageName)) {
            return undoStateResult(RuleServiceContract.RESULT_INVALID, packageName, 0L,
                    null, "valid package and owner are required");
        }
        int callingUid = Binder.getCallingUid();
        if (mPermissionEnforcer.isModuleUid(callingUid)
                || !mPermissionEnforcer.uidOwnsPackage(callingUid, packageName)) {
            return undoStateResult(RuleServiceContract.RESULT_REJECTED, packageName, 0L,
                    null, "undo state is available only to the target editor process");
        }
        if (!areRulesReady()) {
            return undoStateResult(RuleServiceContract.RESULT_BUSY, packageName, 0L,
                    null, "rule service is not ready");
        }
        OperationCoordinator.EditState editState = mOperationLeases.editState();
        if (editState.state != OperationCoordinator.State.EDITING
                && editState.state != OperationCoordinator.State.CLOSING) {
            return undoStateResult(RuleServiceContract.RESULT_EXPIRED, packageName,
                    editState.revision, null, "editor revision is no longer active");
        }
        EditorHistoryOwnerRegistry.OwnerLease historyLease =
                editState.state == OperationCoordinator.State.EDITING
                        ? mHistoryOwners.acquireOrCreate(owner.asBinder(), callingUid,
                        packageName, editState.revision)
                        : mHistoryOwners.acquireExisting(owner.asBinder(), callingUid,
                        packageName, editState.revision);
        if (historyLease == null) {
            OperationCoordinator.EditState confirmed = mOperationLeases.editState();
            if (confirmed.revision != editState.revision
                    || confirmed.state != editState.state) {
                return undoStateResult(RuleServiceContract.RESULT_EXPIRED, packageName,
                        confirmed.revision, null, "editor revision changed");
            }
            return undoStateResult(RuleServiceContract.RESULT_OWNER_MISMATCH, packageName,
                    editState.revision, null, "editor history owner is unavailable");
        }
        try {
            RuleRepository.UndoState state = mRepository.getUndoState(historyLease.scope());
            OperationCoordinator.EditState confirmed = mOperationLeases.editState();
            if (!historyLease.isActive() || confirmed.revision != editState.revision
                    || confirmed.state != editState.state) {
                return undoStateResult(RuleServiceContract.RESULT_EXPIRED, packageName,
                        confirmed.revision, null, "editor revision changed");
            }
            return undoStateResult(RuleServiceContract.RESULT_COMMITTED, packageName,
                    confirmed.revision, state, "authoritative state");
        } finally {
            historyLease.close();
        }
    }

    @Override public UndoResultParcel undoLatest(UndoRequestParcel request, ILeaseOwner owner) {
        if (request == null || owner == null || request.requestId == null
                || request.requestId.isEmpty()
                || !PackageNameValidator.isValid(request.packageName)) {
            return undoResult(request, RuleServiceContract.RESULT_INVALID, 0L, null,
                    "valid undo request and owner are required");
        }
        int callingUid = Binder.getCallingUid();
        if (mPermissionEnforcer.isModuleUid(callingUid)
                || !mPermissionEnforcer.uidOwnsPackage(callingUid, request.packageName)) {
            return undoResult(request, RuleServiceContract.RESULT_REJECTED, 0L, null,
                    "undo is available only to the target editor process");
        }
        OperationLeaseController.PersistencePermit permit = mOperationLeases.acquirePersistence(
                request.leaseToken,
                owner.asBinder(), callingUid, request.packageName);
        if (permit == null) {
            return undoResult(request, RuleServiceContract.RESULT_BUSY, 0L, null,
                    "write lease is not authorized for undo");
        }
        OperationCoordinator.Access access = permit.access();
        try {
            if (!access.editorMutation || access.editRevision != request.expectedEditRevision) {
                return undoResult(request, RuleServiceContract.RESULT_EXPIRED, 0L, null,
                        "editor revision changed");
            }
            EditorHistoryOwnerRegistry.OwnerLease historyLease = mHistoryOwners.acquireExisting(
                    owner.asBinder(), callingUid, request.packageName, access.editRevision);
            if (historyLease == null) {
                return undoResult(request, RuleServiceContract.RESULT_OWNER_MISMATCH, 0L, null,
                        "editor history owner is unavailable");
            }
            try {
                if (!historyLease.isActive()) {
                    return undoResult(request, RuleServiceContract.RESULT_OWNER_MISMATCH, 0L, null,
                            "editor history owner died");
                }
                RuleRepository.UndoResult result = mRepository.undoLatest(historyLease.scope(),
                        request.requestId, request.expectedHistoryRevision,
                        request.expectedTopSequence);
                return mapUndoResult(request, result);
            } finally {
                historyLease.close();
            }
        } finally {
            permit.close();
        }
    }

    @Override public ParcelFileDescriptor openImageFileDescriptor(String filePath)
            throws RemoteException {
        if (!mRepository.isValidImagePath(filePath)) {
            mLogger.w("open image rejected reason=invalid_path");
            throw new RemoteException("unauthorized image path");
        }
        File file = new File(filePath).getAbsoluteFile();
        File parent = file.getParentFile();
        String packageName = parent == null ? "" : parent.getName();
        enforcePackageOrManager(packageName, "open image");
        FileDescriptor fd = null;
        FileDescriptor dirFd = null;
        try {
            File expectedParent = new File(mRepository.getAppDataDir(packageName))
                    .getCanonicalFile();
            if (parent == null || !expectedParent.equals(parent.getCanonicalFile())) {
                throw new RemoteException("image is outside package directory");
            }
            dirFd = Os.open(expectedParent.getPath(), OsConstants.O_RDONLY
                    | OsConstants.O_NOFOLLOW, 0);
            StructStat dirStat = Os.fstat(dirFd);
            fd = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            StructStat stat = Os.fstat(fd);
            if ((stat.st_mode & OsConstants.S_IFMT) != OsConstants.S_IFREG
                    || stat.st_size <= 0 || stat.st_size > GmConstants.MAX_IMAGE_FILE_SIZE_BYTES
                    || stat.st_uid != dirStat.st_uid || stat.st_dev != dirStat.st_dev) {
                throw new RemoteException("invalid image file");
            }
            ParcelFileDescriptor result = ParcelFileDescriptor.dup(fd);
            Os.close(fd);
            fd = null;
            Os.close(dirFd);
            dirFd = null;
            return result;
        } catch (Exception e) {
            Closeables.closeQuietly(fd);
            fd = null;
            Closeables.closeQuietly(dirFd);
            dirFd = null;
            mLogger.w("open image failed package=" + packageName
                    + " file=" + file.getName(), e);
            if (e instanceof RemoteException) throw (RemoteException) e;
            RemoteException remote = new RemoteException("open image failed: " + e.getMessage());
            remote.initCause(e);
            throw remote;
        }
    }

    @Override public String getToolbarHiddenItems(String packageName) throws RemoteException {
        mPermissionEnforcer.enforcePackageOrManager(packageName, true,
                "get toolbar hidden items fail permission denied");
        return mToolbarConfigurationPresent ? mToolbarHiddenItems : null;
    }

    @Override public void log(int level, String packageName, long timestamp, String tag, String msg)
            throws RemoteException {
        enforcePackageOrManager(packageName, "forward log");
        GodModeLog.write(level, packageName, tag, msg, timestamp, Binder.getCallingPid());
    }

    public void shutdown() {
        mStarted = false;
        mOperationLeases.shutdownAndDrain();
        mHistoryOwners.shutdownAndDrain();
        cleanupStaleIncomingFiles();
        mObserverRegistry.shutdown();
        mRepository.shutdown();
    }

    private RuleMutationResult writeRuleMutation(RuleMutationRequest request, IBinder owner,
                                                  OperationCoordinator.Access access,
                                                  RuleRecord rule, Bitmap mainBitmap,
                                                  Bitmap modifiedBitmap, boolean append) {
        if (!request.captureUndo) {
            return mapMutationResult(request.requestId, mRepository.mutateWrite(
                    request.packageName, rule, mainBitmap, modifiedBitmap, append));
        }
        EditorHistoryOwnerRegistry.OwnerLease historyLease = mHistoryOwners.acquireOrCreate(
                owner, access.callingUid, request.packageName, access.editRevision);
        if (historyLease == null) {
            return mutationResult(request.requestId, request.packageName,
                    RuleServiceContract.RESULT_OWNER_MISMATCH, null,
                    "editor history owner is unavailable");
        }
        try {
            if (!historyLease.isActive()) {
                return mutationResult(request.requestId, request.packageName,
                        RuleServiceContract.RESULT_OWNER_MISMATCH, null,
                        "editor history owner died");
            }
            return mapMutationResult(request.requestId, mRepository.mutateWriteUndoable(
                    request.packageName, rule, mainBitmap, modifiedBitmap, append,
                    historyLease.scope(), request.requestId));
        } finally {
            historyLease.close();
        }
    }

    private IncomingImageReader.ReadResult readIncomingImage(ParcelFileDescriptor descriptor,
                                                              String packageName,
                                                              String requestId, String label) {
        try {
            return mIncomingImageReader.read(descriptor,
                    new File(mRepository.getAppDataDir(packageName)), requestId,
                    packageName, label);
        } catch (Exception e) {
            Closeables.closeQuietly(descriptor);
            mLogger.w("fd mutate cannot resolve package directory requestId=" + requestId
                    + " package=" + packageName + " image=" + label, e);
            return IncomingImageReader.ReadResult.invalid();
        }
    }

    private static void closeInputFds(RuleMutationRequest request) {
        if (request == null) return;
        Closeables.closeQuietly(request.mainImageFd);
        Closeables.closeQuietly(request.modifiedImageFd);
    }

    private void cleanupStaleIncomingFiles() {
        int cleaned = IncomingImageReader.cleanupStaleFiles(new File(DATA_DIR));
        if (cleaned > 0) mLogger.i("cleaned stale incoming files count=" + cleaned);
    }

    private void enforceObserverScope(String packageName, String operation)
            throws RemoteException {
        mPermissionEnforcer.enforcePackageOrManager(packageName, true,
                operation + " fail permission denied");
    }

    private void enforcePackageOrManager(String packageName, String operation)
            throws RemoteException {
        mPermissionEnforcer.enforcePackageOrManager(packageName, false,
                operation + " fail permission denied");
    }

    private boolean areRulesReady() {
        return mStarted && mLifecycle.getState() == ModuleLifecycle.State.READY
                && mRepository.isDataLoaded();
    }

    private OperationLeaseParcel leaseResult(int type, int status, String message) {
        return new OperationLeaseParcel(status, type, null, message);
    }

    private RuleMutationResult mutationResult(String requestId, String packageName, int status,
                                              String value, String message) {
        return new RuleMutationResult(status, requestId, packageName,
                mRepository.getGeneration(), value, message);
    }

    private RuleMutationResult mapMutationResult(String requestId,
                                                 RuleRepository.MutationResult mutation) {
        int status;
        switch (mutation.status) {
            case COMMITTED: status = RuleServiceContract.RESULT_COMMITTED; break;
            case NO_CHANGE: status = RuleServiceContract.RESULT_NO_CHANGE; break;
            case REJECTED: status = RuleServiceContract.RESULT_REJECTED; break;
            default: status = RuleServiceContract.RESULT_WRITE_FAILED; break;
        }
        UndoStateParcel undoState = mutation.undoState == null ? null
                : undoStateResult(status, mutation.packageName,
                mutation.undoState.editRevision, mutation.undoState,
                mutation.replayed ? "replayed" : mutation.error);
        return new RuleMutationResult(status, requestId, mutation.packageName,
                mutation.generation == 0L ? mRepository.getGeneration() : mutation.generation,
                null, undoState, mutation.replayed ? "replayed" : mutation.error);
    }

    private UndoResultParcel mapUndoResult(UndoRequestParcel request,
                                           RuleRepository.UndoResult result) {
        int status;
        switch (result.status) {
            case UNDONE: status = RuleServiceContract.RESULT_COMMITTED; break;
            case EMPTY: status = RuleServiceContract.RESULT_NO_CHANGE; break;
            case CAS_MISMATCH:
            case STALE: status = RuleServiceContract.RESULT_STALE; break;
            case REJECTED: status = RuleServiceContract.RESULT_REJECTED; break;
            default: status = RuleServiceContract.RESULT_WRITE_FAILED; break;
        }
        String message = result.replayed ? "replayed" : result.error;
        UndoStateParcel state = undoStateResult(status, request.packageName,
                request.expectedEditRevision, result.undoState, message);
        return new UndoResultParcel(status, request.requestId, request.packageName,
                result.generation == 0L ? mRepository.getGeneration() : result.generation,
                state, message);
    }

    private UndoResultParcel undoResult(UndoRequestParcel request, int status, long generation,
                                        UndoStateParcel state, String message) {
        return new UndoResultParcel(status, request == null ? null : request.requestId,
                request == null ? null : request.packageName,
                generation == 0L ? mRepository.getGeneration() : generation, state, message);
    }

    private UndoStateParcel undoStateResult(int status, String packageName, long editRevision,
                                            RuleRepository.UndoState state, String message) {
        return new UndoStateParcel(status, packageName,
                state == null ? editRevision : state.editRevision,
                state == null ? 0L : state.historyRevision,
                state == null ? 0 : state.depth,
                state == null ? 0L : state.topSequence,
                state == null ? null : state.topSourceRequestId, message);
    }

    private RuleSnapshotParcel unavailableSnapshot(String packageName) {
        return new RuleSnapshotParcel(RuleServiceContract.SNAPSHOT_UNAVAILABLE, packageName,
                mRepository.getGeneration(), 0, "", null);
    }

    private RuleSnapshotParcel createSnapshot(String label, String packageName, Object snapshot,
                                              long generation) throws RemoteException {
        SharedMemory memory = null;
        ByteBuffer buffer = null;
        try {
            byte[] bytes = mGson.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 8 * 1024 * 1024) {
                throw new RemoteException("snapshot exceeds 8 MiB");
            }
            memory = SharedMemory.create("godmode-" + label, Math.max(1, bytes.length));
            buffer = memory.mapReadWrite();
            buffer.put(bytes);
            if (!memory.setProtect(OsConstants.PROT_READ)) {
                throw new RemoteException("snapshot not read-only");
            }
            int status = ((snapshot instanceof ActRules && ((ActRules) snapshot).isEmpty())
                    || (snapshot instanceof AppRules && ((AppRules) snapshot).isEmpty()))
                    ? RuleServiceContract.SNAPSHOT_EMPTY : RuleServiceContract.SNAPSHOT_READY;
            return new RuleSnapshotParcel(status, packageName, generation,
                    bytes.length, sha256(bytes), memory);
        } catch (Exception e) {
            if (memory != null) memory.close();
            mLogger.w("create snapshot failed scope=" + packageName
                    + " generation=" + generation, e);
            if (e instanceof RemoteException) throw (RemoteException) e;
            RemoteException remote = new RemoteException("create snapshot failed: " + e.getMessage());
            remote.initCause(e);
            throw remote;
        } finally {
            if (buffer != null) SharedMemory.unmap(buffer);
        }
    }

    private static boolean requiresRule(int operation) {
        return operation == RuleServiceContract.MUTATION_WRITE
                || operation == RuleServiceContract.MUTATION_UPDATE
                || operation == RuleServiceContract.MUTATION_DELETE;
    }

    private static String sha256(byte[] data) throws RemoteException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (Exception e) {
            RemoteException remote = new RemoteException("sha256 unavailable");
            remote.initCause(e);
            throw remote;
        }
    }

    private static String operationName(int operation) {
        switch (operation) {
            case RuleServiceContract.OP_EDIT: return "edit";
            case RuleServiceContract.OP_RESTORE: return "restore";
            case RuleServiceContract.OP_MUTATION: return "mutation";
            case RuleServiceContract.OP_BACKUP: return "backup";
            default: return "unknown(" + operation + ")";
        }
    }

    private static String resultName(int status) {
        switch (status) {
            case RuleServiceContract.RESULT_COMMITTED: return "committed";
            case RuleServiceContract.RESULT_NO_CHANGE: return "no_change";
            case RuleServiceContract.RESULT_BUSY: return "busy";
            case RuleServiceContract.RESULT_REJECTED: return "rejected";
            case RuleServiceContract.RESULT_WRITE_FAILED: return "write_failed";
            case RuleServiceContract.RESULT_REBOOT_REQUIRED: return "reboot_required";
            case RuleServiceContract.RESULT_INVALID: return "invalid";
            case RuleServiceContract.RESULT_UNCERTAIN: return "uncertain";
            case RuleServiceContract.RESULT_STALE: return "stale";
            case RuleServiceContract.RESULT_EXPIRED: return "expired";
            case RuleServiceContract.RESULT_OWNER_MISMATCH: return "owner_mismatch";
            case RuleServiceContract.RESULT_ALREADY_UNDONE: return "already_undone";
            default: return "unknown(" + status + ")";
        }
    }

    private static String mutationName(int operation) {
        switch (operation) {
            case RuleServiceContract.MUTATION_WRITE: return "write";
            case RuleServiceContract.MUTATION_UPDATE: return "update";
            case RuleServiceContract.MUTATION_DELETE: return "delete";
            case RuleServiceContract.MUTATION_DELETE_ALL: return "delete_all";
            case RuleServiceContract.MUTATION_SET_TOOLBAR: return "set_toolbar";
            default: return "unknown(" + operation + ")";
        }
    }

}
