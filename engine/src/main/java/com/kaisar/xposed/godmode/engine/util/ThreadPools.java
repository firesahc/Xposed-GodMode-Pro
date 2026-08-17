package com.kaisar.xposed.godmode.engine.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一线程池管理。
 * 所有线程均为守护线程，避免阻止 JVM 退出。
 * <p>
 * 三个专用池：
 * <ul>
 *   <li>{@link #IMAGE_LOADER} — 图片加载/解码 I/O 操作</li>
 *   <li>{@link #FD_WRITER} — 单次 Binder mutate 的匿名 pipe 写入</li>
 *   <li>{@link #IO} — 通用文件 I/O 操作</li>
 *   <li>{@link #GENERAL} — 轻量计算任务</li>
 * </ul>
 */
public final class ThreadPools {

    private ThreadPools() {
    }

    /** 图片加载线程池 — 适用于 Glide 回调、Bitmap 解码等 */
    public static final ExecutorService IMAGE_LOADER = Executors.newFixedThreadPool(
            2, new DaemonThreadFactory("GM-ImageLoader"));

    /** FD 写入池固定为两个线程，保证主图和修改图可以同时向 pipe 写入。 */
    public static final ExecutorService FD_WRITER = Executors.newFixedThreadPool(
            2, new DaemonThreadFactory("GM-FD-Writer"));

    /** I/O 线程池 — 适用于文件读写、JSON 序列化、规则持久化等 */
    public static final ExecutorService IO = Executors.newFixedThreadPool(
            2, new DaemonThreadFactory("GM-IO"));

    /** 通用线程池 — 适用于轻量计算、匹配遍历等（有界队列，最大线程数=CPU*2） */
    public static final ExecutorService GENERAL = new ThreadPoolExecutor(
            0, Runtime.getRuntime().availableProcessors() * 2,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(256),
            new DaemonThreadFactory("GM-General"));

    /**
     * 守护线程工厂。
     */
    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        DaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + "-" + threadNumber.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }
}
