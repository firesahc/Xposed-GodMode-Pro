package com.kaisar.xposed.godmode.ui.fragment;

import android.content.ActivityNotFoundException;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.google.android.material.snackbar.Snackbar;
import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ui.model.SharedViewModel;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.util.AppInfoHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 规则记录列表 Fragment — 显示指定应用的屏蔽/修改规则列表。
 * <p>
 * 支持按规则类型（全部/屏蔽/修改）筛选、批量删除、备份规则到文件。
 * 通过 {@link SharedViewModel} 与宿主 Activity 共享规则数据。
 * 使用 DiffUtil 实现 RecyclerView 高效增量更新。
 */
public final class RuleRecordListFragment extends Fragment {

    private static final String TAG = "RuleRecordListFragment";
    private static final int FILTER_ALL = 0;
    private static final int FILTER_REMOVE = 1;
    private static final int FILTER_MODIFY = 2;

    private int mRuleFilter = FILTER_ALL;
    private Menu mMenu;
    private Drawable mIcon;
    private String mPackageName;
    private RecyclerView mRecyclerView;
    private RequestManager mImageRequests;
    private SharedViewModel mSharedViewModel;
    private ActivityResultLauncher<String> mBackupLauncher;
    private List<RuleRecord> mAllRules = new ArrayList<>();
    private List<RuleRecord> mPendingBackupRules;
    private boolean mIsBatchOperation;

    public RuleRecordListFragment() {
        super(R.layout.fragment_rule_list);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        mSharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        mPackageName = mSharedViewModel.selectedPackage.getValue();
        Objects.requireNonNull(mPackageName, "mSelectedPackage should not be null.");
        try {
            PackageManager packageManager = requireContext().getPackageManager();
            mIcon = packageManager.getApplicationIcon(mPackageName);
        } catch (PackageManager.NameNotFoundException e) {
            mIcon = ResourcesCompat.getDrawable(getResources(), R.mipmap.ic_god, requireContext().getTheme());
        }
        mBackupLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument(), this::onBackupFileSelected);
        mSharedViewModel.selectedPackage.observe(this, packageName -> mSharedViewModel.updateRuleRecordList(packageName));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mImageRequests = Glide.with(this);
        RecyclerView recyclerView = (RecyclerView) view;
        ListAdapter adapter = (ListAdapter) recyclerView.getAdapter();
        if (adapter == null) {
            LinearLayoutManager linearLayoutManager = new LinearLayoutManager(requireContext());
            recyclerView.setLayoutManager(linearLayoutManager);
            recyclerView.setItemAnimator(null);
            recyclerView.setAdapter(new ListAdapter());
        }
        maybeShowLongPressHint(view);
        // 使用 viewLifecycleOwner 避免视图销毁后仍收到通知
        mSharedViewModel.actRules.observe(getViewLifecycleOwner(), newData -> {
            mAllRules = newData != null ? newData : new ArrayList<>();
            if (!mIsBatchOperation) {
                updateFilteredList();
            }
        });
    }

    /** 长按删除的可发现性引导 —— 仅首次进入列表页时提示一次. */
    private void maybeShowLongPressHint(View view) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String key = getString(R.string.pref_key_hint_longpress_delete);
        if (sp.getBoolean(key, false)) return;
        Snackbar.make(view, R.string.toast_hint_longpress_delete, Snackbar.LENGTH_LONG).show();
        sp.edit().putBoolean(key, true).apply();
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

    private void updateFilteredList() {
        List<RuleRecord> items = buildFilteredItems();
        if (items.isEmpty() && mAllRules.isEmpty()) {
            NavHostFragment.findNavController(this).popBackStack();
            return;
        }
        ListAdapter adapter = (ListAdapter) mRecyclerView.getAdapter();
        if (adapter != null) {
            List<RuleRecord> oldData = new ArrayList<>(adapter.getItems());
            adapter.setItems(items);
            DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new Callback(oldData, items));
            diffResult.dispatchUpdatesTo(adapter);
        }
        updateTitle(items.size());
    }

    private List<RuleRecord> buildFilteredItems() {
        List<RuleRecord> items = new ArrayList<>();
        for (RuleRecord rule : mAllRules) {
            if (mRuleFilter == FILTER_ALL
                    || (mRuleFilter == FILTER_REMOVE && rule.isRemoveRule())
                    || (mRuleFilter == FILTER_MODIFY && rule.isModifyRule())) {
                items.add(rule);
            }
        }
        return items;
    }

    private void updateTitle(int count) {
        int titleRes;
        if (mRuleFilter == FILTER_REMOVE) {
            titleRes = R.string.menu_filter_remove;
        } else if (mRuleFilter == FILTER_MODIFY) {
            titleRes = R.string.menu_filter_modify;
        } else {
            titleRes = R.string.menu_filter_all;
        }
        String fullTitle = getString(titleRes) + " (" + count + ")";
        androidx.appcompat.widget.Toolbar toolbar = requireActivity().findViewById(R.id.main_toolbar);
        if (toolbar != null) {
            toolbar.setTitle(fullTitle);
        }
    }

    private void onBackupFileSelected(Uri uri) {
        if (uri == null) return;
        List<RuleRecord> rulesToBackup = mPendingBackupRules != null ? mPendingBackupRules : mAllRules;
        mPendingBackupRules = null;
        if (rulesToBackup.isEmpty()) {
            Logger.w(TAG, "backupRules: no rules to backup for " + mPackageName);
            showSnackbar(R.string.snack_bar_msg_backup_rule_fail);
            return;
        }
        mSharedViewModel.backupRules(uri, mPackageName, rulesToBackup, new SharedViewModel.ResultCallback() {
            @Override
            public void onSuccess(int count) {
                showSnackbar(R.string.snack_bar_msg_backup_rule_success, count);
            }

            @Override
            public void onFailure(Exception e) {
                showSnackbar(R.string.snack_bar_msg_backup_rule_fail);
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (mRecyclerView == null) {
            mRecyclerView = (RecyclerView) super.onCreateView(inflater, container, savedInstanceState);
        }
        return mRecyclerView;
    }

    @Override
    public void onDestroyView() {
        if (mRecyclerView != null) {
            mRecyclerView.setAdapter(null);
            mRecyclerView = null;
        }
        mImageRequests = null;
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mRecyclerView == null) return;
        ListAdapter adapter = (ListAdapter) mRecyclerView.getAdapter();
        if (adapter != null) {
            updateTitle(adapter.getItemCount());
        }
    }

    private static final class Callback extends DiffUtil.Callback {

        final List<RuleRecord> mOldData, mNewData;

        private Callback(List<RuleRecord> oldData, List<RuleRecord> newData) {
            mOldData = oldData;
            mNewData = newData;
        }

        @Override
        public int getOldListSize() { return mOldData.size(); }

        @Override
        public int getNewListSize() { return mNewData.size(); }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return mOldData.get(oldItemPosition).equals(mNewData.get(newItemPosition));
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            return mOldData.get(oldItemPosition).contentEquals(mNewData.get(newItemPosition));
        }
    }

    private final class ListAdapter extends RecyclerView.Adapter<ListAdapter.ViewHolder> implements View.OnClickListener {

        @LayoutRes
        private final int mLayoutResId = androidx.preference.R.layout.preference_material;
        private final List<RuleRecord> mData = new ArrayList<>();

        public void setItems(List<RuleRecord> newData) {
            mData.clear();
            mData.addAll(newData);
        }

        public List<RuleRecord> getItems() {
            return mData;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View itemView = LayoutInflater.from(parent.getContext()).inflate(mLayoutResId, parent, false);
            return new ViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            RuleRecord rule = mData.get(position);
            bindItem(holder, rule);
            holder.itemView.setFocusable(true);
            holder.itemView.setClickable(true);
            holder.itemView.setOnClickListener(this);
            holder.itemView.setOnLongClickListener(v -> {
                confirmDeleteRule(rule);
                return true;
            });
        }

        private void bindItem(ViewHolder holder, RuleRecord rule) {
            RequestManager imageRequests = mImageRequests;
            if (imageRequests != null) {
                imageRequests.clear(holder.imageView);
            }
            holder.imageView.setImageDrawable(mIcon);
            if (rule.isRemoveRule()) {
                if (imageRequests != null) {
                    imageRequests.load(com.kaisar.xposed.godmode.ui.glide.RulePreviewSpec.from(rule))
                            .placeholder(mIcon).error(mIcon)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                            .into(holder.imageView);
                }
                bindTitle(holder, rule.getActivityClass(), R.string.rule_type_remove);
                SpannableStringBuilder summaryBuilder = new SpannableStringBuilder();
                if (!TextUtils.isEmpty(rule.alias)) {
                    SpannableString ss = new SpannableString(getString(R.string.field_rule_alias, rule.alias));
                    ss.setSpan(new ForegroundColorSpan(requireContext().getResources().getColor(R.color.prefsAliasColor, requireContext().getTheme())), 0, ss.length(), 0);
                    summaryBuilder.append(ss);
                }
                summaryBuilder.append(getString(R.string.field_view, rule.getViewClass()));
                holder.summaryView.setText(summaryBuilder);
                if (rule.isRepeatable()) appendRepeatableBadge(holder);
            } else {
                if (!TextUtils.isEmpty(rule.imagePath) && imageRequests != null) {
                    imageRequests.load(com.kaisar.xposed.godmode.ui.glide.RulePreviewSpec.from(rule))
                            .placeholder(mIcon).error(mIcon)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                            .into(holder.imageView);
                }
                bindTitle(holder, rule.getActivityClass(), R.string.rule_type_modify);
                SpannableStringBuilder summaryBuilder = new SpannableStringBuilder();
                if (!TextUtils.isEmpty(rule.alias)) {
                    SpannableString ss = new SpannableString(getString(R.string.field_rule_alias, rule.alias));
                    ss.setSpan(new ForegroundColorSpan(requireContext().getResources().getColor(R.color.prefsAliasColor, requireContext().getTheme())), 0, ss.length(), 0);
                    summaryBuilder.append(ss);
                }
                ArrayList<String> mods = new ArrayList<>();
                if (rule.isWidthModified()) mods.add(getString(R.string.modify_detail_width, rule.getModWidth()));
                if (rule.isHeightModified()) mods.add(getString(R.string.modify_detail_height, rule.getModHeight()));
                if (rule.isAlphaModified()) mods.add(getString(R.string.modify_detail_alpha, String.format(Locale.getDefault(), "%.1f", rule.getModAlpha())));
                if (rule.isPositionModified()) mods.add(getString(R.string.modify_detail_position, rule.getModXOffset(), rule.getModYOffset()));
                if (rule.isTextModified()) mods.add(getString(R.string.modify_detail_text));
                if (rule.isImageModified()) mods.add(getString(R.string.modify_detail_image));
                if (!mods.isEmpty()) {
                    summaryBuilder.append(TextUtils.join("\n", mods));
                }
                summaryBuilder.append("\n").append(getString(R.string.field_view, rule.getViewClass()));
                summaryBuilder.append(" ").append(getString(R.string.field_depth, Arrays.toString(rule.getDepth())));
                holder.summaryView.setText(summaryBuilder);
                if (rule.isRepeatable()) appendRepeatableBadge(holder);
            }
        }

        private void appendRepeatableBadge(ViewHolder holder) {
            CharSequence current = holder.summaryView.getText();
            SpannableStringBuilder sb;
            if (current instanceof SpannableStringBuilder) {
                sb = (SpannableStringBuilder) current;
            } else {
                sb = new SpannableStringBuilder(current != null ? current : "");
            }
            sb.append("\n");
            SpannableString badge = new SpannableString(getString(R.string.rule_repeatable_badge));
            badge.setSpan(new ForegroundColorSpan(
                requireContext().getResources().getColor(R.color.prefsAliasColor, requireContext().getTheme())),
                0, badge.length(), 0);
            sb.append(badge);
            holder.summaryView.setText(sb);
        }

        private void bindTitle(ViewHolder holder, String activityClass, int ruleTypeResId) {
            if (activityClass != null) {
                String ruleType = getString(ruleTypeResId);
                String activityName = activityClass.substring(activityClass.lastIndexOf('.') + 1);
                holder.titleView.setText("[" + ruleType + "] " + getString(R.string.field_activity, activityName));
                holder.titleView.setSingleLine();
            }
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            RequestManager imageRequests = mImageRequests;
            if (imageRequests != null) {
                imageRequests.clear(holder.imageView);
            }
            holder.imageView.setImageDrawable(mIcon);
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }

        @Override
        public void onClick(View view) {
            final int position = mRecyclerView.getChildAdapterPosition(view);
            if (position < 0 || position >= mData.size()) return;
            RuleRecord rule = mData.get(position);
            int rulePos = -1;
            for (int index = 0; index < mAllRules.size(); index++) {
                RuleRecord candidate = mAllRules.get(index);
                if (candidate.slotKey(candidate.packageName)
                        .equals(rule.slotKey(rule.packageName))) {
                    rulePos = index;
                }
            }
            if (rulePos >= 0) {
                NavHostFragment.findNavController(RuleRecordListFragment.this).navigate(
                        RuleRecordListFragmentDirections.actionRuleRecordListFragmentToRuleRecordDetailsContainerFragment(rulePos));
            }
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            final ImageView imageView;
            final TextView titleView;
            final TextView summaryView;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                imageView = itemView.findViewById(android.R.id.icon);
                titleView = itemView.findViewById(android.R.id.title);
                summaryView = itemView.findViewById(android.R.id.summary);
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_app_rules, menu);
        MenuItem filterItem = menu.add(Menu.NONE, Menu.NONE, Menu.NONE, R.string.filter_dialog_title);
        filterItem.setIcon(R.drawable.ic_filter);
        filterItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        mMenu = menu;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_delete_rules) {
            if (mRuleFilter == FILTER_ALL) {
                deleteAllRules();
            } else {
                deleteFilteredRules();
            }
            return true;
        } else if (id == R.id.menu_backup_rules) {
            try {
                List<RuleRecord> filtered = buildFilteredItems();
                if (filtered.isEmpty()) {
                    showSnackbar(R.string.snack_bar_msg_backup_rule_fail);
                    return true;
                }
                mPendingBackupRules = new ArrayList<>(filtered);
                mBackupLauncher.launch(AppInfoHelper.generateBackupFilename(requireContext(), mPackageName));
                return true;
            } catch (ActivityNotFoundException | PackageManager.NameNotFoundException e) {
                Logger.w(TAG, "backupRules: launch failed for " + mPackageName, e);
                showSnackbar(R.string.snack_bar_msg_backup_rule_fail);
                return false;
            }
        }
        // Handle filter icon click — compare by title string since it's a programmatic MenuItem
        if (item.getTitle() != null && item.getTitle().equals(getString(R.string.filter_dialog_title))) {
            showFilterDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void deleteAllRules() {
        if (mAllRules.isEmpty()) {
            showSnackbar(R.string.snack_bar_msg_revert_rule_fail);
            return;
        }
        showDeleteConfirmDialog(mAllRules.size(), () -> {
            if (!mSharedViewModel.deleteAppRules(mPackageName)) {
                showSnackbar(R.string.snack_bar_msg_revert_rule_fail);
            }
        });
    }

    private void deleteFilteredRules() {
        List<RuleRecord> filtered = buildFilteredItems();
        if (filtered.isEmpty()) {
            showSnackbar(R.string.snack_bar_msg_revert_rule_fail);
            return;
        }
        showDeleteConfirmDialog(filtered.size(), () -> {
            mIsBatchOperation = true;
            int failed = 0;
            for (RuleRecord rule : filtered) {
                if (!isAdded()) break;
                try {
                    if (!mSharedViewModel.deleteRule(rule)) {
                        failed++;
                        Logger.w(TAG, "deleteFilteredRules: delete returned false package="
                                + mPackageName + " activity=" + rule.getActivityClass()
                                + " view=" + rule.getViewClass()
                                + " resource=" + rule.getResourceName());
                    }
                } catch (Exception e) {
                    failed++;
                    Logger.w(TAG, "deleteFilteredRules: delete exception", e);
                }
            }
            mIsBatchOperation = false;
            if (isAdded()) {
                if (failed == 0) {
                    updateFilteredList();
                } else if (failed == filtered.size()) {
                    updateFilteredList();
                    showSnackbar(R.string.snack_bar_msg_revert_rule_fail);
                } else {
                    // 部分删除失败，重新加载规则列表
                    mSharedViewModel.loadAppRules();
                    if (failed > 0) {
                        Toast.makeText(requireContext(),
                                getString(R.string.snack_bar_msg_revert_rule_fail) + " (" + failed + ")",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    private void showFilterDialog() {
        String[] items = {getString(R.string.menu_filter_all), getString(R.string.menu_filter_remove), getString(R.string.menu_filter_modify)};
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.filter_dialog_title)
                .setSingleChoiceItems(items, mRuleFilter, (dialog, which) -> {
                    mRuleFilter = which;
                    updateFilteredList();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDeleteConfirmDialog(int count, Runnable onConfirm) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.hey_guy)
                .setMessage(getString(R.string.confirm_delete_rules, count) + "\n" + mPackageName)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> onConfirm.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSnackbar(int messageResId, Object... formatArgs) {
        View view = getView();
        if (!isAdded() || view == null) return;
        String message = formatArgs.length == 0
                ? getString(messageResId) : getString(messageResId, formatArgs);
        Snackbar.make(view, message, Snackbar.LENGTH_SHORT).show();
    }
}
