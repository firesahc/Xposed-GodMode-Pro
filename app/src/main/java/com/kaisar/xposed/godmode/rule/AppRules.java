package com.kaisar.xposed.godmode.rule;

import androidx.annotation.Keep;

import java.util.HashMap;

/**
 * Created by jrsen on 17-10-14.
 * <p>
 * Wire 说明：本类是内存 Map 容器，wire 格式只有扁平 JSON（见
 * {@code RuleRecordTypeAdapter}），故意不实现 Parcelable——6.10 跨进程读写
 * 全走只读 SharedMemory 快照 + JSON，不再有 AppRules / ActRules 的 parcel 通道
 *（旧 AIDL 已删除）。DO NOT 加回 Parcelable 实现。
 */
@Keep
public final class AppRules extends HashMap<String, ActRules> {

    public AppRules() {
    }
}
