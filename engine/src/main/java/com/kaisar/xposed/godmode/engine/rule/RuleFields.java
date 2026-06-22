package com.kaisar.xposed.godmode.engine.rule;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;

/**
 * 字段契约接口 — 定义 RuleRecord/RuleMatchSpec 的全部字段的 getter。
 * <p>
 * 继承 {@link MatchFields}（匹配字段），扩展规则标识、移除/修改/原始值等字段。
 * <p>
 * 实现类：{@link RuleMatchSpec}（引擎模块）、
 *      {@link com.kaisar.xposed.godmode.rule.RuleRecord}（app 模块）
 * <p>
 * 【编译期安全】新增字段时，必须在此接口中添加 getter → 所有实现类编译报错。
 * <p>
 * 字段总数: 40 getter（基接口 MatchFields 13 + 本接口 27）
 */
public interface RuleFields extends MatchFields {

    // ===== 规则标识 =====
    String getRuleTag();

    // ===== 移除规则字段 =====
    String getLabel();
    String getPackageName();
    String getMatchVersionName();
    int getMatchVersionCode();
    int getVersionCode();
    String getImagePath();
    String getAlias();
    int getX();
    int getY();
    int getWidth();
    int getHeight();

    // ===== 匹配配置（继承自 MatchFields: matchMode / infoFlowViewType / targetLevel） =====

    int getVisibility();
    long getTimestamp();

    // ===== 修改规则字段 =====
    int getModWidth();
    int getModHeight();
    float getModAlpha();
    int getModXOffset();
    int getModYOffset();
    String getModText();
    String getModImagePath();

    // ===== 原始值（用于撤销修改） =====
    int getOrigWidth();
    int getOrigHeight();
    float getOrigAlpha();
    String getOrigText();
    int getOrigLeftMargin();
    int getOrigTopMargin();
}
