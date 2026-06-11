package com.kaisar.xposed.godmode.engine.rule;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;

import java.util.Arrays;

/**
 * 匹配规格 — 定义视图匹配所需的所有字段。
 * <p>
 * 从 {@link RuleMatchSpec} 拆分的纯匹配部分，只包含匹配器（IMatcher）需要的字段。
 * 不包含任何修改规则或原始值字段。
 * <p>
 * 由 {@link RuleMatchSpec#getMatchSpec()} 生成，或直接构造用于纯匹配场景。
 */
public final class MatchSpec {

    /** 深度路径 — 视图树中从 DecorView 到目标 View 的 childIndex 链 */
    public int[] depth;

    /** 目标 Activity 完整类名 */
    public String activityClass;

    /** 目标 View 完整类名 */
    public String viewClass;

    /** android:resourceName (R.id.xxx) */
    public String resourceName;

    /** RecyclerView item 路径（repeatable 规则） */
    public String[] itemPath;

    /** RecyclerView item 根 View 类名 */
    public String itemRootClass;

    /** 父 View 完整类名 */
    public String parentClass;

    /** 是否为可重复规则（在 RecyclerView/ListView 中匹配多个同类元素） */
    public boolean repeatable;

    /** TextView 文本内容 */
    public String text;

    /** contentDescription 无障碍描述 */
    public String description;

    /** 匹配模式（精确/包含/前缀/后缀/正则），null 等价于 EXACT */
    public MatchMode matchMode;

    /** 匹配阈值，0=使用系统默认值 */
    public int matchThreshold;

    public MatchSpec() {
    }

    /**
     * 从 RuleFields 提取匹配字段构造。
     * <p>
     * 对 repeatable 规则（itemPath 有效），清除 text/description 字段。
     * 信息流匹配依赖卡片内相对位置（itemPath），而非文本内容——每个卡片的
     * 文本内容唯一，参与匹配会阻止定位到其他卡片的同位置元素。</p>
     */
    public static MatchSpec from(RuleFields fields) {
        MatchSpec spec = new MatchSpec();
        spec.depth = fields.getDepth() != null ? fields.getDepth().clone() : null;
        spec.activityClass = fields.getActivityClass();
        spec.viewClass = fields.getViewClass();
        spec.resourceName = fields.getResourceName();
        spec.itemPath = fields.getItemPath() != null ? fields.getItemPath().clone() : null;
        spec.itemRootClass = fields.getItemRootClass();
        spec.parentClass = fields.getParentClass();
        spec.repeatable = fields.isRepeatable();
        // repeatable 规则：匹配只靠 itemPath 位置 + viewClass/parentClass 结构，
        // text/description 内容在卡片间唯一，参与匹配会破坏跨卡片匹配
        if (spec.repeatable && spec.itemPath != null && spec.itemPath.length > 0) {
            spec.text = null;
            spec.description = null;
        } else {
            spec.text = fields.getText();
            spec.description = fields.getDescription();
        }
        spec.matchMode = fields.getMatchMode();
        spec.matchThreshold = fields.getMatchThreshold();
        return spec;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MatchSpec matchSpec = (MatchSpec) o;
        if (repeatable != matchSpec.repeatable) return false;
        if (!Arrays.equals(depth, matchSpec.depth)) return false;
        if (!equalsNullable(activityClass, matchSpec.activityClass)) return false;
        if (!equalsNullable(viewClass, matchSpec.viewClass)) return false;
        if (!equalsNullable(resourceName, matchSpec.resourceName)) return false;
        if (!equalsNullable(text, matchSpec.text)) return false;
        if (!equalsNullable(description, matchSpec.description)) return false;
        if (repeatable) {
            return Arrays.equals(itemPath, matchSpec.itemPath)
                    && equalsNullable(itemRootClass, matchSpec.itemRootClass)
                       && equalsNullable(parentClass, matchSpec.parentClass);
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(depth);
        result = 31 * result + (activityClass != null ? activityClass.hashCode() : 0);
        result = 31 * result + (viewClass != null ? viewClass.hashCode() : 0);
        result = 31 * result + (resourceName != null ? resourceName.hashCode() : 0);
        result = 31 * result + (text != null ? text.hashCode() : 0);
        result = 31 * result + (description != null ? description.hashCode() : 0);
        result = 31 * result + Boolean.hashCode(repeatable);
        if (repeatable) {
            result = 31 * result + Arrays.hashCode(itemPath);
            result = 31 * result + (itemRootClass != null ? itemRootClass.hashCode() : 0);
            result = 31 * result + (parentClass != null ? parentClass.hashCode() : 0);
        }
        return result;
    }

    public MatchSpec clone() {
        MatchSpec cloned = new MatchSpec();
        cloned.depth = depth != null ? depth.clone() : null;
        cloned.activityClass = activityClass;
        cloned.viewClass = viewClass;
        cloned.resourceName = resourceName;
        cloned.itemPath = itemPath != null ? itemPath.clone() : null;
        cloned.itemRootClass = itemRootClass;
        cloned.parentClass = parentClass;
        cloned.repeatable = repeatable;
        cloned.text = text;
        cloned.description = description;
        cloned.matchMode = matchMode;
        cloned.matchThreshold = matchThreshold;
        return cloned;
    }

    private static boolean equalsNullable(Object a, Object b) {
        return (a == null) ? (b == null) : a.equals(b);
    }
}
