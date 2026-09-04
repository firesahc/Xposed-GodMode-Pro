package com.kaisar.xposed.godmode.util;

import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.util.GmConstants;

/**
 * 模块资源访问器 — 被注入进程读取模块自带资源（布局/文本/图片）的统一入口。
 * <p>
 * 与 {@code ModuleResources}（资源注入器）互补而非重复：本类负责“读”已可用的模块资源
 *（含 {@link #markAsGmComponent} 自身 UI 标记，供 engine 遍历跳过自身面板），后者负责“写”
 *（经 AssetManager 把模块资源注入目标进程）。DO NOT 合并两者：注入时序与读取路径分属不同进程阶段。
 */
public final class GmResources {

    private static Resources sModuleRes;

    private GmResources() {}

    public static void init(Resources moduleRes) {
        sModuleRes = moduleRes;
    }

    public static XmlResourceParser getLayout(int id) {
        return sModuleRes.getLayout(id);
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
}
