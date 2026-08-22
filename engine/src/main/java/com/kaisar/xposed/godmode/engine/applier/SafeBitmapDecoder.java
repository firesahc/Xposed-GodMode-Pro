package com.kaisar.xposed.godmode.engine.applier;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Bounded bitmap decoder used for rule-owned images.
 *
 * <p>The bounds pass prevents a corrupt or unexpectedly large image from being
 * decoded at its source dimensions.  A failed decode is represented by
 * {@code null}; callers can then finish the image request without changing the
 * host view.</p>
 */
public final class SafeBitmapDecoder {

    /** Maximum decoded pixel count for one rule image (roughly 4096 x 4096). */
    private static final long MAX_PIXELS = 16L * 1024L * 1024L;

    private SafeBitmapDecoder() {
    }

    public static Bitmap decode(FileDescriptor descriptor) {
        if (descriptor == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(descriptor, null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeFileDescriptor(descriptor, null, options);
            if (bitmap == null) return null;

            long decodedPixels = (long) bitmap.getWidth() * bitmap.getHeight();
            if (decodedPixels > MAX_PIXELS) {
                bitmap.recycle();
                return null;
            }
            return bitmap;
        } catch (OutOfMemoryError | RuntimeException ignored) {
            // A bad image must not take down the hooked process.
            return null;
        }
    }

    /**
     * 采样解码并按规则 ROI 裁剪出预览区域。
     *
     * <p><b>入参为原图坐标系</b>：(x, y, width, height) 以未采样的源图左上角为原点，
     * 与 {@link #decode(FileDescriptor)} 返回的降采样位图坐标不同。</p>
     *
     * <p>流程：bounds 预检取得原图尺寸并校验 ROI 完整落在原图内 → 复用 {@link #decode(FileDescriptor)}
     * 采样解码 → 按解码后实际尺寸与原图尺寸的缩放比把 ROI 映射到降采样位图 → 裁剪返回。
     * 全尺寸解码产物在本方法内部回收，调用方只持有返回的裁剪结果；解码失败或 ROI 无效返回
     * {@code null}。</p>
     *
     * @param descriptor 图像文件描述符
     * @param x          ROI 左上角横坐标（原图坐标系）
     * @param y          ROI 左上角纵坐标（原图坐标系）
     * @param width      ROI 宽度（原图坐标系）
     * @param height     ROI 高度（原图坐标系）
     * @return 裁剪后的预览位图，独立于中间解码产物；失败返回 {@code null}
     */
    public static Bitmap decodeCropped(FileDescriptor descriptor, int x, int y, int width, int height) {
        if (descriptor == null || x < 0 || y < 0 || width <= 0 || height <= 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(descriptor, null, bounds);
            int sourceWidth = bounds.outWidth;
            int sourceHeight = bounds.outHeight;
            if (sourceWidth <= 0 || sourceHeight <= 0) return null;
            if ((long) x + width > sourceWidth || (long) y + height > sourceHeight) return null;

            Bitmap sampled = decode(descriptor);
            if (sampled == null) return null;

            try {
                float scale = (float) sampled.getWidth() / (float) sourceWidth;
                int cropX = Math.round(x * scale);
                int cropY = Math.round(y * scale);
                int cropWidth = Math.max(1, Math.round(width * scale));
                int cropHeight = Math.max(1, Math.round(height * scale));
                // 收敛到降采样位图边界内，避免舍入后越界
                cropX = Math.min(cropX, sampled.getWidth() - 1);
                cropY = Math.min(cropY, sampled.getHeight() - 1);
                cropWidth = Math.min(cropWidth, sampled.getWidth() - cropX);
                cropHeight = Math.min(cropHeight, sampled.getHeight() - cropY);

                Bitmap cropped = Bitmap.createBitmap(sampled, cropX, cropY, cropWidth, cropHeight);
                if (cropped == sampled) {
                    // ROI 覆盖整张降采样图时 createBitmap 返回源对象本身，
                    // 复制一份独立副本，使中间产物仍可安全回收。
                    Bitmap.Config config = sampled.getConfig() != null ? sampled.getConfig() : Bitmap.Config.ARGB_8888;
                    cropped = sampled.copy(config, false);
                }
                return cropped;
            } finally {
                sampled.recycle();
            }
        } catch (OutOfMemoryError | RuntimeException ignored) {
            // A bad image must not take down the hooked process.
            return null;
        }
    }

    /** Decode a path with the same pixel and allocation limits. */
    public static Bitmap decodeFile(String path) {
        if (path == null || path.isEmpty()) return null;
        try (FileInputStream input = new FileInputStream(path)) {
            return decode(input.getFD());
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    /** Rejects oversized source dimensions instead of downsampling untrusted IPC input. */
    public static Bitmap decodeFileStrict(String path) {
        if (path == null || path.isEmpty()) return null;
        try (FileInputStream input = new FileInputStream(path)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(input.getFD(), null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || sampledPixels(bounds.outWidth, bounds.outHeight, 1) > MAX_PIXELS) {
                return null;
            }
            return decode(input.getFD());
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static int sampleSizeFor(int width, int height) {
        int sample = 1;
        while (sample < (1 << 30)
                && sampledPixels(width, height, sample) > MAX_PIXELS) {
            sample <<= 1;
        }
        return sample;
    }

    private static long sampledPixels(int width, int height, int sample) {
        long sampledWidth = (width + (long) sample - 1L) / sample;
        long sampledHeight = (height + (long) sample - 1L) / sample;
        if (sampledWidth > Long.MAX_VALUE / Math.max(1L, sampledHeight)) {
            return Long.MAX_VALUE;
        }
        return sampledWidth * sampledHeight;
    }
}
