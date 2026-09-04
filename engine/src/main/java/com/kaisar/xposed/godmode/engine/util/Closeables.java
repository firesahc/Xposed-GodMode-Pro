package com.kaisar.xposed.godmode.engine.util;

import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import android.system.Os;

import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.FileDescriptor;

/**
 * 预期关闭的静默工具 — 仅用于“关闭失败本身就是预期内、无需处理”的路径
 *（读端/写端 pipe、图片 FD、快照内存、Os 文件描述符的收尾释放）。
 * <p>
 * 与业务异常处理严格区分：吞掉的是关闭动作，不是业务错误。任何带有诊断价值的
 * 失败（持久化、解析、owner 死亡、hook 热路径）DO NOT 经本类静默，必须走 Logger。
 * 调用处保持单行形态，禁止再写 {@code catch (...) ignored} 空块。
 */
public final class Closeables {

    private Closeables() {}

    /** 静默关闭 {@link Closeable}（含 ParcelFileDescriptor 的 Closeable 视角）。 */
    public static void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 预期关闭：释放动作本身失败无业务含义，静默是契约而非疏漏。
        }
    }

    /** 静默关闭 Binder 传递的图片 / pipe 描述符。 */
    public static void closeQuietly(@Nullable ParcelFileDescriptor descriptor) {
        closeQuietly((Closeable) descriptor);
    }

    /** 静默关闭只读快照内存（SharedMemory.close 不抛受检异常，仅做空守卫）。 */
    public static void closeQuietly(@Nullable SharedMemory memory) {
        if (memory != null) {
            memory.close();
        }
    }

    /** 静默关闭 Os 层文件描述符（ErrnoException 同属预期关闭语义）。 */
    public static void closeQuietly(@Nullable FileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            Os.close(descriptor);
        } catch (Exception ignored) {
            // 同上：收尾释放失败无业务含义。
        }
    }
}
