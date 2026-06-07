package com.kaisar.xposed.godmode.util;

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
import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.rule.ViewRule;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

@GlideModule
public class GmGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        registry.prepend(ViewRule.class, Bitmap.class, new RuleModelLoaderFactory());
    }

    static class RuleModelLoaderFactory implements ModelLoaderFactory<ViewRule, Bitmap> {

        @NonNull
        @Override
        public ModelLoader<ViewRule, Bitmap> build(@NonNull MultiModelLoaderFactory multiFactory) {
            return new RuleModelLoader();
        }

        @Override
        public void teardown() {
        }
    }

    static class RuleModelLoader implements ModelLoader<ViewRule, Bitmap> {

        @Override
        public LoadData<Bitmap> buildLoadData(@NonNull ViewRule viewRule, int width, int height, @NonNull Options options) {
            return new LoadData<>(new ObjectKey(viewRule), new RuleDataFetcher(viewRule));
        }

        @Override
        public boolean handles(@NonNull ViewRule viewRule) {
            return true;
        }
    }

    static class RuleDataFetcher implements DataFetcher<Bitmap> {

        final ViewRule mViewRule;

        public RuleDataFetcher(ViewRule viewRule) {
            mViewRule = viewRule;
        }

        @Override
        public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super Bitmap> callback) {
            ParcelFileDescriptor pfd = GodModeManager.getDefault().openImageFileDescriptor(mViewRule.imagePath);
            if (pfd != null) {
                try {
                    InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] temp = new byte[8192];
                    int n;
                    while ((n = in.read(temp)) != -1) {
                        buffer.write(temp, 0, n);
                    }
                    Bitmap bitmap = BitmapFactory.decodeByteArray(buffer.toByteArray(), 0, buffer.size());
                if (mViewRule.x >= 0 && mViewRule.y >= 0 && mViewRule.width > 0 && mViewRule.height > 0
                        && bitmap != null
                        && mViewRule.x + mViewRule.width <= bitmap.getWidth()
                        && mViewRule.y + mViewRule.height <= bitmap.getHeight()) {
                    Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, mViewRule.x, mViewRule.y, mViewRule.width, mViewRule.height);
                    Bitmap markedBitmap = Bitmap.createBitmap(croppedBitmap.getWidth(), croppedBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(markedBitmap);
                    canvas.drawBitmap(croppedBitmap, 0, 0, null);
                    Paint borderPaint = new Paint();
                    borderPaint.setStyle(Paint.Style.STROKE);
                    borderPaint.setColor(Color.RED);
                    borderPaint.setStrokeWidth(3);
                    canvas.drawRect(1, 1, markedBitmap.getWidth() - 1, markedBitmap.getHeight() - 1, borderPaint);
                    callback.onDataReady(markedBitmap);
                    recycleNullableBitmap(croppedBitmap);
                } else {
                    callback.onDataReady(bitmap);
                }
                } catch (Exception e) {
                    callback.onLoadFailed(e);
                }
            } else {
                callback.onLoadFailed(new FileNotFoundException(mViewRule.imagePath));
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
