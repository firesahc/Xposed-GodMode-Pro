package com.kaisar.xposed.godmode.editor;

import android.graphics.Bitmap;

import com.kaisar.xposed.godmode.injection.bridge.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * {@link IRuleEditor} 的默认实现 — 委托给 {@link RuleServiceClient#getDefault()} 通过 Binder IPC
 * 与 system_server 中的 {@code RuleServiceServer} 通信。
 * <p>
 * 这是编辑器中唯一直接依赖 {@code RuleServiceClient} 的类；所有 {@code EditorOrchestrator}
 * 及其子组件的 IPC 调用统一通过此类间接执行。
 * </p>
 */
public final class RuleEditorClient implements IRuleEditor {

    private static final RuleEditorClient sInstance = new RuleEditorClient();

    /** 获取单例 */
    public static RuleEditorClient getInstance() {
        return sInstance;
    }

    @Override
    public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot) {
        return RuleServiceClient.getDefault().writeRule(packageName, rule, snapshot);
    }

    @Override
    public boolean deleteRule(String packageName, RuleRecord rule) {
        return RuleServiceClient.getDefault().deleteRule(packageName, rule);
    }

    @Override
    public String saveImageFile(String packageName, Bitmap bitmap) {
        return RuleServiceClient.getDefault().saveImageFile(packageName, bitmap);
    }
}
