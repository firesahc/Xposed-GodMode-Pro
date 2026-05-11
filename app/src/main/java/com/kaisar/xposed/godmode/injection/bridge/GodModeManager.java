package com.kaisar.xposed.godmode.injection.bridge;

import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.kaisar.xposed.godmode.IGodModeManager;
import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xservicemanager.XServiceManager;

import de.robv.android.xposed.XposedBridge;

public final class GodModeManager {

    private static volatile GodModeManager instance;
    private final IGodModeManager mGMM;

    private GodModeManager(IGodModeManager gmm) {
        this.mGMM = gmm;
    }

    public static GodModeManager getDefault() {
        GodModeManager result = instance;
        if (result == null) {
            synchronized (GodModeManager.class) {
                result = instance;
                if (result == null) {
                    IBinder service = XServiceManager.getService("godmode");
                    if (service != null) {
                        result = new GodModeManager(IGodModeManager.Stub.asInterface(service));
                    } else {
                        result = new GodModeManager(new IGodModeManager.Default());
                    }
                    instance = result;
                }
            }
        }
        return result;
    }

    private static void logError(String method, RemoteException e) {
        XposedBridge.log("[GodMode] GodModeManager#" + method + " failed: " + e.getMessage());
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
        }
    }

    public ActRules getRules(String packageName) {
        try {
            return mGMM.getRules(packageName);
        } catch (RemoteException e) {
            logError("getRules", e);
            return new ActRules();
        }
    }

    public boolean writeRule(String packageName, ViewRule viewRule, Bitmap bitmap) {
        try {
            return mGMM.writeRule(packageName, viewRule, bitmap);
        } catch (RemoteException e) {
            logError("writeRule", e);
            return false;
        }
    }

    public boolean updateRule(String packageName, ViewRule viewRule) {
        try {
            return mGMM.updateRule(packageName, viewRule);
        } catch (RemoteException e) {
            logError("updateRule", e);
            return false;
        }
    }

    public boolean deleteRule(String packageName, ViewRule viewRule) {
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
}
