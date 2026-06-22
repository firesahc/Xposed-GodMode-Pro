package com.kaisar.xposed.godmode.rule;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.Keep;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by jrsen on 17-10-14.
 */
@Keep
public final class ActRules extends ConcurrentHashMap<String, List<RuleRecord>> implements Parcelable {

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
            Log.w(TAG, "Skipping null entry in put()");
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

    protected ActRules(Parcel in) {
        HashMap<String, List<RuleRecord>> temp = new HashMap<>();
        in.readMap(temp, ActRules.class.getClassLoader());
        for (Map.Entry<String, List<RuleRecord>> entry : temp.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                super.put(entry.getKey(), entry.getValue());
            } else {
                Log.w(TAG, "Skipping null entry during Parcel deserialization");
            }
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeMap(this);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ActRules> CREATOR = new Creator<ActRules>() {
        @Override
        public ActRules createFromParcel(Parcel in) {
            return new ActRules(in);
        }

        @Override
        public ActRules[] newArray(int size) {
            return new ActRules[size];
        }
    };

}
