package com.kaisar.xposed.godmode.engine.rule;

import com.kaisar.xposed.godmode.engine.util.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 版本化规则快照 — 包装规则集合加上元数据，使快照可追溯、可校验。
 * <p>
 * 此类型当前保留作为规则集合的通用包装结构，
 * 文件快照链路（data/ 包）已移除，不再作为 Binder 降级数据源。
 * <p>
 * 不可变对象 — 通过 {@link Builder} 构建。
 * <p>
 * 序列化格式 (JSON):
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "generation": 42,
 *   "createdAt": 1712345678000,
 *   "publisher": "system_server:12345",
 *   "packageName": "com.example.app",
 *   "payload": {
 *     "com.example.MainActivity": [ { "ruleTag": "...", ... } ]
 *   }
 * }
 * </pre>
 */
public final class RuleSnapshot {

    private static final String TAG = "RuleSnapshot";

    /** 当前快照格式版本。schema 不兼容变更时 +1，兼容变更无需修改。 */
    public static final int CURRENT_VERSION = 1;
    private static final String DEFAULT_PUBLISHER = "unknown";

    // ===== JSON 键名 =====
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_GENERATION = "generation";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_PUBLISHER = "publisher";
    private static final String KEY_PACKAGE_NAME = "packageName";
    private static final String KEY_PAYLOAD = "payload";

    /** 快照格式版本，升级时 +1 */
    public final int schemaVersion;

    /** 单调递增 generation 号，防止回退 */
    public final long generation;

    /** 创建时间戳（{@link System#currentTimeMillis()}） */
    public final long createdAt;

    /** 发布者标识，例如 {@code "system_server:12345"} */
    public final String publisher;

    /** 快照所属包名 */
    public final String packageName;

    /**
     * 规则负载 — activity 类名 → 规则条目列表。
     * <p>
     * 实际类型为 {@code Map<String, List<Map<String, Object>>>}，
     * 但出于引擎层类型安全声明为 {@code Map<String, ?>}。
     * 消费方在读取时自行转型。
     */
    public final Map<String, ?> payload;

    private RuleSnapshot(int schemaVersion, long generation, long createdAt,
                         String publisher, String packageName, Map<String, ?> payload) {
        this.schemaVersion = schemaVersion;
        this.generation = generation;
        this.createdAt = createdAt;
        this.publisher = publisher;
        this.packageName = packageName;
        this.payload = payload != null
                ? Collections.unmodifiableMap(new HashMap<>(payload))
                : Collections.emptyMap();
    }

    // ===== Builder =====

    public static final class Builder {
        private int schemaVersion = CURRENT_VERSION;
        private long generation;
        private long createdAt;
        private String publisher;
        private String packageName;
        private Map<String, ?> payload;

        public Builder schemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        public Builder generation(long generation) {
            this.generation = generation;
            return this;
        }

        public Builder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder publisher(String publisher) {
            this.publisher = publisher;
            return this;
        }

        public Builder packageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public Builder payload(Map<String, ?> payload) {
            this.payload = payload;
            return this;
        }

        public RuleSnapshot build() {
            return new RuleSnapshot(
                    schemaVersion,
                    generation,
                    createdAt != 0 ? createdAt : System.currentTimeMillis(),
                    publisher,
                    packageName,
                    payload
            );
        }
    }

    // ===== 工厂方法 =====

    /**
     * 创建指定包的快照。
     *
     * @param packageName 包名
     * @param rules       规则集合（Map<String, List<RuleRecord>> 转型而来）
     * @return 新快照实例
     */
    public static RuleSnapshot create(String packageName, Map<String, ?> rules) {
        return create(packageName, rules, System.currentTimeMillis(), DEFAULT_PUBLISHER);
    }

    /**
     * 创建指定包的快照。
     *
     * @param packageName 包名
     * @param rules       规则集合
     * @param generation  单调递增 generation
     * @param publisher   发布者标识
     * @return 新快照实例
     */
    public static RuleSnapshot create(String packageName, Map<String, ?> rules,
                                      long generation, String publisher) {
        long now = System.currentTimeMillis();
        return new Builder()
                .generation(generation)
                .createdAt(now)
                .publisher(publisher != null && !publisher.isEmpty()
                        ? publisher : DEFAULT_PUBLISHER)
                .packageName(packageName)
                .payload(rules)
                .build();
    }

    // ===== 校验 =====

    /**
     * 校验快照的 schemaVersion 和 generation 是否有效。
     *
     * @return true 如果快照有效且可消费
     */
    public boolean validate() {
        if (schemaVersion != CURRENT_VERSION) {
            Logger.w(TAG, "validate failed: schemaVersion=" + schemaVersion
                    + " expected=" + CURRENT_VERSION);
            return false;
        }
        if (generation <= 0) {
            Logger.w(TAG, "validate failed: invalid generation=" + generation);
            return false;
        }
        if (packageName == null || packageName.isEmpty()) {
            Logger.w(TAG, "validate failed: missing packageName");
            return false;
        }
        return true;
    }

    /**
     * 比较两个快照的 generation，判断当前快照是否更新。
     *
     * @param other 另一个快照
     * @return true 如果当前快照 generation 更大
     */
    public boolean isNewerThan(RuleSnapshot other) {
        return other == null || this.generation > other.generation;
    }

    // ===== JSON 序列化 =====

    /**
     * 序列化为 JSON 字符串。
     *
     * @return JSON 表示
     */
    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put(KEY_SCHEMA_VERSION, schemaVersion);
            obj.put(KEY_GENERATION, generation);
            obj.put(KEY_CREATED_AT, createdAt);
            obj.put(KEY_PUBLISHER, publisher);
            obj.put(KEY_PACKAGE_NAME, packageName);
            obj.put(KEY_PAYLOAD, toJsonPayload(payload));
            return obj.toString(2);
        } catch (Exception e) {
            Logger.e(TAG, "toJson failed", e);
            return "{}";
        }
    }

    /**
     * 从 JSON 字符串反序列化。
     *
     * @param json JSON 字符串
     * @return 反序列化的快照，解析失败返回 null
     */
    @SuppressWarnings("unchecked")
    public static RuleSnapshot fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(json);
            int schemaVersion = obj.optInt(KEY_SCHEMA_VERSION, CURRENT_VERSION);
            long generation = obj.optLong(KEY_GENERATION, 0);
            long createdAt = obj.optLong(KEY_CREATED_AT, 0);
            String publisher = obj.optString(KEY_PUBLISHER, null);
            String packageName = obj.optString(KEY_PACKAGE_NAME, null);
            Map<String, Object> payload = fromJsonPayload(obj.optJSONObject(KEY_PAYLOAD));

            return new RuleSnapshot(schemaVersion, generation, createdAt,
                    publisher, packageName, payload);
        } catch (Exception e) {
            Logger.e(TAG, "fromJson failed", e);
            return null;
        }
    }

    // ===== JSON 内部转换 =====

    /**
     * 将 payload（Map<String, Map<String, Object> | List<...>>）转换为 JSONObject。
     */
    @SuppressWarnings("unchecked")
    private static Object toJsonValue(Object value) {
        if (value == null) return JSONObject.NULL;
        if (value instanceof Map) {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, ?> entry : ((Map<String, ?>) value).entrySet()) {
                try {
                    obj.put(entry.getKey(), toJsonValue(entry.getValue()));
                } catch (Exception ignored) {}
            }
            return obj;
        }
        if (value instanceof List) {
            JSONArray arr = new JSONArray();
            for (Object item : (List<?>) value) {
                arr.put(toJsonValue(item));
            }
            return arr;
        }
        // 基础类型 (String, Number, Boolean) 直接返回
        return value;
    }

    private static Object toJsonPayload(Map<String, ?> payload) {
        if (payload == null) return JSONObject.NULL;
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, ?> entry : payload.entrySet()) {
            try {
                obj.put(entry.getKey(), toJsonValue(entry.getValue()));
            } catch (Exception ignored) {}
        }
        return obj;
    }

    /**
     * 将 JSONObject/JSONArray 递归转换为 Map/List。
     */
    private static Object fromJsonValue(Object value) {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            Map<String, Object> map = new HashMap<>();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, fromJsonValue(obj.opt(key)));
            }
            return map;
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            List<Object> list = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                list.add(fromJsonValue(arr.opt(i)));
            }
            return list;
        }
        return value; // String, Number, Boolean
    }

    private static Map<String, Object> fromJsonPayload(JSONObject obj) {
        if (obj == null) return new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = fromJsonValue(obj.opt(key));
            if (value instanceof List) {
                result.put(key, value);
            }
        }
        return result;
    }

    // ===== equals / hashCode / toString =====

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleSnapshot that = (RuleSnapshot) o;
        if (schemaVersion != that.schemaVersion) return false;
        if (generation != that.generation) return false;
        if (packageName != null ? !packageName.equals(that.packageName) : that.packageName != null)
            return false;
        return payload != null ? payload.equals(that.payload) : that.payload == null;
    }

    @Override
    public int hashCode() {
        int result = schemaVersion;
        result = 31 * result + (int) (generation ^ (generation >>> 32));
        result = 31 * result + (packageName != null ? packageName.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "RuleSnapshot{"
                + "schemaVersion=" + schemaVersion
                + ", generation=" + generation
                + ", createdAt=" + createdAt
                + ", publisher='" + publisher + '\''
                + ", packageName='" + packageName + '\''
                + ", payload.size=" + (payload != null ? payload.size() : 0)
                + '}';
    }
}
