package com.kaisar.xposed.godmode.engine.applier;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
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
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/** Applies modify rules while retaining the host-owned state needed for revoke. */
public final class ModifyApplier implements RuleApplier {

    private static final String TAG = "ModifyApplier";

    private static final Map<String, SoftReference<Bitmap>> BITMAP_CACHE =
            Collections.synchronizedMap(new HashMap<>());

    private final WeakHashMap<View, AppliedState> mAppliedViews = new WeakHashMap<>();
    private final ImageLoader mImageLoader;
    private final Executor mImageExecutor;
    private final MainThreadDispatcher mMainThreadDispatcher;
    private final String mActivityClassName;

    public interface ImageLoader {
        ParcelFileDescriptor openImageFileDescriptor(String path) throws Exception;
    }

    interface MainThreadDispatcher {
        boolean post(View view, Runnable action);
    }

    public ModifyApplier(ImageLoader imageLoader) {
        this(imageLoader, null);
    }

    public ModifyApplier(ImageLoader imageLoader, String activityClassName) {
        this(imageLoader, activityClassName, ThreadPools.IMAGE_LOADER,
                (view, action) -> view.post(action));
    }

    ModifyApplier(ImageLoader imageLoader, String activityClassName,
            Executor imageExecutor, MainThreadDispatcher mainThreadDispatcher) {
        mImageLoader = imageLoader;
        mActivityClassName = activityClassName;
        mImageExecutor = imageExecutor;
        mMainThreadDispatcher = mainThreadDispatcher;
    }

    @Override
    public boolean apply(View view, ActionSpec spec) {
        if (view == null || spec == null || !view.isAttachedToWindow()) return false;
        ActionSnapshot action = ActionSnapshot.create(spec);
        AppliedState previous = mAppliedViews.get(view);
        if (previous != null && previous.action.equals(action)
                && previous.isEffectPresent(view)) {
            return false;
        }

        AppliedState state;
        if (previous != null && previous.action.equals(action)) {
            previous.imageLoad.cancel();
            state = previous;
        } else {
            if (previous != null) {
                previous.imageLoad.cancel();
                previous.restoreOwnedProperties(view);
            }
            state = AppliedState.capture(view, action);
        }

        applySynchronousProperties(view, state);
        mAppliedViews.put(view, state);
        applyImage(view, state);
        return true;
    }

    @Override
    public boolean revoke(View view, ActionSpec spec) {
        if (view == null || spec == null) return false;
        AppliedState state = mAppliedViews.get(view);
        if (state == null || !state.action.equals(ActionSnapshot.create(spec))) return false;
        mAppliedViews.remove(view);
        state.imageLoad.cancel();
        state.restoreOwnedProperties(view);
        return true;
    }

    public boolean revokeForView(View view) {
        if (view == null) return false;
        AppliedState state = mAppliedViews.remove(view);
        if (state == null) return false;
        state.imageLoad.cancel();
        state.restoreOwnedProperties(view);
        return true;
    }

    @Override
    public void clearCache() {
        for (AppliedState state : mAppliedViews.values()) {
            if (state != null) state.imageLoad.cancel();
        }
        mAppliedViews.clear();
    }

    String getActivityClassName() {
        return mActivityClassName;
    }

    private static void applySynchronousProperties(View view, AppliedState state) {
        ActionSnapshot action = state.action;
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            if (action.modWidth >= 0) {
                layoutParams.width = action.modWidth;
                state.appliedWidth = action.modWidth;
            }
            if (action.modHeight >= 0) {
                layoutParams.height = action.modHeight;
                state.appliedHeight = action.modHeight;
            }
            if (action.positionModified
                    && layoutParams instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins =
                        (ViewGroup.MarginLayoutParams) layoutParams;
                margins.leftMargin = action.appliedLeftMargin;
                margins.topMargin = action.appliedTopMargin;
                state.appliedLeftMargin = margins.leftMargin;
                state.appliedTopMargin = margins.topMargin;
            }
            view.setLayoutParams(layoutParams);
        }
        if (action.modAlpha >= 0f) {
            view.setAlpha(action.modAlpha);
            state.appliedAlpha = action.modAlpha;
        }
        if (action.modText != null && view instanceof TextView) {
            ((TextView) view).setText(action.modText);
            state.appliedText = ((TextView) view).getText();
        }
    }

    private void applyImage(View view, AppliedState state) {
        String imagePath = state.action.modImagePath;
        if (imagePath == null || !(view instanceof ImageView)) return;
        ImageView target = (ImageView) view;
        SoftReference<Bitmap> cached = BITMAP_CACHE.get(imagePath);
        Bitmap cachedBitmap = cached != null ? cached.get() : null;
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            target.setImageBitmap(cachedBitmap);
            state.appliedDrawable = target.getDrawable();
            return;
        }

        state.imageLoad.submit(mImageExecutor, () -> loadModImage(imagePath),
                action -> mMainThreadDispatcher.post(target, action),
                bitmap -> {
                    if (bitmap == null) return;
                    BITMAP_CACHE.put(imagePath, new SoftReference<>(bitmap));
                    AppliedState current = mAppliedViews.get(target);
                    if (current == state && target.isAttachedToWindow()) {
                        target.setImageBitmap(bitmap);
                        state.appliedDrawable = target.getDrawable();
                    }
                }, failure -> Logger.w(TAG,
                        "async image application failed: " + imagePath, failure));
    }

    private Bitmap loadModImage(String imagePath) {
        try (ParcelFileDescriptor descriptor =
                     mImageLoader.openImageFileDescriptor(imagePath)) {
            if (descriptor == null) return null;
            return BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor());
        } catch (Exception e) {
            Logger.w(TAG, "loadModImage failed: " + imagePath, e);
            return null;
        }
    }

    private static final class AppliedState {
        final ActionSnapshot action;
        final int baselineWidth;
        final int baselineHeight;
        final int baselineLeftMargin;
        final int baselineTopMargin;
        final float baselineAlpha;
        final CharSequence baselineText;
        final Drawable baselineDrawable;
        final ImageLoadState imageLoad = new ImageLoadState();

        Integer appliedWidth;
        Integer appliedHeight;
        Integer appliedLeftMargin;
        Integer appliedTopMargin;
        Float appliedAlpha;
        CharSequence appliedText;
        Drawable appliedDrawable;

        AppliedState(ActionSnapshot action, int baselineWidth, int baselineHeight,
                int baselineLeftMargin, int baselineTopMargin, float baselineAlpha,
                CharSequence baselineText, Drawable baselineDrawable) {
            this.action = action;
            this.baselineWidth = baselineWidth;
            this.baselineHeight = baselineHeight;
            this.baselineLeftMargin = baselineLeftMargin;
            this.baselineTopMargin = baselineTopMargin;
            this.baselineAlpha = baselineAlpha;
            this.baselineText = baselineText;
            this.baselineDrawable = baselineDrawable;
        }

        static AppliedState capture(View view, ActionSnapshot action) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            int width = params != null ? params.width : 0;
            int height = params != null ? params.height : 0;
            int left = 0;
            int top = 0;
            if (params instanceof ViewGroup.MarginLayoutParams) {
                left = ((ViewGroup.MarginLayoutParams) params).leftMargin;
                top = ((ViewGroup.MarginLayoutParams) params).topMargin;
            }
            CharSequence text = view instanceof TextView
                    ? ((TextView) view).getText() : null;
            Drawable drawable = view instanceof ImageView
                    ? ((ImageView) view).getDrawable() : null;
            return new AppliedState(action, width, height, left, top,
                    view.getAlpha(), text, drawable);
        }

        boolean isEffectPresent(View view) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (appliedWidth != null && (params == null || params.width != appliedWidth)) return false;
            if (appliedHeight != null && (params == null || params.height != appliedHeight)) return false;
            if (appliedLeftMargin != null) {
                if (!(params instanceof ViewGroup.MarginLayoutParams)) return false;
                ViewGroup.MarginLayoutParams margins =
                        (ViewGroup.MarginLayoutParams) params;
                if (margins.leftMargin != appliedLeftMargin
                        || margins.topMargin != appliedTopMargin) return false;
            }
            if (appliedAlpha != null
                    && Float.compare(view.getAlpha(), appliedAlpha) != 0) return false;
            if (appliedText != null && (!(view instanceof TextView)
                    || !textEquals(((TextView) view).getText(), appliedText))) return false;
            if (action.modImagePath != null && view instanceof ImageView) {
                return imageLoad.isPending() || (appliedDrawable != null
                        && ((ImageView) view).getDrawable() == appliedDrawable);
            }
            return true;
        }

        void restoreOwnedProperties(View view) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            boolean layoutChanged = false;
            if (params != null) {
                if (appliedWidth != null && params.width == appliedWidth) {
                    params.width = baselineWidth;
                    layoutChanged = true;
                }
                if (appliedHeight != null && params.height == appliedHeight) {
                    params.height = baselineHeight;
                    layoutChanged = true;
                }
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins =
                            (ViewGroup.MarginLayoutParams) params;
                    if (appliedLeftMargin != null
                            && margins.leftMargin == appliedLeftMargin) {
                        margins.leftMargin = baselineLeftMargin;
                        layoutChanged = true;
                    }
                    if (appliedTopMargin != null
                            && margins.topMargin == appliedTopMargin) {
                        margins.topMargin = baselineTopMargin;
                        layoutChanged = true;
                    }
                }
                if (layoutChanged) view.setLayoutParams(params);
            }
            if (appliedAlpha != null
                    && Float.compare(view.getAlpha(), appliedAlpha) == 0) {
                view.setAlpha(baselineAlpha);
            }
            if (appliedText != null && view instanceof TextView
                    && textEquals(((TextView) view).getText(), appliedText)) {
                ((TextView) view).setText(baselineText);
            }
            if (appliedDrawable != null && view instanceof ImageView
                    && ((ImageView) view).getDrawable() == appliedDrawable) {
                ((ImageView) view).setImageDrawable(baselineDrawable);
            }
        }
    }

    private static boolean textEquals(CharSequence first, CharSequence second) {
        return Objects.equals(first != null ? first.toString() : null,
                second != null ? second.toString() : null);
    }

    private static final class ActionSnapshot {
        final int modWidth;
        final int modHeight;
        final float modAlpha;
        final boolean positionModified;
        final int appliedLeftMargin;
        final int appliedTopMargin;
        final String modText;
        final String modImagePath;

        ActionSnapshot(int modWidth, int modHeight, float modAlpha,
                boolean positionModified, int appliedLeftMargin,
                int appliedTopMargin, String modText, String modImagePath) {
            this.modWidth = modWidth;
            this.modHeight = modHeight;
            this.modAlpha = modAlpha;
            this.positionModified = positionModified;
            this.appliedLeftMargin = appliedLeftMargin;
            this.appliedTopMargin = appliedTopMargin;
            this.modText = modText;
            this.modImagePath = modImagePath;
        }

        static ActionSnapshot create(ActionSpec spec) {
            boolean positionModified = spec.isPositionModified();
            return new ActionSnapshot(spec.modWidth, spec.modHeight, spec.modAlpha,
                    positionModified,
                    positionModified ? spec.origLeftMargin + spec.modXOffset : 0,
                    positionModified ? spec.origTopMargin + spec.modYOffset : 0,
                    spec.modText,
                    spec.modImagePath);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof ActionSnapshot)) return false;
            ActionSnapshot other = (ActionSnapshot) value;
            return modWidth == other.modWidth && modHeight == other.modHeight
                    && Float.compare(modAlpha, other.modAlpha) == 0
                    && positionModified == other.positionModified
                    && appliedLeftMargin == other.appliedLeftMargin
                    && appliedTopMargin == other.appliedTopMargin
                    && Objects.equals(modText, other.modText)
                    && Objects.equals(modImagePath, other.modImagePath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(modWidth, modHeight, modAlpha, positionModified,
                    appliedLeftMargin, appliedTopMargin, modText, modImagePath);
        }
    }
}
