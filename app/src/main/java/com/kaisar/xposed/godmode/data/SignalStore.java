package com.kaisar.xposed.godmode.data;

import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXG;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXO;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXU;

import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.engine.util.Logger;

import java.io.File;
import java.io.IOException;

/**
 * 文件信号 — 轻量级跨进程通知机制。
 * <p>
 * 原理：touch 一个空文件表示"某事发生了"，消费者通过 {@link android.os.FileObserver}
 * 或轮询检测文件存在。比 Binder 重但更可靠——Binder 断连不会影响信号文件的创建和检测。
 * <p>
 * 【重复触发保障】同一信号名连续调用 {@link #signal(String)} 时，会更新文件的时间戳
 * 或重写内容，确保 {@code FileObserver} 能再次收到 MODIFY/CLOSE_WRITE/ATTRIB 等事件。
 * <p>
 * 【关键约束】此类不依赖 Binder，不 import {@code ipc/}、{@code control/}、{@code inject/} 包。
 */
public final class SignalStore {

    private static final String TAG = "SignalStore";

    private static volatile SignalStore sInstance;

    /** 信号文件基目录 */
    private final File mSignalDir;

    // ===== 单例 =====

    private SignalStore() {
        this.mSignalDir = new File(DataBusConstants.SIGNAL_DIR);
    }

    public static SignalStore getDefault() {
        if (sInstance == null) {
            synchronized (SignalStore.class) {
                if (sInstance == null) {
                    sInstance = new SignalStore();
                }
            }
        }
        return sInstance;
    }

    /**
     * 供测试或自定义目录使用。
     */
    SignalStore(File signalDir) {
        this.mSignalDir = signalDir;
    }

    // ===== 核心 API =====

    /**
     * 发出信号（touch 空文件或更新时间戳）。
     * <p>
     * 文件不存在时创建新文件；已存在时更新其最后修改时间，
     * 确保 FileObserver 能收到 ATTRIB 或 MODIFY 事件。
     *
     * @param signalName 信号名（如 {@code "rule_changed:com.example.app"}）
     */
    public void signal(String signalName) {
        if (signalName == null || signalName.isEmpty()) {
            Logger.w(TAG, "signal skipped: null or empty signalName");
            return;
        }
        try {
            if (!mSignalDir.exists() && !mSignalDir.mkdirs()) {
                Logger.e(TAG, "signal: failed to create signal dir: " + mSignalDir);
                return;
            }
            FileUtils.setPermissions(mSignalDir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);

            File signalFile = new File(mSignalDir, signalName);
            if (signalFile.exists()) {
                // 已存在：更新时间戳以触发 FileObserver 的 ATTRIB 或 MODIFY 事件
                if (!signalFile.setLastModified(System.currentTimeMillis())) {
                    // setLastModified 失败时，重写文件内容确保有实质变更
                    touchWithContent(signalFile);
                }
            } else {
                // 创建新文件
                if (!signalFile.createNewFile()) {
                    Logger.w(TAG, "signal: createNewFile returned false for " + signalName);
                }
            }
            FileUtils.setPermissions(signalFile, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
        } catch (Exception e) {
            Logger.w(TAG, "signal failed for " + signalName, e);
        }
    }

    /**
     * 检查信号是否存在。
     *
     * @param signalName 信号名
     * @return true 如果信号文件存在
     */
    public boolean isSignaled(String signalName) {
        if (signalName == null) return false;
        return new File(mSignalDir, signalName).exists();
    }

    /**
     * 消费信号（检查并删除）。
     * <p>
     * 用于轮询路径——检查信号文件存在并删除，返回是否消费成功。
     *
     * @param signalName 信号名
     * @return true 如果信号存在并被成功删除
     */
    public boolean consume(String signalName) {
        if (signalName == null) return false;
        File signalFile = new File(mSignalDir, signalName);
        if (!signalFile.exists()) return false;
        return signalFile.delete();
    }

    /**
     * 清除指定信号（不检查是否存在直接删除）。
     *
     * @param signalName 信号名
     */
    public void clear(String signalName) {
        if (signalName == null) return;
        File signalFile = new File(mSignalDir, signalName);
        if (signalFile.exists() && !signalFile.delete()) {
            Logger.w(TAG, "clear: failed to delete " + signalName);
        }
    }

    // ===== 内部辅助 =====

    /**
     * 通过覆写内容来触发文件变更事件（用于已存在文件且 setLastModified 失败时）。
     */
    private static void touchWithContent(File file) throws IOException {
        FileUtils.stringToFile(file, "" + System.currentTimeMillis());
    }
}
