package com.kaisar.xposed.godmode.injection.editor;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;

import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.List;

/**
 * 规则应用服务接口 — 编辑器组件通过此接口执行规则操作，解耦与 ViewController 的直接依赖。
 * <p>
 * 实现类：ViewController（Activity 级实例或进程级单例）。
 *
 * @see com.kaisar.xposed.godmode.injection.ViewController
 */
public interface RuleApplyService {

    /**
     * 应用单条规则到指定 View。
     *
     * @param view 目标视图
     * @param rule 待应用的规则
     * @return true 表示规则成功应用
     */
    boolean applyRule(View view, RuleRecord rule);

    /**
     * 批量应用规则到指定 Activity 的视图树。
     *
     * @param activity   目标 Activity
     * @param rules      待应用的规则列表
     * @param onComplete 全部应用完成后的回调（主线程），可为 null
     */
    void applyRuleBatch(Activity activity, List<RuleRecord> rules, Runnable onComplete);

    /**
     * 撤销单条规则。
     *
     * @param view 被应用规则的视图
     * @param rule 待撤销的规则
     */
    void revokeRule(View view, RuleRecord rule);

    /**
     * 清空 Applier 缓存（RemoveApplier + ModifyApplier）。
     */
    void clearCache();
}
