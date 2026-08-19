package com.kaisar.xposed.godmode.control;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;

import com.kaisar.xposed.godmode.engine.applier.SafeBitmapDecoder;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Copies one incoming pipe to a bounded regular file before safe bitmap decoding. */
final class IncomingImageReader {
    private static final int COPY_BUFFER_SIZE = 16 * 1024;

    private final Logger mLogger;

    IncomingImageReader(Logger logger) {
        mLogger = logger;
    }

    ReadResult read(ParcelFileDescriptor descriptor, File packageDir, String requestId,
                    String packageName, String label) {
        if (descriptor == null) return ReadResult.absent();
        File incoming = new File(packageDir, ".incoming-" + UUID.randomUUID() + ".tmp");
        long total = 0L;
        try {
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 FileOutputStream output = new FileOutputStream(incoming, false)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > GmConstants.MAX_IMAGE_FILE_SIZE_BYTES) {
                        throw new IOException("input image exceeds size limit");
                    }
                    output.write(buffer, 0, count);
                }
                output.flush();
            }
            if (total <= 0L) {
                mLogger.w("fd mutate image rejected requestId=" + requestId
                        + " package=" + packageName + " image=" + label
                        + " bytes=0 reason=empty");
                return ReadResult.invalid();
            }
            Bitmap bitmap = SafeBitmapDecoder.decodeFileStrict(incoming.getPath());
            if (bitmap == null) {
                mLogger.w("fd mutate image rejected requestId=" + requestId
                        + " package=" + packageName + " image=" + label
                        + " bytes=" + total + " reason=decode_failed");
                return ReadResult.invalid();
            }
            return ReadResult.valid(bitmap);
        } catch (Exception e) {
            mLogger.w("fd mutate image rejected requestId=" + requestId
                    + " package=" + packageName + " image=" + label
                    + " bytes=" + total, e);
            return ReadResult.invalid();
        } finally {
            if (incoming.exists() && !FileUtils.delete(incoming.getPath())) {
                mLogger.w("fd mutate temp cleanup failed requestId=" + requestId
                        + " package=" + packageName + " image=" + label
                        + " file=" + incoming.getName());
            }
        }
    }

    static int cleanupStaleFiles(File root) {
        int cleaned = 0;
        File[] packageDirs = root.listFiles(File::isDirectory);
        if (packageDirs == null) return 0;
        for (File packageDir : packageDirs) {
            File[] assets = packageDir.listFiles(file -> file.isFile()
                    && (file.getName().startsWith(".incoming-")
                    || file.getName().startsWith(".asset-"))
                    && file.getName().endsWith(".tmp"));
            if (assets == null) continue;
            for (File asset : assets) {
                if (FileUtils.delete(asset.getPath())) cleaned++;
            }
        }
        return cleaned;
    }

    static final class ReadResult {
        final boolean valid;
        final Bitmap bitmap;

        private ReadResult(boolean valid, Bitmap bitmap) {
            this.valid = valid;
            this.bitmap = bitmap;
        }

        static ReadResult absent() { return new ReadResult(true, null); }
        static ReadResult invalid() { return new ReadResult(false, null); }
        static ReadResult valid(Bitmap bitmap) { return new ReadResult(true, bitmap); }
    }
}
