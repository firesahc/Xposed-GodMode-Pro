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

import com.kaisar.xposed.godmode.engine.pool.ThreadPools;
import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;

import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 修改规则应用器 — 修改视图尺寸/透明度/位置/文本/图片，支持撤销。
 * 从 RuleModificationHelper 提取的核心逻辑。
 * <p>
 * 使用 SoftReference 缓存已加载的图片 Bitmap，避免重复 IPC 请求。
 */
public final class ModifyApplier implements RuleApplier {

    // 复用 ThreadPools.IMAGE_LOADER 而非创建独立线程池
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private final WeakHashMap<View, Integer> mAppliedViews = new WeakHashMap<>();
    private final Map<String, SoftReference<Bitmap>> mBitmapCache =
            Collections.synchronizedMap(new HashMap<>());

    /** 图片加载器接口 — 由调用方注入以实现跨进程图片加载 */
    public interface ImageLoader {
        ParcelFileDescriptor openImageFileDescriptor(String path) throws Exception;
    }

    private final ImageLoader mImageLoader;

    public ModifyApplier(ImageLoader imageLoader) {
        this.mImageLoader = imageLoader;
    }

    // ---- 应用 ----

    @Override
    public boolean apply(View view, RuleMatchSpec rule) {
        if (view == null || rule == null || !view.isAttachedToWindow()) return false;

        Integer appliedHash = mAppliedViews.get(view);
        if (appliedHash != null && appliedHash == rule.hashCode()) return false;

        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (rule.modWidth > 0 && lp != null) {
            lp.width = rule.modWidth;
        }
        if (rule.modHeight > 0 && lp != null) {
            lp.height = rule.modHeight;
        }
        if (rule.modAlpha >= 0f) {
            view.setAlpha(rule.modAlpha);
        }
        if (rule.modXOffset != 0 || rule.modYOffset != 0) {
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mlp.leftMargin = rule.origLeftMargin + rule.modXOffset;
                mlp.topMargin = rule.origTopMargin + rule.modYOffset;
            }
        }
        if (lp != null) view.setLayoutParams(lp);
        if (rule.modText != null && !rule.modText.isEmpty() && view instanceof TextView) {
            ((TextView) view).setText(rule.modText);
        }
        if (rule.modImagePath != null && !rule.modImagePath.isEmpty()
                && view instanceof ImageView) {
            loadAndSetImage((ImageView) view, rule.modImagePath);
        }

        mAppliedViews.put(view, rule.hashCode());
        return true;
    }

    // ---- 撤销 ----

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

    /** 清除与指定规则相关的已应用视图记录 */
    public void clearForRule(RuleMatchSpec rule) {
        int hash = rule.hashCode();
        synchronized (mAppliedViews) {
            mAppliedViews.entrySet().removeIf(e ->
                    e.getValue() != null && e.getValue() == hash);
        }
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
                return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
