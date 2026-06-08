package com.kaisar.xposed.godmode.injection.editor.panel;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;
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
import com.kaisar.xposed.godmode.injection.ModuleResources;
import com.kaisar.xposed.godmode.engine.matcher.ViewFinder;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.rule.RuleMapper;
import com.kaisar.xposed.godmode.injection.editor.RuleRecordFactory;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.util.GmResources;
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 闁诲繒鍋熼崑鐐哄焵椤戭剙鎳愬浠嬪级閸喎鐏存繛闂村嵆瀵?闂?闁?婵?闂備緡鍋勭换鎴澪ｉ幋鐐村劅?婵炶揪绲界粔鍫曟偪?闂佸搫鍊稿ú锕€锕?闂佹悶鍎辨晶鑺ユ櫠閺嶎偂娌柣鎰ˉ閸嬫捁顦寸紒槌栦簼濞煎繘骞嬪▎灞戒壕?
 * 缂備胶濯寸槐鏇㈠箖婵犲伅褰掝敊閼姐倛澹橀梺鐑╂櫓閸犳鎮ラ敐鍡樺劅闁哄啫鍊归弳蹇撯槈閺傛鍎庣紒妤€鍊块幆鍐礋椤栨浜鹃柟閭﹀灱濞兼帡鏌涢妷锕€鍔ら柣顐ｏ耿楠炩偓鐟滃矂骞堥妸鈺佺哗鐟滅増甯楀銊╂煛婢跺函楠忛柍?
 * <p>
 * 闂佺厧宕惌渚€鎯屽Δ鍛櫖?
 * <ul>
 *   <li>闂佸憡姊绘慨鎯归崶顒傚祦闁肩鐏氶埢鏃傜磼閳ь剚娼悧鍫濆伎闂佽　鍋撶憸鐗堝笚濡椼劑鏌?UI</li>
 *   <li>闁诲骸婀遍崑鐐差渻閸屾冻绱ｉ柛鏇ㄥ櫘濞兼棃鎮?婵?闂備緡鍋勭换鎴澪ｉ幋鐐村劅?婵炶揪绲界粔鍫曟偪?闂佸搫鍊稿ú锕€锕?闂佹悶鍎辨晶鑺ユ櫠閺嶎厼鐭楁俊顖滅帛缁?/li>
 *   <li>婵烇絽娲︾换鍌炴偤閵娧勫枂闁糕剝顨嗙粋鍫ユ煕濡厧鏋庢い顐ｅ姍閹晠鎳滅喊妯轰壕濞达絿鍎ら弳蹇撁瑰鍐€楃憸鏉挎搐閳?闁哄鏅滈敋閻?/li>
 *   <li>缂佺虎鍙庨崰娑㈩敇缂佹鈹嶆い鏃囧Г閺嗩參鏌涘顒勵€楅柛鐔告崌瀹?{@link #mTempModifications} 閻庡灚婢橀幊宥囨崲濮樻墎鍋撳☉娆樼劸妞ゎ偄顦靛畷?/li>
 *   <li>闂佸綊鏅插鎺旂不濞嗘挸绀岄柡宓棗鐝Δ鐘靛仦鐎笛囧箰閸楃儐鍟呴棅顐幘缁犱粙鎮楀☉娆樼劷婵炲牊鍨剁粚閬嶎敊閼恒儲姣?/li>
 *   <li>Hook Activity.onActivityResult 婵炲濮伴崕閬嵥囬埡鍛仩闁糕剝顨嗙粋鍫ユ煟濡も偓濞诧箑霉濞戙垹绠?/li>
 * </ul>
 */
public class PropertyEditorPanel {

    private View mPanelView;
    private View mTargetView;
    private ImageView mPendingImageView;
    private Bitmap mOriginalImageBitmap;
    private Bitmap mPendingImageBitmap;
    private HashMap<String, Bitmap> mPendingModBitmaps = new HashMap<>();

    // 閻庡灚婢橀幊宥囨崲濮樻墎鍋撳☉娆樼劷婵炲牊鍨剁粚閬嶎敊閼恒儲姣夐柣鐔哥懃鐎氼剟宕?
    final HashMap<String, RuleRecord> mTempModifications = new HashMap<>();

    // 闂侀潻璐熼崝宀勬偪閸曨垰绫嶉柛顐ｆ处閺嗘洟鎮峰▎蹇曞闁逛究鍔戝銊╂偡閺夋鏋€闂佺顫夊ú婵嬬嵁韫囨稒鍎嶉柛鏇ㄥ櫘濞兼帡鏌涢妷锕€鍔ら悽顖ｅ亝閹便劎鈧綆鍓欑瑧闂?
    private ViewGroup.MarginLayoutParams mSavedLayoutParams;
    private int mSavedWidth = -1;
    private int mSavedHeight = -1;
    private int mSavedPixelWidth;
    private int mSavedPixelHeight;
    private float mSavedAlpha;
    private CharSequence mSavedText;

    // 婵烇絽娲︾换鍌炴偤閵娧勫枂闁糕剝顨嗙粋鍫ユ煛瀹ュ懏绌块柣锔藉灦缁岄亶鍩勯崘褏绀€闂佹寧绋戦惉濂稿极閵堝棛顩?Activity 闂備焦褰冪粔瀵哥磽閹捐瑙﹂幖娣妼濞呫垽鏌￠崒婊冾暭闁绘搫绻濋獮?
    private int[] mModifyingViewDepth;
    private String mModifyingViewActClass;

    private boolean mActivityResultHooked;

    /**
     * 闂佸搫瀚晶浠嬪Φ濮樺彉娌柣鎰ˉ閸嬫捁顦寸紒槌栦簼濞煎繘骞嬮敂鑺ャ€冮梺鍝勵槼閿熴儵鍩€?
     */
    public void show(View targetView, Activity activity, ViewGroup container) {
        if (mPanelView != null || targetView == null) return;
        mTargetView = targetView;
        try {
            saveViewState(targetView);

            ModuleResources.injectInto(activity.getResources());
            LayoutInflater inflater = LayoutInflater.from(activity);
            mPanelView = inflater.inflate(
                    GmResources.getLayout(R.layout.layout_modify_panel), container, false);

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

    /** 闂佺绻戞繛濠偽涢幘顔筋棃闁靛繆鍓濈欢?*/
    public void dismiss() {
        if (mPanelView == null) return;
        View panel = mPanelView;
        mPanelView = null;
        mTargetView = null;
        mPendingImageView = null;
        mOriginalImageBitmap = null;
        mModifyingViewDepth = null;
        mModifyingViewActClass = null;
        // 濠电偛顦崝宥夊礈娴煎瓨鏅慨姗嗗亞閻熸繈鏌涢妷褍浠﹂柡?濠电偞鎸搁幊鎰板煘?mPendingModBitmaps 闂佺偨鍎查弻锟犲焵?婵炶揪绲界粔鏉懨瑰鈧畷锝夘敍濠靛棗骞嬫繛瀵稿Т缁夌兘锝?ImageView 閻庢鍠楀ú婊堝极閵堝鍙婇柛鎾椾椒绮甸梺?
        // 婵?saveAll() 闂傚倸娲犻崑鎾绘偡閺囨氨顦﹂柣锝囧У缁傛帡顢楁担褰掓闂佸綊鏅插鎺旂不濞嗘挸绀岄柡宓嫮顩梺缁橆殔濞诧箓寮搁崘鈺冾浄闂婎剚绁撮崑鎾诲磼濮橆叀鍚?cancel() 闂?saveAll() 婵炴垶鎼╅崢濂杆囬埡鍛仩闁糕剝鍔忛崑?
        panel.animate().alpha(0).setDuration(150).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) panel.getParent();
            if (parent != null) parent.removeView(panel);
        }).start();
    }

    /** 闂佸憡鐟﹂悧妤冪矓闁垮鈹嶆い鏃囧Г閺嗩參鏌ㄥ☉娆庝孩缂佺粯娲熷畷銏ゆ偄濞茬粯缍婇梺鎼炲劚婢ц姤鎱ㄩ幖浣哥畱濞达絾鐡曠€氭瑩鏌涜箛鏂库枙婵☆偅鎸冲Λ鍐閳╁啰鍑?*/
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

    // ---- 闂佸憡鍔曢幊姗€宕?Seeker 缂傚倷鐒﹂崹鐢告偩?----

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

    // ---- 闂佹悶鍎辨晶鑺ユ櫠閺嶎厼鍗抽柟绋块鎼?----

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
                Toast.makeText(activity, "闂佸搫鍟版慨鐢垫兜閸洖绠ラ柟鎯х－绾惧鏌涢妷銉モ挃濠⒀勭墵閺屽懏寰勭€ｎ亶浠撮梺?, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- 婵炶揪绲界粔鍫曟偪閸℃鍤楁い鏃囨硶濞?----

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

    // ---- 缂佺虎鍙庨崰娑㈩敇?/ 闂佸憡鐟﹂悧妤冪矓閻戣棄绠板鑸靛姈鐏?----

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

    // ---- 閻庤鎮堕崕閬嶅矗?----

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

    // ---- 闁荤喐鐟ュΛ妤€霉濮椻偓閹晠鎳滅喊妯轰壕濞达綁顥撶粻浠嬫倵?/ 闁哄鏅滈敋閻?----

    /** 闂侀潻璐熼崝宥呂熸径宀€鐭嗘繛宸簼濡椼劑鏌℃径濠勪虎濠⒀呭У缁屽崬鈹戦崱娆愭喖闁荤喐鐟ュΛ妤€霉濮椻偓瀹曘垽鎮㈡總澶嬬稄闂佺粯顭堥崺鏍焵椤戣法绛忕紒杈ㄧ箞閹粙濡搁妶鍥闂佸憡鐟﹂悧妤冪矓闁垮浜ゆ俊顖氭惈閺?*/
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

    /** 闁哄鏅滈敋閻㈩垼鍋嗛幉鎾幢濡や胶顩梺鍛婂浮椤ｏ妇鎹㈠鎵佸亾濞戞瑯鐒芥繛鍫熷灴瀹曘垽鎮㈡總澶嬬稄闂佺粯顭堥崺鏍焵?*/
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

    /** 濠碘槅鍋€閸嬫捇鏌＄仦璇插姤妞ゎ偄顑夊畷鍫曞矗閵壯呯厳闂佸搫鐗嗛ˇ浼村疾閵夛妇鈻旈柡鍌氬⒔閻熴垹菐閸ャ劎绠撻柣掳鍔戦幆鍐礋椤掑倻鐣抽柟?缂備緡鍋夐褔骞冮弴銏犵鐟滅増甯掔敮?*/
    private boolean verifyViewIdentity(View view) {
        if (!view.isAttachedToWindow()) return false;
        if (mModifyingViewDepth == null || mModifyingViewActClass == null) return true;
        Activity currentAct = ViewUtils.getAttachedActivityFromView(view);
        if (currentAct == null) return false;
        if (!mModifyingViewActClass.equals(currentAct.getComponentName().getClassName())) return false;
        int[] currentDepth = ViewUtils.getViewHierarchyDepth(view);
        return java.util.Arrays.equals(mModifyingViewDepth, currentDepth);
    }

    // ---- 闁圭厧鐡ㄥ濠氬极閵堝棛鈹嶆い鏃囧Г閺?/ 婵烇絽娲︾换鍌炴偤?----

    /** 婵炲濮存鍝ョ礊鐎ｎ喖绀?UI 闂佺粯顭堥崺鏍焵椤戣法鍔嶉柣搴灠椤?RuleRecord 濡ょ姷鍋犲▔娑㈡偤閵娾晛绀傞柕澶堝€曢ˇ鏌ユ煛閸愨晜鏋勯柟渚垮姂瀵劏銇愰幒鎾瑰亖闂?*/
    private void applyModification(View view, SeekBar widthSeek, SeekBar heightSeek,
                                    SeekBar alphaSeek, EditText textInput) {
        int w = widthSeek.getProgress();
        int h = heightSeek.getProgress();
        float a = alphaSeek.getProgress() / 255f;

        String viewKey = ViewUtils.getViewKey(view);
        RuleRecord rule = mTempModifications.get(viewKey);
        if (rule == null) {
            rule = RuleRecordFactory.makeModifyRule(view);
            // 闂?saveViewState 婵炴垶鎼╅崢钘夌暦閻斿吋鍤斿瀣閻ｉ亶鏌涘Ο鐓庢瀻妞ゎ偅鍔欏畷鎰版嚌闁附鑸归梺?originals
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
     * 闂佸綊鏅插鎺旂不濞嗘挸绀岄柡宥冨妼椤ｆ煡鏌￠崼婵愭Ц缂佸墎鍠愮粚鍗炩攽閸℃瑦鎲奸梺姹囧妼鐎氼亪骞堥妸鈺佺哗闁荤喐濯界€氭瑩姊洪锝嗙殤闁绘搫绱曢崠鏍嚒閵堝洤鐓氶梺鍝勭墕缁夊瓨鎱ㄩ悢鐓幬?
     */
    public void saveAll(Activity activity, View nodeSelectorPanel, View maskView, View modifyPanel) {
        if (mTempModifications.isEmpty()) {
            Toast.makeText(activity, "濠电偛澶囬崜婵嗭耿娓氣偓濡線鍩€椤掑倹鍟哄ù锝夘棑缁犱粙鎮楀☉娆樼劷婵炲牊鍨剁粚閬嶎敊閼恒儲姣?, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(activity, "濠电偛澶囬崜婵嗭耿娓氣偓濡線鍩€椤掑倹鍟哄ù锝夘棑缁犱粙鎮楀☉娆樼劷婵炲牊鍨剁粚閬嶎敊閼恒儲姣?, Toast.LENGTH_SHORT).show();
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
                    view = ViewFinder.findViewBestMatch(
                            (ViewGroup) activity.getWindow().getDecorView(), engineRule,
                            activity.getPackageManager(), activity.getPackageName());
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
        // 婵炴垶鎸哥粔鐑姐€呴敃鍌氱倞闁绘劕鐡ㄩ弳?mPendingModBitmaps 婵炴垶鎼╅崢鎯р枔閹寸偞濯寸€广儱鎳忕粋鍫ユ煃閵夛附鐓涢柍褜鍓氶弻銊╂偩閻樺磭顩锋い鎴ｆ硶閻繈鎮?ImageView 閻庢鍠楀ú婊堝极閵堝鍙婇柛鎾椾椒绮甸梺?
        // 閻?writeRule 濡ょ姷鍋炵€笛囧箰闁秴瑙﹂幖鎼灣缁€濉plyModificationToView 婵炴潙鍚嬮惌顔惧垝閻橀潧顥氬ù锝囧劋绾炬悂鏌涢弮鍌毿繛鏉戞喘瀵剚锛愭担铏剐㈤梺鎼炲劤閸嬫挸霉濞戙垹绠叉い顐枤缁€?
        // 闂佸搫鍞查崒婊呅㈤梺鎼炲劜閸庢娊鎯囨ィ鍐ㄧ睄闁告挷鐒﹂弳?GC 闂佹悶鍎抽崑鐐哄极瑜版帒违?
        mPendingModBitmaps.clear();
        mTempModifications.clear();
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        TaskExecutor.executeIo(() -> {
            boolean allOk = true;
            List<String> failedRules = new ArrayList<>();
            for (RuleRecord rule : rulesToSave) {
                Bitmap snapshot = snapshots.get(rule);
                try {
                    if (!GodModeManager.getDefault().writeRule(pkg, rule, snapshot)) {
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
                    "婵犮垺鍎肩划鍓ф喆? " + String.join(", ", failedRules);
            mainHandler.post(() -> {
                if (nodeSelectorPanel != null) nodeSelectorPanel.setVisibility(View.VISIBLE);
                if (modifyPanel != null) modifyPanel.setVisibility(View.VISIBLE);
                if (maskView != null) maskView.setVisibility(View.VISIBLE);
                Toast.makeText(activity,
                        finalAllOk ? "婵烇絽娴傞崰妤呭极閻撳寒鍟呴棅顐幘缁犱粙鎮? : "闂備緡鍠撻崝宀勫垂鎼淬垻鈹嶆い鏃囧Г閺嗩厼菐閸ャ劎绠撻柣掳鍔嶅鍕綇椤愩儛鏄瀗" + finalFailed,
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    // ---- Xposed Hook 闂佹椿娼块崝瀣姳椤掑嫬鐐婇柛鎾楀喚鏆梻渚囧亜椤︽壆鈧哎鍔庣槐鎺楀箻鐎甸晲鍑?----

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

    // ---- 闁荤姳绀佸鈥澄涢崼鏇為棷?----

    public View getPanelView() { return mPanelView; }
    public View getTargetView() { return mTargetView; }
    public ImageView getPendingImageView() { return mPendingImageView; }
    public void setPendingImageView(ImageView v) { mPendingImageView = v; }
    public Bitmap getOriginalImageBitmap() { return mOriginalImageBitmap; }
    public void setOriginalImageBitmap(Bitmap b) { mOriginalImageBitmap = b; }
    public Map<String, Bitmap> getPendingModBitmaps() { return mPendingModBitmaps; }
    public boolean isShowing() { return mPanelView != null; }
}
