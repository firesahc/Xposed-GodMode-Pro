package com.kaisar.xposed.godmode.editor;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * 图片选择端口 — 将图片选择 Hook 与会话守卫迁出 UI 的边界契约（与 P0 Gate / P2-1 Port 同构）。
 * <p>
 * 职责划分：
 * <ul>
 *   <li>UI 侧（{@code PropertyEditorPanel}）只负责组装 {@link ImagePickSession} 不可变快照
 *      （点击时的 generation、editingActivity / targetView 弱引用、存活谓词、Bitmap 交付回调），
 *       并调用 {@link #requestPick(Activity, ImagePickSession)} 发起选图；
 *       Bitmap 所有权始终保留在 Panel（四字段与全部回收点不动），交付回调内完成
 *       旧图回收与落图。</li>
 *   <li>注入侧（{@code inject.hooks.ActivityResultHook}）实现本接口并消费快照：
 *       一次性安装 {@code Activity.onActivityResult} Hook、过滤 REQUEST_CODE、解码 Bitmap、
 *       用快照字段做会话守卫校验；失败只记日志，不抛给宿主。</li>
 * </ul>
 * <p>
 * 依赖方向只允许 editor 定义契约、inject 实现并装配；UI 层不得引用任何
 * {@code de.robv} 符号，注入层不得持有 Panel 的 Bitmap 状态。
 */
public interface ImagePickPort {

    /**
     * 发起一次图片选择：缓存会话快照（覆盖旧会话）、确保 Hook 已安装，
     * 随后拉起系统文档选择器。调用线程即为回调线程，不做线程切换。
     *
     * @param activity 发起选图的宿主 Activity（非空）
     * @param session  UI 组装的不可变会话快照（非空）
     */
    void requestPick(Activity activity, ImagePickSession session);

    /** 丢弃已缓存的会话快照，使迟到的选择结果被守卫丢弃（须幂等，不抛异常）。 */
    void cancel();

    /**
     * 图片选择会话的不可变快照 — 由 UI 在点击选图时组装，由注入侧在回调时消费。
     * <p>
     * 快照一旦构造即冻结：generation 为点击时刻的 Panel 会话代次；
     * editingActivity / targetView 均为弱引用，不延长宿主对象生命；
     * 存活判断委托给 {@link Verify}（Panel 实时状态：面板存活、非保存中、
     * 快照代次未过期、目标视图身份一致）；解码得到的 Bitmap 经 {@link OnImage}
     * 交回 UI，所有权随即转移给 Panel。
     */
    final class ImagePickSession {

        private final long generation;
        private final WeakReference<Activity> editingActivity;
        private final WeakReference<View> targetView;
        private final Verify verify;
        private final OnImage onImage;

        public ImagePickSession(long generation,
                WeakReference<Activity> editingActivity,
                WeakReference<View> targetView,
                Verify verify,
                OnImage onImage) {
            this.generation = generation;
            this.editingActivity = Objects.requireNonNull(editingActivity, "editingActivity");
            this.targetView = Objects.requireNonNull(targetView, "targetView");
            this.verify = Objects.requireNonNull(verify, "verify");
            this.onImage = Objects.requireNonNull(onImage, "onImage");
        }

        /** 点击时刻的会话代次；注入侧用它与 UI 实时代次比较，替代原 mImageRequestGeneration 比对。 */
        public long generation() {
            return generation;
        }

        /** 选图时刻的编辑 Activity（弱引用，可能已失效）。 */
        public WeakReference<Activity> editingActivity() {
            return editingActivity;
        }

        /** 选图时刻的待替换目标视图（弱引用，可能已失效）。 */
        public WeakReference<View> targetView() {
            return targetView;
        }

        /** Panel 实时存活谓词。 */
        public Verify verify() {
            return verify;
        }

        /** Bitmap 交付回调（所有权转移给 UI）。 */
        public OnImage onImage() {
            return onImage;
        }
    }

    /**
     * 会话存活谓词 — 由 UI 实现，捕获 Panel 实时状态。
     * <p>
     * 须覆盖原守卫全部条件：面板仍在展示、未处于保存提交中、
     * {@code requestGeneration} 与 Panel 当前代次一致、目标视图仍为本次编辑目标
     * 且通过视图身份校验。注入侧只调用不解释。
     */
    interface Verify {

        /**
         * @param requestGeneration 快照冻结的会话代次
         * @param targetView 快照目标视图的当前解引用结果（可能为 null）
         * @return 会话仍有效时返回 true
         */
        boolean verify(long requestGeneration, View targetView);
    }

    /**
     * Bitmap 交付回调 — 由 UI 实现，接收解码结果并接管所有权。
     * <p>
     * 调用线程即 Hook 回调线程（与原实现一致，不做线程切换）；
     * 实现方负责旧图回收与落图，异常由注入侧统一记日志。
     */
    interface OnImage {

        /**
         * @param bitmap 解码得到的非空位图，所有权转移给调用方
         */
        void onImage(Bitmap bitmap);
    }
}
