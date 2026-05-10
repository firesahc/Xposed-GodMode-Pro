package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ViewRule;

import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RuleModificationHelper {

    private static final ExecutorService IMAGE_LOADER = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<View, Integer> sAppliedViews = new WeakHashMap<>();
    private static final Map<String, SoftReference<Bitmap>> sBitmapCache =
            Collections.synchronizedMap(new HashMap<>());

    private RuleModificationHelper() {}

    public static void clearAppliedCache() {
        sAppliedViews.clear();
    }

    public static void applyModificationRule(Activity activity, ViewRule rule) {
        try {
            if (rule.depth == null || rule.depth.length == 0) return;
            View view = ViewHelper.findViewByDepth(activity, rule.depth);
            if (view == null) return;
            if (!TextUtils.equals(view.getClass().getName(), rule.viewClass)) return;

            Integer appliedHash = sAppliedViews.get(view);
            if (appliedHash != null && appliedHash == rule.hashCode()) {
                return;
            }

            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (rule.isWidthModified() && lp != null && rule.modWidth > 0) {
                lp.width = rule.modWidth;
            }
            if (rule.isHeightModified() && lp != null && rule.modHeight > 0) {
                lp.height = rule.modHeight;
            }
            if (rule.isAlphaModified() && rule.modAlpha >= 0f) {
                view.setAlpha(rule.modAlpha);
            }
            if (rule.isPositionModified() && lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                if (rule.modXOffset != 0) mlp.leftMargin = rule.origLeftMargin + rule.modXOffset;
                if (rule.modYOffset != 0) mlp.topMargin = rule.origTopMargin + rule.modYOffset;
            }
            if (lp != null) view.setLayoutParams(lp);
            if (rule.isTextModified() && view instanceof TextView) {
                ((TextView) view).setText(rule.modText);
            }
            if (rule.isImageModified() && view instanceof ImageView && rule.modImagePath != null) {
                final ImageView targetView = (ImageView) view;
                final String path = rule.modImagePath;
                SoftReference<Bitmap> cached = sBitmapCache.get(path);
                Bitmap cachedBitmap = cached != null ? cached.get() : null;
                if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
                    targetView.setImageBitmap(cachedBitmap);
                } else {
                    IMAGE_LOADER.execute(() -> {
                        Bitmap bitmap = loadModImage(path);
                        if (bitmap != null) {
                            sBitmapCache.put(path, new SoftReference<>(bitmap));
                            MAIN_HANDLER.post(() -> {
                                if (targetView.isAttachedToWindow()) {
                                    targetView.setImageBitmap(bitmap);
                                }
                            });
                        }
                    });
                }
            }

            sAppliedViews.put(view, rule.hashCode());
        } catch (Exception e) {
            Logger.w(TAG, "[RuleModification] failed to apply modification: " + rule, e);
        }
    }

    private static Bitmap loadModImage(String imagePath) {
        try (ParcelFileDescriptor pfd = GodModeManager.getDefault().openImageFileDescriptor(imagePath)) {
            if (pfd != null) {
                return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
            }
        } catch (Exception e) {
            Logger.w(TAG, "[RuleModification] load mod image via IPC failed: " + imagePath, e);
        }
        return null;
    }
}
