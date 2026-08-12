package com.kaisar.xposed.godmode.testtarget;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int ITEM_COUNT = 120;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RecyclerView verticalList;
    private FeedAdapter adapter;
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), 0);

        TextView title = text("GodMode deterministic target", 20);
        title.setContentDescription("acceptance-static-text");
        root.addView(title, matchWrap());

        LinearLayout observableRow = new LinearLayout(this);
        observableRow.setOrientation(LinearLayout.HORIZONTAL);
        observableRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView image = new ImageView(this);
        image.setImageDrawable(new ColorDrawable(Color.rgb(0, 105, 92)));
        image.setContentDescription("acceptance-static-image");
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        imageParams.setMargins(dp(7), dp(9), dp(11), dp(13));
        observableRow.addView(image, imageParams);

        TextView alphaSample = text("margin=7,9,11,13 alpha=0.65", 14);
        alphaSample.setAlpha(0.65f);
        alphaSample.setContentDescription("acceptance-margin-alpha");
        observableRow.addView(alphaSample, new LinearLayout.LayoutParams(0, dp(64), 1f));
        root.addView(observableRow, matchWrap());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.addView(button("Refresh", "acceptance-refresh", v -> refreshData()), weighted());
        controls.addView(button("Replace", "acceptance-replace", v -> replaceAdapter()), weighted());
        controls.addView(button("Fling", "acceptance-fling", v -> quickScroll()), weighted());
        controls.addView(button("Recreate", "acceptance-recreate", v -> recreate()), weighted());
        root.addView(controls, matchWrap());

        verticalList = new RecyclerView(this);
        verticalList.setContentDescription("acceptance-vertical-list");
        verticalList.setLayoutManager(new LinearLayoutManager(this));
        verticalList.setItemAnimator(null);
        adapter = new FeedAdapter(buildRows(false), generation);
        verticalList.setAdapter(adapter);
        root.addView(verticalList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void refreshData() {
        generation++;
        adapter.replace(buildRows((generation & 1) != 0), generation);
    }

    private void replaceAdapter() {
        generation++;
        adapter = new FeedAdapter(buildRows((generation & 1) != 0), generation);
        verticalList.swapAdapter(adapter, true);
    }

    private void quickScroll() {
        verticalList.smoothScrollToPosition(ITEM_COUNT - 1);
        handler.postDelayed(() -> verticalList.smoothScrollToPosition(0), 700L);
    }

    private List<Integer> buildRows(boolean reverse) {
        List<Integer> rows = new ArrayList<>(ITEM_COUNT);
        for (int i = 0; i < ITEM_COUNT; i++) {
            rows.add(i);
        }
        if (reverse) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Button button(String label, String description, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setContentDescription(description);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.rgb(32, 35, 36));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class FeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_TEXT = 0;
        private static final int TYPE_NESTED = 1;

        private List<Integer> rows;
        private int boundGeneration;

        FeedAdapter(List<Integer> rows, int generation) {
            this.rows = rows;
            this.boundGeneration = generation;
            setHasStableIds(true);
        }

        @SuppressLint("NotifyDataSetChanged")
        void replace(List<Integer> replacement, int generation) {
            rows = replacement;
            boundGeneration = generation;
            // A full invalidation is intentional: this button is the rebind stress surface.
            notifyDataSetChanged();
        }

        @Override
        public long getItemId(int position) {
            return rows.get(position);
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) % 10 == 0 ? TYPE_NESTED : TYPE_TEXT;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_NESTED) {
                LinearLayout container = new LinearLayout(parent.getContext());
                container.setOrientation(LinearLayout.VERTICAL);
                TextView label = text("", 15);
                container.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
                RecyclerView nested = new RecyclerView(parent.getContext());
                nested.setLayoutManager(new LinearLayoutManager(
                        parent.getContext(), RecyclerView.HORIZONTAL, false));
                nested.setItemAnimator(null);
                container.addView(nested, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
                return new NestedHolder(container, label, nested);
            }
            TextView row = text("", 16);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
            return new TextHolder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int row = rows.get(position);
            if (holder instanceof NestedHolder) {
                NestedHolder nested = (NestedHolder) holder;
                nested.label.setText(getString(R.string.nested_row, row, boundGeneration));
                nested.label.setContentDescription("acceptance-nested-label-" + row);
                nested.list.setContentDescription("acceptance-horizontal-list-" + row);
                nested.list.setAdapter(new ChipAdapter(row));
            } else {
                TextView value = ((TextHolder) holder).value;
                value.setText(getString(R.string.feed_row, row, row & 1, boundGeneration));
                value.setContentDescription("acceptance-row-" + row + "-type-" + (row & 1));
                value.setBackgroundColor((row & 1) == 0
                        ? Color.rgb(232, 245, 233) : Color.rgb(227, 242, 253));
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class TextHolder extends RecyclerView.ViewHolder {
        final TextView value;

        TextHolder(TextView value) {
            super(value);
            this.value = value;
        }
    }

    private static final class NestedHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final RecyclerView list;

        NestedHolder(View itemView, TextView label, RecyclerView list) {
            super(itemView);
            this.label = label;
            this.list = list;
        }
    }

    private final class ChipAdapter extends RecyclerView.Adapter<TextHolder> {
        private final int parentRow;

        ChipAdapter(int parentRow) {
            this.parentRow = parentRow;
        }

        @NonNull
        @Override
        public TextHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView chip = text("", 14);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundColor(Color.rgb(255, 243, 224));
            chip.setLayoutParams(new RecyclerView.LayoutParams(dp(150), dp(64)));
            return new TextHolder(chip);
        }

        @Override
        public void onBindViewHolder(@NonNull TextHolder holder, int position) {
            holder.value.setText(getString(R.string.carousel_item, parentRow, position));
            holder.value.setContentDescription(
                    "acceptance-carousel-" + parentRow + "-item-" + position);
        }

        @Override
        public int getItemCount() {
            return 12;
        }
    }
}
