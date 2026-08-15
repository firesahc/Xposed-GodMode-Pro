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

    /** Decode a path with the same pixel and allocation limits. */
    public static Bitmap decodeFile(String path) {
        if (path == null || path.isEmpty()) return null;
        try (FileInputStream input = new FileInputStream(path)) {
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
