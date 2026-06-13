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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 复合匹配器 — IMatcher 的默认实现。
 * <p>
 * 使用 AND 布尔匹配（{@link #isStructuralMatch}），所有非空字段必须全部匹配。
 * 已移除评分体系（computeScore / 阈值 / MatchStrategy 注册表）。
 * <p>
 * 匹配流程：
 * <ul>
 *   <li>{@link #matchView} — resourceId 锚定 → depth 路径锚定 → isStructuralMatch 验证</li>
 *   <li>{@link #matchAllViews} / {@link #matchAllViewsBatch} — 根据 targetLevel：</li>
 *   <ul>
 *     <li>{@link TargetLevel#CARD} — 跳过已隐藏的卡片根，导航 itemPath + isStructuralMatch 验证（同 ELEMENT 精度），匹配通过后返回卡片根视图</li>
 *     <li>{@link TargetLevel#ELEMENT} — itemPath 导航 + classChain 回退 + isStructuralMatch 验证</li>
 *   </ul>
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

    @Override
    public Map<Integer, List<View>> matchAllViewsBatch(View root, List<MatchSpec> specs) {
        Map<Integer, List<View>> results = new HashMap<>();
        if (root == null || specs == null || specs.isEmpty()) return results;

        // 初始化结果映射：只包含符合条件的 repeatable 规则
        int eligibleCount = 0;
        for (int i = 0; i < specs.size(); i++) {
            MatchSpec spec = specs.get(i);
            if (spec != null && spec.repeatable && spec.itemPath != null
                    && spec.itemPath.length > 0 && spec.itemRootClass != null) {
                results.put(i, new ArrayList<>());
                eligibleCount++;
            }
        }
        if (eligibleCount == 0) return results;

        // 单次视图树遍历，收集所有 RecyclerView
        List<ViewGroup> recyclerViews = new ArrayList<>();
        collectRecyclerViews(root, recyclerViews);

        // 对每个 RecyclerView 的子项批量匹配所有规格
        for (ViewGroup rv : recyclerViews) {
            for (int i = 0; i < rv.getChildCount(); i++) {
                View itemRoot = rv.getChildAt(i);
                if (itemRoot == null) continue;

                // 所有规格共一次 itemRoot 遍历
                for (Map.Entry<Integer, List<View>> entry : results.entrySet()) {
                    List<View> partial = entry.getValue();
                    if (partial.size() >= GmConstants.MAX_REPEATABLE_RESULTS) continue;

                    MatchSpec spec = specs.get(entry.getKey());
                    if (!itemRoot.getClass().getName().equals(spec.itemRootClass)) continue;

                    Integer expectedViewType = spec.viewType > 0
                            ? spec.viewType : null;
                    if (expectedViewType != null
                            && !checkViewType(rv, itemRoot, expectedViewType)) {
                        continue;
                    }

                    if (spec.targetLevel == TargetLevel.CARD) {
                        // CARD 模式：跳过已隐藏的卡片根（防止级联重应用），
                        // 然后导航 itemPath + isStructuralMatch 验证精度同 ELEMENT，
                        // 匹配通过后返回卡片根视图而非内部元素。
                        if (itemRoot.getVisibility() != View.VISIBLE) continue;
                        View found = ViewTraversal.findViewByItemPath(
                                itemRoot, spec.itemPath, 0);
                        if (found == null) {
                            found = navigateByClassChain(itemRoot, spec.itemPath, 0);
                        }
                        if (found != null && isStructuralMatch(found, spec, false)) {
                            if (!partial.contains(itemRoot)) {
                                partial.add(itemRoot);
                            }
                        }
                    } else {
                        // ELEMENT 模式：itemPath 导航 + classChain 回退
                        View found = ViewTraversal.findViewByItemPath(
                                itemRoot, spec.itemPath, 0);
                        if (found == null) {
                            found = navigateByClassChain(itemRoot, spec.itemPath, 0);
                        }
                        if (found != null && isStructuralMatch(found, spec, false)) {
                            if (!partial.contains(found)) {
                                partial.add(found);
                            }
                        }
                    }
                }
            }
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

    /** 单次递归遍历，收集视图树中所有 RecyclerView（不递归进入 RecyclerView 内部） */
    private static void collectRecyclerViews(View view, List<ViewGroup> results) {
        if (view.getClass().getName().contains("RecyclerView") && view instanceof ViewGroup) {
            results.add((ViewGroup) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectRecyclerViews(vg.getChildAt(i), results);
            }
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

            Integer expectedViewType = spec.viewType > 0 ? spec.viewType : null;

            for (int i = 0; i < rv.getChildCount()
                    && results.size() < GmConstants.MAX_REPEATABLE_RESULTS; i++) {
                View itemRoot = rv.getChildAt(i);
                if (itemRoot == null) continue;
                if (!itemRoot.getClass().getName().equals(spec.itemRootClass)) continue;

                // viewType 检查（反射避免 RecyclerView 编译期依赖）
                if (expectedViewType != null
                        && !checkViewType(rv, itemRoot, expectedViewType)) {
                    continue;
                }

                if (spec.targetLevel == TargetLevel.CARD) {
                    // CARD 模式：跳过已隐藏的卡片根（防止级联重应用），
                    // 然后导航 itemPath + isStructuralMatch 验证精度同 ELEMENT，
                    // 匹配通过后返回卡片根视图而非内部元素。
                    if (itemRoot.getVisibility() != View.VISIBLE) continue;
                    View found = ViewTraversal.findViewByItemPath(
                            itemRoot, spec.itemPath, 0);
                    if (found == null) {
                        found = navigateByClassChain(itemRoot, spec.itemPath, 0);
                    }
                    if (found != null && isStructuralMatch(found, spec, false)) {
                        if (!results.contains(itemRoot)) {
                            results.add(itemRoot);
                        }
                    }
                } else {
                    // ELEMENT 模式：itemPath 导航 + classChain 回退
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

    /** viewType 检查（反射避免 RecyclerView 编译期依赖） */
    private static boolean checkViewType(ViewGroup rv, View itemRoot, int expectedViewType) {
        try {
            java.lang.reflect.Method getAdapter = rv.getClass().getMethod("getAdapter");
            Object adapter = getAdapter.invoke(rv);
            if (adapter != null) {
                java.lang.reflect.Method getChildAdapterPosition =
                        rv.getClass().getMethod("getChildAdapterPosition", View.class);
                int pos = (int) getChildAdapterPosition.invoke(rv, itemRoot);
                if (pos >= 0) {
                    java.lang.reflect.Method getItemViewType =
                            adapter.getClass().getMethod("getItemViewType", int.class);
                    int viewType = (int) getItemViewType.invoke(adapter, pos);
                    return viewType == expectedViewType;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
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
