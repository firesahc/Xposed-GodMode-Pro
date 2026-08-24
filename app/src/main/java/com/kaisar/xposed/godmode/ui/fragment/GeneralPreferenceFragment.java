package com.kaisar.xposed.godmode.ui.fragment;

import static com.kaisar.xposed.godmode.ui.fragment.GeneralPreferenceFragmentDirections.actionGeneralPreferenceFragmentToAboutFragment;
import static com.kaisar.xposed.godmode.ui.fragment.GeneralPreferenceFragmentDirections.actionGeneralPreferenceFragmentToGuideFragment;
import static com.kaisar.xposed.godmode.ui.fragment.GeneralPreferenceFragmentDirections.actionGeneralPreferenceFragmentToRuleRecordListFragment;
import static com.kaisar.xposed.godmode.ui.fragment.GeneralPreferenceFragmentDirections.actionGeneralPreferenceFragmentToSettingsFragment;
import static com.kaisar.xposed.godmode.ui.fragment.GeneralPreferenceFragmentDirections.actionGeneralPreferenceFragmentToViewRuleDetailsContainerFragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.snackbar.Snackbar;
import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.CrashHandler;
import com.kaisar.xposed.godmode.GodModeApplication;
import com.kaisar.xposed.godmode.ui.EditModeController;
import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.ipc.RuleServiceContract;
import com.kaisar.xposed.godmode.ui.glide.RulePreviewSpec;
import com.kaisar.xposed.godmode.ui.preference.ProgressPreference;
import com.kaisar.xposed.godmode.ui.model.SharedViewModel;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.util.TaskExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GeneralPreferenceFragment extends PreferenceFragmentCompat implements
        Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String SETTING_PREFS = "settings";
    private static final String KEY_VERSION_CODE = "version_code";

    private ProgressPreference mProgressPreference;
    private SwitchPreferenceCompat mEditorSwitchPreference;
    private RequestManager mImageRequests;
    private Drawable mDefaultIcon;

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
        if (appRules == null) {
            showRebootRequiredDialog();
            return;
        }
        Set<Map.Entry<String, ActRules>> entries = appRules.entrySet();
        PreferenceCategory category = (PreferenceCategory) findPreference(getString(R.string.pref_key_app_rules));
        category.removeAll();
        PackageManager pm = requireContext().getPackageManager();
        for (Map.Entry<String, ActRules> entry : entries) {
            String packageName = entry.getKey();
            addAppRulePreference(category, pm, packageName, appRules);
        }
    }

    private void addAppRulePreference(PreferenceCategory category, PackageManager pm, String packageName, AppRules appRules) {
        Drawable icon;
        CharSequence label;
        try {
            ApplicationInfo aInfo = pm.getApplicationInfo(packageName, 0);
            icon = aInfo.loadIcon(pm);
            label = aInfo.loadLabel(pm);
        } catch (PackageManager.NameNotFoundException e) {
            icon = loadDefaultAppIcon();
            label = packageName;
        }
        // 匿名 Preference 内引用需 effectively final
        final Drawable headerIconDrawable = icon;
        final CharSequence headerLabel = label;
        List<RuleRecord> sortedRules = flattenSorted(appRules.get(packageName));

        // 相册行: 分组头(图标+名称+N 个) + 横向滑动缩略卡流。
        // 整行不接管点击——分组头点击进列表/长按清空; 缩略卡点击直达详情/长按删单条。
        Preference preference = new Preference(category.getContext()) {
            @Override
            public void onBindViewHolder(PreferenceViewHolder holder) {
                super.onBindViewHolder(holder);
                holder.itemView.setClickable(false);

                View header = holder.itemView.findViewById(R.id.album_header);
                ImageView headerIcon = holder.itemView.findViewById(R.id.album_header_icon);
                TextView headerName = holder.itemView.findViewById(R.id.album_header_name);
                TextView headerCount = holder.itemView.findViewById(R.id.album_header_count);
                headerIcon.setImageDrawable(headerIconDrawable);
                headerName.setText(headerLabel);
                headerCount.setText(getString(R.string.album_section_count_format, sortedRules.size()));
                header.setOnClickListener(v -> openRuleList(packageName));
                header.setOnLongClickListener(v -> {
                    confirmDeleteApp(packageName, sortedRules.size());
                    return true;
                });

                LinearLayout cardSlot = holder.itemView.findViewById(R.id.album_card_slot);
                cardSlot.removeAllViews();
                LayoutInflater inflater = LayoutInflater.from(cardSlot.getContext());
                for (RuleRecord rule : sortedRules) {
                    View card = inflater.inflate(R.layout.item_album_card, cardSlot, false);
                    ImageView imageView = card.findViewById(R.id.album_card_image);
                    imageView.setImageDrawable(mDefaultIcon);
                    if (mImageRequests != null && !TextUtils.isEmpty(rule.imagePath)) {
                        mImageRequests.load(RulePreviewSpec.from(rule))
                                .placeholder(mDefaultIcon).error(mDefaultIcon)
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .into(imageView);
                    }
                    card.setOnClickListener(v -> openRuleDetails(packageName, rule, sortedRules));
                    card.setOnLongClickListener(v -> {
                        confirmDeleteRule(rule);
                        return true;
                    });
                    cardSlot.addView(card);
                }
            }
        };
        preference.setKey(packageName);
        preference.setLayoutResource(R.layout.item_album_row);
        category.addPreference(preference);
    }

    /** 与 {@link SharedViewModel#updateRuleRecordList(String)} 同序(timestamp 升序拍平), 保证详情 curIndex 对齐. */
    private static List<RuleRecord> flattenSorted(ActRules actRules) {
        List<RuleRecord> list = new ArrayList<>();
        if (actRules != null) actRules.values().forEach(list::addAll);
        Collections.sort(list, (o1, o2) -> Long.compare(o1.timestamp, o2.timestamp));
        return list;
    }

    private void openRuleList(String packageName) {
        mSharedViewModel.updateSelectedPackage(packageName);
        NavController nc = NavHostFragment.findNavController(this);
        nc.navigate(actionGeneralPreferenceFragmentToRuleRecordListFragment());
    }

    private void openRuleDetails(String packageName, RuleRecord rule, List<RuleRecord> sortedRules) {
        int curIndex = -1;
        for (int index = 0; index < sortedRules.size(); index++) {
            RuleRecord candidate = sortedRules.get(index);
            if (candidate.slotKey(candidate.packageName).equals(rule.slotKey(rule.packageName))) {
                curIndex = index;
                break;
            }
        }
        if (curIndex < 0) return;
        mSharedViewModel.updateSelectedPackage(packageName);
        // 跳过列表页时无人响应 selectedPackage 变更, 必须显式填充详情容器的 actRules 数据源
        mSharedViewModel.updateRuleRecordList(packageName);
        NavController nc = NavHostFragment.findNavController(this);
        nc.navigate(actionGeneralPreferenceFragmentToViewRuleDetailsContainerFragment(curIndex));
    }

    private void confirmDeleteApp(String packageName, int ruleCount) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.hey_guy)
                .setMessage(getString(R.string.confirm_delete_rules_longpress, packageName))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (mSharedViewModel.deleteAppRules(packageName)) {
                        Snackbar.make(requireView(),
                                getString(R.string.snack_bar_msg_deleted_app_format, packageName, ruleCount),
                                Snackbar.LENGTH_SHORT).show();
                    } else {
                        Snackbar.make(requireView(), R.string.snack_bar_msg_revert_rule_fail, Snackbar.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void confirmDeleteRule(RuleRecord rule) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.hey_guy)
                .setMessage(R.string.album_confirm_delete_rule)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (mSharedViewModel.deleteRule(rule)) {
                        Snackbar.make(requireView(), R.string.snack_bar_msg_deleted_rule, Snackbar.LENGTH_SHORT).show();
                    } else {
                        Snackbar.make(requireView(), R.string.snack_bar_msg_revert_rule_fail, Snackbar.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private Drawable loadDefaultAppIcon() {
        return ResourcesCompat.getDrawable(getResources(),
                R.mipmap.ic_god, requireContext().getTheme());
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
        int serviceState = RuleServiceClient.getDefault().getServiceState();
        if (serviceState == RuleServiceContract.REBOOT_REQUIRED) {
            mEditorSwitchPreference.setEnabled(false);
            showRebootRequiredDialog();
        } else if (previousVersionCode != BuildConfig.VERSION_CODE) {
            settingsSp.edit().putInt(KEY_VERSION_CODE, BuildConfig.VERSION_CODE).apply();
            showUpdatePolicyDialog();
        } else if (!RuleServiceClient.getDefault().hasLight()) {
            showEnableModuleDialog();
        }
    }

    private void showRebootRequiredDialog() {
        if (!isAdded()) return;
        String reason = RuleServiceClient.getDefault().getServiceFailureMessage();
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.hey_guy)
                .setMessage(reason == null
                        ? getString(R.string.rule_service_reboot_required) : reason)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mImageRequests = Glide.with(this);
        mDefaultIcon = ResourcesCompat.getDrawable(getResources(),
                R.mipmap.ic_god, requireContext().getTheme());
    }

    @Override
    public void onDestroyView() {
        mImageRequests = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return RuleServiceClient.getDefault().hasLight();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        if (mEditorSwitchPreference == preference) {
            RuleServiceClient client = RuleServiceClient.getDefault();
            if (!client.hasLight()) {
                String reason = client.getServiceFailureMessage();
                Toast.makeText(requireContext(), reason == null
                                ? getString(R.string.not_active_module)
                                : getString(R.string.not_active_module_with_reason, reason),
                        Toast.LENGTH_SHORT).show();
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
        }
        return true;
    }

    private void setMasterEnabled(boolean enable) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
        sp.edit().putBoolean(getString(R.string.pref_key_master), enable).apply();
        if (!enable) {
            Context applicationContext = requireContext().getApplicationContext();
            TaskExecutor.executeIo(() ->
                    EditModeController.setEditModeEnabled(applicationContext, false));
        }
        EditModeController.startNotificationService(requireContext());
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
        RuleServiceClient client = RuleServiceClient.getDefault();
        String message = getString(R.string.not_active_module);
        String failureMessage = client.getServiceFailureMessage();
        if (!TextUtils.isEmpty(failureMessage)) {
            message = getString(R.string.not_active_module_with_reason, failureMessage);
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.hey_guy)
                .setMessage(message)
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
