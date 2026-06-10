package com.kaisar.xposed.godmode.injection.bridge;

import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.kaisar.xposed.godmode.IGodModeManager;
import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xservicemanager.XServiceManager;

public final class RuleServiceClient {

    private static final String TAG = "RuleServiceClient";

    private static volatile RuleServiceClient instance;
    private final IGodModeManager mGMM;

    private RuleServiceClient(IGodModeManager gmm) {
        this.mGMM = gmm;
    }

    public static RuleServiceClient getDefault() {
        RuleServiceClient result = instance;
        if (result == null) {
            synchronized (RuleServiceClient.class) {
                result = instance;
                if (result == null) {
                    IBinder service = XServiceManager.getService("godmode");
                    if (service != null) {
                        result = new RuleServiceClient(IGodModeManager.Stub.asInterface(service));
                    } else {
                        result = new RuleServiceClient(new IGodModeManager.Default());
                    }
                    instance = result;
                }
            }
        }
        return result;
    }

    private static void logError(String method, RemoteException e) {
        Logger.e(TAG, "RuleServiceClient#" + method + " failed: " + e.getMessage());
    }

    public boolean hasLight() {
        try {
            return mGMM.hasLight();
        } catch (RemoteException e) {
            logError("hasLight", e);
            return false;
        }
    }

    public void setEditMode(boolean enable) {
        try {
            mGMM.setEditMode(enable);
        } catch (RemoteException e) {
            logError("setEditMode", e);
        }
    }

    public boolean isInEditMode() {
        try {
            return mGMM.isInEditMode();
        } catch (RemoteException e) {
            logError("isInEditMode", e);
            return false;
        }
    }

    public void addObserver(String packageName, IObserver observer) {
        try {
            mGMM.addObserver(packageName, observer);
        } catch (RemoteException e) {
            logError("addObserver", e);
        }
    }

    public AppRules getAllRules() {
        try {
            return mGMM.getAllRules();
        } catch (RemoteException e) {
            logError("getAllRules", e);
            return new AppRules();
        } catch (RuntimeException e) {
            // BadParcelableException: ViewRule → RuleRecord 重命名后，
            // system_server 中旧服务可能仍含有 ViewRule 实例，需静默降级
            logError("getAllRules", new RemoteException(e.getMessage()));
            return new AppRules();
        }
    }

    public ActRules getRules(String packageName) {
        try {
            return mGMM.getRules(packageName);
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
            return mGMM.writeRule(packageName, viewRule, bitmap);
        } catch (RemoteException e) {
            logError("writeRule", e);
            return false;
        }
    }

    public boolean updateRule(String packageName, RuleRecord viewRule) {
        try {
            return mGMM.updateRule(packageName, viewRule);
        } catch (RemoteException e) {
            logError("updateRule", e);
            return false;
        }
    }

    public boolean deleteRule(String packageName, RuleRecord viewRule) {
        try {
            return mGMM.deleteRule(packageName, viewRule);
        } catch (RemoteException e) {
            logError("deleteRule", e);
            return false;
        }
    }

    public boolean deleteRules(String packageName) {
        try {
            return mGMM.deleteRules(packageName);
        } catch (RemoteException e) {
            logError("deleteRules", e);
            return false;
        }
    }

    public ParcelFileDescriptor openImageFileDescriptor(String filePath) {
        try {
            return mGMM.openImageFileDescriptor(filePath);
        } catch (RemoteException e) {
            logError("openImageFileDescriptor", e);
            return null;
        }
    }

    public String saveImageFile(String packageName, Bitmap bitmap) {
        try {
            return mGMM.saveImageFile(packageName, bitmap);
        } catch (RemoteException e) {
            logError("saveImageFile", e);
            return null;
        }
    }

    public String getToolbarHiddenItems() {
        try {
            return mGMM.getToolbarHiddenItems();
        } catch (RemoteException e) {
            logError("getToolbarHiddenItems", e);
            return "";
        }
    }

    public void setToolbarHiddenItems(String items) {
        try {
            mGMM.setToolbarHiddenItems(items);
        } catch (RemoteException e) {
            logError("setToolbarHiddenItems", e);
        }
    }
}
