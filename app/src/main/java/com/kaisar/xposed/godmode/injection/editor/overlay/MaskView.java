package com.kaisar.xposed.godmode.injection.editor.overlay;

import static com.kaisar.xposed.godmode.engine.util.GmConstants.TAG_GM_CMP;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.kaisar.xposed.godmode.injection.util.BitmapUtils;

public final class MaskView extends View implements OverlayWidget {

    private Drawable mMaskDrawable;
    private int mMarkColor = Color.TRANSPARENT;
    private boolean isMarked;

    public MaskView(Context context) { super(context); setTag(TAG_GM_CMP); }
    public MaskView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); setTag(TAG_GM_CMP); }
    public MaskView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); setTag(TAG_GM_CMP); }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mMaskDrawable != null) mMaskDrawable.draw(canvas);
    }

    public void updateOverlayBounds(int x, int y, int w, int h) {
        updateOverlayBounds(new Rect(x, y, x + w, y + h));
    }

    public void updateOverlayBounds(Rect bounds) {
        if (mMaskDrawable != null) { mMaskDrawable.setBounds(bounds); invalidate(); }
    }

    public Rect getRealBounds() {
        return mMaskDrawable != null ? mMaskDrawable.getBounds() : new Rect();
    }

    public void setMarkColor(int color) { mMarkColor = color; }

    public void setMarked(boolean enable) {
        if (isMarked != enable && mMaskDrawable != null) {
            isMarked = enable;
            if (enable) mMaskDrawable.setColorFilter(mMarkColor, PorterDuff.Mode.SRC_ATOP);
            else mMaskDrawable.clearColorFilter();
        }
    }

    public boolean isMarked() { return isMarked; }

    @Override public View getView() { return this; }

    public void setMaskOverlay(View view) {
        Bitmap bitmap = BitmapUtils.cloneViewAsBitmap(view);
        mMaskDrawable = new BitmapDrawable(getResources(), bitmap);
    }

    public void setMaskOverlay(int color) {
        mMaskDrawable = new ColorDrawable(color);
    }

    public static MaskView makeMaskView(Context context) {
        MaskView maskView = new MaskView(context);
        maskView.setLayoutParams(new ViewGroup.MarginLayoutParams(
                ViewGroup.MarginLayoutParams.MATCH_PARENT, ViewGroup.MarginLayoutParams.MATCH_PARENT));
        return maskView;
    }
}
