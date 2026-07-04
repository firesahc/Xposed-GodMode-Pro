package com.kaisar.xposed.godmode.editor;

import android.graphics.Bitmap;

import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 编辑器规则持久化接口 — 抽象编辑模式下规则写入、删除、图片保存操作。
 * <p>
 * 默认实现 {@link RuleEditorClient} 委托给 Binder IPC（{@code RuleServiceClient}），
 * 在单元测试中可传入 mock 实现以验证 {@code EditorOrchestrator} 行为。
 * </p>
 */
public interface IRuleEditor {

    /**
     * 写入/更新一条规则（含修改截图）。
     *
     * @param packageName 目标应用包名
     * @param rule        规则记录
     * @param snapshot    修改前的截图快照（可为 null）
     * @return true 写入成功
     */
    boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot);

    /**
     * 删除一条规则。
     *
     * @param packageName 目标应用包名
     * @param rule        要删除的规则记录
     * @return true 删除成功
     */
    boolean deleteRule(String packageName, RuleRecord rule);

    /**
     * 保存修改图片到文件，返回文件路径。
     *
     * @param packageName 目标应用包名
     * @param bitmap      要保存的位图
     * @return 保存后的文件路径，失败返回 null
     */
    String saveImageFile(String packageName, Bitmap bitmap);

    /**
     * 读取编辑器工具栏隐藏项配置。
     */
    String getToolbarHiddenItems();
}
