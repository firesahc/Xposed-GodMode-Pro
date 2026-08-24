package com.kaisar.xposed.godmode.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.kaisar.xposed.godmode.engine.util.Logger;

/**
 * 位图操作工具 — 视图截图、规则遮罩绘制、调试边框绘制。
 * <p>
 * 从 {@code ViewHelper} 拆分，职责单一。
 */
public final class BitmapUtils {

    private BitmapUtils() {}

    private static final String TAG = "BitmapUtils";

    /**
     * 截取视图的当前显示内容为 Bitmap。
     * <p>
     * 若视图层级中含有已回收的 BitmapDrawable（来自前次修改操作遗留），
     * 捕获 {@link RuntimeException} 并返回空白位图兜底，避免编辑流程中断。
     *
     * @param view 目标视图
     * @return 视图截图，若 view 无效则返回 null；兜底时返回等尺寸透明位图
     */
    public static Bitmap snapshotView(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        Bitmap b = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(-view.getScrollX(), -view.getScrollY());
        try {
            view.draw(c);
        } catch (RuntimeException e) {
            // View 层级中存在已回收的 BitmapDrawable（如 ImageView 从前次修改保有的位图已被回收）。
            // 兜底：擦除为完全透明，防止 Binder IPC 传递已回收位图引发二次崩溃。
            Logger.w(TAG, "snapshotView: view.draw() failed due to recycled bitmap in hierarchy, "
                    + "falling back to blank snapshot"
                    + " view=" + view.getClass().getName(), e);
            b.eraseColor(Color.TRANSPARENT);
        }
        return b;
    }

    /**
     * 将任意 Drawable（含 VectorDrawable）渲染为等尺寸 ARGB_8888 位图。
     * <p>
     * {@link android.graphics.BitmapFactory} 无法解码矢量资源（返回 null），
     * 通知 largeIcon 等要求 Bitmap 参数的场景须先经此方法转换。
     *
     * @param drawable 源 Drawable
     * @return 渲染结果；drawable 无效或无内在尺寸时返回 null
     */
    public static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null || drawable.getIntrinsicWidth() <= 0
                || drawable.getIntrinsicHeight() <= 0) return null;
        Bitmap b = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return b;
    }

    /**
     * 在截图上绘制红色半透明遮罩标记区域。
     *
     * @param bitmap 截图
     * @param x      区域左上角 x
     * @param y      区域左上角 y
     * @param w      区域宽度
     * @param h      区域高度
     */
    public static void drawRectMask(Bitmap bitmap, int x, int y, int w, int h) {
        if (bitmap == null) return;
        Paint markPaint = new Paint();
        markPaint.setColor(Color.RED);
        markPaint.setAlpha(100);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawRect(x, y, x + w, y + h, markPaint);
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
