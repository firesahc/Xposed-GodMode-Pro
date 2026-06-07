package com.kaisar.xposed.godmode.injection.editor;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 编辑交互模式 — 定义节点选择器面板的交互状态。
 * <p>
 * 替代 {@code KeyInterceptor.MODE_INITIAL / MODE_REMOVE / MODE_MODIFY} 硬编码常量。
 */
public final class EditorInteractionMode {

    @IntDef({INITIAL, REMOVE, MODIFY})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {}

    public static final int INITIAL = 0;
    public static final int REMOVE = 1;
    public static final int MODIFY = 2;

    private EditorInteractionMode() {}

    /** 交互模式变更监听器。 */
    public interface OnModeChangeListener {
        /** 当交互模式发生变更时回调。 */
        void onModeChanged(@Mode int mode);
    }
}
