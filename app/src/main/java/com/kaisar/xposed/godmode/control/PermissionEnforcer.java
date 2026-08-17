package com.kaisar.xposed.godmode.control;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.ipc.RuleServiceContract;

/**
 * 权限验证器 — 通过 UID → 包名映射验证调用方身份。
 * <p>
 * 从 {@code service/} 移入 control/ 包，职责不变。
 */
final class PermissionEnforcer {

    interface PackageLookup {
        String[] packagesForUid(int uid);
    }

    interface PackageLookupSource {
        PackageLookup getPackageLookup();
    }

    interface CallingUidSource {
        int getCallingUid();
    }

    private final PackageLookup mPackageLookup;
    private final CallingUidSource mCallingUidSource;
    private final String mModulePackage;

    PermissionEnforcer(@NonNull Context context) {
        this(deferredPackageLookup(() -> {
                    PackageManager packageManager = context.getPackageManager();
                    return packageManager == null ? null : packageManager::getPackagesForUid;
                }), Binder::getCallingUid,
                BuildConfig.APPLICATION_ID);
    }

    PermissionEnforcer(@NonNull PackageLookup packageLookup,
                       @NonNull CallingUidSource callingUidSource,
                       @NonNull String modulePackage) {
        mPackageLookup = packageLookup;
        mCallingUidSource = callingUidSource;
        mModulePackage = modulePackage;
    }

    static PackageLookup deferredPackageLookup(@NonNull PackageLookupSource source) {
        return uid -> {
            PackageLookup lookup = source.getPackageLookup();
            return lookup == null ? null : lookup.packagesForUid(uid);
        };
    }

    /**
     * 检查调用方 UID 是否属于指定包名。
     */
    boolean checkPermission(@NonNull String permPackage) {
        return uidOwnsPackage(mCallingUidSource.getCallingUid(), permPackage);
    }

    boolean uidOwnsPackage(int uid, @NonNull String packageName) {
        String[] packagesForUid = mPackageLookup.packagesForUid(uid);
        if (packagesForUid == null) return false;
        for (String candidate : packagesForUid) {
            if (packageName.equals(candidate)) return true;
        }
        return false;
    }

    boolean isModuleUid(int uid) {
        return uidOwnsPackage(uid, mModulePackage);
    }

    void enforcePackageOrManager(@NonNull String packageName, boolean allowGlobalScope,
                                 String message) throws RemoteException {
        int uid = mCallingUidSource.getCallingUid();
        if (allowGlobalScope && RuleServiceContract.GLOBAL_SCOPE.equals(packageName)) {
            if (isModuleUid(uid)) return;
            throw new RemoteException(message);
        }
        if (PackageNameValidator.isValid(packageName)
                && (isModuleUid(uid) || uidOwnsPackage(uid, packageName))) {
            return;
        }
        throw new RemoteException(message);
    }

    /**
     * 单包名权限校验 — 仅允许 GodMode 自身调用。
     */
    void enforcePermission(String message) throws RemoteException {
        if (!checkPermission(BuildConfig.APPLICATION_ID)) {
            throw new RemoteException(message);
        }
    }
}
