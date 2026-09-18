package com.kaisar.xposed.godmode.orchestrator;

import android.os.ParcelFileDescriptor;

import com.kaisar.xposed.godmode.engine.applier.ModifyApplier;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;

/**
 * Binder 侧图片加载器 — Engine 纯度的边界适配器。
 * <p>
 * 实现 {@link ModifyApplier.ImageLoader}，内部委托
 * {@link RuleServiceClient#getDefault()} 的 {@code openImageFileDescriptor}。
 * Binder 未就绪时直接返回 null（由 ModifyApplier 现有异步链按 no_descriptor 处理）；
 * 异常只记录后透出，不吞异常、不改重试语义。
 */
public final class BinderImageLoader implements ModifyApplier.ImageLoader {

    private static final String TAG = "BinderImageLoader";

    private static volatile BinderImageLoader sInstance;

    /** 进程级共享单例，避免每 Activity 新建 loader。 */
    public static BinderImageLoader getDefault() {
        BinderImageLoader result = sInstance;
        if (result == null) {
            synchronized (BinderImageLoader.class) {
                result = sInstance;
                if (result == null) {
                    result = new BinderImageLoader();
                    sInstance = result;
                }
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openImageFileDescriptor(String path) throws Exception {
        try {
            return RuleServiceClient.getDefault().getImageStore().openImageFileDescriptor(path);
        } catch (Exception e) {
            Logger.w(TAG, "openImageFileDescriptor failed image=" + path, e);
            throw e;
        }
    }
}
