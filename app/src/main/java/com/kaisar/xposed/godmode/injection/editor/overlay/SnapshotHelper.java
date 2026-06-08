package com.kaisar.xposed.godmode.injection.editor.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 截图与规则遮罩绘制工具。
 * 从 ViewHelper 提取的纯图形操作，不依赖 Xposed 或 Hook 上下文。
 */
public final class SnapshotHelper {

    private SnapshotHelper() {}

    /**
     * 截取视图的完整位图快照。
     * @param view 目标视图
     * @return ARGB_8888 格式的 Bitmap，视图为空或尺寸≤0时返回 null
     */
    public static Bitmap snapshotView(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        Bitmap b = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(-view.getScrollX(), -view.getScrollY());
        view.draw(c);
        return b;
    }

    /**
     * 在截图上绘制规则边界遮罩（红色半透明矩形）。
     * @param bitmap 目标位图（就地修改）
     * @param rule   包含位置和尺寸信息的规则
     */
    public static void drawRuleMask(Bitmap bitmap, RuleRecord rule) {
        if (bitmap == null || rule == null) return;
        Paint markPaint = new Paint();
        markPaint.setColor(Color.RED);
        markPaint.setAlpha(100);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawRect(rule.x, rule.y, rule.x + rule.width,
                rule.y + rule.height, markPaint);
    }

    /**
     * 克隆视图为带调试边框的位图。
     * @param view 目标视图
     * @return 带红色描边和蓝色角标的位图
     */
    public static Bitmap cloneViewAsBitmap(View view) {
        Bitmap bitmap = snapshotView(view);
        if (bitmap == null) return null;

        Paint paint = new Paint();
        paint.setAntiAlias(false);

        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        Canvas canvas = new Canvas(bitmap);
        drawRect(canvas, paint, 0, 0, canvas.getWidth() - 1, canvas.getHeight() - 1);

        paint.setColor(Color.rgb(63, 127, 255));
        paint.setStyle(Paint.Style.FILL);

        float scale = view.getContext().getResources().getDisplayMetrics().density;
        int lineLength = (int) (8 * scale + 0.5f);
        int lineWidth = (int) (1 * scale + 0.5f);
        drawRectCorners(canvas, 0, 0, canvas.getWidth(), canvas.getHeight(),
                paint, lineLength, lineWidth);
        return bitmap;
    }

    // ---- 内部图形工具 ----

    private static void drawRect(Canvas canvas, Paint paint,
            int x1, int y1, int x2, int y2) {
        float[] lines = {x1, y1, x2, y1, x2, y1, x2, y2,
                x2, y2, x1, y2, x1, y2, x1, y1};
        canvas.drawLines(lines, paint);
    }

    private static void drawRectCorners(Canvas canvas, int x1, int y1,
            int x2, int y2, Paint paint, int lineLength, int lineWidth) {
        drawCorner(canvas, paint, x1, y1, lineLength, lineLength, lineWidth);
        drawCorner(canvas, paint, x1, y2, lineLength, -lineLength, lineWidth);
        drawCorner(canvas, paint, x2, y1, -lineLength, lineLength, lineWidth);
        drawCorner(canvas, paint, x2, y2, -lineLength, -lineLength, lineWidth);
    }

    private static void drawCorner(Canvas c, Paint paint,
            int x, int y, int dx, int dy, int lw) {
        fillRect(c, paint, x, y, x + dx, y + lw * sign(dy));
        fillRect(c, paint, x, y, x + lw * sign(dx), y + dy);
    }

    private static void fillRect(Canvas canvas, Paint paint,
            int x1, int y1, int x2, int y2) {
        if (x1 != x2 && y1 != y2) {
            if (x1 > x2) { int t = x1; x1 = x2; x2 = t; }
            if (y1 > y2) { int t = y1; y1 = y2; y2 = t; }
            canvas.drawRect(x1, y1, x2, y2, paint);
        }
    }

    private static int sign(int x) {
        return (x >= 0) ? 1 : -1;
    }
}
