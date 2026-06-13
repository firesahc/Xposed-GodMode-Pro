package com.kaisar.xposed.godmode.injection.editor.panel;

import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

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
 *   <li>Tracks pending modifications in {@link #mTempModifications} for batch save/cancel</li>
 *   <li>Captures original view state via {@link ViewSnapshot} before editing</li>
 *   <li>Hooks Activity.onActivityResult to handle image picker result</li>
 * </ul>
 */
public class PropertyEditorPanel {

    private static final String TAG = "PropertyEditorPanel";

    private View mPanelView;
    private View mTargetView;
    private ImageView mPendingImageView;
    private Bitmap mOriginalImageBitmap;
    private Bitmap mPendingImageBitmap;
    private HashMap<String, Bitmap> mPendingModBitmaps = new HashMap<>();

    // Pending modifications keyed by rule key — batch saved or cancelled together
    final HashMap<String, RuleRecord> mTempModifications = new HashMap<>();

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
            mPanelView.animate().alpha(1).setDuration(200).start();
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
        // Recycle pending mod bitmaps on dismiss (mPendingModBitmaps stores loaded replacement images).
        // For cancel() / saveAll() see the confirm/cancel button handlers.
        panel.animate().alpha(0).setDuration(150).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) panel.getParent();
            if (parent != null) parent.removeView(panel);
        }).start();
    }

    /** Handle Activity onActivityResult for image picker callback. */
    public void cancel() {
        revertViewState();
        for (Map.Entry<String, Bitmap> entry : mPendingModBitmaps.entrySet()) {
            recycleNullableBitmap(entry.getValue());
        }
        mPendingModBitmaps.clear();
        recycleNullableBitmap(mPendingImageBitmap);
        mPendingImageBitmap = null;
        dismiss();
    }

    // ---- 闂備礁鎲￠崝鏇㈠箠濮椻偓瀹?Seeker 缂傚倸鍊烽悞锕傚垂閻㈠憡鍋?----

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

    // ---- 闂備焦鎮堕崕杈ㄦ櫠閼恒儲娅犻柡宥庡幖閸楁娊鏌熺粙鍧楊€楅幖?----

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

    // ---- 闁诲氦顫夐幃鍫曞磿闁秴鐭?----

    private void bindSeek(SeekBar seekBar, EditText text, View target, SeekerType type) {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                if (!fromUser) return;
                text.setText(String.valueOf(val));
                ViewGroup.LayoutParams lp = target.getLayoutParams();
                switch (type) {
                    case WIDTH: if (lp != null) { lp.width = val; target.setLayoutParams(lp); } break;
                    case HEIGHT: if (lp != null) { lp.height = val; target.setLayoutParams(lp); } break;
                    case ALPHA: target.setAlpha(val / 255f); break;
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private enum SeekerType { WIDTH, HEIGHT, ALPHA }

    // ---- 闂佽崵鍠愰悷銉ノ涘Δ鈧湁婵せ鍋撻柟顔规櫊閹虫粎鍠婂Ο杞板婵炶揪缍侀ˉ鎾剁不娴犲鍊?/ 闂佸搫顦弲婊堟晪闁?----

    /** 闂備線娼荤拹鐔煎礉瀹ュ憘鐔稿緞瀹€鈧惌鍡樼箾瀹割喕绨兼俊妞煎姂閺屸剝寰勬繝鍕檸婵犫拃鍛ｇ紒灞藉船閳规垿宕卞▎鎰枛闂佽崵鍠愰悷銉ノ涘Δ鈧湁婵せ鍋撶€规洏鍨介幃銏＄附婢跺绋勯梻浣虹帛椤牓宕洪弽顓炵劦妞ゆ垼娉曠粵蹇曠磼鏉堛劎绠為柟顔荤矙婵℃悂濡堕崶顏勵棟闂備礁鎲￠悷锕傛偋濡ゅ啰鐭撻梺鍨儐娴溿倖淇婇姘儓闁?*/
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

    /** 闂佸搫顦弲婊堟晪闁汇埄鍨奸崑鍡涘箟閹绢喖骞㈡俊銈勮兌椤╊參姊洪崨濠傛诞妞わ綇濡囬幑銏狀潩閹典礁浜炬繛鎴炵懐閻掕姤绻涢崼鐔风伌鐎规洏鍨介幃銏＄附婢跺绋勯梻浣虹帛椤牓宕洪弽顓炵劦?*/
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

    /** 婵犵妲呴崑鈧柛瀣崌閺岋紕浠︾拠鎻掑Г濡炪値鍋勯澶婄暦閸洖鐭楅柕澹懐鍘抽梻浣告惈閻楀棝藝娴兼潙鐤鹃柕澶涘閳绘棃鏌￠崒姘挃闁荤喆鍨硅彁闁搞儯鍔庣粻鎾绘煟鎺抽崝鎴﹀箚閸愵喖绀嬫い鎺戝€婚悾鎶芥煙?缂傚倷绶￠崑澶愵敋瑜旈獮鍐即閵忕姷顦遍悷婊呭鐢帞鏁?*/
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

    /** 应用 UI 控件的修改值到 RuleRecord（创建或更新）。
     * <p>
     * 结构信息和原始值来自预捕获的 {@link #mSnapshot}（视图未修改时冻结），
     * 修改值（mod*）来自 UI 控件当前状态。创建时无需后置修正。 */
    private void applyModification(View view, SeekBar widthSeek, SeekBar heightSeek,
                                    SeekBar alphaSeek, EditText textInput) {
        int w = widthSeek.getProgress();
        int h = heightSeek.getProgress();
        float a = alphaSeek.getProgress() / 255f;

        String viewKey = ViewUtils.getViewKey(view);
        RuleRecord rule = mTempModifications.get(viewKey);
        if (rule == null) {
            // 使用快照创建规则 — 结构来自视图，原始值来自快照，无需后置修正
            rule = RuleRecordFactory.makeModifyRule(view, mSnapshot);
            mTempModifications.put(viewKey, rule);
        }
        // 规则已存在（如 image-pick 预创建）— 创建时已用快照，orig* 正确，无需修正

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
     * 闂備礁缍婇弲鎻掝渻閹烘梻涓嶆繛鍡樻尭缁€宀勬煛瀹ュ啫濡兼い锝嗙叀閺岋繝宕煎┑鎰︾紓浣稿閸犳劗绮氶崡鐐╂斀闁糕剝鐟﹂幉濂告⒑濮瑰洤濡奸悗姘间邯楠炲牓濡搁埡浣哄摋闂佽崵鍠愭刊鐣屸偓姘懇濮婃椽顢曢敐鍡欐闂佺粯鎼槐鏇㈠礌閺嶎厽鍤掗柕鍫濇搐閻撴岸姊洪崫鍕缂佸鐡ㄩ幈銊╂偄閻撳宫?
     */
    public void saveAll(Activity activity, View nodeSelectorPanel, View maskView, View modifyPanel) {
        if (mTempModifications.isEmpty()) {
            Toast.makeText(activity, "没有需要保存的修改", Toast.LENGTH_SHORT).show();
            return;
        }
        String pkg = activity.getPackageName();
        for (RuleRecord rule : mTempModifications.values()) {
            if ("pending".equals(rule.modImagePath)) {
                StringBuilder sb = new StringBuilder(rule.activityClass);
                if (rule.depth != null) {
                    for (int d : rule.depth) sb.append('_').append(d);
                }
                String viewKey = sb.toString();
                Bitmap bmp = mPendingModBitmaps.get(viewKey);
                if (bmp != null && !bmp.isRecycled()) {
                    String savedPath = RuleServiceClient.getDefault().saveImageFile(pkg, bmp);
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

        final List<RuleRecord> rulesToSave = new ArrayList<>(mTempModifications.values());
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
        // 濠电偞鍨堕幐鍝ョ矓閻戝鈧懘鏁冮崒姘卞€為梺缁樺姇閻°劑寮?mPendingModBitmaps 濠电偞鍨堕幖鈺呭储閹€鏋旈柟瀵稿仦婵鈧箍鍎遍幊蹇曠矉閸儲鐓冮柕澶涢檮閻撴盯鏌嶈閸撴岸寮婚妸鈺傚仼闁绘ê纾々閿嬨亜閹达絾纭堕柣顓熺箞閹?ImageView 闁诲孩顔栭崰妤€煤濠婂牆鏋侀柕鍫濐槸閸欏﹪鏌涢幘妞炬缁敻姊?
        // 闁?writeRule 婵°倗濮烽崑鐐碘偓绗涘洤绠伴梺顒€绉寸憴锕傚箹閹碱厼鐏ｇ紒鈧繅顪秔lyModificationToView 濠电偞娼欓崥瀣儗椤旀儳鍨濋柣姗€娼чˉ姘归敐鍥у妺缁剧偓鎮傞弻娑㈠籍閸屾顒佺箾閺夋垶鍠樼€殿噮鍓氶敍鎰媴閾忓墣銏ゆ⒑閹肩偛鍔ら柛瀣尭闇夋繛鎴欏灩缁犲弶銇勯顐㈡灓缂佲偓?
        // 闂備礁鎼崬鏌ュ磼濠婂憛銏ゆ⒑閹肩偛鍔滈柛搴㈠▕閹洦銈ｉ崘銊х潉闂佸憡鎸烽悞锕傚汲?GC 闂備焦鎮堕崕鎶藉磻閻愬搫鏋佺憸鐗堝笒杩?
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
                    recycleNullableBitmap(snapshot);
                }
            }
            boolean finalAllOk = allOk;
            String finalFailed = failedRules.isEmpty() ? "" :
                    "失败: " + String.join(", ", failedRules);
            mainHandler.post(() -> {
                if (nodeSelectorPanel != null) nodeSelectorPanel.setVisibility(View.VISIBLE);
                if (modifyPanel != null) modifyPanel.setVisibility(View.VISIBLE);
                if (maskView != null) maskView.setVisibility(View.VISIBLE);
                Toast.makeText(activity,
                        finalAllOk ? "所有修改已保存" : "保存失败: " + finalFailed,
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    // ---- Xposed Hook 闂備焦妞垮鍧楀礉鐎ｎ剝濮虫い鎺戝閻愬﹪鏌涢幘妤€鍠氶弳顒勬⒒娓氬洤浜滄い锔藉閳ь剚鍝庨崝搴ｆ閹烘绠婚悗鐢告櫜閸?----

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
                                    RuleRecord rule = mTempModifications.get(viewKey);
                                    if (rule == null) {
                                        rule = RuleRecordFactory.makeModifyRule(targetView, mSnapshot);
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

    // ---- 闂佽崵濮崇粈浣割焽閳ユ緞娑㈠醇閺囩偤妫?----

    public View getPanelView() { return mPanelView; }
    public View getTargetView() { return mTargetView; }
    public ImageView getPendingImageView() { return mPendingImageView; }
    public void setPendingImageView(ImageView v) { mPendingImageView = v; }
    public Bitmap getOriginalImageBitmap() { return mOriginalImageBitmap; }
    public void setOriginalImageBitmap(Bitmap b) { mOriginalImageBitmap = b; }
    public Map<String, Bitmap> getPendingModBitmaps() { return mPendingModBitmaps; }
    public boolean isShowing() { return mPanelView != null; }
}
