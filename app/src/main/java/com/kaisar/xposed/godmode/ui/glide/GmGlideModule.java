package com.kaisar.xposed.godmode.ui.glide;

import static com.kaisar.xposed.godmode.engine.util.Closeables.closeQuietly;
import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.signature.ObjectKey;
import com.kaisar.xposed.godmode.engine.applier.SafeBitmapDecoder;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;

import java.io.FileNotFoundException;

@GlideModule
public class GmGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        registry.prepend(RulePreviewSpec.class, Bitmap.class, new RuleModelLoaderFactory());
    }

    static class RuleModelLoaderFactory implements ModelLoaderFactory<RulePreviewSpec, Bitmap> {

        @NonNull
        @Override
        public ModelLoader<RulePreviewSpec, Bitmap> build(@NonNull MultiModelLoaderFactory multiFactory) {
            return new RuleModelLoader();
        }

        @Override
        public void teardown() {
        }
    }

    static class RuleModelLoader implements ModelLoader<RulePreviewSpec, Bitmap> {

        @Override
        public LoadData<Bitmap> buildLoadData(@NonNull RulePreviewSpec viewRule, int width, int height, @NonNull Options options) {
            return new LoadData<>(new ObjectKey(viewRule), new RuleDataFetcher(viewRule));
        }

        @Override
        public boolean handles(@NonNull RulePreviewSpec viewRule) {
            return true;
        }
    }

    static class RuleDataFetcher implements DataFetcher<Bitmap> {

        final RulePreviewSpec mPreview;

        public RuleDataFetcher(RulePreviewSpec viewRule) {
            mPreview = viewRule;
        }

        @Override
        public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super Bitmap> callback) {
            ParcelFileDescriptor pfd = RuleServiceClient.getDefault().openImageFileDescriptor(mPreview.imagePath);
            if (pfd != null) {
                try {
                    // 采样解码并按原图坐标 ROI 裁剪，避免大图全量解码导致 OOM；
                    // ROI 无效或裁剪失败时回退为带采样保护的整图解码
                    Bitmap croppedBitmap = SafeBitmapDecoder.decodeCropped(
                            pfd.getFileDescriptor(), mPreview.x, mPreview.y, mPreview.width, mPreview.height);
                    if (croppedBitmap != null) {
                        Bitmap markedBitmap = Bitmap.createBitmap(croppedBitmap.getWidth(), croppedBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(markedBitmap);
                        canvas.drawBitmap(croppedBitmap, 0, 0, null);
                        Paint borderPaint = new Paint();
                        borderPaint.setStyle(Paint.Style.STROKE);
                        borderPaint.setColor(Color.RED); // 截图边框描边颜色
                        borderPaint.setStrokeWidth(3);
                        canvas.drawRect(1, 1, markedBitmap.getWidth() - 1, markedBitmap.getHeight() - 1, borderPaint);
                        callback.onDataReady(markedBitmap);
                        // decodeCropped 返回的是独立裁剪副本，内部中间产物已自行回收，此处不构成双重 recycle
                        recycleNullableBitmap(croppedBitmap);
                    } else {
                        callback.onDataReady(SafeBitmapDecoder.decode(pfd.getFileDescriptor()));
                    }
                } finally {
                    closeQuietly(pfd);
                }
            } else {
                callback.onLoadFailed(new FileNotFoundException(mPreview.imagePath));
            }
        }

        @Override
        public void cleanup() {
        }

        @Override
        public void cancel() {
        }

        @NonNull
        @Override
        public Class<Bitmap> getDataClass() {
            return Bitmap.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.LOCAL;
        }
    }
}
