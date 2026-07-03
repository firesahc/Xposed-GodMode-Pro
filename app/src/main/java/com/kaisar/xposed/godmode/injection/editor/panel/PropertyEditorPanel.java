package com.kaisar.xposed.godmode.injection.editor.panel;

import com.kaisar.xposed.godmode.engine.util.CommonUtils;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.matcher.CompositeMatcher;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.rule.ActionSpec;
import com.kaisar.xposed.godmode.engine.rule.RuleMapper;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.ModuleResources;
import com.kaisar.xposed.godmode.injection.bridge.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.RuleRecordFactory;
import com.kaisar.xposed.godmode.rule.ViewSnapshot;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.util.GmResources;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Property editor panel for modifying view attributes.
 * <p>
 * Displays a floating panel allowing the user to adjust width, height, alpha,
 * text, image, and position of the selected view.
 * <ul>
 *   <li>UI built from R.layout.panel_modify with sliders, text fields, and preview</li>
 *   <li>Supports image replacement via gallery/file picker (Activity result)</li>
 *   <li>Tracks pending modifications in {@link #mTempModifications} as {@link ActionSpec}
 *       for batch save/cancel — cancel discards ActionSpec with no side effects</li>
 *   <li>{@link #mOriginalRule} stores structure fields (depth/viewClass/resourceName)
 *       captured once, merged with ActionSpec on save</li>
 *   <li>Hooks Activity.onActivityResult to handle image picker result</li>
 * </ul>
 */
public class PropertyEditorPanel {

    private static final String TAG = "PropertyEditorPanel";

    private static final int REQUEST_CODE_PICK_IMAGE = 0x5A45;
    private static final int ANIM_DURATION_SHORT = 200;
    private static final int ANIM_DURATION_MEDIUM = 150;

    private View mPanelView;
    private View mTargetView;
    private ImageView mPendingImageView;
    private Bitmap mOriginalImageBitmap;
    private Bitmap mPendingImageBitmap;
    private HashMap<String, Bitmap> mPendingModBitmaps = new HashMap<>();

    // SeekBar 拖动帧级合并：避免每次 onProgressChanged 触发 setLayoutParams → requestLayout
    private int mPendingSeekWidth = -1;
    private int mPendingSeekHeight = -1;
    private boolean mSeekLayoutPending = false;
    private final Runnable mApplySeekLayoutRunnable = new Runnable() {
        @Override
        public void run() {
            mSeekLayoutPending = false;
            if (mTargetView == null) return;
            ViewGroup.LayoutParams lp = mTargetView.getLayoutParams();
            if (lp == null) return;
            boolean changed = false;
            if (mPendingSeekWidth > 0 && lp.width != mPendingSeekWidth) {
                lp.width = mPendingSeekWidth;
                changed = true;
            }
            if (mPendingSeekHeight > 0 && lp.height != mPendingSeekHeight) {
                lp.height = mPendingSeekHeight;
                changed = true;
            }
            if (changed) {
                mTargetView.setLayoutParams(lp);
            }
            mPendingSeekWidth = -1;
            mPendingSeekHeight = -1;
        }
    };

    // Pending modifications keyed by view key — batch saved or cancelled together.
    // Changed from RuleRecord to ActionSpec: only stores modify fields,
    // cancel discards with no side effects.
    final HashMap<String, ActionSpec> mTempModifications = new HashMap<>();

    // Original rule with structure fields (depth/viewClass/activityClass/itemPath etc.)
    // — captured once when editing begins, merged with mTempModifications on save.
    private RuleRecord mOriginalRule;

    // Saved original view state before modification — used for revert / cancel

    // Saved original view state before modification — used for revert / cancel
    private ViewGroup.MarginLayoutParams mSavedLayoutParams;
    private int mSavedWidth = -1;
    private int mSavedHeight = -1;
    private int mSavedPixelWidth;
    private int mSavedPixelHeight;
    private float mSavedAlpha;
    private CharSequence mSavedText;

    /** Snapshot of the view state before editing. */
    private ViewSnapshot mSnapshot;

    // Depth path and activity class of the view being modified — used to lock
    // the rule lookup to the correct Activity context
    private int[] mModifyingViewDepth;
    private String mModifyingViewActClass;

    private boolean mActivityResultHooked;

    /**
     * Show the property editor panel for the target view.
     */
    public void show(View targetView, Activity activity, ViewGroup container) {
        if (mPanelView != null || targetView == null) return;
        mTargetView = targetView;
        try {
            saveViewState(targetView);
            // 在视图被编辑前捕获原始状态快照
            mSnapshot = ViewSnapshot.capture(targetView);

            ModuleResources.injectInto(activity.getResources());
            LayoutInflater inflater = LayoutInflater.from(activity);
            mPanelView = inflater.inflate(
                    GmResources.getLayout(R.layout.panel_modify), container, false);

            setupSeekers(mPanelView, targetView);
            setupTextEdit(mPanelView, targetView);
            setupImageReplacement(mPanelView, targetView, activity);
            setupPositionNudge(mPanelView, targetView);
            setupConfirmCancel(mPanelView, targetView);

            container.addView(mPanelView);
            mPanelView.setAlpha(0);
            mPanelView.animate().alpha(1).setDuration(ANIM_DURATION_SHORT).start();
        } catch (Exception e) {
            Logger.e(TAG, "[ModifyPanel] showModifyPanel fail", e);
            dismiss();
        }
    }

    /** Dismiss the property editor panel. */
    public void dismiss() {
        if (mPanelView == null) return;
        View panel = mPanelView;
        mPanelView = null;
        mTargetView = null;
        mPendingImageView = null;
        mOriginalImageBitmap = null;
        mModifyingViewDepth = null;
        mModifyingViewActClass = null;
        mSnapshot = null;
        // mOriginalRule 和 mTempModifications 不移除：confirm 后保留累积修改，
        // 供 saveAll() 持久化；cancel 路径通过 revertViewState() 已清理当前视图。
        // 参见 saveAll() 末尾的 mTempModifications.clear()、cancel() 中的 revertViewState()。
        mSeekLayoutPending = false;
        mPendingSeekWidth = -1;
        mPendingSeekHeight = -1;
        // Recycle pending mod bitmaps on dismiss (mPendingModBitmaps stores loaded replacement images).
        // For cancel() / saveAll() see the confirm/cancel button handlers.
            panel.animate().alpha(0).setDuration(ANIM_DURATION_MEDIUM).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) panel.getParent();
            if (parent != null) parent.removeView(panel);
        }).start();
    }

    /** Handle Activity onActivityResult for image picker callback. */
    public void cancel() {
        revertViewState();
        mPendingModBitmaps.values().forEach(
                CommonUtils::recycleNullableBitmap);
        mPendingModBitmaps.clear();
        CommonUtils.recycleNullableBitmap(mPendingImageBitmap);
        mPendingImageBitmap = null;
        dismiss();
    }

    // ---- Seeker 状态更新（宽/高/透明度滑块）----

    private void setupSeekers(View panel, View selectedView) {
        SeekBar widthSeek = panel.findViewById(R.id.mod_width_seek);
        EditText widthText = panel.findViewById(R.id.mod_width_text);
        SeekBar heightSeek = panel.findViewById(R.id.mod_height_seek);
        EditText heightText = panel.findViewById(R.id.mod_height_text);
        SeekBar alphaSeek = panel.findViewById(R.id.mod_alpha_seek);
        EditText alphaText = panel.findViewById(R.id.mod_alpha_text);

        ViewGroup.LayoutParams lp = selectedView.getLayoutParams();
        int w = lp != null && lp.width > 0 ? Math.min(lp.width, 2000) : selectedView.getWidth();
        int h = lp != null && lp.height > 0 ? Math.min(lp.height, 2000) : selectedView.getHeight();
        int a = (int) (selectedView.getAlpha() * 255);

        widthSeek.setProgress(w); heightSeek.setProgress(h); alphaSeek.setProgress(a);
        widthText.setText(String.valueOf(w)); heightText.setText(String.valueOf(h)); alphaText.setText(String.valueOf(a));

        bindSeek(widthSeek, widthText, selectedView, SeekerType.WIDTH);
        bindSeek(heightSeek, heightText, selectedView, SeekerType.HEIGHT);
        bindSeek(alphaSeek, alphaText, selectedView, SeekerType.ALPHA);
    }

    private void setupTextEdit(View panel, View selectedView) {
        LinearLayout textSection = panel.findViewById(R.id.mod_text_section);
        EditText textInput = panel.findViewById(R.id.mod_text_input);
        if (!(selectedView instanceof TextView)) return;
        textSection.setVisibility(View.VISIBLE);
        textInput.setText(((TextView) selectedView).getText());
        textInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (mTargetView instanceof TextView) ((TextView) mTargetView).setText(s);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    // ---- 文本修改处理（编辑文本）----

    private void setupImageReplacement(View panel, View selectedView, Activity activity) {
        LinearLayout imageSection = panel.findViewById(R.id.mod_image_section);
        if (!(selectedView instanceof ImageView)) return;

        imageSection.setVisibility(View.VISIBLE);
        mPendingImageView = (ImageView) selectedView;
        hookActivityResult(activity);
        panel.findViewById(R.id.mod_image_pick).setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                activity.startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
            } catch (Exception e) {
                Toast.makeText(activity, GmResources.getString(R.string.toast_cannot_open_image_picker), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- Image replacement ----

    private void setupPositionNudge(View panel, View selectedView) {
        View.OnClickListener nudgeListener = v -> {
            int id = v.getId();
            int dx = 0, dy = 0;
            if (id == R.id.mod_pos_up) dy = -10;
            else if (id == R.id.mod_pos_down) dy = 10;
            else if (id == R.id.mod_pos_left) dx = -10;
            else if (id == R.id.mod_pos_right) dx = 10;

            if (selectedView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) selectedView.getLayoutParams();
                mlp.leftMargin += dx;
                mlp.topMargin += dy;
                selectedView.setLayoutParams(mlp);
            }
        };
        panel.findViewById(R.id.mod_pos_up).setOnClickListener(nudgeListener);
        panel.findViewById(R.id.mod_pos_down).setOnClickListener(nudgeListener);
        panel.findViewById(R.id.mod_pos_left).setOnClickListener(nudgeListener);
        panel.findViewById(R.id.mod_pos_right).setOnClickListener(nudgeListener);
    }

    // ---- Position nudge (margin adjustment) ----

    private void setupConfirmCancel(View panel, View selectedView) {
        SeekBar widthSeek = panel.findViewById(R.id.mod_width_seek);
        SeekBar heightSeek = panel.findViewById(R.id.mod_height_seek);
        SeekBar alphaSeek = panel.findViewById(R.id.mod_alpha_seek);
        EditText textInput = panel.findViewById(R.id.mod_text_input);

        panel.findViewById(R.id.mod_cancel).setOnClickListener(v -> cancel());
        panel.findViewById(R.id.mod_confirm).setOnClickListener(v -> {
            applyModification(mTargetView != null ? mTargetView : selectedView,
                    widthSeek, heightSeek, alphaSeek, textInput);
            dismiss();
        });
    }

    // ---- SeekBar 绑定逻辑 ----

    private void bindSeek(SeekBar seekBar, EditText text, View target, SeekerType type) {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                if (!fromUser) return;
                text.setText(String.valueOf(val));
                switch (type) {
                    case WIDTH:
                        mPendingSeekWidth = val;
                        scheduleSeekLayoutApply(target);
                        break;
                    case HEIGHT:
                        mPendingSeekHeight = val;
                        scheduleSeekLayoutApply(target);
                        break;
                    case ALPHA:
                        target.setAlpha(val / 255f);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown SeekerType: " + type);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    /**
     * 调度 SeekBar 修改的布局应用。同一帧内多次调用只触发一次 setLayoutParams。
     * 使用 View.post 确保在下一帧绘制前批量应用，避免每次 onProgressChanged 触发布局重排。
     */
    private void scheduleSeekLayoutApply(View target) {
        if (!mSeekLayoutPending) {
            mSeekLayoutPending = true;
            target.post(mApplySeekLayoutRunnable);
        }
    }

    private enum SeekerType { WIDTH, HEIGHT, ALPHA }

    // ---- 修改状态保存与恢复 — 缓存修改前的视图值以便撤销 ----

    /** 保存视图修改前的状态快照（宽高、透明度、文本、图片等），
     *  在用户确认修改或撤销修改时用于对比和恢复。 */
    private void saveViewState(View view) {
        mSavedLayoutParams = null;
        mSavedWidth = -1;
        mSavedHeight = -1;
        mSavedPixelWidth = view.getWidth();
        mSavedPixelHeight = view.getHeight();
        mSavedAlpha = view.getAlpha();
        mSavedText = null;
        mOriginalImageBitmap = null;
        mPendingImageBitmap = null;
        mModifyingViewDepth = ViewUtils.getViewHierarchyDepth(view);
        Activity act = ViewUtils.getAttachedActivityFromView(view);
        mModifyingViewActClass = act != null ? act.getComponentName().getClassName() : null;

        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            mSavedWidth = lp.width;
            mSavedHeight = lp.height;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mSavedLayoutParams = new ViewGroup.MarginLayoutParams(mlp.width, mlp.height);
                mSavedLayoutParams.leftMargin = mlp.leftMargin;
                mSavedLayoutParams.topMargin = mlp.topMargin;
            }
        }

        if (view instanceof TextView) {
            mSavedText = ((TextView) view).getText();
        }
        if (view instanceof ImageView) {
            android.graphics.drawable.Drawable drawable = ((ImageView) view).getDrawable();
            if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                mOriginalImageBitmap = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
            }
        }
    }

    /** 恢复视图修改前的状态，撤销尚未保存的编辑。 */
    private void revertViewState() {
        if (mTargetView == null) return;
        if (!verifyViewIdentity(mTargetView)) {
            Logger.w(TAG, "[ModifyPanel] revertViewState: view identity changed, skip revert for safety");
            return;
        }

        ViewGroup.LayoutParams lp = mTargetView.getLayoutParams();
        if (lp != null) {
            if (mSavedLayoutParams != null && lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mlp.width = mSavedLayoutParams.width;
                mlp.height = mSavedLayoutParams.height;
                mlp.leftMargin = mSavedLayoutParams.leftMargin;
                mlp.topMargin = mSavedLayoutParams.topMargin;
                mTargetView.setLayoutParams(mlp);
            } else if (mSavedWidth >= 0 || mSavedHeight >= 0) {
                if (mSavedWidth >= 0) lp.width = mSavedWidth;
                if (mSavedHeight >= 0) lp.height = mSavedHeight;
                mTargetView.setLayoutParams(lp);
            }
        }

        mTargetView.setAlpha(mSavedAlpha);

        if (mTargetView instanceof TextView && mSavedText != null) {
            ((TextView) mTargetView).setText(mSavedText);
        }

        if (mTargetView instanceof ImageView) {
            if (mOriginalImageBitmap != null) {
                ((ImageView) mTargetView).setImageBitmap(mOriginalImageBitmap);
            } else {
                ((ImageView) mTargetView).setImageDrawable(null);
            }
        }

        String viewKey = ViewUtils.getViewKey(mTargetView);
        mTempModifications.remove(viewKey);
    }

    /** 验证目标视图是否仍然是修改时选中的那个视图（通过层级深度和 Activity 身份校验）*/
    private boolean verifyViewIdentity(View view) {
        if (!view.isAttachedToWindow()) return false;
        if (mModifyingViewDepth == null || mModifyingViewActClass == null) return true;
        Activity currentAct = ViewUtils.getAttachedActivityFromView(view);
        if (currentAct == null) return false;
        if (!mModifyingViewActClass.equals(currentAct.getComponentName().getClassName())) return false;
        int[] currentDepth = ViewUtils.getViewHierarchyDepth(view);
        return java.util.Arrays.equals(mModifyingViewDepth, currentDepth);
    }

    // ---- Confirm / Cancel buttons ----

    /** 应用 UI 控件的修改值到 ActionSpec（创建或更新）。
     * <p>
     * 仅存储修改字段（mod*），结构字段始终从 {@link #mOriginalRule} 获取。
     * 取消时丢弃 ActionSpec 即可，无副作用。
     * 修改值（mod*）来自 UI 控件当前状态。 */
    private void applyModification(View view, SeekBar widthSeek, SeekBar heightSeek,
                                    SeekBar alphaSeek, EditText textInput) {
        int w = widthSeek.getProgress();
        int h = heightSeek.getProgress();
        float a = alphaSeek.getProgress() / 255f;

        String viewKey = ViewUtils.getViewKey(view);
        ActionSpec spec = mTempModifications.get(viewKey);
        if (spec == null) {
            // 第一次修改：创建 mOriginalRule（结构字段）和 ActionSpec（修改字段）
            if (mOriginalRule == null) {
                mOriginalRule = RuleRecordFactory.makeModifyRule(view, mSnapshot);
            }
            spec = mOriginalRule.asActionSpec();
            mTempModifications.put(viewKey, spec);
        }

        if (mOriginalRule.origWidth > 0 && w != mOriginalRule.origWidth) {
            spec.modWidth = w;
        }
        if (mOriginalRule.origHeight > 0 && h != mOriginalRule.origHeight) {
            spec.modHeight = h;
        }
        if (Math.abs(mOriginalRule.origAlpha - a) > 0.01f) {
            spec.modAlpha = a;
        }

        if (view instanceof TextView && textInput != null && textInput.getVisibility() == View.VISIBLE) {
            String newText = textInput.getText().toString();
            if (!newText.equals(mOriginalRule.origText)) {
                spec.modText = newText;
            }
        }

        if (!spec.isWidthModified() && !spec.isHeightModified() && !spec.isAlphaModified()
                && !spec.isTextModified() && !spec.isImageModified()
                && !spec.isPositionModified()) {
            mTempModifications.remove(viewKey);
        }
    }

    /**
     * 保存所有修改到规则文件，同时生成截图快照，
     * 清理临时修改缓存，完成后通过回调通知结果。
     */
    public void saveAll(Activity activity, View nodeSelectorPanel, View maskView, View modifyPanel) {
        if (mTempModifications.isEmpty() || mOriginalRule == null) {
            Toast.makeText(activity, GmResources.getString(R.string.toast_no_modifications_to_save), Toast.LENGTH_SHORT).show();
            return;
        }
        String pkg = activity.getPackageName();

        // 合并 ActionSpec 修改字段到 RuleRecord 结构 → 完整 RuleRecord 列表
        final List<RuleRecord> rulesToSave = new ArrayList<>();
        for (Map.Entry<String, ActionSpec> entry : mTempModifications.entrySet()) {
            RuleRecord rule = mOriginalRule.clone();
            ActionSpec spec = entry.getValue();
            // 将 ActionSpec 的修改字段拷贝到 RuleRecord
            rule.modWidth = spec.modWidth;
            rule.modHeight = spec.modHeight;
            rule.modAlpha = spec.modAlpha;
            rule.modXOffset = spec.modXOffset;
            rule.modYOffset = spec.modYOffset;
            rule.modText = spec.modText;
            rule.modImagePath = spec.modImagePath;
            rule.origWidth = spec.origWidth;
            rule.origHeight = spec.origHeight;
            rule.origAlpha = spec.origAlpha;
            rule.origText = spec.origText;
            rule.origLeftMargin = spec.origLeftMargin;
            rule.origTopMargin = spec.origTopMargin;

            if ("pending".equals(rule.modImagePath)) {
                StringBuilder sb = new StringBuilder(rule.activityClass);
                if (rule.depth != null) {
                    for (int d : rule.depth) sb.append('_').append(d);
                }
                String viewKey = sb.toString();
                Bitmap bmp = mPendingModBitmaps.get(viewKey);
                if (bmp != null && !bmp.isRecycled()) {
                    String savedPath = RuleServiceClient.getDefault().saveImageFile(pkg, bmp);
                    rule.modImagePath = savedPath != null ? savedPath : null;
                } else {
                    rule.modImagePath = null;
                }
            }

            if (rule.hasModifications()) {
                rulesToSave.add(rule);
            }
        }
        if (rulesToSave.isEmpty()) {
            Toast.makeText(activity, GmResources.getString(R.string.toast_no_modifications_to_save), Toast.LENGTH_SHORT).show();
            return;
        }
        if (nodeSelectorPanel != null) nodeSelectorPanel.setVisibility(View.INVISIBLE);
        if (modifyPanel != null) modifyPanel.setVisibility(View.INVISIBLE);
        if (maskView != null) maskView.setVisibility(View.INVISIBLE);

        final HashMap<RuleRecord, Bitmap> snapshots = new HashMap<>();
        for (RuleRecord rule : rulesToSave) {
            try {
                View view;
                if (rule.repeatable) {
                    com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec engineRule =
                            RuleMapper.toEngine(rule);
                    List<View> matchedViews = new CompositeMatcher().matchAllViews(
                            activity.getWindow().getDecorView(), engineRule.getMatchSpec());
                    view = (matchedViews != null && !matchedViews.isEmpty()) ? matchedViews.get(0) : null;
                } else {
                    view = activity != null && activity.getWindow() != null && rule.depth != null
                            ? ViewTraversal.findViewByDepth(activity.getWindow().getDecorView(), rule.depth)
                            : null;
                }
                if (view != null) {
                    Bitmap snapshot = BitmapUtils.snapshotView(ViewUtils.findTopParentViewByChildView(view));
                    BitmapUtils.drawRuleMask(snapshot, rule);
                    snapshots.put(rule, snapshot);
                }
            } catch (Exception e) {
                Logger.w(TAG, "[ModifyPanel] saveAll: snapshot failed for rule", e);
            }
        }
        // 回收临时缓存的位图（mPendingModBitmaps），它们已通过 writeRule 写入文件，
        // 稍后 applyModificationToView 会替换 ImageView 的 src 为文件路径。
        // 清理内存中的临时修改和位图，等待 GC 回收。
        mPendingModBitmaps.clear();
        mTempModifications.clear();
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        TaskExecutor.executeIo(() -> {
            boolean allOk = true;
            List<String> failedRules = new ArrayList<>();
            for (RuleRecord rule : rulesToSave) {
                Bitmap snapshot = snapshots.get(rule);
                try {
                    if (!RuleServiceClient.getDefault().writeRule(pkg, rule, snapshot)) {
                        allOk = false;
                        failedRules.add(rule.activityClass + "#" + rule.viewClass);
                    }
                } catch (Exception e) {
                    Logger.e(TAG, "[ModifyPanel] saveAll: writeRule failed for "
                            + rule.activityClass + "#" + rule.viewClass, e);
                    allOk = false;
                    failedRules.add(rule.activityClass + "#" + rule.viewClass);
                } finally {
                    CommonUtils.recycleNullableBitmap(snapshot);
                }
            }
            boolean finalAllOk = allOk;
            String finalFailed = failedRules.isEmpty() ? "" :
                    GmResources.getString(R.string.toast_save_failed_rules_format, String.join(", ", failedRules));
            mainHandler.post(() -> {
                if (nodeSelectorPanel != null) nodeSelectorPanel.setVisibility(View.VISIBLE);
                if (modifyPanel != null) modifyPanel.setVisibility(View.VISIBLE);
                if (maskView != null) maskView.setVisibility(View.VISIBLE);
                Toast.makeText(activity,
                        finalAllOk ? GmResources.getString(R.string.toast_modifications_saved)
                                : GmResources.getString(R.string.toast_modifications_save_failed_format, finalFailed),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    // ---- Xposed Hook 图片替换（拦截图片选择器返回结果进行位图替换）----

    private void hookActivityResult(Activity activity) {
        if (mActivityResultHooked) return;
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult",
                    int.class, int.class, Intent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int requestCode = (int) param.args[0];
                            int resultCode = (int) param.args[1];
                            Intent data = (Intent) param.args[2];
                            if (requestCode != REQUEST_CODE_PICK_IMAGE || resultCode != Activity.RESULT_OK || data == null) return;

                            try {
                                android.net.Uri uri = data.getData();
                                if (uri == null) return;
                                Activity currentActivity = (Activity) param.thisObject;
                                try (InputStream is = currentActivity.getContentResolver().openInputStream(uri)) {
                                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                                    if (bitmap == null) return;

                                    View targetView = null;
                                    if (mModifyingViewDepth != null && mModifyingViewActClass != null
                                            && mModifyingViewActClass.equals(currentActivity.getComponentName().getClassName())
                                            && currentActivity.getWindow() != null) {
                                        targetView = ViewTraversal.findViewByDepth(
                                                currentActivity.getWindow().getDecorView(), mModifyingViewDepth);
                                        if (targetView instanceof ImageView) {
                                            mPendingImageView = (ImageView) targetView;
                                            mTargetView = targetView;
                                            saveViewState(targetView);
                                        } else {
                                            targetView = null;
                                        }
                                    }
                                    if (targetView == null) {
                                        targetView = mPendingImageView;
                                        if (targetView == null || !targetView.isAttachedToWindow()) return;
                                    }

                                    mPendingImageBitmap = bitmap;
                                    ((ImageView) targetView).setImageBitmap(bitmap);
                                    String viewKey = ViewUtils.getViewKey(targetView);
                                    ActionSpec spec = mTempModifications.get(viewKey);
                                    if (spec == null) {
                                        if (mOriginalRule == null) {
                                            mOriginalRule = RuleRecordFactory.makeModifyRule(targetView, mSnapshot);
                                        }
                                        spec = mOriginalRule.asActionSpec();
                                        mTempModifications.put(viewKey, spec);
                                    }
                                    spec.modImagePath = "pending";
                                    mPendingModBitmaps.put(viewKey, bitmap);
                                }
                            } catch (Exception e) {
                                Logger.e(TAG, "[ModifyPanel] handle image pick fail", e);
                            }
                        }
                    });
            mActivityResultHooked = true;
        } catch (Exception e) {
            Logger.e(TAG, "[ModifyPanel] hookActivityResult: Xposed hook failed, image replacement disabled", e);
        }
    }

    // ---- 公共方法 ----

    public View getPanelView() { return mPanelView; }
    public View getTargetView() { return mTargetView; }
    public ImageView getPendingImageView() { return mPendingImageView; }
    public void setPendingImageView(ImageView v) { mPendingImageView = v; }
    public Bitmap getOriginalImageBitmap() { return mOriginalImageBitmap; }
    public void setOriginalImageBitmap(Bitmap b) { mOriginalImageBitmap = b; }
    public Map<String, Bitmap> getPendingModBitmaps() { return mPendingModBitmaps; }
    public boolean isShowing() { return mPanelView != null; }
}
