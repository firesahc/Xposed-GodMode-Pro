package com.kaisar.xposed.godmode.control;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ModuleLifecycle} 状态机转换矩阵锁定。
 * <p>
 * 重点覆盖故障恢复边：ERROR/DEGRADED 在全部层回到 HEALTHY 后必须回归 READY
 * （javadoc 状态图承诺的 DEGRADED → READY 边曾长期缺失实现）。
 */
public class ModuleLifecycleTest {

    private static final ModuleLifecycle.Layer[] CONTROL_ONLY = {
            ModuleLifecycle.Layer.CONTROL};

    // ===== 基线正向路径 =====

    @Test
    public void initStaysInitUntilAnyLayerIsMarked() {
        ModuleLifecycle lifecycle = new ModuleLifecycle(CONTROL_ONLY);
        assertEquals(ModuleLifecycle.State.INIT, lifecycle.getState());
        assertFalse(lifecycle.isOperational());

        lifecycle.transition(ModuleLifecycle.State.LOADING);
        assertEquals(ModuleLifecycle.State.LOADING, lifecycle.getState());

        lifecycle.markHealthy(ModuleLifecycle.Layer.CONTROL);
        assertEquals(ModuleLifecycle.State.READY, lifecycle.getState());
        assertTrue(lifecycle.isOperational());
    }

    @Test
    public void transitionAcceptsExplicitTargetState() {
        ModuleLifecycle lifecycle = new ModuleLifecycle(CONTROL_ONLY);
        lifecycle.transition(ModuleLifecycle.State.ERROR);
        assertEquals(ModuleLifecycle.State.ERROR, lifecycle.getState());
    }

    // ===== 故障进入 =====

    @Test
    public void errorLayerForcesOverallError() {
        ModuleLifecycle lifecycle = new ModuleLifecycle(CONTROL_ONLY);
        lifecycle.transition(ModuleLifecycle.State.READY);
        lifecycle.markHealthy(ModuleLifecycle.Layer.CONTROL);

        lifecycle.markError(ModuleLifecycle.Layer.CONTROL, "load rules failed");
        assertEquals(ModuleLifecycle.State.ERROR, lifecycle.getState());
        assertFalse(lifecycle.isOperational());
    }

    @Test
    public void degradedLayerForcesOverallDegradedAfterLoading() {
        ModuleLifecycle lifecycle = new ModuleLifecycle(CONTROL_ONLY);
        lifecycle.transition(ModuleLifecycle.State.LOADING);

        lifecycle.markDegraded(ModuleLifecycle.Layer.CONTROL, "observer lost");
        assertEquals(ModuleLifecycle.State.DEGRADED, lifecycle.getState());
        assertTrue(lifecycle.isOperational());
    }

    // ===== 故障恢复边（回归锁定）=====

    @Test
    public void errorRecoversToReadyWhenAllLayersReturnHealthy() {
        ModuleLifecycle lifecycle = new ModuleLifecycle(CONTROL_ONLY);
        lifecycle.transition(ModuleLifecycle.State.LOADING);
        lifecycle.markError(ModuleLifecycle.Layer.CONTROL, "load rules failed");
        assertEquals(ModuleLifecycle.State.ERROR, lifecycle.getState());

        lifecycle.markHealthy(ModuleLifecycle.Layer.CONTROL);
        assertEquals("ERROR 层恢复后必须回到 READY，否则模块全局失效直至重启",
                ModuleLifecycle.State.READY, lifecycle.getState());
        assertTrue(lifecycle.isOperational());
    }

    @Test
    public void degradedRecoversToReadyWhenAllLayersReturnHealthy() {
        ModuleLifecycle lifecycle = new ModuleLifecycle(CONTROL_ONLY);
        lifecycle.transition(ModuleLifecycle.State.LOADING);
        lifecycle.markDegraded(ModuleLifecycle.Layer.CONTROL, "observer lost");
        assertEquals(ModuleLifecycle.State.DEGRADED, lifecycle.getState());

        lifecycle.markHealthy(ModuleLifecycle.Layer.CONTROL);
        assertEquals("DEGRADED 恢复边为 javadoc 状态图明确承诺的转换",
                ModuleLifecycle.State.READY, lifecycle.getState());
        assertTrue(lifecycle.isOperational());
    }

    @Test
    public void recoveryRequiresEveryRequiredLayerHealthy() {
        ModuleLifecycle lifecycle = new ModuleLifecycle(
                ModuleLifecycle.Layer.CONTROL, ModuleLifecycle.Layer.DATA);
        lifecycle.transition(ModuleLifecycle.State.LOADING);
        lifecycle.markHealthy(ModuleLifecycle.Layer.CONTROL);
        lifecycle.markHealthy(ModuleLifecycle.Layer.DATA);
        assertEquals(ModuleLifecycle.State.READY, lifecycle.getState());

        // 仅一层恢复健康时，另一层的 ERROR 仍应压制整体状态
        lifecycle.markError(ModuleLifecycle.Layer.CONTROL, "x");
        lifecycle.markError(ModuleLifecycle.Layer.DATA, "y");
        lifecycle.markHealthy(ModuleLifecycle.Layer.CONTROL);
        assertEquals(ModuleLifecycle.State.ERROR, lifecycle.getState());

        lifecycle.markHealthy(ModuleLifecycle.Layer.DATA);
        assertEquals(ModuleLifecycle.State.READY, lifecycle.getState());
    }
}
