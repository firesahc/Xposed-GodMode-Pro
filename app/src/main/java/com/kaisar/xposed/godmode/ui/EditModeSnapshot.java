package com.kaisar.xposed.godmode.ui;

import android.content.Context;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;

/**
 * 编辑态单快照 — 同一 tick 内一次抓取的只读组合视图。
 * <p>
 * 底层有两个独立事实：{@code master}（用户在设置里打开的总开关，本地 pref，
 * 表意图）与租约三元组 ready / known / closing / enabled（system_server 权威，
 * 表能力）。历史教训是各入口（QS 磁贴、通知栏、设置页）各自手拼组合条件，
 * 同一时刻读到互相矛盾的编辑态。本类是唯一的派生逻辑归属：
 * {@link #available()} 与 {@link #active()} 只在这里定义，调用方只读快照。
 * <p>
 * 诚实说明：快照内各字段是同一次调用内连续抓取，不是 Binder 原子事务；
 * 仍 strictly 优于之前“三处三个时刻各读一次”。租约语义本身不动。
 */
public final class EditModeSnapshot {

    private final boolean master;
    private final boolean ready;
    private final boolean known;
    private final boolean closing;
    private final boolean enabled;

    public EditModeSnapshot(boolean master, boolean ready, boolean known,
                            boolean closing, boolean enabled) {
        this.master = master;
        this.ready = ready;
        this.known = known;
        this.closing = closing;
        this.enabled = enabled;
    }

    /** 管理端默认取本应用 master 开关的便捷抓取。 */
    public static EditModeSnapshot capture(Context context) {
        return capture(context, R.string.pref_key_master);
    }

    /** 一次抓取全部底层事实；调用方不得再自行组合散读。 */
    public static EditModeSnapshot capture(Context context, int prefKeyMasterResId) {
        RuleServiceClient client = RuleServiceClient.getDefault();
        return new EditModeSnapshot(
                EditModeController.isMasterEnabled(context, prefKeyMasterResId),
                client.hasReadyConnection(),
                client.isEditStateKnown(),
                client.isEditModeClosing(),
                client.isEditModeEnabled());
    }

    public boolean master() { return master; }
    public boolean ready() { return ready; }
    public boolean known() { return known; }
    public boolean closing() { return closing; }
    public boolean enabled() { return enabled; }

    /** 界面可用：意图开 + 连接就绪 + 状态已知 + 非关闭中。 */
    public boolean available() {
        return master && ready && known && !closing;
    }

    /** 激活态：可用且租约 enabled。磁贴 ACTIVE / 通知 exit 文案以此为准。 */
    public boolean active() {
        return available() && enabled;
    }
}
