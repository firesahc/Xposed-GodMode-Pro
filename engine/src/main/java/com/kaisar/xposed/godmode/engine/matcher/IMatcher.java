package com.kaisar.xposed.godmode.engine.matcher;

import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.MatchSpec;

import java.util.List;

/**
 * 匹配器门面接口 — engine 对外暴露的唯一匹配 API。
 * <p>
 * 实现类：{@link CompositeMatcher}。
 */
public interface IMatcher {

    /** 在视图树中查找单个最佳匹配视图 */
    View matchView(View root, MatchSpec spec);

    /** 在视图树中查找所有匹配的视图 */
    List<View> matchAllViews(View root, MatchSpec spec);
}
