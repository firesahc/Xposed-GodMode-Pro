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
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The single system_server authority for rule, asset and toolbar mutations. */
public final class RuleServiceServer extends IRuleService.Stub {
    private final PermissionEnforcer mPermissionEnforcer;
    private final RuleRepository mRepository;
    private final ObserverRegistry mObserverRegistry;
    private final OperationCoordinator mCoordinator = new OperationCoordinator();
    private final ModuleLifecycle mLifecycle;
    private final Logger mLogger;
    private final IncomingImageReader mIncomingImageReader;
    private final Gson mGson = new GsonBuilder().setPrettyPrinting().create();
    private final Object mOwnerLock = new Object();
    private final Map<String, LeaseRegistration> mOwnerRegistrations = new HashMap<>();
    private volatile boolean mStarted;
    private volatile String mToolbarHiddenItems = "";
    private volatile boolean mToolbarConfigurationPresent;

    public RuleServiceServer(Context context) {
        mLogger = Logger.getLogger("RuleServiceServer");
        mIncomingImageReader = new IncomingImageReader(mLogger);
        mPermissionEnforcer = new PermissionEnforcer(context);
        mObserverRegistry = new ObserverRegistry(Logger.getLogger("ObserverRegistry"));
        mLifecycle = new ModuleLifecycle(ModuleLifecycle.Layer.CONTROL);
        mLifecycle.transition(ModuleLifecycle.State.LOADING);
        mRepository = new RuleRepository(mGson, Logger.getLogger("RuleRepository"),
                mObserverRegistry);
        cleanupStaleIncomingFiles();
        Logger.setWriter((level, tag, msg, timestamp) ->
                GodModeLog.write(level, "system_server", tag, msg, timestamp));
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
        if (!areRulesReady()) return leaseResult(operationType,
                RuleServiceContract.RESULT_BUSY, "rule service is not ready");
        if (owner == null) return leaseResult(operationType,
                RuleServiceContract.RESULT_INVALID, "operation owner is required");
        int callingUid = Binder.getCallingUid();
        boolean moduleCaller = mPermissionEnforcer.isModuleUid(callingUid);
        boolean ownsPackage = PackageNameValidator.isValid(packageName)
                && mPermissionEnforcer.uidOwnsPackage(callingUid, packageName);
        OperationCoordinator.OpenResult opened = mCoordinator.open(operationType, packageName,
                callingUid, moduleCaller, ownsPackage, owner.asBinder());
        if (opened.status != RuleServiceContract.RESULT_COMMITTED) {
            return leaseResult(operationType, opened.status, opened.message);
        }
        if (!registerOwner(opened.token, operationType, owner.asBinder())) {
            OperationCoordinator.CloseResult closed = mCoordinator.ownerDied(opened.token,
                    owner.asBinder());
            handleEditTransition(closed);
            return leaseResult(operationType, RuleServiceContract.RESULT_BUSY,
                    "operation owner already died");
        }
        if (opened.editChanged) {
            mObserverRegistry.notifyObserverEditModeChanged(opened.editEnabled,
                    opened.editRevision);
        }
        return new OperationLeaseParcel(opened.status, operationType, opened.token,
                opened.message);
    }

    @Override public OperationLeaseParcel closeOperation(String leaseToken, ILeaseOwner owner)
            throws RemoteException {
        if (owner == null || leaseToken == null) return leaseResult(0,
                RuleServiceContract.RESULT_INVALID, "operation owner and token are required");
        LeaseRegistration registration = ownerRegistration(leaseToken);
        if (registration == null) {
            return new OperationLeaseParcel(RuleServiceContract.RESULT_NO_CHANGE, 0, null,
                    "lease already released");
        }
        int type = registration.type;
        try {
            OperationCoordinator.CloseResult closed = mCoordinator.close(leaseToken,
                    owner.asBinder(), Binder.getCallingUid(), OperationCoordinator.CLOSE_TIMEOUT_MS);
            handleEditTransition(closed);
            if (!closed.closed) return leaseResult(type, RuleServiceContract.RESULT_BUSY,
                    "operation is still active");
            unregisterOwner(leaseToken, true);
            return new OperationLeaseParcel(RuleServiceContract.RESULT_COMMITTED, type, null,
                    "lease released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return leaseResult(type, RuleServiceContract.RESULT_BUSY,
                    "interrupted while closing operation");
        }
    }

    @Override public ObserverRegistrationParcel addObserver(String packageName,
                                                            IRuleObserver observer)
            throws RemoteException {
        enforceObserverScope(packageName, "register observer");
        if (!mStarted || observer == null) return new ObserverRegistrationParcel(
                RuleServiceContract.RESULT_BUSY, false, 0L, mRepository.getGeneration(),
                "rule service is not ready");
        boolean registered = mObserverRegistry.addObserver(packageName, observer);
        OperationCoordinator.EditState state = mCoordinator.editState();
        return new ObserverRegistrationParcel(registered
                ? RuleServiceContract.RESULT_COMMITTED : RuleServiceContract.RESULT_NO_CHANGE,
                state.enabled, state.revision, mRepository.getGeneration(),
                registered ? "observer registered" : "observer already registered");
    }

    @Override public void removeObserver(String packageName, IRuleObserver observer)
            throws RemoteException {
        enforceObserverScope(packageName, "unregister observer");
        mObserverRegistry.removeObserver(observer);
    }

    @Override public RuleSnapshotParcel getAllRulesSnapshot() throws RemoteException {
        mPermissionEnforcer.enforcePermission("get all rules fail permission denied");
        if (!areRulesReady()) return unavailableSnapshot(RuleServiceContract.GLOBAL_SCOPE);
        RuleRepository.RepositorySnapshot<AppRules> snapshot =
                mRepository.getAllRulesSnapshot();
        return createSnapshot("all", RuleServiceContract.GLOBAL_SCOPE, snapshot.value,
                snapshot.generation);
    }

    @Override public RuleSnapshotParcel getRulesSnapshot(String packageName)
            throws RemoteException {
        enforcePackageOrManager(packageName, "get rules");
        if (!areRulesReady()) return unavailableSnapshot(packageName);
        RuleRepository.RepositorySnapshot<ActRules> snapshot =
                mRepository.getRulesSnapshot(packageName);
        return createSnapshot(packageName, packageName, snapshot.value, snapshot.generation);
    }

    @Override public RuleMutationResult mutate(RuleMutationRequest request, ILeaseOwner owner) {
        try {
            return mutateInternal(request, owner);
        } finally {
            closeInputFds(request);
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

        OperationCoordinator.Access access = mCoordinator.beginPersistence(request.leaseToken,
                owner.asBinder(), callingUid, request.packageName);
        if (access == null) return mutationResult(request.requestId, request.packageName,
                RuleServiceContract.RESULT_BUSY, null,
                "write lease is not authorized for this mutation");

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
            switch (request.operation) {
                case RuleServiceContract.MUTATION_WRITE:
                    return mapMutationResult(request.requestId, mRepository.mutateWrite(
                            request.packageName, rule, mainBitmap, modifiedBitmap, true));
                case RuleServiceContract.MUTATION_UPDATE:
                    return mapMutationResult(request.requestId, mRepository.mutateWrite(
                            request.packageName, rule, mainBitmap, modifiedBitmap, false));
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
            mLogger.w("mutation failed for " + request.packageName, e);
            return mutationResult(request.requestId, request.packageName,
                    RuleServiceContract.RESULT_WRITE_FAILED, null, e.getMessage());
        } finally {
            if (mainBitmap != null && !mainBitmap.isRecycled()) mainBitmap.recycle();
            if (modifiedBitmap != null && !modifiedBitmap.isRecycled()) modifiedBitmap.recycle();
            OperationCoordinator.CloseResult finished = mCoordinator.finishPersistence(
                    request.leaseToken, owner.asBinder());
            handleEditTransition(finished);
            if (!mCoordinator.contains(request.leaseToken)) {
                unregisterOwner(request.leaseToken, true);
            }
        }
    }

    @Override public ParcelFileDescriptor openImageFileDescriptor(String filePath)
            throws RemoteException {
        if (!mRepository.isValidImagePath(filePath)) {
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
            if (fd != null) try { Os.close(fd); } catch (Exception ignored) { }
            if (dirFd != null) try { Os.close(dirFd); } catch (Exception ignored) { }
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
        GodModeLog.write(level, packageName, tag, msg, timestamp);
    }

    public void shutdown() {
        mStarted = false;
        mCoordinator.shutdown();
        List<LeaseRegistration> registrations;
        synchronized (mOwnerLock) {
            registrations = new ArrayList<>(mOwnerRegistrations.values());
            mOwnerRegistrations.clear();
        }
        for (LeaseRegistration registration : registrations) {
            try {
                registration.owner.unlinkToDeath(registration.deathRecipient, 0);
            } catch (Exception ignored) { }
        }
        cleanupStaleIncomingFiles();
        mObserverRegistry.shutdown();
        mRepository.shutdown();
    }

    private boolean registerOwner(String token, int type, IBinder owner) {
        IBinder.DeathRecipient deathRecipient = () -> onOwnerDied(token, owner);
        LeaseRegistration registration = new LeaseRegistration(type, owner, deathRecipient);
        synchronized (mOwnerLock) {
            mOwnerRegistrations.put(token, registration);
        }
        try {
            owner.linkToDeath(deathRecipient, 0);
            return true;
        } catch (RemoteException e) {
            synchronized (mOwnerLock) {
                mOwnerRegistrations.remove(token);
            }
            return false;
        }
    }

    private void onOwnerDied(String token, IBinder owner) {
        synchronized (mOwnerLock) {
            mOwnerRegistrations.remove(token);
        }
        OperationCoordinator.CloseResult closed = mCoordinator.ownerDied(token, owner);
        handleEditTransition(closed);
    }

    private LeaseRegistration ownerRegistration(String token) {
        synchronized (mOwnerLock) {
            return mOwnerRegistrations.get(token);
        }
    }

    private void unregisterOwner(String token, boolean unlink) {
        LeaseRegistration registration;
        synchronized (mOwnerLock) {
            registration = mOwnerRegistrations.remove(token);
        }
        if (unlink && registration != null) {
            try {
                registration.owner.unlinkToDeath(registration.deathRecipient, 0);
            } catch (Exception ignored) { }
        }
    }

    private void handleEditTransition(OperationCoordinator.CloseResult result) {
        if (result.editChanged) {
            mObserverRegistry.notifyObserverEditModeChanged(result.editEnabled,
                    result.editRevision);
        }
        if (result.releasedEditToken != null) {
            unregisterOwner(result.releasedEditToken, true);
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
            closeQuietly(descriptor);
            mLogger.w("fd mutate cannot resolve package directory requestId=" + requestId
                    + " package=" + packageName + " image=" + label, e);
            return IncomingImageReader.ReadResult.invalid();
        }
    }

    private static void closeInputFds(RuleMutationRequest request) {
        if (request == null) return;
        closeQuietly(request.mainImageFd);
        closeQuietly(request.modifiedImageFd);
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (IOException ignored) { }
    }

    private void cleanupStaleIncomingFiles() {
        IncomingImageReader.cleanupStaleFiles(new File(DATA_DIR));
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
        return new RuleMutationResult(status, requestId, mutation.packageName,
                mutation.generation == 0L ? mRepository.getGeneration() : mutation.generation,
                null, mutation.error);
    }

    private RuleSnapshotParcel unavailableSnapshot(String packageName) {
        return new RuleSnapshotParcel(RuleServiceContract.SNAPSHOT_UNAVAILABLE, packageName,
                mRepository.getGeneration(), 0, "", null);
    }

    private RuleSnapshotParcel createSnapshot(String label, String packageName, Object snapshot,
                                              long generation) throws RemoteException {
        byte[] bytes = mGson.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 8 * 1024 * 1024) {
            throw new RemoteException("snapshot exceeds 8 MiB");
        }
        SharedMemory memory = null;
        ByteBuffer buffer = null;
        try {
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

    private static final class LeaseRegistration {
        final int type;
        final IBinder owner;
        final IBinder.DeathRecipient deathRecipient;

        LeaseRegistration(int type, IBinder owner, IBinder.DeathRecipient deathRecipient) {
            this.type = type;
            this.owner = owner;
            this.deathRecipient = deathRecipient;
        }
    }

}
