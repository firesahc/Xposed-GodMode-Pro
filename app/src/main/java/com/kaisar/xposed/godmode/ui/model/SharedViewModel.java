package com.kaisar.xposed.godmode.ui.model;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.control.RuleBackupManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.kaisar.xposed.godmode.util.TaskExecutor;

public class SharedViewModel extends ViewModel {

    private static final String TAG = "SharedViewModel";

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    public final MutableLiveData<AppRules> appRules = new MutableLiveData<>();
    public final MutableLiveData<List<RuleRecord>> actRules = new MutableLiveData<>();
    public final MutableLiveData<String> selectedPackage = new MutableLiveData<>();
    private final RuleServiceClient.ObserverCallback mRuleObserver =
            new RuleServiceClient.ObserverCallback() {
        @Override
        public void onEditModeChanged(boolean enable, long editRevision, long connectionEpoch) {
        }

        @Override
        public void onRulesInvalidated(String packageName, long generation,
                                       long connectionEpoch) {
            RuleServiceClient client = RuleServiceClient.getDefault();
            AppRules latest = client.getAllRulesAtLeast(generation);
            mMainHandler.post(() -> {
                if (!client.isCurrentRuleEvent(connectionEpoch, generation)) return;
                appRules.setValue(latest);
                if (TextUtils.equals(packageName, selectedPackage.getValue())) {
                    selectedPackage.setValue(packageName);
                }
            });
        }
    };

    public SharedViewModel() {
        try {
            RuleServiceClient.getDefault().addObserver("*", mRuleObserver);
        } catch (Exception e) {
            Logger.w(TAG, "SharedViewModel: register observer failed", e);
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        RuleServiceClient.getDefault().removeObserver("*", mRuleObserver);
        mMainHandler.removeCallbacksAndMessages(null);
    }

    public void loadAppRules() {
        TaskExecutor.executeIo(() -> appRules.postValue(RuleServiceClient.getDefault().getAllRules()));
    }

    public void updateSelectedPackage(String packageName) {
        selectedPackage.postValue(packageName);
    }

    public void updateRuleRecordList(String packageName) {
        ArrayList<RuleRecord> viewRules = new ArrayList<>();
        AppRules rules = this.appRules.getValue();
        if (rules != null && rules.containsKey(packageName)) {
            ActRules actRules = rules.get(packageName);
            if (actRules != null && !actRules.isEmpty()) {
                actRules.values().forEach(viewRules::addAll);
                Collections.sort(viewRules, (o1, o2) -> Long.compare(o1.timestamp, o2.timestamp));
            }
        }
        actRules.setValue(viewRules);
    }

    public boolean deleteAppRules(String packageName) {
        return RuleServiceClient.getDefault().deleteRules(packageName);
    }

    public boolean updateRule(RuleRecord rule) {
        return RuleServiceClient.getDefault().updateRule(rule.packageName, rule);
    }

    public boolean deleteRule(RuleRecord rule) {
        return RuleServiceClient.getDefault().deleteRule(rule.packageName, rule);
    }

    public void setIconHidden(Context context, boolean hidden) {
        PackageManager pm = context.getPackageManager();
        ComponentName cmp = new ComponentName(context.getPackageName(), "com.kaisar.xposed.godmode.SettingsAliasActivity");
        pm.setComponentEnabledSetting(cmp, hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
    }

    public boolean isIconHidden(Context context) {
        PackageManager pm = context.getPackageManager();
        ComponentName cmp = new ComponentName(context.getPackageName(), "com.kaisar.xposed.godmode.SettingsAliasActivity");
        return pm.getComponentEnabledSetting(cmp) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    public void restoreRules(Uri uri, RestoreCallback callback) {
        TaskExecutor.executeIo(() -> {
            try {
                RuleBackupManager.RestoreReport report = RuleBackupManager.restoreRules(uri);
                mMainHandler.post(() -> callback.onSuccess(report));
            } catch (RuleBackupManager.RestoreException e) {
                mMainHandler.post(() -> callback.onFailure(e));
            }
        });
    }

    public void backupRules(Uri uri, String packageName, List<RuleRecord> viewRules, ResultCallback callback) {
        TaskExecutor.executeIo(() -> {
            try {
                RuleBackupManager.backupRules(uri, packageName, viewRules);
                mMainHandler.post(() -> callback.onSuccess(viewRules.size()));
            } catch (RuleBackupManager.BackupException e) {
                mMainHandler.post(() -> callback.onFailure(e));
            }
        });
    }

    public interface ResultCallback {
        void onSuccess(int count);
        void onFailure(Exception e);
    }

    public interface RestoreCallback {
        void onSuccess(RuleBackupManager.RestoreReport report);
        void onFailure(Exception e);
    }
}
