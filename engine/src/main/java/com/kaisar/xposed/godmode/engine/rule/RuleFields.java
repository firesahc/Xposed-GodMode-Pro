package com.kaisar.xposed.godmode.engine.rule;

/**
 * 字段契约接口 — 定义 RuleRecord/RuleMatchSpec 的全部字段的 getter。
 * <p>
 * 实现类：{@link RuleMatchSpec}（引擎模块）、
     * {@link com.kaisar.xposed.godmode.rule.RuleRecord}（app 模块）
 * <p>
 * 【编译期安全】新增字段时，必须在此接口中添加 getter → 所有实现类编译报错。
 */
public interface RuleFields {

    // ===== 规则标识 =====
    String getRuleTag();

    // ===== 移除规则字段 =====
    String getLabel();
    String getPackageName();
    String getMatchVersionName();
    int getMatchVersionCode();
    int getVersionCode();
    String getImagePath();
    String getAlias();
    int getX();
    int getY();
    int getWidth();
    int getHeight();
    int[] getDepth();
    String getActivityClass();
    String getViewClass();
    String getResourceName();
    String[] getItemPath();
    String getItemRootClass();
    String getParentClass();
    boolean isRepeatable();
    String getText();
    String getDescription();
    int getVisibility();
    long getTimestamp();

    // ===== 修改规则字段 =====
    int getModWidth();
    int getModHeight();
    float getModAlpha();
    int getModXOffset();
    int getModYOffset();
    String getModText();
    String getModImagePath();

    // ===== 原始值（用于撤销修改） =====
    int getOrigWidth();
    int getOrigHeight();
    float getOrigAlpha();
    String getOrigText();
    int getOrigLeftMargin();
    int getOrigTopMargin();

    /**
     * 判断此规则是否与另一规则定位到同一个 View。
     * <p>
     * 替代 app 模块中 {@code RuleRecord.equals()} 的窄匹配语义，
     * 用于集合查找（indexOf / remove / contains）场景。
     * 不比较修改规则字段、时间戳等业务无关属性。
     * <p>
     * 行为与 {@code RuleMatchSpec.equals()} 的深比较不同，
     * 仅匹配「定位身份」——activityClass + viewClass + depth（或 itemPath）。
     *
     * @param other 另一规则，允许为 null
     * @return 如果两个规则定位到同一个 View 则返回 true
     */
    default boolean isSameViewAs(RuleFields other) {
        if (other == null) return false;
        if (!nullableEquals(getActivityClass(), other.getActivityClass())) return false;
        if (!nullableEquals(getViewClass(), other.getViewClass())) return false;
        if (isRepeatable() && other.isRepeatable()) {
            return java.util.Arrays.equals(getItemPath(), other.getItemPath());
        }
        return java.util.Arrays.equals(getDepth(), other.getDepth());
    }

    /**
     * null-safe 相等比较。
     *
     * @param a 第一个对象，允许为 null
     * @param b 第二个对象，允许为 null
     * @return 如果两个对象均为 null 或 {@code a.equals(b)} 返回 true
     */
    static boolean nullableEquals(Object a, Object b) {
        return (a == null) ? (b == null) : a.equals(b);
    }
}
