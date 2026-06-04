package com.kaisar.xposed.godmode.engine.matcher;

import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.ViewRule;

import java.util.List;

/**
 * 匹配器门面接口 — engine 对外暴露的唯一匹配 API。
 * 内部通过 CompositeMatcher 编排多个 MatchStrategy 子策略。
 */
public interface IMatcher {

    /** 在视图树中查找单个最佳匹配视图 */
    View matchView(View root, ViewRule rule);

    /** 在视图树中查找所有匹配的视图（支持 RecyclerView 等重复结构） */
    List<View> matchAllViews(View root, ViewRule rule);
}
