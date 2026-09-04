package com.kaisar.xposed.godmode.engine.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.text.TextUtils;
import android.util.Log;

import org.junit.Test;

/**
 * 桩掩码特征测试 — 为 {@code engine/build.gradle} 的
 * {@code unitTests.returnDefaultValues = true} 立字据。
 * <p>
 * JVM 单测中的 Android 框架 API 全部走桩、静默返回默认值。本类把两处关键掩码行为
 * 写成断言：只要它们还成立，就证明相关生产代码在 JVM 下跑的是桩路径而非真逻辑，
 * 读测试结论时必须打折；若某天桩行为变化（测试变红），正好提醒复核
 * {@code Preconditions} 与 {@code Logger} 的测试有效性。DO NOT 删除本类而不提供替代
 * 守卫（如 Robolectric / shadow）。
 */
public final class StubMaskingCanaryTest {

    @Test
    public void textUtilsIsEmptyIsMaskedToFalse() {
        // 真机上 isEmpty("") == true；桩下恒为 false。
        // Preconditions.optionDefault(CharSequence, CharSequence) 依赖它，
        // 因此该分支在 JVM 下从未被真测过——见 stableOptionDefaultPaths 只覆盖稳定路径。
        assertFalse(TextUtils.isEmpty(""));
    }

    @Test
    public void logStackTraceAndLevelAreMaskedToDefaults() {
        // 真机上返回非空堆栈与真实 level 门禁；桩下对象返回恒为 null、boolean 恒为 false。
        // Logger.getStackTraceString / isLoggable 在 JVM 下同样只跑桩路径。
        assertNull(Log.getStackTraceString(new RuntimeException("probe")));
        assertFalse(Log.isLoggable("GodModePro", Log.DEBUG));
    }

    @Test
    public void stableOptionDefaultPaths() {
        // 以下路径在桩下与真机行为一致（不依赖被掩码的 API），可放心断言：
        assertSame("x", Preconditions.optionDefault("x", "d"));
        assertSame("v", Preconditions.checkNotNull("v"));
    }

    @Test
    public void nullReferenceDivergesBetweenStubAndDevice() {
        // 最锋利的掩码证据：真机上 TextUtils.isEmpty(null) == true，本应返回 "d"；
        // 桩下 isEmpty 恒为 false，直接返回 null 引用。本断言锁定桩行为——
        // 若它变红，说明桩环境变了，必须复核所有依赖 isEmpty 的生产分支。
        assertNull(Preconditions.optionDefault(null, "d"));
    }
}
