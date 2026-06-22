package com.kaisar.xposed.godmode.injection.editor;

import android.graphics.Bitmap;

import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 规则持久化服务接口 — 编辑器组件通过此接口执行 IPC 持久化，解耦与 RuleServiceClient 的直接依赖。
 * <p>
 * 实现类：RuleServiceClient（AIDL 客户端代理）。
 *
 * @see com.kaisar.xposed.godmode.injection.bridge.RuleServiceClient
 */
public interface IRulePersistenceService {

    /**
     * 写入（新增）一条规则到系统服务。
     *
     * @param packageName 目标应用包名
     * @param rule        待写入的规则
     * @param snapshot    规则截图（可为 null）
     * @return true 表示写入成功
     */
    boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot);

    /**
     * 更新已有规则。
     *
     * @param packageName 目标应用包名
     * @param rule        更新后的规则
     * @return true 表示更新成功
     */
    boolean updateRule(String packageName, RuleRecord rule);

    /**
     * 删除指定规则。
     *
     * @param packageName 目标应用包名
     * @param rule        待删除的规则
     * @return true 表示删除成功
     */
    boolean deleteRule(String packageName, RuleRecord rule);
}
