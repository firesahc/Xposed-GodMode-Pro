package com.kaisar.xposed.godmode;

import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;

interface IGodModeManager {

    boolean hasLight();

    void setEditMode(boolean enable);

    boolean isInEditMode();

    /**
     * 注册规则变更观察者。
     * <p>
     * packageName 为 "*" 时表示通配符观察者，对所有应用的规则变更都会收到回调。
     * 普通观察者（非 "*"）只接收指定包的规则变更通知。
     * <p>
     * 同一 packageName + observer 对重复注册是安全的（幂等）。
     *
     * @param packageName 应用包名，或 "*" 表示监听所有应用
     * @param observer    规则变更回调，onViewRuleChanged 为全量替换语义
     */
    void addObserver(String packageName, in IObserver observer);

    void removeObserver(String packageName, in IObserver observer);

    AppRules getAllRules();

    ActRules getRules(String packageName);

    /**
     * 写入（新增）一条规则。
     * <p>
     * 传输限制：bitmap 参数建议控制在 1MB 以内（通过 ParcelFileDescriptor 传输的
     * 文件无此限制，应优先使用 saveImageFile + writeRule 分离模式）。
     *
     * @param packageName 目标应用包名
     * @param viewRule    待写入的规则记录
     * @param bitmap      规则截图（可为 null）。bitmap 较大时建议先调用 saveImageFile
     *                    保存，然后通过 imagePath 字段引用
     */
    boolean writeRule(String packageName, in RuleRecord viewRule, in Bitmap bitmap);

    /**
     * 更新已有规则。
     * <p>
     * 传输限制：RuleRecord 作为 Parcel 传输时建议控制在 1MB 以内。
     * 大尺寸字段（如 imagePath 指向的文件数据）应通过 ParcelFileDescriptor 另行传输。
     */
    boolean updateRule(String packageName, in RuleRecord viewRule);

    boolean deleteRule(String packageName, in RuleRecord viewRule);

    boolean deleteRules(String packageName);

    ParcelFileDescriptor openImageFileDescriptor(String filePath);

    String saveImageFile(String packageName, in Bitmap bitmap);

    String getToolbarHiddenItems();

    void setToolbarHiddenItems(String items);

    /**
     * 应用进程通过 IPC 向 system_server 转发日志。
     * system_server 会将日志统一写入 godmodepro.log，
     * 格式为 "[packageName] level/tag: msg"。
     *
     * @param level       日志级别（参见 android.util.Log: DEBUG=3, INFO=4, WARN=5, ERROR=6）
     * @param packageName 日志来源应用包名
     * @param timestamp   日志产生时间戳（毫秒）
     * @param tag         日志标签
     * @param msg         日志消息
     */
    void log(int level, String packageName, long timestamp, String tag, String msg);
}
