package com.kaisar.xposed.godmode.ipc;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.kaisar.xposed.godmode.engine.util.Closeables;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.util.TaskExecutor;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * B4 图片库：持有 pipe 双写并发与 FD 只读语义。
 *
 * <p>逐行平移自 RuleServiceClient（双 pipe 并发写/await 10s 超时/失败语义/
 * closeRead/closeWrite 配对防泄漏）：连接态单源（构造注入的 ServiceConnection，
 * 不新开单例），空连接/异常路径只记日志不抛宿主。Client 保留
 * openImageFileDescriptor 公开签名转发，写入 owner 经本实现直调。
 */
public final class ImageStore {
    private static final String TAG = "RuleServiceClient";

    private final ServiceConnection mServiceConnection;

    public ImageStore(ServiceConnection serviceConnection) {
        if (serviceConnection == null) throw new IllegalArgumentException("serviceConnection is required");
        mServiceConnection = serviceConnection;
    }

    public PipeAsset openPipe(Bitmap bitmap) {
        if (bitmap == null) return null;
        if (bitmap.isRecycled()) {
            mServiceConnection.recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                    DiagnosticMessages.IMAGE_RECYCLED_MUTATION_CANCELLED_DETAIL));
            return null;
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            PipeAsset asset = new PipeAsset(pipe[0], pipe[1], bitmap);
            TaskExecutor.executeFdWrite(asset::write);
            return asset;
        } catch (IOException e) {
            mServiceConnection.recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                    String.format(Locale.US, DiagnosticMessages.IMAGE_PIPE_CREATE_FAILED_DETAIL, e.getMessage())));
            Logger.w(TAG, "create image pipe failed", e);
            return null;
        }
    }

    public static void awaitPipe(PipeAsset asset) {
        if (asset == null) return;
        try {
            if (!asset.finished.await(10, TimeUnit.SECONDS)) {
                asset.failure.compareAndSet(null,
                        new IOException("pipe writer did not stop within 10 seconds"));
                asset.closeWrite();
                Logger.w(TAG, "image pipe writer timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            asset.failure.compareAndSet(null, e);
            asset.closeWrite();
            Logger.w(TAG, "image pipe writer wait interrupted", e);
        }
    }

    public static void closePipe(PipeAsset asset) {
        if (asset == null) return;
        asset.closeRead();
        asset.closeWrite();
    }

    public static Throwable firstFailure(PipeAsset first, PipeAsset second) {
        Throwable failure = first == null ? null : first.failure.get();
        return failure != null || second == null ? failure : second.failure.get();
    }

    /** B4：pipe 机制已迁入本类，跨包供写入 owner 调用。 */
    public final class PipeAsset {
        public final ParcelFileDescriptor readEnd;
        private ParcelFileDescriptor writeEnd;
        final Bitmap bitmap;
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        PipeAsset(ParcelFileDescriptor readEnd, ParcelFileDescriptor writeEnd, Bitmap bitmap) {
            this.readEnd = readEnd;
            this.writeEnd = writeEnd;
            this.bitmap = bitmap;
        }

        void write() {
            try (ParcelFileDescriptor.AutoCloseOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                writeEnd = null;
                if (!bitmap.compress(Bitmap.CompressFormat.WEBP, 80, output)) {
                    throw new IOException("bitmap encode failed");
                }
                output.flush();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                closeWrite();
            } finally {
                finished.countDown();
            }
        }

        public void closeRead() {
            Closeables.closeQuietly(readEnd);
        }

        void closeWrite() {
            ParcelFileDescriptor current = writeEnd;
            writeEnd = null;
            Closeables.closeQuietly(current);
        }
    }

    public ParcelFileDescriptor openImageFileDescriptor(String path) {
        ServiceConnection.Connection c = mServiceConnection.ensureConnection();
        if (c == null) return null;
        try {
            return c.service.openImageFileDescriptor(path);
        } catch (RemoteException e) {
            mServiceConnection.logError("openImageFileDescriptor", c, e);
            return null;
        }
    }
}
