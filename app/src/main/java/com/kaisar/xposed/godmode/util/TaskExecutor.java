package com.kaisar.xposed.godmode.util;

import com.kaisar.xposed.godmode.engine.util.ThreadPools;

/**
 * 应用层任务执行器：封装 {@link ThreadPools}，提供统一的异步任务入口。
 * <p>
 * 所有异步 I/O、图片加载、后台计算均通过此门面提交，
 * 避免直接操作线程或分散引用 ExecutorService。
 * <p>
 * 线程模型委托给 engine 层的 {@link ThreadPools}：
 * <ul>
 *   <li>{@link #IO}：文件读写、规则持久化等 I/O 密集型任务</li>
 *   <li>{@link #IMAGE_LOADER}：图片解码、Bitmap 处理</li>
 *   <li>{@link #GENERAL}：轻量计算、视图遍历等</li>
 * </ul>
 */
public final class TaskExecutor {

    private TaskExecutor() {
    }

    /** I/O 线程池：文件读写、JSON 序列化、规则持久化等。 */
    public static void executeIo(Runnable task) {
        ThreadPools.IO.execute(task);
    }

    /** 图片加载线程池：Bitmap 解码、图片 I/O 等。 */
    public static void executeImageLoad(Runnable task) {
        ThreadPools.IMAGE_LOADER.execute(task);
    }

    /** 通用线程池：轻量计算、视图遍历等。 */
    public static void executeGeneral(Runnable task) {
        ThreadPools.GENERAL.execute(task);
    }
}
