package com.kaisar.xposed.godmode.engine.rule;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;

import java.util.Arrays;

/**
 * 匹配规格 — 定义视图匹配所需的所有字段。
 * <p>
 * 从 {@link RuleMatchSpec} 拆分的纯匹配部分，只包含匹配器（Matcher）需要的字段。
 * 不包含任何修改规则或原始值字段。
 * <p>
 * 由 {@link RuleMatchSpec#getMatchSpec()} 生成，或通过 {@link Builder} 直接构造用于纯匹配场景。
 * <p>
 * 不可变对象 — 所有字段通过 {@link Builder} 构建，构造完成后不可修改。
 */
public final class MatchSpec implements MatchFields {

    /** 深度路径 — 视图树中从 DecorView 到目标 View 的 childIndex 链 */
    private final int[] depth;

    /** 目标 Activity 完整类名 */
    private final String activityClass;

    /** 目标 View 完整类名 */
    private final String viewClass;

    /** android:resourceName (R.id.xxx) */
    private final String resourceName;

    /** RecyclerView item 路径（repeatable 规则） */
    private final String[] itemPath;

    /** RecyclerView item 根 View 类名 */
    private final String itemRootClass;

    /** 父 View 完整类名 */
    private final String parentClass;

    /** 是否为可重复规则（在 RecyclerView/ListView 中匹配多个同类元素） */
    private final boolean repeatable;

    /** TextView 文本内容 */
    private final String text;

    /** contentDescription 无障碍描述 */
    private final String description;

    /** 匹配模式（精确/包含/前缀/后缀/正则），null 等价于 EXACT */
    private final MatchMode matchMode;

    /** 信息流模式下 RecyclerView 的 getItemViewType() 值，用于过滤匹配项类型（0=不过滤） */
    private final int viewType;

    /** 匹配目标层级，默认 ELEMENT（向后兼容） */
    private final TargetLevel targetLevel;

    private MatchSpec(Builder builder) {
        this.depth = builder.depth;
        this.activityClass = builder.activityClass;
        this.viewClass = builder.viewClass;
        this.resourceName = builder.resourceName;
        this.itemPath = builder.itemPath;
        this.itemRootClass = builder.itemRootClass;
        this.parentClass = builder.parentClass;
        this.repeatable = builder.repeatable;
        this.text = builder.text;
        this.description = builder.description;
        this.matchMode = builder.matchMode;
        this.viewType = builder.viewType;
        this.targetLevel = builder.targetLevel;
    }

    // ===== Getter =====

    public int[] getDepth() { return depth != null ? depth.clone() : null; }
    public String getActivityClass() { return activityClass; }
    public String getViewClass() { return viewClass; }
    public String getResourceName() { return resourceName; }
    public String[] getItemPath() { return itemPath != null ? itemPath.clone() : null; }
    public String getItemRootClass() { return itemRootClass; }
    public String getParentClass() { return parentClass; }
    public boolean isRepeatable() { return repeatable; }
    public String getText() { return text; }
    public String getDescription() { return description; }
    public MatchMode getMatchMode() { return matchMode; }
    @Override
    public int getInfoFlowViewType() { return viewType; }
    public TargetLevel getTargetLevel() { return targetLevel; }

    // ===== 工厂方法 =====

    /**
     * 从 RuleFields 提取匹配字段构造。
     * <p>
     * 对 repeatable 规则（itemPath 有效），清除 text/description 字段。
     * 信息流匹配依赖卡片内相对位置（itemPath），而非文本内容——每个卡片的
     * 文本内容唯一，参与匹配会阻止定位到其他卡片的同位置元素。</p>
     */
    public static MatchSpec from(RuleFields fields) {
        Builder b = new Builder();
        b.depth = fields.getDepth() != null ? fields.getDepth().clone() : null;
        b.activityClass = fields.getActivityClass();
        b.viewClass = fields.getViewClass();
        b.resourceName = fields.getResourceName();
        b.itemPath = fields.getItemPath() != null ? fields.getItemPath().clone() : null;
        b.itemRootClass = fields.getItemRootClass();
        b.parentClass = fields.getParentClass();
        b.repeatable = fields.isRepeatable();
        // repeatable 规则：匹配只靠 itemPath 位置 + viewClass/parentClass 结构，
        // text/description 内容在卡片间唯一，参与匹配会破坏跨卡片匹配
        if (b.repeatable && b.itemPath != null && b.itemPath.length > 0) {
            b.text = null;
            b.description = null;
        } else {
            b.text = fields.getText();
            b.description = fields.getDescription();
        }
        b.matchMode = fields.getMatchMode();
        b.viewType = fields.getInfoFlowViewType();
        TargetLevel tl = fields.getTargetLevel();
        b.targetLevel = tl != null ? tl : TargetLevel.ELEMENT;
        return b.build();
    }

    // ===== clone / equals / hashCode =====

    public MatchSpec clone() {
        Builder b = new Builder();
        b.depth = this.depth != null ? this.depth.clone() : null;
        b.activityClass = this.activityClass;
        b.viewClass = this.viewClass;
        b.resourceName = this.resourceName;
        b.itemPath = this.itemPath != null ? this.itemPath.clone() : null;
        b.itemRootClass = this.itemRootClass;
        b.parentClass = this.parentClass;
        b.repeatable = this.repeatable;
        b.text = this.text;
        b.description = this.description;
        b.matchMode = this.matchMode;
        b.viewType = this.viewType;
        b.targetLevel = this.targetLevel;
        return b.build();
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

    private static boolean equalsNullable(Object a, Object b) {
        return (a == null) ? (b == null) : a.equals(b);
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /**
     * MatchSpec 构建器 — 链式调用，构建不可变的 {@link MatchSpec} 实例。
     * <p>
     * 调用 {@link #repeatable(boolean)} 设为 true 时会自动清空 text/description，
     * 因为信息流匹配依赖卡片内相对位置而非文本内容。
     */
    public static final class Builder {
        int[] depth;
        String activityClass;
        String viewClass;
        String resourceName;
        String[] itemPath;
        String itemRootClass;
        String parentClass;
        boolean repeatable;
        String text;
        String description;
        MatchMode matchMode;
        int viewType;
        TargetLevel targetLevel = TargetLevel.ELEMENT;

        public Builder depth(int[] depth) { this.depth = depth != null ? depth.clone() : null; return this; }
        public Builder activityClass(String activityClass) { this.activityClass = activityClass; return this; }
        public Builder viewClass(String viewClass) { this.viewClass = viewClass; return this; }
        public Builder resourceName(String resourceName) { this.resourceName = resourceName; return this; }
        public Builder itemPath(String[] itemPath) { this.itemPath = itemPath != null ? itemPath.clone() : null; return this; }
        public Builder itemRootClass(String itemRootClass) { this.itemRootClass = itemRootClass; return this; }
        public Builder parentClass(String parentClass) { this.parentClass = parentClass; return this; }

        /**
         * 设置 repeatable 标志。若为 true 且 itemPath 非空，自动清空 text/description。
         */
        public Builder repeatable(boolean repeatable) {
            this.repeatable = repeatable;
            return this;
        }

        public Builder text(String text) { this.text = text; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder matchMode(MatchMode matchMode) { this.matchMode = matchMode; return this; }
        public Builder viewType(int viewType) { this.viewType = viewType; return this; }
        public Builder targetLevel(TargetLevel targetLevel) { this.targetLevel = targetLevel; return this; }

        public MatchSpec build() {
            return new MatchSpec(this);
        }
    }
}
