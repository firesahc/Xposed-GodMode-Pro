package com.kaisar.xposed.godmode.engine.matcher;

import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.TextMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 复合匹配器 — IMatcher 的默认实现。
 * <p>
 * 使用 AND 布尔匹配（{@link #isStructuralMatch}），所有非空字段必须全部匹配。
 * 已移除评分体系（computeScore / 阈值 / MatchStrategy 注册表）。
 * <p>
 * 匹配流程：
 * <ul>
 *   <li>{@link #matchView} — resourceId 锚定 → depth 路径锚定 → isStructuralMatch 验证</li>
 *   <li>{@link #matchAllViews} — itemPath 导航 + classChain 回退 + isStructuralMatch 验证</li>
 * </ul>
 */
public final class CompositeMatcher implements IMatcher {

    public CompositeMatcher() {
    }

    // ---- IMatcher 实现 ----

    @Override
    public View matchView(View root, MatchSpec spec) {
        if (root == null || spec == null) return null;

        // 非 repeatable 规则必须有可靠锚定（resourceId/depth）
        // ── 策略 1: resourceId 锚定 ──
        if (!TextUtils.isEmpty(spec.resourceName)) {
            View viewById = findByResourceId(root, spec);
            if (viewById != null && isStructuralMatch(viewById, spec, true)) {
                return viewById;
            }
        }

        // ── 策略 2: depth 路径锚定 ──
        if (spec.depth != null && spec.depth.length > 0) {
            View depthView = ViewTraversal.findViewByDepth(root, spec.depth);
            if (depthView != null && isVisibleView(depthView)
                    && isStructuralMatch(depthView, spec, true)) {
                return depthView;
            }
        }

        return null;
    }

    @Override
    public List<View> matchAllViews(View root, MatchSpec spec) {
        if (root == null || spec == null) return new ArrayList<>();

        // 信息流规则：itemPath 导航 + AND 验证，无全树兜底
        List<View> results = new ArrayList<>();
        if (spec.repeatable && spec.itemPath != null && spec.itemPath.length > 0
                && spec.itemRootClass != null) {
            collectRecyclerMatches(root, spec, results);
            if (!results.isEmpty()) return results;
        }

        return results;
    }

    // ---- 内部辅助方法 ----

    /**
     * 按 resourceName 锚定：解析为 int ID，通过 findViewById 精确查找。
     * 不包含评分验证，由调用方负责 isStructuralMatch 检查。
     */
    private static View findByResourceId(View root, MatchSpec spec) {
        if (root == null || TextUtils.isEmpty(spec.resourceName)) return null;
        try {
            int id = root.getResources().getIdentifier(spec.resourceName, "id", null);
            if (id == 0 || id == View.NO_ID) return null;
            View found = root.findViewById(id);
            if (found == null || !isVisibleView(found)) return null;
            return found;
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    /**
     * 递归遍历视图树，收集 RecyclerView 中按 itemPath 导航 + AND 验证匹配的视图。
     * 无 collectMatches 全树兜底。
     */
    private static void collectRecyclerMatches(View view, MatchSpec spec, List<View> results) {
        if (results.size() >= GmConstants.MAX_REPEATABLE_RESULTS) return;
        boolean isRecyclerView = view.getClass().getName().contains("RecyclerView")
                && view instanceof ViewGroup;
        if (isRecyclerView) {
            ViewGroup rv = (ViewGroup) view;

            // viewType 过滤（如果有）
            Integer expectedViewType = spec.matchThreshold > 0 ? spec.matchThreshold : null;

            for (int i = 0; i < rv.getChildCount()
                    && results.size() < GmConstants.MAX_REPEATABLE_RESULTS; i++) {
                View itemRoot = rv.getChildAt(i);
                if (itemRoot == null) continue;
                if (!itemRoot.getClass().getName().equals(spec.itemRootClass)) continue;

                // viewType 检查（反射避免 RecyclerView 编译期依赖）
                if (expectedViewType != null) {
                    try {
                        java.lang.reflect.Method getAdapter =
                                rv.getClass().getMethod("getAdapter");
                        Object adapter = getAdapter.invoke(rv);
                        if (adapter != null) {
                            java.lang.reflect.Method getChildAdapterPosition =
                                    rv.getClass().getMethod("getChildAdapterPosition", View.class);
                            int pos = (int) getChildAdapterPosition.invoke(rv, itemRoot);
                            if (pos >= 0) {
                                java.lang.reflect.Method getItemViewType =
                                        adapter.getClass().getMethod("getItemViewType", int.class);
                                int viewType = (int) getItemViewType.invoke(adapter, pos);
                                if (viewType != expectedViewType) continue;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                // itemPath 导航 + classChain 回退
                View found = ViewTraversal.findViewByItemPath(itemRoot, spec.itemPath, 0);
                if (found == null) {
                    found = navigateByClassChain(itemRoot, spec.itemPath, 0);
                }
                if (found != null && isStructuralMatch(found, spec, false)) {
                    if (!results.contains(found)) {
                        results.add(found);
                    }
                }
            }
            return;
        }

        // 非 RecyclerView → 继续递归
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount()
                    && results.size() < GmConstants.MAX_REPEATABLE_RESULTS; i++) {
                collectRecyclerMatches(vg.getChildAt(i), spec, results);
            }
        }
    }

    /** 判断视图是否可见且非 GM 自身组件 */
    private static boolean isVisibleView(View view) {
        return view.getVisibility() == View.VISIBLE
                && !GmConstants.TAG_GM_CMP.equals(view.getTag());
    }

    // =========================================================================
    // isStructuralMatch — AND 布尔匹配（最终验证）
    // =========================================================================

    /**
     * AND 布尔匹配：MatchSpec 中所有非空字段必须全部匹配。
     *
     * @param strictParent true=单元素模式，parentClass 也必须匹配；
     *                     false=信息流模式，parentClass 提供但不强制
     */
    static boolean isStructuralMatch(View view, MatchSpec spec, boolean strictParent) {
        if (view == null || spec == null) return false;

        // ── viewClass ──
        if (hasContent(spec.viewClass)
                && !view.getClass().getName().equals(spec.viewClass)) {
            return false;
        }

        // ── text ──
        if (hasContent(spec.text)) {
            if (!(view instanceof TextView)) return false;
            CharSequence t = ((TextView) view).getText();
            if (t == null) return false;
            if (!TextMatcher.matchText(t.toString(), spec.text, spec.matchMode)) {
                return false;
            }
        }

        // ── description ──
        if (hasContent(spec.description)) {
            CharSequence d = view.getContentDescription();
            if (d == null) return false;
            if (!TextMatcher.matchText(d.toString(), spec.description, spec.matchMode)) {
                return false;
            }
        }

        // ── parentClass（条件检） ──
        if (hasContent(spec.parentClass) && strictParent) {
            ViewParent p = view.getParent();
            if (!(p instanceof View)
                    || !p.getClass().getName().equals(spec.parentClass)) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasContent(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * 按类链导航（不依赖 index），处理卡片间结构微变。
     * 从 itemPath 数组提取每个元素的 ":ClassName" 部分做纯类链匹配。
     */
    private static View navigateByClassChain(View root, String[] itemPath, int startIndex) {
        if (itemPath == null || startIndex >= itemPath.length) return root;
        String entry = itemPath[startIndex];
        int colonPos = entry.indexOf(':');
        if (colonPos < 0) return null;
        String className = entry.substring(colonPos + 1);

        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child != null && child.getClass().getName().equals(className)) {
                    return navigateByClassChain(child, itemPath, startIndex + 1);
                }
            }
        }
        return null;
    }
}
