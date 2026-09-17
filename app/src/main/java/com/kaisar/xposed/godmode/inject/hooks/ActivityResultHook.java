package com.kaisar.xposed.godmode.inject.hooks;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.editor.ImagePickPort;
import com.kaisar.xposed.godmode.engine.util.CommonUtils;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.util.GmResources;

import java.io.InputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 图片选择注入 Hook — {@link ImagePickPort} 的 inject 侧唯一实现。
 * <p>
 * 由 {@code AppInjector} 构造并装配给 {@code PropertyEditorPanel} 持有
 * （实例归属 Panel 链路，不新开全局单例）。职责：
 * <ul>
 *   <li>{@code Activity.onActivityResult} 一次安装（端口内 boolean guard，
 *       与原 Panel 语义一致）；</li>
 *   <li>REQUEST_CODE 过滤、ContentResolver 解码；</li>
 *   <li>会话守卫：原 Panel 744-757 逻辑逐行平移，其中
 *       {@code mImageRequestGeneration != mGeneration} 改为快照字段比较
 *       （{@code session.generation()} 经 {@code Verify} 与 UI 实时代次比对），
 *       {@code mEditingActivity / mPendingImageView / mTargetView} 改为快照弱引用解引用，
 *       {@code mPanelView / mSaving / verifyViewIdentity} 收敛进 {@code Verify} 谓词；</li>
 *   <li>解码失败 / 校验失败走 {@code CommonUtils.recycleNullableBitmap} 路径；
 *       全部异常只记日志，不抛给宿主；解码与交付仍在 Hook 回调线程，
 *       不引入线程切换。</li>
 * </ul>
 * <p>
 * 本类是 {@code de.robv} 引用的终点：editor/UI 层不得再出现 Xposed 符号。
 */
public final class ActivityResultHook extends XC_MethodHook implements ImagePickPort {

    private static final String TAG = "ModifyPanel";
    private static final int REQUEST_CODE_PICK_IMAGE = 0x5A45;

    private volatile ImagePickSession mSession;
    private boolean mHookInstalled;

    @Override
    public void requestPick(Activity activity, ImagePickSession session) {
        if (activity == null || session == null) return;
        mSession = session;
        ensureInstalled();
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            activity.startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
        } catch (Exception e) {
            Toast.makeText(activity, GmResources.getString(R.string.toast_cannot_open_image_picker), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void cancel() {
        mSession = null;
    }

    private void ensureInstalled() {
        if (mHookInstalled) return;
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult",
                    int.class, int.class, Intent.class, this);
            mHookInstalled = true;
        } catch (Throwable failure) {
            Logger.e(TAG,
                    "hookActivityResult: Xposed hook failed, image replacement disabled", failure);
        }
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        int requestCode = (int) param.args[0];
        int resultCode = (int) param.args[1];
        Intent data = (Intent) param.args[2];
        if (requestCode != REQUEST_CODE_PICK_IMAGE || resultCode != Activity.RESULT_OK || data == null) return;

        try {
            Uri uri = data.getData();
            if (uri == null) return;
            Activity currentActivity = (Activity) param.thisObject;
            try (InputStream is = currentActivity.getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                if (bitmap == null) return;

                ImagePickSession session = mSession;
                if (session == null) {
                    CommonUtils.recycleNullableBitmap(bitmap);
                    return;
                }
                Activity editingActivity = session.editingActivity().get();
                View targetView = session.targetView().get();
                if (editingActivity == null
                        || currentActivity != editingActivity
                        || !(targetView instanceof ImageView)
                        || !session.verify().verify(session.generation(), targetView)) {
                    CommonUtils.recycleNullableBitmap(bitmap);
                    return;
                }

                session.onImage().onImage(bitmap);
            }
        } catch (Exception e) {
            Logger.e(TAG, "handle image pick fail", e);
        }
    }
}
