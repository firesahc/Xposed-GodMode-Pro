package com.kaisar.xposed.godmode.injection.util;

import com.kaisar.xposed.godmode.engine.util.ThreadPools;

/**
 * 搴旂敤灞備换鍔℃墽琛屽櫒 鈥?灏佽 {@link ThreadPools}锛屾彁渚涚粺涓€鐨勫紓姝ヤ换鍔″叆鍙ｃ€? * <p>
 * 鎵€鏈夊紓姝?I/O銆佸浘鐗囧姞杞姐€佸悗鍙拌绠楀潎閫氳繃姝ら棬闈㈡彁浜わ紝
 * 閬垮厤鐩存帴鎿嶄綔绾跨▼鎴栧垎鏁ｇ殑 ExecutorService 寮曠敤銆? * <p>
 * 绾跨▼妯″瀷濮旀墭鑷?engine 灞傜殑 {@link ThreadPools}锛? * <ul>
 *   <li>{@link #IO} 鈥?鏂囦欢璇诲啓銆佽鍒欐寔涔呭寲绛?I/O 瀵嗛泦鍨嬩换鍔?/li>
 *   <li>{@link #IMAGE_LOADER} 鈥?鍥剧墖瑙ｇ爜銆丅itmap 澶勭悊</li>
 *   <li>{@link #GENERAL} 鈥?杞婚噺璁＄畻銆佸尮閰嶉亶鍘?/li>
 * </ul>
 */
public final class TaskExecutor {

    private TaskExecutor() {
    }

    /** I/O 绾跨▼姹?鈥?鏂囦欢璇诲啓銆丣SON 搴忓垪鍖栥€佽鍒欐寔涔呭寲绛夈€?*/
    public static void executeIo(Runnable task) {
        ThreadPools.IO.execute(task);
    }

    /** 鍥剧墖鍔犺浇绾跨▼姹?鈥?Bitmap 瑙ｇ爜銆佸浘鐗?I/O 绛夈€?*/
    public static void executeImageLoad(Runnable task) {
        ThreadPools.IMAGE_LOADER.execute(task);
    }

    /** 閫氱敤绾跨▼姹?鈥?杞婚噺璁＄畻銆佽鍥鹃亶鍘嗙瓑銆?*/
    public static void executeGeneral(Runnable task) {
        ThreadPools.GENERAL.execute(task);
    }
}
