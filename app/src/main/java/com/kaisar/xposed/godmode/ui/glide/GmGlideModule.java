package com.kaisar.xposed.godmode.ui.glide;

import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;

import java.io.FileNotFoundException;
import java.io.IOException;

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
                    // 直接使用 decodeFileDescriptor 解码，避免 ByteArrayOutputStream 中间缓冲区
                    Bitmap bitmap = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                    if (mPreview.x >= 0 && mPreview.y >= 0 && mPreview.width > 0 && mPreview.height > 0
                            && bitmap != null
                            && mPreview.x + mPreview.width <= bitmap.getWidth()
                            && mPreview.y + mPreview.height <= bitmap.getHeight()) {
                        Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, mPreview.x, mPreview.y, mPreview.width, mPreview.height);
                        Bitmap markedBitmap = Bitmap.createBitmap(croppedBitmap.getWidth(), croppedBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(markedBitmap);
                        canvas.drawBitmap(croppedBitmap, 0, 0, null);
                        Paint borderPaint = new Paint();
                        borderPaint.setStyle(Paint.Style.STROKE);
                        borderPaint.setColor(Color.RED); // 截图边框描边颜色
                        borderPaint.setStrokeWidth(3);
                        canvas.drawRect(1, 1, markedBitmap.getWidth() - 1, markedBitmap.getHeight() - 1, borderPaint);
                        callback.onDataReady(markedBitmap);
                        recycleNullableBitmap(croppedBitmap);
                    } else {
                        callback.onDataReady(bitmap);
                    }
                } finally {
                    try { pfd.close(); } catch (IOException ignored) { }
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
