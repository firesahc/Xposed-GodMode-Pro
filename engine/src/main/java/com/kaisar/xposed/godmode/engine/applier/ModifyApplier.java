package com.kaisar.xposed.godmode.engine.applier;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.ThreadPools;

import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 娣囶喗鏁肩憴鍕灟鎼存梻鏁ら崳?閳?娣囶喗鏁肩憴鍡楁禈鐏忓搫顕?闁繑妲戞惔?娴ｅ秶鐤?閺傚洦婀?閸ュ墽澧栭敍灞炬暜閹镐焦鎸欓柨鈧妴?
 * 娴?RuleModificationHelper 閹绘劕褰囬惃鍕壋韫囧啴鈧槒绶妴?
 * <p>
 * 娴ｈ法鏁?SoftReference 缂傛挸鐡ㄥ鎻掑鏉炵晫娈戦崶鍓у Bitmap閿涘矂浼╅崗宥夊櫢婢?IPC 鐠囬攱鐪伴妴?
 */
public final class ModifyApplier implements RuleApplier {

    // 婢跺秶鏁?ThreadPools.IMAGE_LOADER 閼板矂娼崚娑樼紦閻欘剛鐝涚痪璺ㄢ柤濮?
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private final WeakHashMap<View, Integer> mAppliedViews = new WeakHashMap<>();
    private final Map<String, SoftReference<Bitmap>> mBitmapCache =
            Collections.synchronizedMap(new HashMap<>());

    /** 閸ュ墽澧栭崝鐘烘祰閸ｃ劍甯撮崣?閳?閻㈣精鐨熼悽銊︽煙濞夈劌鍙嗘禒銉ョ杽閻滄媽娉曟潻娑氣柤閸ュ墽澧栭崝鐘烘祰 */
    public interface ImageLoader {
        ParcelFileDescriptor openImageFileDescriptor(String path) throws Exception;
    }

    private final ImageLoader mImageLoader;

    public ModifyApplier(ImageLoader imageLoader) {
        this.mImageLoader = imageLoader;
    }

    // ---- 鎼存梻鏁?----

    @Override
    public boolean apply(View view, RuleMatchSpec rule) {
        if (view == null || rule == null || !view.isAttachedToWindow()) return false;
        if (isAlreadyApplied(view, rule)) return false;

        ViewGroup.LayoutParams lp = view.getLayoutParams();
        applyDimensions(rule, lp);
        applyAlpha(view, rule);
        applyOffset(rule, lp);
        if (lp != null) view.setLayoutParams(lp);
        applyText(view, rule);
        applyImage(view, rule);

        mAppliedViews.put(view, rule.hashCode());
        return true;
    }

    private boolean isAlreadyApplied(View view, RuleMatchSpec rule) {
        // 娴ｈ法鏁ゅ鏇犳暏閻╁摜鐡戦幀褎顥呴弻?hashCode閿涘潟nt閿涘绱濋柆鍨帳閼奉亜濮╃憗鍛唸
        Integer appliedHash = mAppliedViews.get(view);
        return appliedHash != null && appliedHash == rule.hashCode();
    }

    private static void applyDimensions(RuleMatchSpec rule, ViewGroup.LayoutParams lp) {
        if (lp == null) return;
        if (rule.modWidth > 0) lp.width = rule.modWidth;
        if (rule.modHeight > 0) lp.height = rule.modHeight;
    }

    private static void applyAlpha(View view, RuleMatchSpec rule) {
        if (rule.modAlpha >= 0f) view.setAlpha(rule.modAlpha);
    }

    private static void applyOffset(RuleMatchSpec rule, ViewGroup.LayoutParams lp) {
        if ((rule.modXOffset == 0 && rule.modYOffset == 0)
                || !(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        mlp.leftMargin = rule.origLeftMargin + rule.modXOffset;
        mlp.topMargin = rule.origTopMargin + rule.modYOffset;
    }

    private static void applyText(View view, RuleMatchSpec rule) {
        if (rule.modText != null && !rule.modText.isEmpty() && view instanceof TextView) {
            ((TextView) view).setText(rule.modText);
        }
    }

    private void applyImage(View view, RuleMatchSpec rule) {
        if (rule.modImagePath != null && !rule.modImagePath.isEmpty()
                && view instanceof ImageView) {
            loadAndSetImage((ImageView) view, rule.modImagePath);
        }
    }

    // ---- 閹俱倝鏀?----

    @Override
    public boolean revoke(View view, RuleMatchSpec rule) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            if (rule.origWidth > 0) lp.width = rule.origWidth;
            if (rule.origHeight > 0) lp.height = rule.origHeight;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mlp.leftMargin = rule.origLeftMargin;
                mlp.topMargin = rule.origTopMargin;
            }
        }
        view.setAlpha(rule.origAlpha > 0f ? rule.origAlpha : 1f);
        if (view instanceof TextView && rule.origText != null) {
            ((TextView) view).setText(rule.origText);
        }
        if (lp != null) view.setLayoutParams(lp);
        mAppliedViews.remove(view);
        return true;
    }

    @Override
    public void clearCache() {
        mAppliedViews.clear();
        mBitmapCache.clear();
    }

    // ---- 閸ュ墽澧栭崝鐘烘祰 ----

    private void loadAndSetImage(ImageView targetView, String imagePath) {
        SoftReference<Bitmap> cached = mBitmapCache.get(imagePath);
        Bitmap cachedBitmap = cached != null ? cached.get() : null;
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            targetView.setImageBitmap(cachedBitmap);
        } else {
            ThreadPools.IMAGE_LOADER.execute(() -> {
                Bitmap bitmap = loadModImage(imagePath);
                if (bitmap != null) {
                    mBitmapCache.put(imagePath, new SoftReference<>(bitmap));
                    MAIN_HANDLER.post(() -> {
                        if (targetView.isAttachedToWindow()) {
                            targetView.setImageBitmap(bitmap);
                        }
                    });
                }
            });
        }
    }

    private Bitmap loadModImage(String imagePath) {
        try (ParcelFileDescriptor pfd = mImageLoader.openImageFileDescriptor(imagePath)) {
            if (pfd != null) {
                Bitmap bitmap = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                if (bitmap != null) return bitmap;
            }
        } catch (Exception e) {
            Logger.w("ModifyApplier", "loadModImage failed: " + imagePath, e);
        }
        return null;
    }
}
