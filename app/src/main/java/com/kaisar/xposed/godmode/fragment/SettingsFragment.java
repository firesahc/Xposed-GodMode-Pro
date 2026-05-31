package com.kaisar.xposed.godmode.fragment;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.snackbar.Snackbar;
import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.model.SharedViewModel;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.util.AppInfoHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SettingsFragment extends PreferenceFragmentCompat implements
        Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

    private static final String TOOLBAR_PREFS = "toolbar_prefs";
    private static final String TOOLBAR_HIDDEN_ITEMS = "toolbar_hidden_items";

    private SharedViewModel mSharedViewModel;
    private ActivityResultLauncher<String[]> mRestoreLauncher;
    private ActivityResultLauncher<Uri> mSaveAllLauncher;
    private Snackbar mProgressSnackbar;

    // Group B state for sequential save-all-rules via SAF directory
    private Uri mSaveAllTreeUri;
    private List<String> mBackupQueue;
    private int mBackupIndex;
    private int mBackupSuccess;
    private int mBackupFailed;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        mSharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);

        mRestoreLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onRestoreFileSelected
        );
        mSaveAllLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                this::onSaveAllDirectorySelected
        );
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.pref_settings, rootKey);

        // Group A: Restore Rules
        Preference restoreRules = findPreference(getString(R.string.pref_restore_rules));
        if (restoreRules != null) {
            restoreRules.setOnPreferenceClickListener(this);
        }

        // Group A: Hide Icon
        SwitchPreferenceCompat hideIcon = (SwitchPreferenceCompat) findPreference(getString(R.string.pref_key_hide_icon));
        if (hideIcon != null) {
            hideIcon.setChecked(mSharedViewModel.isIconHidden(requireContext()));
            hideIcon.setOnPreferenceChangeListener(this);
        }

        // Group B: Save All Rules
        Preference saveAllRules = findPreference(getString(R.string.settings_save_all_rules));
        if (saveAllRules != null) {
            saveAllRules.setOnPreferenceClickListener(this);
        }

        // Group C: Toolbar preferences — read from local SharedPreferences (app process)
        SharedPreferences toolbarPrefs = requireContext().getSharedPreferences(TOOLBAR_PREFS, Context.MODE_PRIVATE);
        Set<String> hiddenItems = loadHiddenItemsMigrate(toolbarPrefs);

        String[] toolbarKeys = {
                getString(R.string.pref_key_show_info_flow_mode),
                getString(R.string.pref_key_show_remove_mode),
                getString(R.string.pref_key_show_modify_mode)
        };
        for (String key : toolbarKeys) {
            SwitchPreferenceCompat pref = (SwitchPreferenceCompat) findPreference(key);
            if (pref != null) {
                pref.setChecked(!hiddenItems.contains(key));
                pref.setOnPreferenceChangeListener(this);
            }
        }
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        String key = preference.getKey();

        if (TextUtils.equals(key, getString(R.string.pref_restore_rules))) {
            // Group A: Open file picker for restore
            try {
                mRestoreLauncher.launch(new String[]{"*/*"});
            } catch (Exception e) {
                Logger.w(TAG, "[Settings] restore launch failed", e);
                Snackbar.make(requireView(), R.string.snack_bar_msg_restore_rules_fail, Snackbar.LENGTH_SHORT).show();
            }
            return true;
        }

        if (TextUtils.equals(key, getString(R.string.settings_save_all_rules))) {
            // Group B: Save all rules — collect packages with rules, then open SAF directory picker
            final AppRules appRules = mSharedViewModel.appRules.getValue();
            if (appRules == null || appRules.isEmpty()) {
                Snackbar.make(requireView(), R.string.snack_bar_msg_backup_rule_fail, Snackbar.LENGTH_SHORT).show();
                return true;
            }
            mBackupQueue = new ArrayList<>();
            for (Map.Entry<String, ActRules> entry : appRules.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    mBackupQueue.add(entry.getKey());
                }
            }
            if (mBackupQueue.isEmpty()) {
                Snackbar.make(requireView(), R.string.snack_bar_msg_backup_rule_fail, Snackbar.LENGTH_SHORT).show();
                return true;
            }
            // Let user pick a directory, then save each package as an individual file
            mSaveAllLauncher.launch(null);
            return true;
        }

        return false;
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
        String key = preference.getKey();
        boolean enabled = (Boolean) newValue;

        if (TextUtils.equals(key, getString(R.string.pref_key_hide_icon))) {
            // Group A: Hide icon toggle
            mSharedViewModel.setIconHidden(requireContext(), enabled);
            return true;
        }

        // Group C: Toolbar preferences
        if (TextUtils.equals(key, getString(R.string.pref_key_show_remove_mode))
                || TextUtils.equals(key, getString(R.string.pref_key_show_modify_mode))
                || TextUtils.equals(key, getString(R.string.pref_key_show_info_flow_mode))) {
            saveToolbarPreference(key, enabled);
            return true;
        }

        return false;
    }

    // Group A: Handle restore file result
    private void onRestoreFileSelected(Uri uri) {
        if (uri == null) return;
        Logger.i(TAG, "[Settings] restoreRules: file selected, uri=" + uri);
        showProgressSnackbar(getString(R.string.menu_title_restore_rules) + "...");
        mSharedViewModel.restoreRules(uri, new SharedViewModel.ResultCallback() {
            @Override
            public void onSuccess(int count) {
                if (!isAdded()) return;
                Logger.i(TAG, "[Settings] restoreRules: success, count=" + count);
                dismissProgressSnackbar();
                Snackbar.make(requireView(), getString(R.string.snack_bar_msg_restore_rules_success, count), Snackbar.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(Exception e) {
                if (!isAdded()) return;
                dismissProgressSnackbar();
                Logger.w(TAG, "[Settings] restoreRules: failed", e);
                Snackbar.make(requireView(), R.string.snack_bar_msg_restore_rules_fail, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    // Group B: Handle save-all directory selection — save each package as an individual file
    private void onSaveAllDirectorySelected(Uri treeUri) {
        if (treeUri == null || !isAdded()) return;
        if (mBackupQueue == null || mBackupQueue.isEmpty()) {
            Logger.w(TAG, "[Settings] saveAllRules: backup queue lost (activity recreated?), abort");
            Snackbar.make(requireView(), R.string.snack_bar_msg_backup_rule_fail, Snackbar.LENGTH_SHORT).show();
            return;
        }

        // Take persistable permission so we can write multiple files
        try {
            requireContext().getContentResolver().takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            Logger.w(TAG, "[Settings] saveAllRules: take permission failed", e);
            // Continue anyway — some SAF providers don't support persistable permissions
        }

        mBackupIndex = 0;
        mBackupSuccess = 0;
        mBackupFailed = 0;
        mSaveAllTreeUri = treeUri;
        Logger.i(TAG, "[Settings] saveAllRules: start, packageCount=" + mBackupQueue.size());
        showProgressSnackbar(getBackupProgressMessage());
        backupNextPackageToDirectory();
    }

    private void backupNextPackageToDirectory() {
        if (!isAdded()) return;
        if (mBackupQueue == null || mBackupIndex >= mBackupQueue.size()) {
            Logger.i(TAG, "[Settings] saveAllRules: completed, success=" + mBackupSuccess + ", failed=" + mBackupFailed);
            dismissProgressSnackbar();
            Snackbar.make(requireView(),
                    getString(R.string.snack_bar_msg_backup_all_result, mBackupSuccess, mBackupFailed),
                    Snackbar.LENGTH_LONG).show();
            return;
        }

        final String packageName = mBackupQueue.get(mBackupIndex);
        final AppRules appRules = mSharedViewModel.appRules.getValue();
        ActRules actRules = appRules != null ? appRules.get(packageName) : null;

        if (actRules == null || actRules.isEmpty()) {
            mBackupIndex++;
            mBackupFailed++;
            showProgressSnackbar(getBackupProgressMessage());
            backupNextPackageToDirectory();
            return;
        }

        // Collect all rules for this package
        List<ViewRule> rules = new ArrayList<>();
        for (List<ViewRule> ruleList : actRules.values()) {
            rules.addAll(ruleList);
        }

        try {
            // Create a file inside the chosen directory
            String filename = AppInfoHelper.generateBackupFilename(requireContext(), packageName);
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(
                    mSaveAllTreeUri, DocumentsContract.getTreeDocumentId(mSaveAllTreeUri));
            Uri docUri = DocumentsContract.createDocument(
                    requireContext().getContentResolver(),
                    parentUri,
                    "application/gzip",
                    filename
            );
            if (docUri == null) {
                mBackupIndex++;
                mBackupFailed++;
                showProgressSnackbar(getBackupProgressMessage());
                backupNextPackageToDirectory();
                return;
            }

            mSharedViewModel.backupRules(docUri, packageName, rules, new SharedViewModel.ResultCallback() {
                @Override
                public void onSuccess(int count) {
                    if (!isAdded()) return;
                    mBackupSuccess++;
                    mBackupIndex++;
                    showProgressSnackbar(getBackupProgressMessage());
                    backupNextPackageToDirectory();
                }

                @Override
                public void onFailure(Exception e) {
                    if (!isAdded()) return;
                    Logger.w(TAG, "[Settings] saveAllRules: backup failed for " + packageName, e);
                    mBackupFailed++;
                    mBackupIndex++;
                    showProgressSnackbar(getBackupProgressMessage());
                    backupNextPackageToDirectory();
                }
            });
        } catch (Exception e) {
            Logger.w(TAG, "[Settings] saveAllRules: backup exception for " + packageName, e);
            mBackupFailed++;
            mBackupIndex++;
            showProgressSnackbar(getBackupProgressMessage());
            backupNextPackageToDirectory();
        }
    }

    private String getBackupProgressMessage() {
        return getString(R.string.settings_save_all_rules_desc)
                + " (" + mBackupIndex + "/" + mBackupQueue.size() + ")";
    }

    // Group C: Persist toolbar preference — stored as comma-separated string
    private void saveToolbarPreference(String key, boolean enabled) {
        SharedPreferences sp = requireContext().getSharedPreferences(TOOLBAR_PREFS, Context.MODE_PRIVATE);
        Set<String> hidden = loadHiddenItemsMigrate(sp);
        if (!enabled) {
            hidden.add(key);
        } else {
            hidden.remove(key);
        }
        String value = TextUtils.join(",", hidden);
        sp.edit().putString(TOOLBAR_HIDDEN_ITEMS, value).apply();
        // Sync via AIDL for cross-process access
        GodModeManager.getDefault().setToolbarHiddenItems(value);
    }

    /**
     * 读取工具栏隐藏项，兼容旧版 StringSet 格式并自动迁移为逗号分隔字符串。
     */
    private static Set<String> loadHiddenItemsMigrate(SharedPreferences sp) {
        Map<String, ?> all = sp.getAll();
        Object raw = all.get(TOOLBAR_HIDDEN_ITEMS);
        if (raw instanceof Set) {
            @SuppressWarnings("unchecked")
            Set<String> oldSet = (Set<String>) raw;
            // Migrate old StringSet format to comma-separated string
            sp.edit().putString(TOOLBAR_HIDDEN_ITEMS, TextUtils.join(",", oldSet)).apply();
            return new HashSet<>(oldSet);
        } else if (raw instanceof String) {
            Set<String> result = new HashSet<>();
            String value = (String) raw;
            if (!TextUtils.isEmpty(value)) {
                for (String s : value.split(",")) {
                    if (!TextUtils.isEmpty(s)) {
                        result.add(s);
                    }
                }
            }
            return result;
        }
        return new HashSet<>();
    }

    private void showProgressSnackbar(String message) {
        dismissProgressSnackbar();
        mProgressSnackbar = Snackbar.make(requireView(), message, Snackbar.LENGTH_INDEFINITE);
        mProgressSnackbar.show();
    }

    private void dismissProgressSnackbar() {
        if (mProgressSnackbar != null) {
            mProgressSnackbar.dismiss();
            mProgressSnackbar = null;
        }
    }
}
