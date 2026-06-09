package com.kaisar.xposed.godmode.engine.matcher;

import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;

import java.util.List;

/**
 * 匹配器门面接口 — engine 对外暴露的唯一匹配 API。
 * <p>
 * 实现类：{@link CompositeMatcher}。
 * 新代码应优先使用接受 {@link MatchSpec} 的方法，而非过时的 RuleMatchSpec 版本。
 */
public interface IMatcher {

    // ===== 新 API：使用纯 MatchSpec =====

    /** 在视图树中查找单个最佳匹配视图 */
    View matchView(View root, MatchSpec spec);

    /** 在视图树中查找所有匹配的视图 */
    List<View> matchAllViews(View root, MatchSpec spec);

    // ===== 旧 API：使用 RuleMatchSpec（内部转为 MatchSpec 后委托） =====

    /** @deprecated 改为调用 {@link #matchView(View, MatchSpec)} */
    @Deprecated
    default View matchView(View root, RuleMatchSpec rule) {
        return matchView(root, rule != null ? rule.getMatchSpec() : null);
    }

    /** @deprecated 改为调用 {@link #matchAllViews(View, MatchSpec)} */
    @Deprecated
    default List<View> matchAllViews(View root, RuleMatchSpec rule) {
        return matchAllViews(root, rule != null ? rule.getMatchSpec() : null);
    }
}
