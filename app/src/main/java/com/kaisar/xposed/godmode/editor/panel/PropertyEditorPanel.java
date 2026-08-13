package com.kaisar.xposed.godmode.editor.panel;

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
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.inject.ModuleBootstrap;
import com.kaisar.xposed.godmode.util.ModuleResources;
import com.kaisar.xposed.godmode.editor.IRuleEditor;
import com.kaisar.xposed.godmode.rule.RuleRecordFactory;
import com.kaisar.xposed.godmode.rule.ViewSnapshot;
import com.kaisar.xposed.godmode.orchestrator.RuleLifecycleManager;
import com.kaisar.xposed.godmode.orchestrator.ViewController;
import com.kaisar.xposed.godmode.util.BitmapUtils;
import com.kaisar.xposed.godmode.util.GmResources;
import com.kaisar.xposed.godmode.util.TaskExecutor;
import com.kaisar.xposed.godmode.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.InputStream;
import java.util.Objects;
import java.lang.ref.WeakReference;
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
 *   <li>Captures one target baseline and writes one RuleRecord per save</li>
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
    private Bitmap mInFlightImageBitmap;
    private boolean mSaving;
    private long mGeneration;
    private SessionListener mSessionListener;
    private boolean mPreviewing;

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

    private RuleRecord mOriginalRule;

    public interface SessionListener {
        void onSessionStateChanged(boolean active, boolean previewing, boolean previewToggleEnabled);
    }

    // Saved original view state before modification — used for revert / cancel

    // Saved original view state before modification — used for revert / cancel
    private ViewGroup.MarginLayoutParams mSavedLayoutParams;
    private int mSavedWidth = -1;
    private int mSavedHeight = -1;
    private float mSavedAlpha;
    private CharSequence mSavedText;

    /** Snapshot of the view state before editing. */
    private ViewSnapshot mSnapshot;

    // Depth path and activity class of the view being modified — used to lock
    // the rule lookup to the correct Activity context
    private int[] mModifyingViewDepth;
    private String mModifyingViewActClass;

    private boolean mActivityResultHooked;
    private WeakReference<Activity> mEditingActivity = new WeakReference<>(null);
    private long mImageRequestGeneration = -1L;
    private final IRuleEditor mRuleEditor;

    public PropertyEditorPanel(IRuleEditor ruleEditor) {
        this(ruleEditor, null);
    }

    public PropertyEditorPanel(IRuleEditor ruleEditor, SessionListener sessionListener) {
        this.mRuleEditor = ruleEditor;
        this.mSessionListener = sessionListener;
    }

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
            mOriginalRule = RuleRecordFactory.makeModifyRule(targetView, mSnapshot,
                    ModuleBootstrap.getEditorOrchestrator().isInfoFlowMode());
            mSaving = false;
            mPreviewing = false;
            mGeneration++;
            mEditingActivity = new WeakReference<>(activity);

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
            notifySession();
            mPanelView.setAlpha(0);
            mPanelView.animate().alpha(1).setDuration(ANIM_DURATION_SHORT).start();
        } catch (Exception e) {
            Logger.e(TAG, "[ModifyPanel] showModifyPanel fail", e);
            mPanelView = null;
            mTargetView = null;
            mOriginalRule = null;
            mSnapshot = null;
            mGeneration++;
            notifySession();
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
        mEditingActivity = new WeakReference<>(null);
        mOriginalImageBitmap = null;
        mModifyingViewDepth = null;
        mModifyingViewActClass = null;
        mSnapshot = null;
        mOriginalRule = null;
        mSaving = false;
        mPreviewing = false;
        mGeneration++;
        mSeekLayoutPending = false;
        mPendingSeekWidth = -1;
        mPendingSeekHeight = -1;
        if (mPendingImageBitmap != mInFlightImageBitmap) {
            CommonUtils.recycleNullableBitmap(mPendingImageBitmap);
        }
        mPendingImageBitmap = null;
        notifySession();
            panel.animate().alpha(0).setDuration(ANIM_DURATION_MEDIUM).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) panel.getParent();
            if (parent != null) parent.removeView(panel);
        }).start();
    }

    /** Handle Activity onActivityResult for image picker callback. */
    public void cancel() {
        if (mSaving) return;
        revertViewState();
        CommonUtils.recycleNullableBitmap(mPendingImageBitmap);
        mPendingImageBitmap = null;
        dismiss();
    }

    /** Release a panel owned by an Activity that is leaving the editor. */
    public void abandon() {
        mGeneration++;
        mSaving = false;
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
                mImageRequestGeneration = mGeneration;
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
        panel.findViewById(R.id.mod_save).setOnClickListener(v -> saveCurrent());
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

    private RuleRecord buildCurrentRule(View view) {
        if (mOriginalRule == null) return null;
        RuleRecord rule = mOriginalRule.clone();
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        int w = lp != null && lp.width > 0 ? lp.width : view.getWidth();
        int h = lp != null && lp.height > 0 ? lp.height : view.getHeight();
        float alpha = view.getAlpha();
        if (rule.origWidth > 0 && w != rule.origWidth) rule.modWidth = w;
        if (rule.origHeight > 0 && h != rule.origHeight) rule.modHeight = h;
        if (Math.abs(rule.origAlpha - alpha) > 0.01f) rule.modAlpha = alpha;
        if (view instanceof TextView
                && !Objects.equals(rule.origText, ((TextView) view).getText().toString())) {
            rule.modText = ((TextView) view).getText().toString();
        }
        if (view instanceof ImageView && mPendingImageBitmap != null) rule.modImagePath = "pending";
        ViewGroup.LayoutParams current = view.getLayoutParams();
        if (current instanceof ViewGroup.MarginLayoutParams && mSavedLayoutParams != null) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) current;
            rule.modXOffset = margins.leftMargin - mSavedLayoutParams.leftMargin;
            rule.modYOffset = margins.topMargin - mSavedLayoutParams.topMargin;
        }
        return rule;
    }

    public void saveCurrent() {
        if (mSaving || mTargetView == null) return;
        Activity activity = ViewUtils.getAttachedActivityFromView(mTargetView);
        if (activity == null || !verifyViewIdentity(mTargetView)) return;
        mApplySeekLayoutRunnable.run();
        final RuleRecord rule = buildCurrentRule(mTargetView);
        if (rule == null || !rule.hasModifications()) {
            Toast.makeText(activity, GmResources.getString(R.string.toast_no_modifications_to_save), Toast.LENGTH_SHORT).show();
            return;
        }
        final long generation = mGeneration;
        final String pkg = activity.getPackageName();
        final Bitmap pendingImage = mPendingImageBitmap;
        mInFlightImageBitmap = pendingImage;
        mSaving = true;
        setPanelControlsEnabled(false);
        notifySession();

        Bitmap snapshot = null;
        try {
            snapshot = BitmapUtils.snapshotView(ViewUtils.findTopParentViewByChildView(mTargetView));
            BitmapUtils.drawRectMask(snapshot, rule.x, rule.y, rule.width, rule.height);
        } catch (Exception e) { Logger.w(TAG, "[ModifyPanel] snapshot failed", e); }
        final Bitmap finalSnapshot = snapshot;
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        TaskExecutor.executeIo(() -> {
            boolean imageReady = true;
            try {
                if (pendingImage != null && !pendingImage.isRecycled()) {
                    String path = mRuleEditor.saveImageFile(pkg, pendingImage);
                    if (path == null) imageReady = false; else rule.modImagePath = path;
                }
            } catch (Exception e) { imageReady = false; Logger.e(TAG, "[ModifyPanel] image save failed", e); }
            final boolean finalImageReady = imageReady;
            mainHandler.post(() -> {
                if (generation != mGeneration || mTargetView == null || !verifyViewIdentity(mTargetView)) {
                    CommonUtils.recycleNullableBitmap(finalSnapshot);
                    releaseInFlightImage(pendingImage);
                    abortSaveIfActive();
                    return;
                }
                if (!finalImageReady) {
                    mInFlightImageBitmap = null;
                    finishSaveFailure(activity, "image save failed");
                    CommonUtils.recycleNullableBitmap(finalSnapshot);
                    return;
                }
                View target = mTargetView;
                revertViewState();
                ViewController controller = RuleLifecycleManager.getInstance().getViewController(activity);
                if (!controller.applyRule(target, rule)) {
                    applyDraftToView(target, rule);
                    mInFlightImageBitmap = null;
                    finishSaveFailure(activity, "runtime apply failed");
                    CommonUtils.recycleNullableBitmap(finalSnapshot);
                    return;
                }
                TaskExecutor.executeIo(() -> {
                    boolean accepted;
                    try { accepted = mRuleEditor.writeRule(pkg, rule, finalSnapshot); }
                    catch (Exception e) { accepted = false; Logger.e(TAG, "[ModifyPanel] writeRule failed", e); }
                    final boolean finalAccepted = accepted;
                    mainHandler.post(() -> {
                        CommonUtils.recycleNullableBitmap(finalSnapshot);
                        if (generation != mGeneration || mTargetView != target
                                || !verifyViewIdentity(target)) {
                            releaseInFlightImage(pendingImage);
                            abortSaveIfActive();
                            return;
                        }
                        mSaving = false;
                        releaseInFlightImage(pendingImage);
                        if (finalAccepted) {
                            Toast.makeText(activity, GmResources.getString(R.string.toast_modifications_saved), Toast.LENGTH_SHORT).show();
                            dismiss();
                        } else {
                            controller.revokeRule(target, rule);
                            applyDraftToView(target, rule);
                            mInFlightImageBitmap = null;
                            finishSaveFailure(activity, "request rejected");
                        }
                    });
                });
            });
        });
    }

    private void applyDraftToView(View target, RuleRecord rule) {
        ViewGroup.LayoutParams lp = target.getLayoutParams();
        if (lp != null) {
            if (rule.isWidthModified()) lp.width = rule.modWidth;
            if (rule.isHeightModified()) lp.height = rule.modHeight;
            if (lp instanceof ViewGroup.MarginLayoutParams && mSavedLayoutParams != null) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) lp;
                margins.leftMargin = mSavedLayoutParams.leftMargin + rule.modXOffset;
                margins.topMargin = mSavedLayoutParams.topMargin + rule.modYOffset;
            }
            target.setLayoutParams(lp);
        }
        if (rule.isAlphaModified()) target.setAlpha(rule.modAlpha);
        if (target instanceof TextView && rule.isTextModified()) ((TextView) target).setText(rule.modText);
        if (target instanceof ImageView && mPendingImageBitmap != null && !mPendingImageBitmap.isRecycled()) {
            ((ImageView) target).setImageBitmap(mPendingImageBitmap);
        }
    }

    private void finishSaveFailure(Activity activity, String reason) {
        mSaving = false;
        setPanelControlsEnabled(true);
        notifySession();
        Toast.makeText(activity, GmResources.getString(
                R.string.toast_modifications_save_failed_format, reason), Toast.LENGTH_SHORT).show();
    }

    private void releaseInFlightImage(Bitmap image) {
        if (mInFlightImageBitmap == image) {
            CommonUtils.recycleNullableBitmap(image);
            mInFlightImageBitmap = null;
        }
    }

    private void abortSaveIfActive() {
        if (mPanelView != null) {
            mSaving = false;
            setPanelControlsEnabled(true);
            notifySession();
        }
    }

    private void setPanelControlsEnabled(boolean enabled) {
        if (mPanelView == null) return;
        mPanelView.setEnabled(enabled);
        if (mPanelView instanceof ViewGroup) {
            setChildrenEnabled((ViewGroup) mPanelView, enabled);
        }
    }

    private void setChildrenEnabled(ViewGroup parent, boolean enabled) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            child.setEnabled(enabled);
            if (child instanceof ViewGroup) setChildrenEnabled((ViewGroup) child, enabled);
        }
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

                                    Activity editingActivity = mEditingActivity.get();
                                    if (mPanelView == null || mSaving || editingActivity == null
                                            || currentActivity != editingActivity
                                            || mImageRequestGeneration != mGeneration) {
                                        CommonUtils.recycleNullableBitmap(bitmap);
                                        return;
                                    }
                                    View targetView = mPendingImageView;
                                    if (!(targetView instanceof ImageView)
                                            || targetView != mTargetView
                                            || !verifyViewIdentity(targetView)) {
                                        CommonUtils.recycleNullableBitmap(bitmap);
                                        return;
                                    }

                                    CommonUtils.recycleNullableBitmap(mPendingImageBitmap);
                                    mPendingImageBitmap = bitmap;
                                    ((ImageView) targetView).setImageBitmap(bitmap);
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
    public boolean isSaving() { return mSaving; }
    public boolean isPreviewing() { return mPreviewing; }
    public void togglePreview() {
        if (mPanelView == null || mSaving) return;
        mPreviewing = !mPreviewing;
        mPanelView.setVisibility(mPreviewing ? View.GONE : View.VISIBLE);
        notifySession();
    }

    private void notifySession() {
        if (mSessionListener != null) {
            mSessionListener.onSessionStateChanged(mPanelView != null, mPreviewing, mPanelView != null && !mSaving);
        }
    }
    public boolean isShowing() { return mPanelView != null; }
}
