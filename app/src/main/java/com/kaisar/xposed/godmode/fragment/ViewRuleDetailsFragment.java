package com.kaisar.xposed.godmode.fragment;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.DropDownPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.model.SharedViewModel;
import com.kaisar.xposed.godmode.preference.ImageViewPreference;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.engine.pool.ThreadPools;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.util.Preconditions;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/**
 * Created by jrsen on 17-10-29.
 */

public final class ViewRuleDetailsFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceChangeListener {

    private ViewRule mViewRule;

    private SharedViewModel mSharedViewModel;
    private EditTextPreference mAliasPreference;
    private DropDownPreference mVisiblePreference;
    private ImageViewPreference mImagePreference;
    private Handler mHandler;

    public void setViewRule(ViewRule viewRule) {
        mViewRule = viewRule;
    }
    
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.pref_rule_details);

        mSharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        String packageName = mSharedViewModel.selectedPackage.getValue();
        Objects.requireNonNull(packageName, "packageName should not be null.");
        Drawable icon = ResourcesCompat.getDrawable(getResources(), R.mipmap.ic_god, requireContext().getTheme());
        String label = packageName;
        try {
            PackageManager packageManager = requireContext().getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            icon = applicationInfo.loadIcon(packageManager);
            label = applicationInfo.loadLabel(packageManager).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        Preference headerPreference = findPreference(getString(R.string.pref_key_detail_rule_info));
        headerPreference.setIcon(icon);
        headerPreference.setTitle(label);
        headerPreference.setSummary(packageName);

        Preference preference = findPreference(getString(R.string.pref_key_detail_rule_created_time));
        preference.setTitle(R.string.rule_details_field_create_time);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault());
        preference.setSummary(dateFormat.format(new Date(mViewRule.timestamp)));

        preference = findPreference(getString(R.string.pref_key_detail_rule_match_version));
        preference.setTitle(R.string.rule_details_field_generate_version);
        if (!TextUtils.isEmpty(mViewRule.matchVersionName)) {
            preference.setSummary(String.format(Locale.getDefault(), "%s %s", label, mViewRule.matchVersionName));
        } else {
            preference.setSummary(label);
        }

        preference = findPreference(getString(R.string.pref_key_detail_rule_applied_activity));
        preference.setTitle(R.string.rule_details_field_activity);
        preference.setSummary(Preconditions.optionDefault(mViewRule.activityClass, "None"));

        mAliasPreference = (EditTextPreference) findPreference(getString(R.string.pref_key_detail_rule_alias));
        mAliasPreference.setTitle(R.string.rule_details_field_alias);
        mAliasPreference.setDialogTitle(R.string.rule_details_set_alias);
        mAliasPreference.setSummary(Preconditions.optionDefault(mViewRule.alias, getString(R.string.rule_details_set_alias)));
        mAliasPreference.setPersistent(false);
        mAliasPreference.setOnPreferenceChangeListener(this);
        mAliasPreference.setOnPreferenceClickListener(preference1 -> {
            ((EditTextPreference) preference1).setText(mViewRule.alias);
            return false;
        });

        preference = findPreference(getString(R.string.pref_key_detail_view_bounds));
        if (mViewRule.x >= 0 && mViewRule.y >= 0) {
            Rect bounds = new Rect(mViewRule.x, mViewRule.y, mViewRule.x + mViewRule.width, mViewRule.y + mViewRule.height);
            preference.setTitle(R.string.rule_details_field_view_bounds);
            preference.setSummary(bounds.toShortString());
        } else {
            preference.setVisible(false);
        }

        preference = findPreference(getString(R.string.pref_key_detail_view_type));
        preference.setTitle(R.string.rule_details_field_view_type);
        preference.setSummary(mViewRule.viewClass);

        preference = findPreference(getString(R.string.pref_key_detail_view_depth));
        preference.setTitle(R.string.rule_details_field_view_depth);
        preference.setSummary(Arrays.toString(mViewRule.depth));

        if (!TextUtils.isEmpty(mViewRule.resourceName)) {
            preference = findPreference(getString(R.string.pref_key_detail_view_res_name));
            preference.setTitle(R.string.rule_details_field_res_name);
            preference.setSummary(mViewRule.resourceName);
            preference.setVisible(true);
        }

        if (!TextUtils.isEmpty(mViewRule.text)) {
            preference = findPreference(getString(R.string.pref_key_detail_view_text));
            preference.setTitle(R.string.rule_details_field_text);
            preference.setSummary(mViewRule.text);
            preference.setVisible(true);
        }
        if (!TextUtils.isEmpty(mViewRule.description)) {
            preference = findPreference(getString(R.string.pref_key_detail_view_description));
            preference.setTitle(R.string.rule_details_field_description);
            preference.setSummary(mViewRule.description);
            preference.setVisible(true);
        }

        mVisiblePreference = (DropDownPreference) findPreference(getString(R.string.pref_key_detail_view_visible));
        mVisiblePreference.setOnPreferenceChangeListener(this);
        mVisiblePreference.setTitle(R.string.rule_details_field_visible);
        String[] entries = getResources().getStringArray(R.array.visible_entries);
        String[] values = getResources().getStringArray(R.array.visible_values);
        mVisiblePreference.setSummary("%s");
        mVisiblePreference.setEntries(entries);
        mVisiblePreference.setEntryValues(values);
        mVisiblePreference.setValue(String.valueOf(mViewRule.visibility));

        mImagePreference = (ImageViewPreference) findPreference(getString(R.string.pref_key_detail_preview_image));
        if (!TextUtils.isEmpty(mViewRule.imagePath)) {
            loadRuleImage();
        }

        if (mViewRule.isModifyRule()) {
            showModifyDetails();
        }
    }

    private void showModifyDetails() {
        Preference pref;

        if (mViewRule.isWidthModified()) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_width));
            pref.setVisible(true);
            pref.setSummary(String.valueOf(mViewRule.modWidth));
        }
        if (mViewRule.isHeightModified()) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_height));
            pref.setVisible(true);
            pref.setSummary(String.valueOf(mViewRule.modHeight));
        }
        if (mViewRule.isAlphaModified()) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_alpha));
            pref.setVisible(true);
            pref.setSummary(String.format(Locale.getDefault(), "%.2f", mViewRule.modAlpha));
        }
        if (mViewRule.isPositionModified()) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_position));
            pref.setVisible(true);
            pref.setSummary(String.format(Locale.getDefault(), "(%d, %d)", mViewRule.modXOffset, mViewRule.modYOffset));
        }
        if (mViewRule.isTextModified() && !TextUtils.isEmpty(mViewRule.modText)) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_text));
            pref.setVisible(true);
            pref.setSummary(mViewRule.modText);
        }
        if (mViewRule.isImageModified() && !TextUtils.isEmpty(mViewRule.modImagePath)) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_image));
            pref.setVisible(true);
            pref.setSummary(mViewRule.modImagePath);
        }
    }

    private void loadRuleImage() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
            TaskExecutor.executeImageLoad(() -> {
            if (!isAdded()) return;
            Bitmap bitmap = loadRuleImageBitmap(mViewRule);
            mHandler.post(() -> {
                if (bitmap != null && isAdded()) {
                    mImagePreference.setImageBitmap(bitmap);
                }
            });
        });
    }

    @Nullable
    private static Bitmap loadRuleImageBitmap(@NonNull ViewRule viewRule) {
        try {
            ParcelFileDescriptor pfd = GodModeManager.getDefault().openImageFileDescriptor(viewRule.imagePath);
            Objects.requireNonNull(pfd, String.format("Can not open %s", viewRule.imagePath));
            try {
                InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] temp = new byte[8192];
                int n;
                while ((n = in.read(temp)) != -1) {
                    buffer.write(temp, 0, n);
                }
                return BitmapFactory.decodeByteArray(buffer.toByteArray(), 0, buffer.size());
            } finally {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Logger.w(TAG, "[ViewRuleDetails] " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mAliasPreference) {
            mViewRule.alias = (String) newValue;
            preference.setSummary(mViewRule.alias);
            mSharedViewModel.updateRule(mViewRule);
        } else if (preference == mVisiblePreference) {
            int newVisibility = Integer.parseInt((String) newValue);
            if (newVisibility != mViewRule.visibility) {
                mViewRule.visibility = newVisibility;
                mSharedViewModel.updateRule(mViewRule);
            }
        }
        return true;
    }
}
