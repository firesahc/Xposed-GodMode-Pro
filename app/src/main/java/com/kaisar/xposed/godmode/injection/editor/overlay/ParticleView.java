package com.kaisar.xposed.godmode.injection.editor.overlay;

import static com.kaisar.xposed.godmode.injection.ViewHelper.TAG_GM_CMP;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

public final class ParticleView extends View implements OverlayWidget {

    private ValueAnimator mParticleAnimator;
    private int duration = 4000;
    private OnAnimationListener mOnAnimationListener;
    private Paint mPaint;
    private Particle[][] mParticles;

    public void setOnAnimationListener(OnAnimationListener mOnAnimationListener) {
        this.mOnAnimationListener = mOnAnimationListener;
    }

    public ParticleView(Context context) {
        super(context);
        init();
    }

    public ParticleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ParticleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setTag(TAG_GM_CMP);
        mPaint = new Paint();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mParticleAnimator != null) drawParticle(canvas);
    }

    public void drawParticle(Canvas canvas) {
        for (Particle[] particle : mParticles) {
            for (Particle p : particle) {
                p.update((Float) mParticleAnimator.getAnimatedValue());
                mPaint.setColor(p.color);
                mPaint.setAlpha((int) (Color.alpha(p.color) * p.alpha));
                canvas.drawCircle(p.cx, p.cy, p.radius, mPaint);
            }
        }
    }

    public void boom(final View view) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() == 0
                || (mParticleAnimator != null && mParticleAnimator.isRunning())) return;
        view.post(() -> {
            int[] location = new int[2];
            view.getLocationInWindow(location);
            Rect rect = new Rect(location[0], location[1],
                    location[0] + view.getMeasuredWidth(),
                    location[1] + view.getMeasuredHeight());
            Bitmap cacheBitmap = getCacheBitmapFromView(view);
            mParticles = Particle.generateParticles(cacheBitmap, rect);
            cacheBitmap.recycle();
            mParticleAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            mParticleAnimator.setDuration(duration);
            mParticleAnimator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationStart(Animator a) {
                    if (mOnAnimationListener != null) mOnAnimationListener.onAnimationStart(view, a);
                }
                @Override public void onAnimationEnd(Animator a) {
                    if (mOnAnimationListener != null) mOnAnimationListener.onAnimationEnd(view, a);
                }
            });
            mParticleAnimator.addUpdateListener(animation -> invalidate());
            mParticleAnimator.start();
        });
    }

    private Bitmap getCacheBitmapFromView(View view) {
        int w = Math.max(view.getWidth(), 1);
        int h = Math.max(view.getHeight(), 1);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(-view.getScrollX(), -view.getScrollY());
        view.draw(canvas);
        return bitmap;
    }

    public void setDuration(int duration) { this.duration = duration; }

    @Override public View getView() { return this; }

    @Override
    public void attachToContainer(ViewGroup container) {
        container.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void detachFromContainer() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
    }

    public interface OnAnimationListener {
        void onAnimationStart(View v, Animator animation);
        void onAnimationEnd(View v, Animator animation);
    }
}
