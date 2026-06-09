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

import com.kaisar.xposed.godmode.engine.rule.ActionSpec;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.ThreadPools;

import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 修改规则应用器 — 将修改规则应用到具体 View。
 */
public final class ModifyApplier implements RuleApplier {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private final WeakHashMap<View, Integer> mAppliedViews = new WeakHashMap<>();
    private final Map<String, SoftReference<Bitmap>> mBitmapCache =
            Collections.synchronizedMap(new HashMap<>());

    public interface ImageLoader {
        ParcelFileDescriptor openImageFileDescriptor(String path) throws Exception;
    }

    private final ImageLoader mImageLoader;

    public ModifyApplier(ImageLoader imageLoader) {
        this.mImageLoader = imageLoader;
    }

    // ---- 应用（ActionSpec API） ----

    @Override
    public boolean apply(View view, ActionSpec spec) {
        if (view == null || spec == null || !view.isAttachedToWindow()) return false;
        if (isAlreadyApplied(view, spec)) return false;

        ViewGroup.LayoutParams lp = view.getLayoutParams();
        applyDimensions(spec, lp);
        applyAlpha(view, spec);
        applyOffset(spec, lp);
        if (lp != null) view.setLayoutParams(lp);
        applyText(view, spec);
        applyImage(view, spec);

        mAppliedViews.put(view, spec.hashCode());
        return true;
    }

    private boolean isAlreadyApplied(View view, ActionSpec spec) {
        Integer appliedHash = mAppliedViews.get(view);
        return appliedHash != null && appliedHash == spec.hashCode();
    }

    private static void applyDimensions(ActionSpec spec, ViewGroup.LayoutParams lp) {
        if (lp == null) return;
        if (spec.modWidth > 0) lp.width = spec.modWidth;
        if (spec.modHeight > 0) lp.height = spec.modHeight;
    }

    private static void applyAlpha(View view, ActionSpec spec) {
        if (spec.modAlpha >= 0f) view.setAlpha(spec.modAlpha);
    }

    private static void applyOffset(ActionSpec spec, ViewGroup.LayoutParams lp) {
        if ((spec.modXOffset == 0 && spec.modYOffset == 0)
                || !(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        mlp.leftMargin = spec.origLeftMargin + spec.modXOffset;
        mlp.topMargin = spec.origTopMargin + spec.modYOffset;
    }

    private static void applyText(View view, ActionSpec spec) {
        if (spec.modText != null && !spec.modText.isEmpty() && view instanceof TextView) {
            ((TextView) view).setText(spec.modText);
        }
    }

    private void applyImage(View view, ActionSpec spec) {
        if (spec.modImagePath != null && !spec.modImagePath.isEmpty()
                && view instanceof ImageView) {
            loadAndSetImage((ImageView) view, spec.modImagePath);
        }
    }

    // ---- 撤销（ActionSpec API） ----

    @Override
    public boolean revoke(View view, ActionSpec spec) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            if (spec.origWidth > 0) lp.width = spec.origWidth;
            if (spec.origHeight > 0) lp.height = spec.origHeight;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mlp.leftMargin = spec.origLeftMargin;
                mlp.topMargin = spec.origTopMargin;
            }
        }
        view.setAlpha(spec.origAlpha > 0f ? spec.origAlpha : 1f);
        if (view instanceof TextView && spec.origText != null) {
            ((TextView) view).setText(spec.origText);
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

    // ---- 图片加载 ----

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
