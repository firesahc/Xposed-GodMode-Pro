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

import com.kaisar.xposed.godmode.engine.util.ThreadPools;
import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;

import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 淇敼瑙勫垯搴旂敤鍣?鈥?淇敼瑙嗗浘灏哄/閫忔槑搴?浣嶇疆/鏂囨湰/鍥剧墖锛屾敮鎸佹挙閿€銆?
 * 浠?RuleModificationHelper 鎻愬彇鐨勬牳蹇冮€昏緫銆?
 * <p>
 * 浣跨敤 SoftReference 缂撳瓨宸插姞杞界殑鍥剧墖 Bitmap锛岄伩鍏嶉噸澶?IPC 璇锋眰銆?
 */
public final class ModifyApplier implements RuleApplier {

    // 澶嶇敤 ThreadPools.IMAGE_LOADER 鑰岄潪鍒涘缓鐙珛绾跨▼姹?
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private final WeakHashMap<View, Integer> mAppliedViews = new WeakHashMap<>();
    private final Map<String, SoftReference<Bitmap>> mBitmapCache =
            Collections.synchronizedMap(new HashMap<>());

    /** 鍥剧墖鍔犺浇鍣ㄦ帴鍙?鈥?鐢辫皟鐢ㄦ柟娉ㄥ叆浠ュ疄鐜拌法杩涚▼鍥剧墖鍔犺浇 */
    public interface ImageLoader {
        ParcelFileDescriptor openImageFileDescriptor(String path) throws Exception;
    }

    private final ImageLoader mImageLoader;

    public ModifyApplier(ImageLoader imageLoader) {
        this.mImageLoader = imageLoader;
    }

    // ---- 搴旂敤 ----

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
        // 浣跨敤寮曠敤鐩哥瓑鎬ф鏌?hashCode锛坕nt锛夛紝閬垮厤鑷姩瑁呯
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

    // ---- 鎾ら攢 ----

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

    // ---- 鍥剧墖鍔犺浇 ----

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
            android.util.Log.w("ModifyApplier", "loadModImage failed: " + imagePath, e);
        }
        return null;
    }
}
