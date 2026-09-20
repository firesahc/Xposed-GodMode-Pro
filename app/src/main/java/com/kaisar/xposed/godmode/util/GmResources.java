package com.kaisar.xposed.godmode.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.Logger;

/**
 * 模块资源访问器 — 被注入进程读取模块自带资源（布局/文本/图片）的统一入口。
 * <p>
 * 与 {@code ModuleResources}（资源注入器）互补而非重复：本类负责“读”已可用的模块资源
 *（含 {@link #markAsGmComponent} 自身 UI 标记，供 engine 遍历跳过自身面板），后者负责“写”
 *（经 AssetManager 把模块资源注入目标进程）。DO NOT 合并两者：注入时序与读取路径分属不同进程阶段。
 */
public final class GmResources {

    private static final String TAG = "GmResources";

    /** 安装包名即模块包名（applicationId），createPackageContext 用它取模块资源。 */
    private static final String MODULE_PACKAGE = BuildConfig.APPLICATION_ID;

    private static Resources sModuleRes;

    private GmResources() {}

    public static void init(Resources moduleRes) {
        sModuleRes = moduleRes;
    }

    public static XmlResourceParser getLayout(int id) {
        return sModuleRes.getLayout(id);
    }

    /**
     * 创建面板渲染上下文：Resource Owner = Module，Configuration = 当前
     * Activity 快照，Theme Owner = Module（{@code GmPanelTheme}）。
     * <p>
     * 降级链（有序，每级失败记 d 日志）：flag=0 的包上下文 →
     * {@code CONTEXT_IGNORE_SECURITY} → 手写 ModuleContext 套模块主题 →
     * 手写 ModuleContext（宿主主题，旧行为）。只取资源不取代码，
     * 因此默认不申请 {@code CONTEXT_INCLUDE_CODE}。
     */
    public static Context createUiContext(Activity activity) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        Configuration hostConfig = null;
        try {
            hostConfig = activity.getResources().getConfiguration();
        } catch (RuntimeException e) {
            Logger.d(TAG, "host configuration unavailable, use base", e);
        }
        try {
            return themedUiContext(activity.createPackageContext(MODULE_PACKAGE, 0), hostConfig);
        } catch (Throwable first) {
            Logger.d(TAG, "package context flag=0 failed, try IGNORE_SECURITY", first);
        }
        try {
            return themedUiContext(
                    activity.createPackageContext(
                            MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY),
                    hostConfig);
        } catch (Throwable second) {
            Logger.d(TAG, "package context IGNORE_SECURITY failed, hand-rolled fallback", second);
        }
        if (sModuleRes == null) {
            throw new IllegalStateException("module Resources not ready");
        }
        // 手写回退：资源走 sModuleRes；主题仍套模块主题（框架 ContextThemeWrapper
        // 把主题挂在新实例上，绝不污染 base；若主题本身解析失败则保留宿主主题）。
        try {
            return new android.view.ContextThemeWrapper(
                    new ModuleContext(activity, sModuleRes), R.style.GmPanelTheme);
        } catch (Throwable third) {
            Logger.d(TAG, "module theme on hand-rolled context failed, host theme kept", third);
            return new ModuleContext(activity, sModuleRes);
        }
    }

    /** 包上下文套宿主配置快照，再套模块主题；任一步失败由调用方降级。
     * 颜色权威在此：面板涟漪/文字色一律取自 GmPanelTheme，与宿主主题无关；
     * 换涟漪色只改主题一处，勿改 ripple XML 的颜色引用。 */
    private static Context themedUiContext(Context packageContext, Configuration hostConfig) {
        Context configured = packageContext;
        if (hostConfig != null) {
            configured = packageContext.createConfigurationContext(new Configuration(hostConfig));
        }
        return new android.view.ContextThemeWrapper(configured, R.style.GmPanelTheme);
    }

    /** 手写回退 Context：资源走模块，主题默认沿 base（仅在包上下文不可用时启用）。 */
    private static final class ModuleContext extends ContextWrapper {
        private final Resources mModuleRes;

        ModuleContext(Context base, Resources moduleRes) {
            super(base);
            mModuleRes = moduleRes;
        }

        @Override
        public Resources getResources() {
            return mModuleRes;
        }

        @Override
        public AssetManager getAssets() {
            return mModuleRes.getAssets();
        }
    }

    /**
     * 统一 inflate 入口 — 模块优先，宿主兼容回退。
     * <p>
     * 宿主资源环境已被实证不可信（微信下 0x95 包名表缺失、MIUI 主题链丢引用），
     * 因此主路径即模块 UI Context；host 仅作可选回退。parser 一次性消费，
     * 重试前重新 {@link #getLayout}。
     */
    public static View inflate(Context uiContext, Activity hostActivity,
            int layoutId, ViewGroup parent, boolean attach) {
        if (uiContext == null) throw new IllegalArgumentException("uiContext is required");
        try {
            return LayoutInflater.from(uiContext)
                    .inflate(uiContext.getResources().getLayout(layoutId), parent, attach);
        } catch (RuntimeException moduleFailure) {
            if (!isInflationCompatibilityFailure(moduleFailure)) throw moduleFailure;
            Logger.w(TAG, "module inflate failed, host fallback"
                    + " activity=" + (hostActivity == null
                            ? "null" : hostActivity.getClass().getName())
                    + " layout=0x" + Integer.toHexString(layoutId), moduleFailure);
            try {
                if (hostActivity == null) throw moduleFailure;
                return LayoutInflater.from(hostActivity)
                        .inflate(getLayout(layoutId), parent, attach);
            } catch (RuntimeException hostFailure) {
                hostFailure.addSuppressed(moduleFailure);
                throw hostFailure;
            }
        }
    }

    /**
     * 仅资源兼容性失败允许 fallback；真正的程序 bug（NPE 等）必须原样抛出，
     * 否则会把编码错误伪装成宿主兼容问题。
     */
    private static boolean isInflationCompatibilityFailure(Throwable t) {
        while (t != null) {
            if (t instanceof Resources.NotFoundException
                    || t instanceof android.view.InflateException
                    || t instanceof UnsupportedOperationException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * 递归为模块注入的视图树标注 GM 组件 tag。
     * <p>
     * engine 的视图遍历（{@code ViewTraversal} / {@code CompositeMatcher}）依赖该 tag
     * 跳过自身 UI，防止面板被用户规则误屏蔽。统一在 inflate 后调用，
     * 替代布局中逐节点手工标注，避免新增控件漏标。
     *
     * @param root 模块 inflate 出的根视图
     */
    public static void markAsGmComponent(View root) {
        if (root == null) return;
        root.setTag(GmConstants.TAG_GM_CMP);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                markAsGmComponent(group.getChildAt(i));
            }
        }
    }

    public static CharSequence getText(int id) throws Resources.NotFoundException {
        return sModuleRes.getText(id);
    }

    public static String getString(int id) throws Resources.NotFoundException {
        return sModuleRes.getString(id);
    }

    public static String getString(int id, Object... formatArgs) throws Resources.NotFoundException {
        return sModuleRes.getString(id, formatArgs);
    }

    public static android.graphics.drawable.Drawable getDrawable(int id) throws Resources.NotFoundException {
        return sModuleRes.getDrawable(id);
    }

    public static android.content.res.ColorStateList getColorStateList(int id)
            throws Resources.NotFoundException {
        return sModuleRes.getColorStateList(id, null);
    }
}
