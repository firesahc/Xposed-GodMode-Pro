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
 * 闂佽绻掗崑鐔煎磻閻愬搫鐒垫い鎴墮閹虫劕顪冩禒瀣骇闁割偆鍠庨悘瀛樼箾闂傛潙宓嗙€?闂?闂?濠?闂傚倷绶￠崑鍕崲閹存惊锝夊箣閻愭潙鍔?濠电偠鎻徊鐣岀矓閸洘鍋?闂備礁鎼崐绋棵洪敃鈧敃?闂備焦鎮堕崕杈ㄦ櫠閼恒儲娅犻柡宥庡亗濞岊亪鏌ｉ幇顔克夐柛瀣崄椤﹀绱掓鏍︾凹婵炵厧绻橀獮瀣枎鐏炴垝澹?
 * 缂傚倷鑳舵刊瀵告閺囥垹绠栧┑鐘蹭紖瑜版帩鏁婇柤濮愬€涙竟姗€姊洪悜鈺傛珦闁哥姵顨婇幃銉╂晲閸℃ê鍔呴梺鍝勫暙閸婂綊寮宠箛鎾闁哄倹顑欓崕搴ｇ磼濡も偓閸婂潡骞嗛崘顔肩妞ゆ牗顨呮禍楣冩煙闁箑鐏辨繛鍏煎浮閺屾盯濡烽敃鈧崝銈夋煟椤愶綇鑰挎鐐╁亾閻熸粌鐭傞獮鍫ュΩ閳轰胶鍝楅悷婊呭鐢顩奸妸鈺傜厸濠㈣泛鍑芥蹇涙煃?
 * <p>
 * 闂備胶鍘у畷顒勬儗娓氣偓閹苯螖閸涱喗娅?
 * <ul>
 *   <li>闂備礁鎲″缁樻叏閹灐褰掑炊椤掑倸绁﹂梺鑲╊焾閻忔岸鍩㈤弮鍌滅＜闁逞屽墯濞碱亪鎮ч崼婵嗕紟闂備浇銆€閸嬫挾鎲搁悧鍫濈瑲婵℃ぜ鍔戦弻?UI</li>
 *   <li>闂佽楠稿﹢閬嶅磻閻愬樊娓婚柛灞惧喕缁憋綁鏌涢弴銊ユ珮婵炲吋妫冮幃?濠?闂傚倷绶￠崑鍕崲閹存惊锝夊箣閻愭潙鍔?濠电偠鎻徊鐣岀矓閸洘鍋?闂備礁鎼崐绋棵洪敃鈧敃?闂備焦鎮堕崕杈ㄦ櫠閼恒儲娅犻柡宥庡幖閻淇婇婊呭笡缂?/li>
 *   <li>濠电儑绲藉ú锔炬崲閸岀偞鍋ら柕濞у嫬鏋傞梺绯曞墲椤ㄥ棛绮嬮崼銉︾厱婵☆垳鍘ч弸搴亜椤愶絽濮嶉柟顔规櫊閹虫粎鍠婂Ο杞板婵炶揪绲块崕銈夊汲韫囨拋鐟邦煥閸愵噮鈧鎲搁弶鎸庢悙闁?闂佸搫顦弲婊堟晪闁?/li>
 *   <li>缂備胶铏庨崣搴ㄥ窗濞戙埄鏁囩紓浣诡焽閳瑰秵銇勯弮鍥撻柡鍡╁弮閺屾稑顫濋鍕碘偓妤呮煕閻斿憡宕岀€?{@link #mTempModifications} 闁诲骸鐏氬姗€骞婂鍥ㄥ床婵ɑ澧庨崑鎾斥槈濞嗘鍔稿銈庡亜椤﹂潧鐣?/li>
 *   <li>闂備礁缍婇弲鎻掝渻閹烘梻涓嶆繛鍡樻尭缁€宀勬煛瀹擃喖妫楅悵顖毼旈悩闈涗沪閻庣瑳鍥х闁告鍎愰崯鍛存椤愵偄骞樼紒鐘辩矙閹鈽夊▎妯煎姺濠电偛鐗婇崹鍓佺矚闁稁鏁婇柤鎭掑劜濮?/li>
 *   <li>Hook Activity.onActivityResult 濠电偛顕慨浼村磿闁单鍥煛閸涱喖浠╅梺绯曞墲椤ㄥ棛绮嬮崼銉︾厽婵°倐鍋撴繛璇х畱闇夋繛鎴欏灩缁?/li>
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

    // 闁诲骸鐏氬姗€骞婂鍥ㄥ床婵ɑ澧庨崑鎾斥槈濞嗘鍔峰┑鐐茬墛閸ㄥ墎绮氶柆宥庢晩闁兼亽鍎插В澶愭煟閻斿摜鎳冮悗姘煎墴瀹?
    final HashMap<String, RuleRecord> mTempModifications = new HashMap<>();

    // 闂備線娼荤拹鐔煎礉瀹€鍕仾闁告洦鍨扮猾宥夋煕椤愶絾澶勯柡鍡樻礋閹嘲鈻庤箛鏇烆暪闂侀€涚┒閸旀垵顕ｉ妸鈺傚仭闁哄顑欓弸鈧梻浣侯攰椤煤濠靛宓侀煫鍥ㄧ⊕閸庡秹鏌涢弴銊ユ珮婵炲吋甯￠弻娑㈠Ψ閿曗偓閸斻倝鎮介锝呬簼闁逛究鍔庨埀顒婄秵閸撴瑧鐟ч梻?
    private ViewGroup.MarginLayoutParams mSavedLayoutParams;
    private int mSavedWidth = -1;
    private int mSavedHeight = -1;
    private int mSavedPixelWidth;
    private int mSavedPixelHeight;
    private float mSavedAlpha;
    private CharSequence mSavedText;

    // 濠电儑绲藉ú锔炬崲閸岀偞鍋ら柕濞у嫬鏋傞梺绯曞墲椤ㄥ棛绮嬮崼銉︾厸鐎广儱鎳忕粚鍧楁煟閿旇棄鐏︾紒宀勪憾閸╁嫰宕樿缁€鈧梻浣瑰缁嬫垿鎯夋總绋挎瀬闁靛牆妫涢々?Activity 闂傚倷鐒﹁ぐ鍐矓鐎靛摜纾介柟鎹愵嚙鐟欙箓骞栧ǎ顒€濡兼繛鍛灲閺岋繝宕掑鍐炬毉闂佺粯鎼换婵嬬嵁?
    private int[] mModifyingViewDepth;
    private String mModifyingViewActClass;

    private boolean mActivityResultHooked;

    /**
     * 闂備礁鎼€氼剚鏅舵禒瀣︽慨妯哄綁濞岊亪鏌ｉ幇顔克夐柛瀣崄椤﹀绱掓鏍︾凹婵炵厧绻橀獮瀣晜閼恒儯鈧啴姊洪崫鍕垫Ъ闁跨喆鍎甸崺鈧?
     */
    public void show(View targetView, Activity activity, ViewGroup container) {
        if (mPanelView != null || targetView == null) return;
        mTargetView = targetView;
        try {
            saveViewState(targetView);

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

    /** 闂備胶顭堢换鎴炵箾婵犲伣娑㈠箻椤旂瓔妫冮梺闈涚箚閸撴繄娆?*/
    public void dismiss() {
        if (mPanelView == null) return;
        View panel = mPanelView;
        mPanelView = null;
        mTargetView = null;
        mPendingImageView = null;
        mOriginalImageBitmap = null;
        mModifyingViewDepth = null;
        mModifyingViewActClass = null;
        // 婵犵數鍋涢ˇ顓㈠礉瀹ュ绀堝ù鐓庣摠閺咁剚鎱ㄥ鍡椾簽闁荤喐绻堥弻娑㈠Ψ瑜嶆禒锕傛煛?婵犵數鍋為幐鎼佸箠閹版澘鐓?mPendingModBitmaps 闂備胶鍋ㄩ崕鏌ュ蓟閿熺姴鐒?濠电偠鎻徊鐣岀矓閺夋嚚鐟邦潨閳ь剙鐣烽敐澶樻晬婵犻潧妫楅獮瀣箾鐎电孝缂佸鍏橀敐?ImageView 闁诲孩顔栭崰妤€煤濠婂牆鏋侀柕鍫濐槸閸欏﹪鏌涢幘妞炬缁敻姊?
        // 濠?saveAll() 闂傚倸鍊稿ú鐘诲磻閹剧粯鍋￠柡鍥ㄦ皑椤︼箓鏌ｉ敐鍥ｇ紒鍌涘浮椤㈡鎷呰ぐ鎺擃€栭梻浣哥秺閺呮彃顪冮幒鏃備笉婵炲棙鎸哥粈宀勬煛瀹擃喖瀚々顓㈡⒑缂佹﹩娈旀繛璇х畵瀵悂宕橀埡鍐炬祫闂傚鍓氱粊鎾磻閹捐纾兼慨姗嗗弨閸?cancel() 闂?saveAll() 濠电偞鍨堕幖鈺呭储婵傛潌鍥煛閸涱喖浠╅梺绯曞墲閸斿繘宕?
        panel.animate().alpha(0).setDuration(150).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) panel.getParent();
            if (parent != null) parent.removeView(panel);
        }).start();
    }

    /** 闂備礁鎲￠悷锕傛偋濡ゅ啰鐭撻梺鍨儑閳瑰秵銇勯弮鍥撻柡鍡╁弮閺屻劌鈽夊▎搴濆缂備胶绮ú鐔风暦閵忋倖鍋勬繛鑼帛缂嶅﹪姊洪幖鐐插姎濠⒀嗗Г閹便劑骞栨担鍝ョ暠婵炶揪绲鹃悺鏇犫偓姘懇閺屾稖绠涢弬搴撴灆濠碘槅鍋呴幐鍐参涢崘顔碱潊闁斥晛鍟伴崙?*/
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

    // ---- 濠电偠鎻徊鐣岀矓閸洘鍋柛鈩冾殢閸ゆ銇勯弮鍥ㄧ《婵?----

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

    // ---- 缂備胶铏庨崣搴ㄥ窗濞戙埄鏁?/ 闂備礁鎲￠悷锕傛偋濡ゅ啰鐭撻柣鎴ｆ缁犳澘顭块懜闈涘閻?----

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

    // ---- 闂佸湱鍘ч悺銊ヮ潖婵犳艾鏋侀柕鍫濇閳瑰秵銇勯弮鍥撻柡?/ 濠电儑绲藉ú锔炬崲閸岀偞鍋?----

    /** 濠电偛顕慨瀛橆殽閸濄儳绀婇悗锝庡枛缁€?UI 闂備胶绮…鍫ュ春閺嶎厼鐒垫い鎴ｆ硶閸斿秹鏌ｆ惔顔肩仩妞?RuleRecord 婵°倗濮烽崑鐘测枖濞戙垺鍋ら柕濞炬櫅缁€鍌炴煏婢跺牆鈧洟藝閺屻儲鐓涢柛鎰ㄦ櫆閺嬪嫰鏌熸笟鍨鐎殿喓鍔忛妵鎰板箳閹剧懓浜栭梻?*/
    private void applyModification(View view, SeekBar widthSeek, SeekBar heightSeek,
                                    SeekBar alphaSeek, EditText textInput) {
        int w = widthSeek.getProgress();
        int h = heightSeek.getProgress();
        float a = alphaSeek.getProgress() / 255f;

        String viewKey = ViewUtils.getViewKey(view);
        RuleRecord rule = mTempModifications.get(viewKey);
        if (rule == null) {
            rule = RuleRecordFactory.makeModifyRule(view);
            // 闂?saveViewState 濠电偞鍨堕幖鈺呭储閽樺鏆﹂柣鏂垮悑閸ゆ柨顪冪€ｎ亜顒㈤柣锝変憾閺屾稑螣閻撳孩鐎诲銈庡亝閸旀瑥鐣烽幇鐗堝殞闂侇叏闄勯懜褰掓⒑?originals
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
                                        rule = RuleRecordFactory.makeModifyRule(targetView);
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
