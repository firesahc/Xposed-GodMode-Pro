package com.kaisar.xposed.godmode.inject;

import static org.junit.Assert.assertNotNull;

import com.kaisar.xposed.godmode.editor.EditorOrchestrator;
import com.kaisar.xposed.godmode.editor.RuleEditorClient;
import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.ipc.ServiceConnection;

import org.junit.Test;

/**
 * Xposed 入口静态链 fork 安全红线（常驻评审门禁）。
 *
 * <p>LSPosed 在 Zygote fork 子进程（system_server 与各 App）时立即初始化
 * {@code ModuleBootstrap}，此时主 Looper 尚未 prepare，任何
 * {@code new Handler(Looper.getMainLooper())} 都会以 NPE 炸掉整个模块加载
 * （见 19-09-18 桥接不可用事故：ServiceConnection 字段期建 Handler）。
 *
 * <p>因此入口静态链（ModuleBootstrap → EditorOrchestrator →
 * RuleEditorClient → 各 IPC 门面真单例）的构造路径必须 fork-safe：
 * 禁止 Looper/Handler/Binder/Context。上两测试在 JVM 下主 Looper 恒为 null，
 * 恰好复刻 fork 期条件；任一构造抛异常即回归。
 */
public final class XposedEntryForkSafetyTest {

    @Test
    public void serviceConnectionConstructsWithoutMainLooper() {
        assertNotNull(new ServiceConnection());
    }

    @Test
    public void editorChainConstructsWithoutMainLooper() {
        Property<Boolean> switchProp = new Property<>(false);
        EditorOrchestrator orchestrator =
                new EditorOrchestrator(switchProp, RuleEditorClient.getInstance());
        assertNotNull(orchestrator);
    }
}
