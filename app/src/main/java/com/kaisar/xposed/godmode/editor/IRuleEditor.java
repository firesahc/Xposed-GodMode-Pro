package com.kaisar.xposed.godmode.editor;

import android.graphics.Bitmap;

import com.kaisar.xposed.godmode.ipc.contract.RuleMutationResult;
import com.kaisar.xposed.godmode.ipc.contract.UndoResultParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 编辑器规则持久化接口 — 抽象编辑模式下规则写入和删除操作。
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

    /** Writes both rule-owned images in the same authoritative mutation. */
    default boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot,
                              Bitmap modifiedSnapshot) {
        return writeRule(packageName, rule, snapshot);
    }

    /** Writes an editor mutation and asks system_server to capture it in its undo journal. */
    RuleMutationResult writeUndoableRule(String packageName, RuleRecord rule, Bitmap snapshot,
                                         Bitmap modifiedSnapshot);

    /** Returns the authoritative bounded-history projection for this editor process. */
    UndoStateParcel getUndoState(String packageName);

    /** Undoes the latest entry iff {@code expected} still matches the authoritative top. */
    UndoResultParcel undoLatest(String packageName, UndoStateParcel expected);

    /**
     * 删除一条规则。
     *
     * @param packageName 目标应用包名
     * @param rule        要删除的规则记录
     * @return true 删除成功
     */
    boolean deleteRule(String packageName, RuleRecord rule);

    /** Returns an actionable user-facing explanation for the latest failed request. */
    default String getFailureMessage() {
        return null;
    }

    /**
     * 读取编辑器工具栏隐藏项配置。
     */
    String getToolbarHiddenItems(String packageName);
}
