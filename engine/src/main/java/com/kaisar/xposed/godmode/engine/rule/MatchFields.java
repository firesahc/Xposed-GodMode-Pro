package com.kaisar.xposed.godmode.engine.rule;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;

/**
 * 匹配字段契约 — 限定 Matcher 能访问的字段，从 {@link RuleFields} 中提取。
 * <p>
 * 实现类：{@link MatchSpec}（纯匹配）、{@link RuleFields}（全字段）。
 * <p>
 * 【编译期安全】Matcher 的方法参数限制为此接口，编译期无法访问 mod / orig 等动作字段。
 *
 * @see RuleFields
 * @see MatchSpec
 */
public interface MatchFields {

    /** 深度路径 — 视图树中从 DecorView 到目标 View 的 childIndex 链 */
    int[] getDepth();

    /** 目标 Activity 完整类名 */
    String getActivityClass();

    /** 目标 View 完整类名 */
    String getViewClass();

    /** android:resourceName (R.id.xxx) */
    String getResourceName();

    /** RecyclerView item 路径（repeatable 规则） */
    String[] getItemPath();

    /** RecyclerView item 根 View 类名 */
    String getItemRootClass();

    /** 父 View 完整类名 */
    String getParentClass();

    /** 是否为可重复规则（在 RecyclerView/ListView 中匹配多个同类元素） */
    boolean isRepeatable();

    /** TextView 文本内容 */
    String getText();

    /** contentDescription 无障碍描述 */
    String getDescription();

    /** 匹配模式（精确/包含/前缀/后缀/正则），null 等价于 EXACT */
    MatchMode getMatchMode();

    /** 信息流模式下 RecyclerView 的 getItemViewType() 值，用于过滤匹配项类型（0=不过滤） */
    int getInfoFlowViewType();

    /** 匹配目标层级，null 等价于 ELEMENT（向后兼容） */
    TargetLevel getTargetLevel();

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
    default boolean isSameViewAs(MatchFields other) {
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
     */
    static boolean nullableEquals(Object a, Object b) {
        return (a == null) ? (b == null) : a.equals(b);
    }
}
