package com.kaisar.xposed.godmode.service;

import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import com.kaisar.xposed.godmode.BuildConfig;

import java.util.Arrays;

/**
 * 权限验证器 — 通过 UID → 包名映射验证调用方身份。
 * 从 RuleServiceServer 提取的独立职责。
 */
final class PermissionEnforcer {

    private final Context mContext;

    PermissionEnforcer(@NonNull Context context) {
        this.mContext = context;
    }

    /**
     * 检查调用方 UID 是否属于指定包名。
     */
    boolean checkPermission(@NonNull String permPackage) {
        int callingUid = Binder.getCallingUid();
        String[] packagesForUid = mContext.getPackageManager().getPackagesForUid(callingUid);
        return packagesForUid != null && Arrays.asList(packagesForUid).contains(permPackage);
    }

    /**
     * 多包名权限校验 — 调用方只需匹配其中一个包名。
     */
    void enforcePermission(@NonNull String[] permPackages, String message) throws RemoteException {
        for (String permPackage : permPackages) {
            if (checkPermission(permPackage)) {
                return;
            }
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
