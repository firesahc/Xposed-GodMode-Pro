package com.kaisar.xposed.godmode.injection.bridge;

import android.graphics.Bitmap;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.kaisar.xposed.godmode.IGodModeManager;
import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.HookLauncher;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xservicemanager.XServiceManager;

public final class RuleServiceClient {

    private static final String TAG = "RuleServiceClient";
    private static final IGodModeManager FALLBACK = new IGodModeManager.Default();

    private static volatile RuleServiceClient instance;
    private volatile IGodModeManager mGMM;
    private volatile IBinder mBinder;

    private RuleServiceClient() {
    }

    public static RuleServiceClient getDefault() {
        RuleServiceClient result = instance;
        if (result == null) {
            synchronized (RuleServiceClient.class) {
                result = instance;
                if (result == null) {
                    result = new RuleServiceClient();
                    result.ensureService();
                    instance = result;
                }
            }
        }
        return result;
    }

    private IGodModeManager ensureService() {
        IGodModeManager current = mGMM;
        IBinder binder = mBinder;
        if (current != null && binder != null && binder.isBinderAlive()) {
            return current;
        }
        synchronized (this) {
            current = mGMM;
            binder = mBinder;
            if (current != null && binder != null && binder.isBinderAlive()) {
                return current;
            }
            IBinder service = XServiceManager.getService("godmode");
            if (service == null) {
                mBinder = null;
                mGMM = null;
                Logger.e(TAG, "godmode service is null - XServiceManager proxy not installed"
                        + " or RuleServiceServer not created");
                return FALLBACK;
            }
            try {
                service.linkToDeath(() -> {
                    synchronized (RuleServiceClient.this) {
                        if (mBinder == service) {
                            mBinder = null;
                            mGMM = null;
                        }
                    }
                    Logger.w(TAG, "godmode service binder died, will reconnect on next call");
                }, 0);
            } catch (RemoteException e) {
                mBinder = null;
                mGMM = null;
                Logger.w(TAG, "godmode service died before linkToDeath", e);
                return FALLBACK;
            }
            mBinder = service;
            mGMM = IGodModeManager.Stub.asInterface(service);
            Logger.i(TAG, "connected to godmode service via clipboard delegate");
            return mGMM;
        }
    }

    private void markServiceDead() {
        synchronized (this) {
            mBinder = null;
            mGMM = null;
        }
    }

    private void logError(String method, RemoteException e) {
        IBinder binder = mBinder;
        if (e instanceof DeadObjectException || binder == null || !binder.isBinderAlive()) {
            markServiceDead();
        }
        Logger.e(TAG, "RuleServiceClient#" + method + " failed: " + e.getMessage());
    }

    public boolean hasLight() {
        try {
            return ensureService().hasLight();
        } catch (RemoteException e) {
            logError("hasLight", e);
            return false;
        }
    }

    public void setEditMode(boolean enable) {
        try {
            ensureService().setEditMode(enable);
        } catch (RemoteException e) {
            logError("setEditMode", e);
        }
    }

    public boolean isInEditMode() {
        try {
            return ensureService().isInEditMode();
        } catch (RemoteException e) {
            logError("isInEditMode", e);
            return false;
        }
    }

    public void addObserver(String packageName, IObserver observer) {
        try {
            ensureService().addObserver(packageName, observer);
        } catch (RemoteException e) {
            logError("addObserver", e);
        }
    }

    public void removeObserver(String packageName, IObserver observer) {
        try {
            ensureService().removeObserver(packageName, observer);
        } catch (RemoteException e) {
            logError("removeObserver", e);
        }
    }

    public AppRules getAllRules() {
        try {
            return ensureService().getAllRules();
        } catch (RemoteException e) {
            logError("getAllRules", e);
            return new AppRules();
        } catch (RuntimeException e) {
            logError("getAllRules", new RemoteException(e.getMessage()));
            return new AppRules();
        }
    }

    public ActRules getRules(String packageName) {
        try {
            return ensureService().getRules(packageName);
        } catch (RemoteException e) {
            logError("getRules", e);
            return new ActRules();
        } catch (RuntimeException e) {
            logError("getRules", new RemoteException(e.getMessage()));
            return new ActRules();
        }
    }

    public boolean writeRule(String packageName, RuleRecord viewRule, Bitmap bitmap) {
        try {
            return ensureService().writeRule(packageName, viewRule, bitmap);
        } catch (RemoteException e) {
            logError("writeRule", e);
            return false;
        }
    }

    public boolean updateRule(String packageName, RuleRecord viewRule) {
        try {
            return ensureService().updateRule(packageName, viewRule);
        } catch (RemoteException e) {
            logError("updateRule", e);
            return false;
        }
    }

    public boolean deleteRule(String packageName, RuleRecord viewRule) {
        try {
            return ensureService().deleteRule(packageName, viewRule);
        } catch (RemoteException e) {
            logError("deleteRule", e);
            return false;
        }
    }

    public boolean deleteRules(String packageName) {
        try {
            return ensureService().deleteRules(packageName);
        } catch (RemoteException e) {
            logError("deleteRules", e);
            return false;
        }
    }

    public ParcelFileDescriptor openImageFileDescriptor(String filePath) {
        try {
            return ensureService().openImageFileDescriptor(filePath);
        } catch (RemoteException e) {
            logError("openImageFileDescriptor", e);
            return null;
        }
    }

    public String saveImageFile(String packageName, Bitmap bitmap) {
        try {
            return ensureService().saveImageFile(packageName, bitmap);
        } catch (RemoteException e) {
            logError("saveImageFile", e);
            return null;
        }
    }

    public String getToolbarHiddenItems() {
        try {
            return ensureService().getToolbarHiddenItems();
        } catch (RemoteException e) {
            logError("getToolbarHiddenItems", e);
            return "";
        }
    }

    public void setToolbarHiddenItems(String items) {
        try {
            ensureService().setToolbarHiddenItems(items);
        } catch (RemoteException e) {
            logError("setToolbarHiddenItems", e);
        }
    }

    // ---- 日志转发（Logger.Writer 回调）----

    /**
     * 通过 IPC 向 system_server 转发一条日志。
     * 此方法仅供 {@link com.kaisar.xposed.godmode.engine.util.Logger.Writer} 使用，
     * 内部直接用 android.util.Log 处理异常，避免通过 Logger 导致无限递归。
     */
    public void forwardLog(int level, String tag, String msg, long timestamp) {
        try {
            ensureService().log(level,
                    HookLauncher.loadPackageParam != null
                            ? HookLauncher.loadPackageParam.packageName
                            : "unknown",
                    timestamp,
                    tag, msg);
        } catch (RemoteException e) {
            android.util.Log.w(TAG, "forwardLog IPC failed: " + e.getMessage());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "forwardLog unexpected error", t);
        }
    }
}
