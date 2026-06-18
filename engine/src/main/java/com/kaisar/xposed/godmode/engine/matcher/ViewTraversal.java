package com.kaisar.xposed.godmode.engine.matcher;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.kaisar.xposed.godmode.engine.util.GmConstants;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 视图树遍历工具。
 * 从 ViewHelper 提取的纯遍历逻辑，不依赖 Xposed/matching/rule 等上层概念。
 */
public final class ViewTraversal {

    private ViewTraversal() {
    }

    /**
     * 构建可见视图节点列表。
     * 递归遍历视图树，跳过不可见视图和 GM 自身覆盖层组件（tag=gm_cmp）。
     *
     * @param root 根视图
     * @return 所有可见非 GM 视图的弱引用列表
     */
    public static List<WeakReference<View>> buildViewNodes(View root) {
        ArrayList<WeakReference<View>> views = new ArrayList<>();
        if (root.getVisibility() == View.VISIBLE
                && !GmConstants.TAG_GM_CMP.equals(root.getTag())) {
            views.add(new WeakReference<>(root));
            if (root instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) root;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    views.addAll(buildViewNodes(child));
                }
            }
        }
        return views;
    }

    /**
     * 获取视图在视图树中的深度路径。
     * 从根 DecorView 到目标视图的每一层 childIndex 数组。
     *
     * @param view 目标视图
     * @return 深度路径数组，索引 0 为最顶层（DecorView）
     */
    public static int[] getViewHierarchyDepth(View view) {
        ArrayList<Integer> depthList = new ArrayList<>();
        ViewParent parent = view.getParent();
        while (parent instanceof ViewGroup) {
            depthList.add(((ViewGroup) parent).indexOfChild(view));
            view = (View) parent;
            parent = parent.getParent();
        }
        int[] depth = new int[depthList.size()];
        for (int i = 0; i < depth.length; i++) {
            depth[i] = depthList.get(depth.length - 1 - i);
        }
        return depth;
    }

    /**
     * 按深度路径定位视图。
     *
     * @param root   根视图
     * @param depths 深度路径
     * @return 定位到的视图，如果路径中某步为空则返回 null
     */
    public static View findViewByDepth(View root, int[] depths) {
        if (root == null || depths == null) return null;
        View current = root;
        for (int depth : depths) {
            if (current instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) current;
                if (depth >= 0 && depth < vg.getChildCount()) {
                    current = vg.getChildAt(depth);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * 查找视图树中包含 RecyclerView 类名的最顶层祖先。
     *
     * @param view 起点视图
     * @return RecyclerView 祖先，如果不存在则返回 null
     */
    public static ViewGroup findRecyclerViewAncestor(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof ViewGroup) {
            if (parent.getClass().getName().contains("RecyclerView")) {
                return (ViewGroup) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    /**
     * 判断视图是否在 RecyclerView 内部。
     */
    public static boolean isInRecyclerView(View view) {
        return findRecyclerViewAncestor(view) != null;
    }

    /**
     * 从子视图向上查找顶层父视图（直到父视图不是 ViewGroup）。
     * 迭代实现，避免递归在深视图树上栈溢出。
     */
    public static View findTopParentView(View view) {
        View current = view;
        ViewParent parent = current.getParent();
        while (parent instanceof ViewGroup) {
            current = (View) parent;
            parent = current.getParent();
        }
        return current;
    }

    /**
     * 构建视图在 RecyclerView 内的 item 路径。
     * <p>
     * 路径格式：["index:ClassName", ...]，从 RecyclerView 的直接子元素到目标视图。
     *
     * @param v            目标视图
     * @param recyclerView RecyclerView 祖先
     * @return item 路径数组（从 RecyclerView 子元素到目标视图）
     */
    public static String[] getItemPath(View v, ViewGroup recyclerView) {
        ArrayList<String> path = new ArrayList<>();
        View current = v;
        ViewParent parent = v.getParent();
        while (parent != recyclerView && parent instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) parent;
            int idx = vg.indexOfChild(current);
            path.add(idx + ":" + current.getClass().getName());
            current = (View) parent;
            parent = parent.getParent();
        }
        Collections.reverse(path);
        return path.toArray(new String[0]);
    }

    /**
     * 按 item 路径查找视图。
     *
     * @param root  起始视图（RecyclerView 的直接子元素）
     * @param path  item 路径数组
     * @param index 当前路径索引（起始为 0）
     * @return 路径末端的视图，查找失败返回 null
     */
    public static View findViewByItemPath(View root, String[] path, int index) {
        if (index >= path.length) return root;
        String entry = path[index];
        int colonPos = entry.indexOf(':');
        if (colonPos < 0) return null;
        int childIdx;
        try {
            childIdx = Integer.parseInt(entry.substring(0, colonPos));
        } catch (NumberFormatException e) {
            return null;
        }
        String className = entry.substring(colonPos + 1);
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            if (childIdx < vg.getChildCount()) {
                View child = vg.getChildAt(childIdx);
                if (child != null && child.getClass().getName().equals(className)) {
                    return findViewByItemPath(child, path, index + 1);
                }
            }
        }
        return null;
    }

    public static View findViewByClassChain(View root, String[] path, int index) {
        if (path == null || index >= path.length) return root;
        String entry = path[index];
        int colonPos = entry.indexOf(':');
        if (colonPos < 0) return null;
        String className = entry.substring(colonPos + 1);

        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child != null && child.getClass().getName().equals(className)) {
                    return findViewByClassChain(child, path, index + 1);
                }
            }
        }
        return null;
    }
}
