package com.kaisar.xposed.godmode.rule;

import com.kaisar.xposed.godmode.engine.util.Logger;

import androidx.annotation.Keep;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by jrsen on 17-10-14.
 * <p>
 * Wire 说明：本类是内存 Map 容器，wire 格式只有扁平 JSON（见
 * {@code RuleRecordTypeAdapter}），故意不实现 Parcelable——6.10 跨进程读写
 * 全走只读 SharedMemory 快照 + JSON，不再有 AppRules / ActRules 的 parcel 通道
 *（旧 AIDL 已删除）。DO NOT 加回 Parcelable 实现。
 */
@Keep
public final class ActRules extends ConcurrentHashMap<String, List<RuleRecord>> {

    private static final String TAG = "ActRules";

    public ActRules() {
    }

    public ActRules(int initialCapacity) {
        super(initialCapacity);
    }

    // =========================================================================
    // 防御性 null 过滤 — ConcurrentHashMap 禁止 null key/value
    // Gson MapTypeAdapterFactory 在反序列化 JSON 每项时直接调用 put()，
    // HashMap 可以容忍 null 但 ConcurrentHashMap 会抛 NPE。
    // 同时覆盖 putAll() 因为 ConcurrentHashMap.putAll() 内部调用 putVal() 绕过了 put()。
    // =========================================================================

    @Override
    public List<RuleRecord> put(String key, List<RuleRecord> value) {
        if (key == null || value == null) {
            Logger.w(TAG, "Skipping null entry in put()");
            return null;
        }
        return super.put(key, value);
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<RuleRecord>> m) {
        for (Map.Entry<? extends String, ? extends List<RuleRecord>> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

}
