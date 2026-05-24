package com.kaisar.xposed.godmode.fragment;

import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
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
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.snackbar.Snackbar;
import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.model.SharedViewModel;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.util.AppInfoHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ViewRuleListFragment extends Fragment {

    private static final String TAG = "GodMode";
    private static final int FILTER_ALL = 0;
    private static final int FILTER_REMOVE = 1;
    private static final int FILTER_MODIFY = 2;

    private int mRuleFilter = FILTER_ALL;
    private Menu mMenu;
    private Drawable mIcon;
    private String mPackageName;
    private RecyclerView mRecyclerView;
    private SharedViewModel mSharedViewModel;
    private ActivityResultLauncher<String> mBackupLauncher;
    private List<ViewRule> mAllRules = new ArrayList<>();
    private List<ViewRule> mPendingBackupRules;
    private boolean mIsBatchOperation;

    public ViewRuleListFragment() {
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
        mSharedViewModel.selectedPackage.observe(this, packageName -> mSharedViewModel.updateViewRuleList(packageName));
        mSharedViewModel.actRules.observe(this, newData -> {
            mAllRules = newData != null ? newData : new ArrayList<>();
            if (!mIsBatchOperation) {
                updateFilteredList();
            }
        });
    }

    private void updateFilteredList() {
        List<ViewRule> items = buildFilteredItems();
        if (items.isEmpty() && mAllRules.isEmpty()) {
            NavHostFragment.findNavController(this).popBackStack();
            return;
        }
        ListAdapter adapter = (ListAdapter) mRecyclerView.getAdapter();
        if (adapter != null) {
            List<ViewRule> oldData = new ArrayList<>(adapter.getItems());
            adapter.setItems(items);
            DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new Callback(oldData, items));
            diffResult.dispatchUpdatesTo(adapter);
        }
        updateTitle(items.size());
    }

    private List<ViewRule> buildFilteredItems() {
        List<ViewRule> items = new ArrayList<>();
        for (ViewRule rule : mAllRules) {
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
        List<ViewRule> rulesToBackup = mPendingBackupRules != null ? mPendingBackupRules : mAllRules;
        mPendingBackupRules = null;
        if (rulesToBackup.isEmpty()) {
            Snackbar.make(requireView(), R.string.snack_bar_msg_backup_rule_fail, Snackbar.LENGTH_SHORT).show();
            return;
        }
        mSharedViewModel.backupRules(uri, mPackageName, rulesToBackup, new SharedViewModel.ResultCallback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "[ViewRuleList] backup success: " + rulesToBackup.size() + " rules");
            }

            @Override
            public void onFailure(Exception e) {
                Log.w(TAG, "[ViewRuleList] backup failed", e);
                Snackbar.make(requireView(), R.string.snack_bar_msg_backup_rule_fail, Snackbar.LENGTH_SHORT).show();
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
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView recyclerView = (RecyclerView) view;
        ListAdapter adapter = (ListAdapter) recyclerView.getAdapter();
        if (adapter == null) {
            LinearLayoutManager linearLayoutManager = new LinearLayoutManager(requireContext());
            recyclerView.setLayoutManager(linearLayoutManager);
            recyclerView.setAdapter(new ListAdapter());
        }
    }

    private static final class Callback extends DiffUtil.Callback {

        final List<ViewRule> mOldData, mNewData;

        private Callback(List<ViewRule> oldData, List<ViewRule> newData) {
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
            return Objects.equals(mOldData.get(oldItemPosition), mNewData.get(newItemPosition));
        }
    }

    private final class ListAdapter extends RecyclerView.Adapter<ListAdapter.ViewHolder> implements View.OnClickListener {

        @LayoutRes
        private final int mLayoutResId = androidx.preference.R.layout.preference_material;
        private final List<ViewRule> mData = new ArrayList<>();

        public void setItems(List<ViewRule> newData) {
            mData.clear();
            mData.addAll(newData);
        }

        public List<ViewRule> getItems() {
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
            ViewRule rule = mData.get(position);
            bindItem(holder, rule);
            holder.itemView.setFocusable(true);
            holder.itemView.setClickable(true);
            holder.itemView.setTag(position);
            holder.itemView.setOnClickListener(this);
        }

        private void bindItem(ViewHolder holder, ViewRule rule) {
            if (rule.isRemoveRule()) {
                Glide.with(ViewRuleListFragment.this).load(rule).error(mIcon).diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).into(holder.imageView);
                bindTitle(holder, rule.activityClass, R.string.rule_type_remove);
                SpannableStringBuilder summaryBuilder = new SpannableStringBuilder();
                if (!TextUtils.isEmpty(rule.alias)) {
                    SpannableString ss = new SpannableString(getString(R.string.field_rule_alias, rule.alias));
                    ss.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.prefsAliasColor)), 0, ss.length(), 0);
                    summaryBuilder.append(ss);
                }
                summaryBuilder.append(getString(R.string.field_view, rule.viewClass));
                holder.summaryView.setText(summaryBuilder);
            } else {
                if (!TextUtils.isEmpty(rule.imagePath)) {
                    Glide.with(ViewRuleListFragment.this).load(rule).error(mIcon).diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).into(holder.imageView);
                } else {
                    holder.imageView.setImageDrawable(mIcon);
                }
                bindTitle(holder, rule.activityClass, R.string.rule_type_modify);
                SpannableStringBuilder summaryBuilder = new SpannableStringBuilder();
                if (!TextUtils.isEmpty(rule.alias)) {
                    SpannableString ss = new SpannableString(getString(R.string.field_rule_alias, rule.alias));
                    ss.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.prefsAliasColor)), 0, ss.length(), 0);
                    summaryBuilder.append(ss);
                }
                ArrayList<String> mods = new ArrayList<>();
                if (rule.isWidthModified()) mods.add(getString(R.string.modify_detail_width, rule.modWidth));
                if (rule.isHeightModified()) mods.add(getString(R.string.modify_detail_height, rule.modHeight));
                if (rule.isAlphaModified()) mods.add(getString(R.string.modify_detail_alpha, String.format(Locale.getDefault(), "%.1f", rule.modAlpha)));
                if (rule.isPositionModified()) mods.add(getString(R.string.modify_detail_position, rule.modXOffset, rule.modYOffset));
                if (rule.isTextModified()) mods.add(getString(R.string.modify_detail_text));
                if (rule.isImageModified()) mods.add(getString(R.string.modify_detail_image));
                if (!mods.isEmpty()) {
                    summaryBuilder.append(TextUtils.join("\n", mods));
                }
                summaryBuilder.append("\n").append(getString(R.string.field_view, rule.viewClass));
                summaryBuilder.append(" ").append(getString(R.string.field_depth, Arrays.toString(rule.depth)));
                holder.summaryView.setText(summaryBuilder);
            }
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
        public int getItemCount() {
            return mData.size();
        }

        @Override
        public void onClick(View view) {
            final int position = (Integer) view.getTag();
            ViewRule rule = mData.get(position);
            int rulePos = mAllRules.indexOf(rule);
            if (rulePos >= 0) {
                NavHostFragment.findNavController(ViewRuleListFragment.this).navigate(
                        ViewRuleListFragmentDirections.actionViewRuleListFragmentToViewRuleDetailsContainerFragment(rulePos));
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
        mMenu = menu;
        restoreMenuCheck(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_filter_all) {
            mRuleFilter = FILTER_ALL;
            updateFilteredList();
            restoreMenuCheck(mMenu);
            return true;
        } else if (id == R.id.menu_filter_remove) {
            mRuleFilter = FILTER_REMOVE;
            updateFilteredList();
            restoreMenuCheck(mMenu);
            return true;
        } else if (id == R.id.menu_filter_modify) {
            mRuleFilter = FILTER_MODIFY;
            updateFilteredList();
            restoreMenuCheck(mMenu);
            return true;
        } else if (id == R.id.menu_delete_rules) {
            if (mRuleFilter == FILTER_ALL) {
                deleteAllRules();
            } else {
                deleteFilteredRules();
            }
            return true;
        } else if (id == R.id.menu_backup_rules) {
            try {
                List<ViewRule> filtered = buildFilteredItems();
                if (filtered.isEmpty()) {
                    Snackbar.make(requireView(), R.string.snack_bar_msg_backup_rule_fail, Snackbar.LENGTH_SHORT).show();
                    return true;
                }
                mPendingBackupRules = new ArrayList<>(filtered);
                mBackupLauncher.launch(AppInfoHelper.generateBackupFilename(requireContext(), mPackageName));
                return true;
            } catch (ActivityNotFoundException | PackageManager.NameNotFoundException e) {
                Log.w(TAG, "[ViewRuleList] backup launch failed", e);
                Snackbar.make(requireView(), R.string.snack_bar_msg_backup_rule_fail, Snackbar.LENGTH_SHORT).show();
                return false;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void deleteAllRules() {
        if (mAllRules.isEmpty()) {
            Snackbar.make(requireView(), R.string.snack_bar_msg_revert_rule_fail, Snackbar.LENGTH_SHORT).show();
            return;
        }
        showDeleteConfirmDialog(mAllRules.size(), () -> {
            if (!mSharedViewModel.deleteAppRules(mPackageName)) {
                Snackbar.make(requireView(), R.string.snack_bar_msg_revert_rule_fail, Snackbar.LENGTH_SHORT).show();
            }
        });
    }

    private void deleteFilteredRules() {
        List<ViewRule> filtered = buildFilteredItems();
        if (filtered.isEmpty()) {
            Snackbar.make(requireView(), R.string.snack_bar_msg_revert_rule_fail, Snackbar.LENGTH_SHORT).show();
            return;
        }
        showDeleteConfirmDialog(filtered.size(), () -> {
            mIsBatchOperation = true;
            int failed = 0;
            for (ViewRule rule : filtered) {
                if (!isAdded()) break;
                try {
                    if (!mSharedViewModel.deleteRule(rule)) {
                        failed++;
                        Log.w(TAG, "[ViewRuleList] delete rule failed: " + rule);
                    }
                } catch (Exception e) {
                    failed++;
                    Log.w(TAG, "[ViewRuleList] delete rule failed: " + rule);
                    Log.e(TAG, "[ViewRuleList] delete rule exception", e);
                }
            }
            mIsBatchOperation = false;
            if (isAdded()) {
                if (failed == 0) {
                    updateFilteredList();
                } else if (failed == filtered.size()) {
                    updateFilteredList();
                    Snackbar.make(requireView(), R.string.snack_bar_msg_revert_rule_fail, Snackbar.LENGTH_SHORT).show();
                } else {
                    // 部分失败：重新加载完整数据
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

    private void showDeleteConfirmDialog(int count, Runnable onConfirm) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.hey_guy)
                .setMessage(getString(R.string.confirm_delete_rules, count) + "\n" + mPackageName)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> onConfirm.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void restoreMenuCheck(Menu menu) {
        menu.findItem(R.id.menu_filter_all).setChecked(mRuleFilter == FILTER_ALL);
        menu.findItem(R.id.menu_filter_remove).setChecked(mRuleFilter == FILTER_REMOVE);
        menu.findItem(R.id.menu_filter_modify).setChecked(mRuleFilter == FILTER_MODIFY);
    }
}
