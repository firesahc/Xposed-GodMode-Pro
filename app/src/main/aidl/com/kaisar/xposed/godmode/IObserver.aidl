package com.kaisar.xposed.godmode;

import com.kaisar.xposed.godmode.rule.ActRules;

interface IObserver {
    /**
     * 编辑模式切换回调。
     */
    void onEditModeChanged(boolean enable);

    /**
     * 规则变更回调——全量替换语义。
     * <p>
     * 每次回调的 actRules 是目标包当前规则的完整快照（非增量），接收方必须：
     * <ol>
     *   <li>计算新快照与本地缓存之间的差集（旧规则中不在新规则内的 = 已删除）</li>
     *   <li>先撤销差集中的旧规则（必须在清除缓存之前）</li>
     *   <li>清除 RemoveApplier/ModifyApplier 缓存</li>
     *   <li>用新快照替换本地缓存</li>
     *   <li>应用新规则到活跃的 Activity</li>
     * </ol>
     * 规则删除时（deleteRule），actRules 中不包含被删除的规则，
     * 接收方通过差集计算识别并撤销对应的视图修改。
     *
     * @param packageName 规则所属应用包名（非 "*" 通配符）
     * @param actRules    该包下所有 Activity 的规则全量快照
     */
    void onViewRuleChanged(String packageName, in ActRules actRules);
}
