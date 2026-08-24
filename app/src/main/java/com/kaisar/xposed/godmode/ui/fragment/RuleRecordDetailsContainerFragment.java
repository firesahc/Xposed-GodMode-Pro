package com.kaisar.xposed.godmode.ui.fragment;

import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback;

import com.google.android.material.snackbar.Snackbar;
import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.ui.model.SharedViewModel;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.engine.rule.RuleSlotKey;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.util.AppInfoHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RuleRecordDetailsContainerFragment extends Fragment {

    private static final String STATE_CUR_INDEX = "current_rule_index";

    private int mCurIndex;

    private ViewPager2 mViewPager;
    private SharedViewModel mSharedViewModel;

    private ActivityResultLauncher<String> mBackupLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        RuleRecordDetailsContainerFragmentArgs args = RuleRecordDetailsContainerFragmentArgs.fromBundle(requireArguments());
        mCurIndex = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_CUR_INDEX, args.getCurIndex())
                : args.getCurIndex();
        mSharedViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        mBackupLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument(), this::onBackupFileSelected);
    }

    private void onBackupFileSelected(Uri uri) {
        if (uri == null) return;
        List<RuleRecord> rules = mSharedViewModel.actRules.getValue();
        if (rules != null && !rules.isEmpty()) {
            RuleRecord viewRule = rules.get(mCurIndex);
            List<RuleRecord> viewRules = rules.subList(mCurIndex, mCurIndex + 1);
            mSharedViewModel.backupRules(uri, viewRule.packageName, viewRules, new SharedViewModel.ResultCallback() {
                @Override
                public void onSuccess(int count) {
                    showSnackbar(R.string.snack_bar_msg_backup_done_format, count);
                }

                @Override
                public void onFailure(Exception e) {
                    showSnackbar(R.string.snack_bar_msg_backup_rule_fail);
                }
            });
        } else {
            showSnackbar(R.string.snack_bar_msg_backup_rule_fail);
        }
    }

    public RuleRecordDetailsContainerFragment() {
    }

    private final OnPageChangeCallback mCallback = new OnPageChangeCallback() {
        @Override
        public void onPageSelected(int position) {
            mCurIndex = position;
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        DetailFragmentStateAdapter detailFragmentStateAdapter= new DetailFragmentStateAdapter(this);
        detailFragmentStateAdapter.setData(mSharedViewModel.actRules.getValue());
        mViewPager = (ViewPager2) inflater.inflate(R.layout.fragment_rule_details_container, container, false);
        mViewPager.setAdapter(detailFragmentStateAdapter);
        mViewPager.registerOnPageChangeCallback(mCallback);
        int safeIndex = Math.min(mCurIndex, Math.max(0, detailFragmentStateAdapter.getItemCount() - 1));
        mCurIndex = safeIndex;
        mViewPager.setCurrentItem(safeIndex, false);
        return mViewPager;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_CUR_INDEX, mCurIndex);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        if (mViewPager != null) {
            mViewPager.unregisterOnPageChangeCallback(mCallback);
            mViewPager.setAdapter(null);
            mViewPager = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_app_rule, menu);
    }

    @Override
    public void onStart() {
        super.onStart();
        mSharedViewModel.actRules.observe(getViewLifecycleOwner(), newData -> {
            if (newData.isEmpty()) {
                NavHostFragment.findNavController(this).popBackStack();
            } else {
                DetailFragmentStateAdapter adapter = (DetailFragmentStateAdapter) mViewPager.getAdapter();
                if (adapter == null) return;
                List<RuleRecord> oldData = adapter.getData();
                DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                    @Override public int getOldListSize() { return oldData.size(); }
                    @Override public int getNewListSize() { return newData.size(); }
                    @Override
                    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                        RuleRecord oldRule = oldData.get(oldItemPosition);
                        RuleRecord newRule = newData.get(newItemPosition);
                        return oldRule.slotKey(oldRule.packageName)
                                .equals(newRule.slotKey(newRule.packageName));
                    }
                    @Override
                    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                        return oldData.get(oldItemPosition).contentEquals(newData.get(newItemPosition));
                    }
                });
                adapter.setData(newData);
                diffResult.dispatchUpdatesTo(adapter);
                if (mCurIndex >= newData.size() && newData.size() > 0) {
                    mCurIndex = newData.size() - 1;
                    mViewPager.setCurrentItem(mCurIndex, false);
                }
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        List<RuleRecord> viewRules = mSharedViewModel.actRules.getValue();
        if (viewRules != null && !viewRules.isEmpty() && mCurIndex < viewRules.size()) {
            RuleRecord viewRule = viewRules.get(mCurIndex);
            if (item.getItemId() == R.id.menu_delete_rule) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.hey_guy)
                        .setMessage(R.string.album_confirm_delete_rule)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            mSharedViewModel.deleteRule(viewRule);
                            NavHostFragment.findNavController(this).popBackStack();
                        })
                        .setNegativeButton(android.R.string.cancel, null).show();
            } else if (item.getItemId() == R.id.menu_backup_rule) {
                try {
                    String packageName = mSharedViewModel.selectedPackage.getValue();
                    if (packageName == null)
                        throw new PackageManager.NameNotFoundException("packageName should not be null.");
                    mBackupLauncher.launch(AppInfoHelper.generateBackupFilename(requireContext(), packageName));
                    return true;
                } catch (ActivityNotFoundException | PackageManager.NameNotFoundException e) {
                    showSnackbar(R.string.snack_bar_msg_backup_rule_fail);
                    return false;
                }
            }
        }
        return true;
    }

    private void showSnackbar(int messageResId) {
        View view = getView();
        if (!isAdded() || view == null) return;
        Snackbar.make(view, messageResId, Snackbar.LENGTH_SHORT).show();
    }

    private void showSnackbar(int messageResId, Object... formatArgs) {
        View view = getView();
        if (!isAdded() || view == null) return;
        Snackbar.make(view, getString(messageResId, formatArgs), Snackbar.LENGTH_SHORT).show();
    }

    static final class DetailFragmentStateAdapter extends FragmentStateAdapter {

        final List<RuleRecord> mData = new ArrayList<>();
        final Map<RuleSlotKey, List<Long>> mIds = new HashMap<>();
        final List<Long> mItemIds = new ArrayList<>();
        long mNextId;

        public DetailFragmentStateAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        public List<RuleRecord> getData() {
            return mData;
        }

        public void setData(List<RuleRecord> data) {
            Set<RuleSlotKey> liveKeys = new HashSet<>();
            Map<RuleSlotKey, Integer> occurrences = new HashMap<>();
            mItemIds.clear();
            if (data != null) {
                for (RuleRecord rule : data) {
                    RuleSlotKey key = rule.slotKey(rule.packageName);
                    liveKeys.add(key);
                    int occurrence = occurrences.containsKey(key) ? occurrences.get(key) : 0;
                    occurrences.put(key, occurrence + 1);
                    List<Long> ids = mIds.computeIfAbsent(key, unused -> new ArrayList<>());
                    while (ids.size() <= occurrence) ids.add(mNextId++);
                    mItemIds.add(ids.get(occurrence));
                    if (occurrence > 0) {
                        Logger.w("RuleDetails", "duplicate RuleSlotKey retained for legacy compatibility: " + key);
                    }
                }
            }
            mIds.keySet().removeIf(key -> !liveKeys.contains(key));
            mData.clear();
            if (data != null) mData.addAll(data);
        }

        @Override
        public long getItemId(int position) {
            return mItemIds.get(position);
        }

        @Override
        public boolean containsItem(long itemId) {
            return mItemIds.contains(itemId);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            RuleRecord viewRule = mData.get(position);
            return RuleRecordDetailsFragment.newInstance(viewRule);
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }
    }


}
