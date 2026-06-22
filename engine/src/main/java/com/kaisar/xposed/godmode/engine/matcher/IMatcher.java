package com.kaisar.xposed.godmode.engine.matcher;

import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.MatchFields;

import java.util.List;
import java.util.Map;

/**
 * 匹配器门面接口 — engine 对外暴露的唯一匹配 API。
 * <p>
 * 实现类：{@link CompositeMatcher}。
 */
public interface IMatcher {

    /** 在视图树中查找单个最佳匹配视图 */
    View matchView(View root, MatchFields spec);

    /** 在视图树中查找所有匹配的视图 */
    List<View> matchAllViews(View root, MatchFields spec);

    /**
     * 批量匹配可重复规则——共享一次视图树遍历。
     * <p>
     * 遍历视图树一次找到所有 RecyclerView，然后对每个 RecyclerView 的子项
     * 并行匹配多条规则，避免每条规则独立遍历全树（O(R × T) → O(T + R × I)）。
     * 非 repeatable 或不满足条件的规格返回空列表。
     *
     * @param root  根视图
     * @param specs 多条匹配规格（长度随意，结果按原索引映射）
     * @return 匹配结果映射 key=spec 在列表中的索引, value=匹配到的视图列表
     */
    Map<Integer, List<View>> matchAllViewsBatch(View root, List<? extends MatchFields> specs);
}
