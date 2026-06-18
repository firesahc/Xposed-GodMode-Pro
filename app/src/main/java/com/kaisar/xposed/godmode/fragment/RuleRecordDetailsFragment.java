package com.kaisar.xposed.godmode.fragment;

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
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.Preconditions;
import com.kaisar.xposed.godmode.injection.bridge.RuleServiceClient;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.model.SharedViewModel;
import com.kaisar.xposed.godmode.preference.ImageViewPreference;
import com.kaisar.xposed.godmode.rule.RuleRecord;


import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/**
 * Created by jrsen on 17-10-29.
 */

public final class RuleRecordDetailsFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "RuleRecordDetailsFragment";

    private RuleRecord mRuleRecord;

    private SharedViewModel mSharedViewModel;
    private EditTextPreference mAliasPreference;
    private DropDownPreference mVisiblePreference;
    private ImageViewPreference mImagePreference;
    private Handler mHandler;

    public void setRuleRecord(RuleRecord viewRule) {
        mRuleRecord = viewRule;
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
        } catch (PackageManager.NameNotFoundException e) {
            // 包名未找到，使用默认图标
        }
        Preference headerPreference = findPreference(getString(R.string.pref_key_detail_rule_info));
        headerPreference.setIcon(icon);
        headerPreference.setTitle(label);
        headerPreference.setSummary(packageName);

        Preference preference = findPreference(getString(R.string.pref_key_detail_rule_created_time));
        preference.setTitle(R.string.rule_details_field_create_time);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault());
        preference.setSummary(dateFormat.format(new Date(mRuleRecord.timestamp)));

        preference = findPreference(getString(R.string.pref_key_detail_rule_match_version));
        preference.setTitle(R.string.rule_details_field_generate_version);
        if (!TextUtils.isEmpty(mRuleRecord.matchVersionName)) {
            preference.setSummary(String.format(Locale.getDefault(), "%s %s", label, mRuleRecord.matchVersionName));
        } else {
            preference.setSummary(label);
        }

        preference = findPreference(getString(R.string.pref_key_detail_rule_applied_activity));
        preference.setTitle(R.string.rule_details_field_activity);
        preference.setSummary(Preconditions.optionDefault(mRuleRecord.activityClass, "None"));

        mAliasPreference = (EditTextPreference) findPreference(getString(R.string.pref_key_detail_rule_alias));
        mAliasPreference.setTitle(R.string.rule_details_field_alias);
        mAliasPreference.setDialogTitle(R.string.rule_details_set_alias);
        mAliasPreference.setSummary(Preconditions.optionDefault(mRuleRecord.alias, getString(R.string.rule_details_set_alias)));
        mAliasPreference.setPersistent(false);
        mAliasPreference.setOnPreferenceChangeListener(this);
        mAliasPreference.setOnPreferenceClickListener(preference1 -> {
            ((EditTextPreference) preference1).setText(mRuleRecord.alias);
            return false;
        });

        preference = findPreference(getString(R.string.pref_key_detail_view_bounds));
        if (mRuleRecord.x >= 0 && mRuleRecord.y >= 0) {
            Rect bounds = new Rect(mRuleRecord.x, mRuleRecord.y, mRuleRecord.x + mRuleRecord.width, mRuleRecord.y + mRuleRecord.height);
            preference.setTitle(R.string.rule_details_field_view_bounds);
            preference.setSummary(bounds.toShortString());
        } else {
            preference.setVisible(false);
        }

        preference = findPreference(getString(R.string.pref_key_detail_view_type));
        preference.setTitle(R.string.rule_details_field_view_type);
        preference.setSummary(mRuleRecord.viewClass);

        preference = findPreference(getString(R.string.pref_key_detail_view_depth));
        preference.setTitle(R.string.rule_details_field_view_depth);
        preference.setSummary(Arrays.toString(mRuleRecord.depth));

        if (!TextUtils.isEmpty(mRuleRecord.resourceName)) {
            preference = findPreference(getString(R.string.pref_key_detail_view_res_name));
            preference.setTitle(R.string.rule_details_field_res_name);
            preference.setSummary(mRuleRecord.resourceName);
            preference.setVisible(true);
        }

        if (!TextUtils.isEmpty(mRuleRecord.text)) {
            preference = findPreference(getString(R.string.pref_key_detail_view_text));
            preference.setTitle(R.string.rule_details_field_text);
            preference.setSummary(mRuleRecord.text);
            preference.setVisible(true);
        }
        if (!TextUtils.isEmpty(mRuleRecord.description)) {
            preference = findPreference(getString(R.string.pref_key_detail_view_description));
            preference.setTitle(R.string.rule_details_field_description);
            preference.setSummary(mRuleRecord.description);
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
        mVisiblePreference.setValue(String.valueOf(mRuleRecord.visibility));

        mImagePreference = (ImageViewPreference) findPreference(getString(R.string.pref_key_detail_preview_image));
        if (!TextUtils.isEmpty(mRuleRecord.imagePath)) {
            // 同步解码图片尺寸，抢在 View 绑定前设置占位高度
            reserveImagePlaceholder();
            loadRuleImage();
        } else {
            mImagePreference.setVisible(false);
        }

        if (mRuleRecord.isModifyRule()) {
            showModifyDetails();
        }
    }

    private void showModifyDetails() {
        Preference pref;

        if (mRuleRecord.isWidthModified()) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_width));
            pref.setVisible(true);
            pref.setSummary(String.valueOf(mRuleRecord.modWidth));
        }
        if (mRuleRecord.isHeightModified()) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_height));
            pref.setVisible(true);
            pref.setSummary(String.valueOf(mRuleRecord.modHeight));
        }
        if (mRuleRecord.isAlphaModified()) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_alpha));
            pref.setVisible(true);
            pref.setSummary(String.format(Locale.getDefault(), "%.2f", mRuleRecord.modAlpha));
        }
        if (mRuleRecord.isPositionModified()) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_position));
            pref.setVisible(true);
            pref.setSummary(String.format(Locale.getDefault(), "(%d, %d)", mRuleRecord.modXOffset, mRuleRecord.modYOffset));
        }
        if (mRuleRecord.isTextModified() && !TextUtils.isEmpty(mRuleRecord.modText)) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_text));
            pref.setVisible(true);
            pref.setSummary(mRuleRecord.modText);
        }
        if (mRuleRecord.isImageModified() && !TextUtils.isEmpty(mRuleRecord.modImagePath)) {
            pref = findPreference(getString(R.string.pref_key_detail_mod_image));
            pref.setVisible(true);
            pref.setSummary(mRuleRecord.modImagePath);
        }
    }

    private void reserveImagePlaceholder() {
        try {
            ParcelFileDescriptor pfd = RuleServiceClient.getDefault().openImageFileDescriptor(mRuleRecord.imagePath);
            if (pfd == null) return;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
            try { pfd.close(); } catch (Exception ignored) { }
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int marginPx = (int) (20 * getResources().getDisplayMetrics().density);
                int availableWidth = screenWidth - marginPx;
                int height = (int) ((float) availableWidth * opts.outHeight / opts.outWidth);
                mImagePreference.reserveHeight(Math.max(height, 1));
            }
        } catch (Exception e) {
            Logger.w(TAG, "[reserveImagePlaceholder] " + e.getMessage());
        }
    }

    private void loadRuleImage() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        TaskExecutor.executeImageLoad(() -> {
            if (!isAdded()) return;
            Bitmap bitmap = loadRuleImageBitmap(mRuleRecord);
            if (bitmap == null) return;
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int marginPx = (int) (20 * getResources().getDisplayMetrics().density);
            int availableWidth = screenWidth - marginPx;
            int fixedHeight = (int) ((float) availableWidth * bitmap.getHeight() / bitmap.getWidth());
            mHandler.post(() -> {
                if (isAdded()) {
                    mImagePreference.displayBitmap(bitmap, Math.max(fixedHeight, 1));
                }
            });
        });
    }

    @Nullable
    private static Bitmap loadRuleImageBitmap(@NonNull RuleRecord viewRule) {
        try {
            ParcelFileDescriptor pfd = RuleServiceClient.getDefault().openImageFileDescriptor(viewRule.imagePath);
            Objects.requireNonNull(pfd, String.format("Can not open %s", viewRule.imagePath));
            try {
                // 直接使用 decodeFileDescriptor 解码，避免 ByteArrayOutputStream 中间缓冲区
                return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
            } finally {
                try { pfd.close(); } catch (Exception e) { /* closeSilently */ }
            }
        } catch (Exception e) {
            Logger.w(TAG, "[RuleRecordDetails] " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mAliasPreference) {
            mRuleRecord.alias = (String) newValue;
            preference.setSummary(mRuleRecord.alias);
            mSharedViewModel.updateRule(mRuleRecord);
        } else if (preference == mVisiblePreference) {
            int newVisibility = Integer.parseInt((String) newValue);
            if (newVisibility != mRuleRecord.visibility) {
                mRuleRecord.visibility = newVisibility;
                mSharedViewModel.updateRule(mRuleRecord);
            }
        }
        return true;
    }
}
