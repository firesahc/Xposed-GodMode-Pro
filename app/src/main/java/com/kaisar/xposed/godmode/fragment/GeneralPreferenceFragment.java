package com.kaisar.xposed.godmode.fragment;

import static com.kaisar.xposed.godmode.fragment.GeneralPreferenceFragmentDirections.actionGeneralPreferenceFragmentToAboutFragment;
import static com.kaisar.xposed.godmode.fragment.GeneralPreferenceFragmentDirections.actionGeneralPreferenceFragmentToGuideFragment;
import static com.kaisar.xposed.godmode.fragment.GeneralPreferenceFragmentDirections.actionGeneralPreferenceFragmentToViewRuleListFragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.snackbar.Snackbar;
import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.CrashHandler;
import com.kaisar.xposed.godmode.GodModeApplication;
import com.kaisar.xposed.godmode.GodModeHelper;
import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.model.SharedViewModel;
import com.kaisar.xposed.godmode.preference.ProgressPreference;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;

import java.util.Map;
import java.util.Set;

public final class GeneralPreferenceFragment extends PreferenceFragmentCompat implements
        Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String SETTING_PREFS = "settings";
    private static final String KEY_VERSION_CODE = "version_code";

    private ProgressPreference mProgressPreference;
    private SwitchPreferenceCompat mEditorSwitchPreference;

    private SharedViewModel mSharedViewModel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(this);
        mSharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        mSharedViewModel.appRules.observe(this, this::onAppRuleChange);
        if (!checkCrash()) {
            mProgressPreference.setVisible(true);
            mSharedViewModel.loadAppRules();
        }
    }

    private boolean checkCrash() {
        String crashInfo = CrashHandler.getLastCrashInfo(GodModeApplication.getApplication());
        if (crashInfo != null) {
            SpannableString text = new SpannableString(getString(R.string.crash_tip));
            SpannableString st = new SpannableString(crashInfo);
            st.setSpan(new RelativeSizeSpan(0.7f), 0, st.length(), 0);
            CharSequence message = TextUtils.concat(text, st);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.hey_guy)
                    .setMessage(message)
                    .setPositiveButton(R.string.dialog_btn_copy, (dialog, which) -> com.kaisar.xposed.godmode.util.Clipboard.putContent(requireContext(), crashInfo))
                    .show();
            return true;
        }
        return false;
    }

    private void onAppRuleChange(AppRules appRules) {
        mProgressPreference.setVisible(false);
        appRules = appRules != null ? appRules : new AppRules();
        Set<Map.Entry<String, ActRules>> entries = appRules.entrySet();
        PreferenceCategory category = (PreferenceCategory) findPreference(getString(R.string.pref_key_app_rules));
        category.removeAll();
        PackageManager pm = requireContext().getPackageManager();
        for (Map.Entry<String, ActRules> entry : entries) {
            String packageName = entry.getKey();
            addAppRulePreference(category, pm, packageName);
        }
    }

    private void addAppRulePreference(PreferenceCategory category, PackageManager pm, String packageName) {
        Drawable icon;
        CharSequence label;
        try {
            ApplicationInfo aInfo = pm.getApplicationInfo(packageName, 0);
            icon = aInfo.loadIcon(pm);
            label = aInfo.loadLabel(pm);
        } catch (PackageManager.NameNotFoundException ignore) {
            icon = ResourcesCompat.getDrawable(getResources(), R.mipmap.ic_god, requireContext().getTheme());
            label = packageName;
        }
        Preference preference = new Preference(category.getContext()) {
            @Override
            public void onBindViewHolder(PreferenceViewHolder holder) {
                super.onBindViewHolder(holder);
                holder.itemView.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.hey_guy)
                            .setMessage(getString(R.string.confirm_delete_rules_longpress, packageName))
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                if (mSharedViewModel.deleteAppRules(packageName)) mSharedViewModel.loadAppRules();
                                else Snackbar.make(requireView(), R.string.snack_bar_msg_revert_rule_fail, Snackbar.LENGTH_SHORT).show();
                            })
                            .setNegativeButton(android.R.string.cancel, null).show();
                    return true;
                });
            }
        };
        preference.setIcon(icon);
        preference.setTitle(label);
        preference.setSummary(packageName);
        preference.setKey(packageName);
        preference.setOnPreferenceClickListener(this);
        category.addPreference(preference);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.pref_general, rootKey);
        mProgressPreference = (ProgressPreference) findPreference(getString(R.string.pref_key_progress_indicator));
        mProgressPreference.setVisible(false);
        mEditorSwitchPreference = (SwitchPreferenceCompat) findPreference(getString(R.string.pref_key_master));
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean masterEnabled = sp.getBoolean(getString(R.string.pref_key_master), false);
        mEditorSwitchPreference.setChecked(masterEnabled);
        mEditorSwitchPreference.setOnPreferenceClickListener(this);
        mEditorSwitchPreference.setOnPreferenceChangeListener(this);

        Preference guidePreference = findPreference(getString(R.string.pref_key_guide));
        if (guidePreference != null) {
            guidePreference.setOnPreferenceClickListener(this);
        }

        Preference aboutPreference = findPreference(getString(R.string.pref_key_about));
        if (aboutPreference != null) {
            aboutPreference.setOnPreferenceClickListener(this);
        }

        SharedPreferences settingsSp = requireContext().getSharedPreferences(SETTING_PREFS, Context.MODE_PRIVATE);
        int previousVersionCode = settingsSp.getInt(KEY_VERSION_CODE, 0);
        if (previousVersionCode != BuildConfig.VERSION_CODE) {
            settingsSp.edit().putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE).apply();
            showUpdatePolicyDialog();
        } else if (!GodModeManager.getDefault().hasLight()) {
            showEnableModuleDialog();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return GodModeManager.getDefault().hasLight();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        if (mEditorSwitchPreference == preference) {
            if (!GodModeManager.getDefault().hasLight()) {
                Toast.makeText(requireContext(), R.string.not_active_module, Toast.LENGTH_SHORT).show();
                return true;
            }
            boolean masterOn = mEditorSwitchPreference.isChecked();
        setMasterEnabled(masterOn);
        } else if (TextUtils.equals(key, getString(R.string.pref_key_guide))) {
            NavController navController = NavHostFragment.findNavController(this);
            navController.navigate(actionGeneralPreferenceFragmentToGuideFragment());
        } else if (TextUtils.equals(key, getString(R.string.pref_key_about))) {
            NavController navController = NavHostFragment.findNavController(this);
            navController.navigate(actionGeneralPreferenceFragmentToAboutFragment());
        } else {
            String packageName = preference.getKey();
            mSharedViewModel.updateSelectedPackage(packageName);
            NavController navController = NavHostFragment.findNavController(this);
            navController.navigate(actionGeneralPreferenceFragmentToViewRuleListFragment());
        }
        return true;
    }

    private void setMasterEnabled(boolean enable) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
        sp.edit().putBoolean(getString(R.string.pref_key_master), enable).apply();
        if (!enable) {
            GodModeManager.getDefault().setEditMode(false);
            sp.edit().putBoolean(getString(R.string.pref_key_editor), false).apply();
        }
        GodModeHelper.startNotificationService(requireContext());
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (TextUtils.equals(key, getString(R.string.pref_key_master))) {
            mEditorSwitchPreference.setChecked(sp.getBoolean(key, false));
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_general, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            NavController nc = NavHostFragment.findNavController(this);
            nc.navigate(GeneralPreferenceFragmentDirections.actionGeneralPreferenceFragmentToSettingsFragment());
            return true;
        } else if (item.getItemId() == android.R.id.home) {
            return false;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showEnableModuleDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.hey_guy)
                .setMessage(R.string.not_active_module)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showUpdatePolicyDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.welcome_title)
                .setMessage(R.string.update_tips)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

}
