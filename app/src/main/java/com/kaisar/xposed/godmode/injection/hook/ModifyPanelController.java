package com.kaisar.xposed.godmode.injection.hook;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;
import static com.kaisar.xposed.godmode.injection.util.CommonUtils.recycleNullableBitmap;

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
import com.kaisar.xposed.godmode.injection.GodModeInjector;
import com.kaisar.xposed.godmode.injection.ModuleResources;
import com.kaisar.xposed.godmode.injection.ViewHelper;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.util.GmResources;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ViewRule;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 管理浮于目标应用之上的逐视图属性修改面板。
 * <p>
 * 职责：
 * <ul>
 *   <li>加载并显示修改面板 UI</li>
 *   <li>实时预览宽/高/透明度/位置/文本/图片变更</li>
 *   <li>保存视图原始状态用于取消/还原</li>
 *   <li>确认修改后写入 {@link #mTempModifications} 待保存规则</li>
 *   <li>持久化并广播已保存的修改</li>
 *   <li>Hook Activity.onActivityResult 以处理图片替换</li>
 * </ul>
 * <p>
 * 持有者 {@link DispatchKeyEventHook} 通过 {@link #show(View, Activity, ViewGroup)}
 * 显示本面板，通过 {@link #isPanelShowing()} 判断是否禁止切换元素，
 * 并在用户请求批量保存时调用 {@link #saveAll(Activity, View, View, View)}。
 */
final class ModifyPanelController {

    private View mModifyPanel;
    private View mCurrentlyModifyingView;
    private ImageView mPendingImageView;
    final HashMap<String, ViewRule> mTempModifications = new HashMap<>();
    private final HashMap<String, Bitmap> mPendingModBitmaps = new HashMap<>();

    // 在实时预览修改前捕获的视图原始状态
    private ViewGroup.MarginLayoutParams mSavedLayoutParams;
    private int mSavedWidth = -1;
    private int mSavedHeight = -1;
    private int mSavedPixelWidth;
    private int mSavedPixelHeight;
    private float mSavedAlpha;
    private CharSequence mSavedText;
    private Bitmap mOriginalImageBitmap;
    private Bitmap mPendingImageBitmap;

    // 保存视图标识信息，用于 Activity 重建后重新查找
    private int[] mModifyingViewDepth;
    private String mModifyingViewActClass;

    private boolean mActivityResultHooked;

    boolean isPanelShowing() {
        return mModifyPanel != null;
    }

    View getPanelView() {
        return mModifyPanel;
    }

    /** 取消修改：还原视图状态并关闭面板 */
    void cancel() {
        revertViewState();
        for (java.util.Map.Entry<String, Bitmap> entry : mPendingModBitmaps.entrySet()) {
            recycleNullableBitmap(entry.getValue());
        }
        mPendingModBitmaps.clear();
        recycleNullableBitmap(mPendingImageBitmap);
        mPendingImageBitmap = null;
        dismiss();
    }

    // ---- 显示 / 关闭 ----

    void show(View selectedView, Activity activity, ViewGroup container) {
        if (mModifyPanel != null) return;
        if (selectedView == null) return;

        try {
            mCurrentlyModifyingView = selectedView;
            saveViewState(selectedView);

            ModuleResources.injectInto(activity.getResources());
            LayoutInflater inflater = LayoutInflater.from(activity);
            mModifyPanel = inflater.inflate(
                    GmResources.getLayout(R.layout.layout_modify_panel), container, false);

            setupWidthHeightAlpha(mModifyPanel, selectedView);
            setupTextEditing(mModifyPanel, selectedView);
            setupImageReplacement(mModifyPanel, selectedView, activity);
            setupPositionNudge(mModifyPanel, selectedView);
            setupConfirmCancel(mModifyPanel, selectedView);

            container.addView(mModifyPanel);
            mModifyPanel.setAlpha(0);
            mModifyPanel.animate().alpha(1).setDuration(200).start();
        } catch (Exception e) {
            Logger.e(TAG, "[ModifyPanel] showModifyPanel fail", e);
            dismiss();
        }
    }

    /** 关闭修改面板并清理所有关联状态 */
    void dismiss() {
        if (mModifyPanel == null) return;
        View panel = mModifyPanel;
        mModifyPanel = null;
        mCurrentlyModifyingView = null;
        mPendingImageView = null;
        mOriginalImageBitmap = null;
        mModifyingViewDepth = null;
        mModifyingViewActClass = null;
        panel.animate().alpha(0).setDuration(150).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) panel.getParent();
            if (parent != null) parent.removeView(panel);
        }).start();
    }

    // ---- 宽度 / 高度 / 透明度 ----

    private void setupWidthHeightAlpha(View panel, View selectedView) {
        SeekBar widthSeek = panel.findViewById(R.id.mod_width_seek);
        EditText widthText = panel.findViewById(R.id.mod_width_text);
        SeekBar heightSeek = panel.findViewById(R.id.mod_height_seek);
        EditText heightText = panel.findViewById(R.id.mod_height_text);
        SeekBar alphaSeek = panel.findViewById(R.id.mod_alpha_seek);
        EditText alphaText = panel.findViewById(R.id.mod_alpha_text);

        ViewGroup.LayoutParams lp = selectedView.getLayoutParams();
        int curWidth = lp != null ? lp.width : selectedView.getWidth();
        int curHeight = lp != null ? lp.height : selectedView.getHeight();
        int curAlpha = (int) (selectedView.getAlpha() * 255);

        widthSeek.setProgress(curWidth > 0 ? Math.min(curWidth, 2000) : selectedView.getWidth());
        heightSeek.setProgress(curHeight > 0 ? Math.min(curHeight, 2000) : selectedView.getHeight());
        alphaSeek.setProgress(curAlpha);

        widthText.setText(String.valueOf(widthSeek.getProgress()));
        heightText.setText(String.valueOf(heightSeek.getProgress()));
        alphaText.setText(String.valueOf(alphaSeek.getProgress()));

        bindSeekBar(widthSeek, widthText, SeekerType.WIDTH);
        bindSeekBar(heightSeek, heightText, SeekerType.HEIGHT);
        bindSeekBar(alphaSeek, alphaText, SeekerType.ALPHA);
    }

    // ---- 文本编辑 ----

    private void setupTextEditing(View panel, View selectedView) {
        LinearLayout textSection = panel.findViewById(R.id.mod_text_section);
        EditText textInput = panel.findViewById(R.id.mod_text_input);
        if (!(selectedView instanceof TextView)) return;

        textSection.setVisibility(View.VISIBLE);
        textInput.setText(((TextView) selectedView).getText());
        textInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (mCurrentlyModifyingView instanceof TextView) {
                    ((TextView) mCurrentlyModifyingView).setText(s);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    // ---- 图片替换 ----

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
                activity.startActivityForResult(intent, 0x5A45);
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开图片选择器", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- 位置微调 ----

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

    // ---- 确认 / 取消按钮 ----

    private void setupConfirmCancel(View panel, View selectedView) {
        SeekBar widthSeek = panel.findViewById(R.id.mod_width_seek);
        SeekBar heightSeek = panel.findViewById(R.id.mod_height_seek);
        SeekBar alphaSeek = panel.findViewById(R.id.mod_alpha_seek);
        EditText textInput = panel.findViewById(R.id.mod_text_input);

        panel.findViewById(R.id.mod_cancel).setOnClickListener(v -> cancel());
        panel.findViewById(R.id.mod_confirm).setOnClickListener(v -> {
            // 使用 mCurrentlyModifyingView 而非 captured selectedView，
            // 因为 hookActivityResult 可能在图片选择后更新了当前视图引用
            applyModification(mCurrentlyModifyingView != null ? mCurrentlyModifyingView : selectedView,
                    widthSeek, heightSeek, alphaSeek, textInput);
            dismiss();
        });
    }

    // ---- SeekBar 绑定 ----

    private void bindSeekBar(SeekBar seekBar, EditText editText, SeekerType type) {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                editText.setText(String.valueOf(p));
                if (fromUser && mCurrentlyModifyingView != null) {
                    applySeekProgressToView(mCurrentlyModifyingView, type, p);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private enum SeekerType { WIDTH, HEIGHT, ALPHA }

    private void applySeekProgressToView(View view, SeekerType type, int progress) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        switch (type) {
            case WIDTH:
            case HEIGHT:
                if (lp != null) {
                    if (type == SeekerType.WIDTH) lp.width = progress;
                    else lp.height = progress;
                    view.setLayoutParams(lp);
                }
                break;
            case ALPHA:
                view.setAlpha(progress / 255f);
                break;
        }
    }

    // ---- 应用修改 / 保存 / 还原状态 ----

    private void applyModification(View view, SeekBar widthSeek, SeekBar heightSeek,
                                   SeekBar alphaSeek, EditText textInput) {
        int w = widthSeek.getProgress();
        int h = heightSeek.getProgress();
        float a = alphaSeek.getProgress() / 255f;

        String viewKey = ViewHelper.getViewKey(view);
        ViewRule rule = mTempModifications.get(viewKey);
        if (rule == null) {
            rule = ViewHelper.makeModifyRule(view);
            // 用 saveViewState 中捕获的原始值覆盖 originals（在实时预览修改之前捕获）
            rule.origWidth = mSavedWidth > 0 ? mSavedWidth : mSavedPixelWidth;
            rule.origHeight = mSavedHeight > 0 ? mSavedHeight : mSavedPixelHeight;
            rule.origAlpha = mSavedAlpha;
            if (mSavedText != null) {
                rule.origText = mSavedText.toString();
            }
            if (mSavedLayoutParams != null) {
                rule.origLeftMargin = mSavedLayoutParams.leftMargin;
                rule.origTopMargin = mSavedLayoutParams.topMargin;
            }
            mTempModifications.put(viewKey, rule);
        }

        if (rule.origWidth > 0 && w != rule.origWidth) {
            rule.modWidth = w;
        }
        if (rule.origHeight > 0 && h != rule.origHeight) {
            rule.modHeight = h;
        }
        if (Math.abs(rule.origAlpha - a) > 0.01f) {
            rule.modAlpha = a;
        }

        if (view instanceof TextView && textInput != null && textInput.getVisibility() == View.VISIBLE) {
            String newText = textInput.getText().toString();
            if (!newText.equals(rule.origText)) {
                rule.modText = newText;
            }
        }

        if (!rule.hasModifications()) {
            mTempModifications.remove(viewKey);
        }
    }

    /**
     * 持久化所有待保存的修改并通知系统服务。
     */
    void saveAll(Activity activity, View nodeSelectorPanel, View maskView, View modifyPanel) {
        if (mTempModifications.isEmpty()) {
            Toast.makeText(activity, "没有需要保存的修改", Toast.LENGTH_SHORT).show();
            return;
        }
        String pkg = activity.getPackageName();
        for (ViewRule rule : mTempModifications.values()) {
            if ("pending".equals(rule.modImagePath)) {
                StringBuilder sb = new StringBuilder(rule.activityClass);
                if (rule.depth != null) {
                    for (int d : rule.depth) sb.append('_').append(d);
                }
                String viewKey = sb.toString();
                Bitmap bmp = mPendingModBitmaps.get(viewKey);
                if (bmp != null && !bmp.isRecycled()) {
                    String savedPath = GodModeManager.getDefault().saveImageFile(pkg, bmp);
                    if (savedPath != null) {
                        rule.modImagePath = savedPath;
                    } else {
                        rule.modImagePath = null;
                        Logger.w(TAG, "[ModifyPanel] saveAll: save modification image failed via IPC");
                    }
                } else {
                    rule.modImagePath = null;
                }
            }
        }
        mTempModifications.entrySet().removeIf(entry -> !entry.getValue().hasModifications());
        if (mTempModifications.isEmpty()) {
            Toast.makeText(activity, "没有需要保存的修改", Toast.LENGTH_SHORT).show();
            return;
        }
        if (nodeSelectorPanel != null) nodeSelectorPanel.setVisibility(View.INVISIBLE);
        if (modifyPanel != null) modifyPanel.setVisibility(View.INVISIBLE);
        if (maskView != null) maskView.setVisibility(View.INVISIBLE);

        final List<ViewRule> rulesToSave = new ArrayList<>(mTempModifications.values());
        final HashMap<ViewRule, Bitmap> snapshots = new HashMap<>();
        for (ViewRule rule : rulesToSave) {
            try {
                View view = rule.repeatable ? ViewHelper.findViewBestMatch(activity, rule) : ViewHelper.findViewByDepth(activity, rule.depth);
                if (view != null) {
                    Bitmap snapshot = ViewHelper.snapshotView(ViewHelper.findTopParentViewByChildView(view));
                    ViewHelper.drawRuleMask(snapshot, rule);
                    snapshots.put(rule, snapshot);
                }
            } catch (Exception e) {
                Logger.w(TAG, "[ModifyPanel] saveAll: snapshot failed for rule", e);
            }
        }
        // 不要回收 mPendingModBitmaps 中的位图——它们仍被 ImageView 引用显示。
        // 待 writeRule 广播后，applyModificationToView 会从磁盘加载新位图替换，
        // 旧位图届时由 GC 回收。
        mPendingModBitmaps.clear();
        mTempModifications.clear();
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            boolean allOk = true;
            for (ViewRule rule : rulesToSave) {
                Bitmap snapshot = snapshots.get(rule);
                try {
                    if (!GodModeManager.getDefault().writeRule(pkg, rule, snapshot)) {
                        allOk = false;
                    }
                } catch (Exception e) {
                    Logger.e(TAG, "[ModifyPanel] saveAll: writeRule failed", e);
                    allOk = false;
                } finally {
                    recycleNullableBitmap(snapshot);
                }
            }
            boolean finalAllOk = allOk;
            mainHandler.post(() -> {
                if (nodeSelectorPanel != null) nodeSelectorPanel.setVisibility(View.VISIBLE);
                if (modifyPanel != null) modifyPanel.setVisibility(View.VISIBLE);
                if (maskView != null) maskView.setVisibility(View.VISIBLE);
                Toast.makeText(activity,
                        finalAllOk ? "修改已保存" : "部分修改保存失败", Toast.LENGTH_SHORT).show();
            });
        }, "gm-save-thread").start();
    }

    /** 在显示面板前保存视图原始状态，用于取消还原 */
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
        mModifyingViewDepth = ViewHelper.getViewHierarchyDepth(view);
        Activity act = ViewHelper.getAttachedActivityFromView(view);
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

    private void revertViewState() {
        if (mCurrentlyModifyingView == null) return;
        if (!verifyViewIdentity(mCurrentlyModifyingView)) {
            Logger.w(TAG, "[ModifyPanel] revertViewState: view identity changed, skip revert for safety");
            return;
        }

        ViewGroup.LayoutParams lp = mCurrentlyModifyingView.getLayoutParams();
        if (lp != null) {
            if (mSavedLayoutParams != null && lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mlp.width = mSavedLayoutParams.width;
                mlp.height = mSavedLayoutParams.height;
                mlp.leftMargin = mSavedLayoutParams.leftMargin;
                mlp.topMargin = mSavedLayoutParams.topMargin;
                mCurrentlyModifyingView.setLayoutParams(mlp);
            } else if (mSavedWidth >= 0 || mSavedHeight >= 0) {
                if (mSavedWidth >= 0) lp.width = mSavedWidth;
                if (mSavedHeight >= 0) lp.height = mSavedHeight;
                mCurrentlyModifyingView.setLayoutParams(lp);
            }
        }

        mCurrentlyModifyingView.setAlpha(mSavedAlpha);

        if (mCurrentlyModifyingView instanceof TextView && mSavedText != null) {
            ((TextView) mCurrentlyModifyingView).setText(mSavedText);
        }

        if (mCurrentlyModifyingView instanceof ImageView) {
            if (mOriginalImageBitmap != null) {
                ((ImageView) mCurrentlyModifyingView).setImageBitmap(mOriginalImageBitmap);
            } else {
                ((ImageView) mCurrentlyModifyingView).setImageDrawable(null);
            }
        }

        String viewKey = ViewHelper.getViewKey(mCurrentlyModifyingView);
        mTempModifications.remove(viewKey);
    }

    private boolean verifyViewIdentity(View view) {
        if (!view.isAttachedToWindow()) return false;
        if (mModifyingViewDepth == null || mModifyingViewActClass == null) return true;
        Activity currentAct = ViewHelper.getAttachedActivityFromView(view);
        if (currentAct == null) return false;
        if (!mModifyingViewActClass.equals(currentAct.getComponentName().getClassName())) return false;
        int[] currentDepth = ViewHelper.getViewHierarchyDepth(view);
        return java.util.Arrays.equals(mModifyingViewDepth, currentDepth);
    }

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
                            if (requestCode != 0x5A45 || resultCode != Activity.RESULT_OK || data == null) return;

                            try {
                                android.net.Uri uri = data.getData();
                                if (uri == null) return;
                                Activity currentActivity = (Activity) param.thisObject;
                                try (InputStream is = currentActivity.getContentResolver().openInputStream(uri)) {
                                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                                    if (bitmap == null) return;

                                    View targetView = null;
                                    if (mModifyingViewDepth != null && mModifyingViewActClass != null
                                            && mModifyingViewActClass.equals(currentActivity.getComponentName().getClassName())) {
                                        targetView = ViewHelper.findViewByDepth(currentActivity, mModifyingViewDepth);
                                        if (targetView instanceof ImageView) {
                                            mPendingImageView = (ImageView) targetView;
                                            mCurrentlyModifyingView = targetView;
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
                                    String viewKey = ViewHelper.getViewKey(targetView);
                                    ViewRule rule = mTempModifications.get(viewKey);
                                    if (rule == null) {
                                        rule = ViewHelper.makeModifyRule(targetView);
                                        mTempModifications.put(viewKey, rule);
                                    }
                                    rule.modImagePath = "pending";
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
}
