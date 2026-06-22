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

    private static final String TAG = "ModifyApplier";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private final WeakHashMap<View, Integer> mAppliedViews = new WeakHashMap<>();

    /** 位图缓存 — static 进程级共享，避免多 Activity 实例重复加载同一图片 */
    private static final Map<String, SoftReference<Bitmap>> sBitmapCache =
            Collections.synchronizedMap(new HashMap<>());

    public interface ImageLoader {
        ParcelFileDescriptor openImageFileDescriptor(String path) throws Exception;
    }

    private final ImageLoader mImageLoader;

    /** 当前 Activity 类名，用于 Activity 级缓存隔离（可能为 null） */
    private final String mActivityClassName;

    /** 进程级单例构造（位图缓存 static 共享，但 appliedViews 未经 Activity 隔离） */
    public ModifyApplier(ImageLoader imageLoader) {
        this.mImageLoader = imageLoader;
        this.mActivityClassName = null;
    }

    /** Activity 级实例构造（appliedViews 按 Activity 隔离，位图缓存 static 共享） */
    public ModifyApplier(ImageLoader imageLoader, String activityClassName) {
        this.mImageLoader = imageLoader;
        this.mActivityClassName = activityClassName;
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
        if (view == null || spec == null) return false;
        if (!isAlreadyApplied(view, spec)) return false;

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

    /**
     * 对单个 View 进行撤销恢复操作（不依赖规则集，纯缓存操作）。
     * <p>
     * 用于 onViewRecycled 回调——当 RecyclerView 回收 View 时，
     * 通过 {@link WeakHashMap#containsKey} 检查该 View 是否曾被修改过，
     * 若有则撤销当前修改状态并移除缓存项。
     *
     * @param view 被回收的 View
     * @return true 表示该 View 曾被应用过规则并已成功撤销
     */
    public boolean revokeForView(View view) {
        if (view == null) return false;
        if (!mAppliedViews.containsKey(view)) return false;
        // View 曾被修改过，但撤销原始值需要从缓存中恢复。
        // 由于 cached hash 不足以恢复原始状态，回退方案：移除缓存项，让下次 bind 时重新 apply。
        mAppliedViews.remove(view);
        // 不执行 LayoutParams 恢复（无法确定原始值），依赖 bindViewHolder 重新应用规则。
        return true;
    }

    @Override
    public void clearCache() {
        mAppliedViews.clear();
        // sBitmapCache 是 static 进程级共享，不在此处清除
    }

    // ---- 图片加载 ----

    private void loadAndSetImage(ImageView targetView, String imagePath) {
        SoftReference<Bitmap> cached = sBitmapCache.get(imagePath);
        Bitmap cachedBitmap = cached != null ? cached.get() : null;
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            targetView.setImageBitmap(cachedBitmap);
        } else {
            ThreadPools.IMAGE_LOADER.execute(() -> {
                Bitmap bitmap = loadModImage(imagePath);
                if (bitmap != null) {
                    sBitmapCache.put(imagePath, new SoftReference<>(bitmap));
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
            Logger.w(TAG, "loadModImage failed: " + imagePath, e);
        }
        return null;
    }
}
