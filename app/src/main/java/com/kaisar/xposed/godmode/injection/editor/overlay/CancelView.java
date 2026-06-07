package com.kaisar.xposed.godmode.injection.editor.overlay;

import static com.kaisar.xposed.godmode.engine.util.GmConstants.TAG_GM_CMP;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.injection.util.GmResources;

@SuppressLint("AppCompatCustomView")
public final class CancelView extends View implements OverlayWidget {

    private final Paint rectPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private CharSequence text;
    private final Rect statusBarBounds = new Rect();
    private final Rect textLayoutBounds = new Rect();
    private final Rect textBounds = new Rect();

    public CancelView(Context context) { super(context); init(context); }
    public CancelView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(context); }
    public CancelView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(context); }

    private void init(Context context) {
        setTag(TAG_GM_CMP);
        text = GmResources.getText(R.string.top_revert_tip);
        rectPaint.setStyle(Paint.Style.FILL);
        rectPaint.setColor(Color.argb(230, 139, 195, 75));
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, getResources().getDisplayMetrics()));
        textPaint.setColor(Color.WHITE);
        textPaint.getTextBounds(text.toString(), 0, text.length(), textBounds);
        textBounds.offsetTo(0, 0);
        setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
    }

    @Override public View getView() { return this; }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.drawRect(getStatusBarBounds(), rectPaint);
        Rect layout = getTextLayoutBounds();
        canvas.drawRect(layout, rectPaint);
        float x = layout.centerX() - textBounds.centerX();
        float y = layout.centerY() + textBounds.centerY();
        canvas.drawText(text, 0, text.length(), x, y, textPaint);
        canvas.restore();
    }

    private Rect getStatusBarBounds() {
        if (statusBarBounds.isEmpty())
            statusBarBounds.set(getLeft(), 0, getRight(), getStatusBarHeight());
        return statusBarBounds;
    }

    private Rect getTextLayoutBounds() {
        if (textLayoutBounds.isEmpty()) {
            TypedValue tv = new TypedValue();
            if (getContext().getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                int h = TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
                textLayoutBounds.set(getLeft(), getStatusBarHeight(), getRight(), getStatusBarHeight() + h);
            }
        }
        return textLayoutBounds;
    }

    public Rect getRealBounds() {
        return new Rect(statusBarBounds.left, statusBarBounds.top, statusBarBounds.right, textLayoutBounds.bottom);
    }
}
