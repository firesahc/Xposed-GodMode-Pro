package com.kaisar.xposed.godmode.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 位图操作工具 — 视图截图、规则遮罩绘制、调试边框绘制。
 * <p>
 * 从 {@code ViewHelper} 拆分，职责单一。
 */
public final class BitmapUtils {

    private BitmapUtils() {}

    /**
     * 截取视图的当前显示内容为 Bitmap。
     *
     * @param view 目标视图
     * @return 视图截图，若 view 无效则返回 null
     */
    public static Bitmap snapshotView(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        Bitmap b = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(-view.getScrollX(), -view.getScrollY());
        view.draw(c);
        return b;
    }

    /**
     * 在截图上绘制红色半透明遮罩标记规则命中区域。
     *
     * @param bitmap 截图
     * @param rule   视图规则（提供 x/y/width/height）
     */
    public static void drawRuleMask(Bitmap bitmap, RuleRecord rule) {
        if (bitmap == null || rule == null) return;
        Paint markPaint = new Paint();
        markPaint.setColor(Color.RED);
        markPaint.setAlpha(100);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawRect(rule.x, rule.y, rule.x + rule.width, rule.y + rule.height, markPaint);
    }

    /**
     * 截取视图并叠加调试边框（用于属性编辑面板中的缩略图）。
     *
     * @param view 目标视图
     * @return 带调试边框的截图
     */
    public static Bitmap cloneViewAsBitmap(View view) {
        Bitmap bitmap = snapshotView(view);
        if (bitmap == null) return null;

        Paint paint = new Paint();
        paint.setAntiAlias(false);

        // Draw optical bounds
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);

        Canvas canvas = new Canvas(bitmap);
        drawRect(canvas, paint, 0, 0, canvas.getWidth() - 1, canvas.getHeight() - 1);

        // Draw clip bounds
        paint.setColor(Color.rgb(63, 127, 255));
        paint.setStyle(Paint.Style.FILL);

        Context context = view.getContext();
        int lineLength = dipsToPixels(context, 8);
        int lineWidth = dipsToPixels(context, 1);
        drawRectCorners(canvas, 0, 0, canvas.getWidth(), canvas.getHeight(),
                paint, lineLength, lineWidth);
        return bitmap;
    }

    // =========================================================================
    // 内部绘制辅助
    // =========================================================================

    private static int dipsToPixels(Context context, int dips) {
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dips * scale + 0.5f);
    }

    private static void drawRect(Canvas canvas, Paint paint, int x1, int y1, int x2, int y2) {
        float[] debugLines = new float[16];
        debugLines[0] = x1;  debugLines[1] = y1;  debugLines[2] = x2;  debugLines[3] = y1;
        debugLines[4] = x2;  debugLines[5] = y1;  debugLines[6] = x2;  debugLines[7] = y2;
        debugLines[8] = x2;  debugLines[9] = y2;  debugLines[10] = x1; debugLines[11] = y2;
        debugLines[12] = x1; debugLines[13] = y2; debugLines[14] = x1; debugLines[15] = y1;
        canvas.drawLines(debugLines, paint);
    }

    private static void drawRectCorners(Canvas canvas, int x1, int y1, int x2, int y2,
            Paint paint, int lineLength, int lineWidth) {
        drawCorner(canvas, paint, x1, y1, lineLength, lineLength, lineWidth);
        drawCorner(canvas, paint, x1, y2, lineLength, -lineLength, lineWidth);
        drawCorner(canvas, paint, x2, y1, -lineLength, lineLength, lineWidth);
        drawCorner(canvas, paint, x2, y2, -lineLength, -lineLength, lineWidth);
    }

    private static void drawCorner(Canvas c, Paint paint, int x1, int y1, int dx, int dy, int lw) {
        fillRect(c, paint, x1, y1, x1 + dx, y1 + lw * sign(dy));
        fillRect(c, paint, x1, y1, x1 + lw * sign(dx), y1 + dy);
    }

    private static void fillRect(Canvas canvas, Paint paint, int x1, int y1, int x2, int y2) {
        if (x1 != x2 && y1 != y2) {
            if (x1 > x2) { int tmp = x1; x1 = x2; x2 = tmp; }
            if (y1 > y2) { int tmp = y1; y1 = y2; y2 = tmp; }
            canvas.drawRect(x1, y1, x2, y2, paint);
        }
    }

    private static int sign(int x) {
        return (x >= 0) ? 1 : -1;
    }
}
