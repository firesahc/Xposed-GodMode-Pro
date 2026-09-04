package com.kaisar.xposed.godmode.engine.rule;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;

import java.util.Arrays;
import java.util.Objects;

/**
 * 匹配规格 — 定义视图匹配所需的所有字段。
 * <p>
 * 规则的持久化匹配组成，只包含匹配器（Matcher）需要的字段。
 * 不包含任何修改规则或原始值字段。
 * <p>
 * 通过 {@link Builder} 构造用于匹配和持久化场景。
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
        this.depth = copy(builder.depth);
        this.activityClass = builder.activityClass;
        this.viewClass = builder.viewClass;
        this.resourceName = builder.resourceName;
        this.itemPath = copy(builder.itemPath);
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
     * 从匹配字段构造无损规格。
     * <p>该方法保留原始 text/description 和 nullable enum 值。信息流运行时忽略
     * 文本的规则只属于匹配语义，不能在持久化组件构造阶段破坏原始数据。</p>
     */
    public static MatchSpec from(MatchFields fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        Builder b = new Builder();
        b.depth = copy(fields.getDepth());
        b.activityClass = fields.getActivityClass();
        b.viewClass = fields.getViewClass();
        b.resourceName = fields.getResourceName();
        b.itemPath = copy(fields.getItemPath());
        b.itemRootClass = fields.getItemRootClass();
        b.parentClass = fields.getParentClass();
        b.repeatable = fields.isRepeatable();
        b.text = fields.getText();
        b.description = fields.getDescription();
        b.matchMode = fields.getMatchMode();
        b.viewType = fields.getInfoFlowViewType();
        b.targetLevel = fields.getTargetLevel();
        return b.build();
    }

    // ===== raw value / runtime semantics =====

    public MatchSpec clone() {
        return toBuilder().build();
    }

    /** Creates a raw-value builder without applying runtime normalization. */
    public Builder toBuilder() {
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
        return b;
    }

    /**
     * 是否为具有有效 itemPath 的信息流匹配规格。
     */
    public boolean hasRepeatableLocator() {
        return repeatable && itemPath != null && itemPath.length > 0;
    }

    /**
     * 比较真正会改变 Matcher 行为的有效语义。
     * <p>
     * 有效 repeatable 规格不使用 text/description；null MatchMode 与 EXACT、
     * null TargetLevel 与 ELEMENT 在现有 Matcher 中语义等价。其他匹配字段仍
     * 保守参与比较，确保 matcher 输入变化能够触发重评估。
     */
    public boolean hasSameRuntimeSemantics(MatchSpec other) {
        if (other == null) return false;
        return Arrays.equals(depth, other.depth)
                && Objects.equals(activityClass, other.activityClass)
                && Objects.equals(viewClass, other.viewClass)
                && Objects.equals(resourceName, other.resourceName)
                && Arrays.equals(itemPath, other.itemPath)
                && Objects.equals(itemRootClass, other.itemRootClass)
                && Objects.equals(parentClass, other.parentClass)
                && repeatable == other.repeatable
                && Objects.equals(effectiveText(), other.effectiveText())
                && Objects.equals(effectiveDescription(), other.effectiveDescription())
                && effectiveMatchMode() == other.effectiveMatchMode()
                && viewType == other.viewType
                && effectiveTargetLevel() == other.effectiveTargetLevel();
    }

    /** Hash counterpart of {@link #hasSameRuntimeSemantics(MatchSpec)}. */
    public int runtimeSemanticsHashCode() {
        int result = Arrays.hashCode(depth);
        result = 31 * result + Objects.hashCode(activityClass);
        result = 31 * result + Objects.hashCode(viewClass);
        result = 31 * result + Objects.hashCode(resourceName);
        result = 31 * result + Arrays.hashCode(itemPath);
        result = 31 * result + Objects.hashCode(itemRootClass);
        result = 31 * result + Objects.hashCode(parentClass);
        result = 31 * result + Boolean.hashCode(repeatable);
        result = 31 * result + Objects.hashCode(effectiveText());
        result = 31 * result + Objects.hashCode(effectiveDescription());
        result = 31 * result + effectiveMatchMode().hashCode();
        result = 31 * result + viewType;
        result = 31 * result + effectiveTargetLevel().hashCode();
        return result;
    }

    private String effectiveText() {
        return hasRepeatableLocator() ? null : text;
    }

    private String effectiveDescription() {
        return hasRepeatableLocator() ? null : description;
    }

    private MatchMode effectiveMatchMode() {
        return matchMode != null ? matchMode : MatchMode.EXACT;
    }

    private TargetLevel effectiveTargetLevel() {
        return targetLevel != null ? targetLevel : TargetLevel.ELEMENT;
    }

    /**
     * 原始值全等 — 持久化/传输层的严格比较。
     * <p>
     * 与 {@link #hasSameRuntimeSemantics} 的区别：后者比较“真正改变 Matcher 行为的
     * 有效语义”（repeatable 忽略 text/desc，null 模式取默认值）。运行时比较用后者，
     * DO NOT 统一两者。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MatchSpec matchSpec = (MatchSpec) o;
        return repeatable == matchSpec.repeatable
                && viewType == matchSpec.viewType
                && Arrays.equals(depth, matchSpec.depth)
                && Objects.equals(activityClass, matchSpec.activityClass)
                && Objects.equals(viewClass, matchSpec.viewClass)
                && Objects.equals(resourceName, matchSpec.resourceName)
                && Arrays.equals(itemPath, matchSpec.itemPath)
                && Objects.equals(itemRootClass, matchSpec.itemRootClass)
                && Objects.equals(parentClass, matchSpec.parentClass)
                && Objects.equals(text, matchSpec.text)
                && Objects.equals(description, matchSpec.description)
                && matchMode == matchSpec.matchMode
                && targetLevel == matchSpec.targetLevel;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(depth);
        result = 31 * result + Objects.hashCode(activityClass);
        result = 31 * result + Objects.hashCode(viewClass);
        result = 31 * result + Objects.hashCode(resourceName);
        result = 31 * result + Arrays.hashCode(itemPath);
        result = 31 * result + Objects.hashCode(itemRootClass);
        result = 31 * result + Objects.hashCode(parentClass);
        result = 31 * result + Boolean.hashCode(repeatable);
        result = 31 * result + Objects.hashCode(text);
        result = 31 * result + Objects.hashCode(description);
        result = 31 * result + Objects.hashCode(matchMode);
        result = 31 * result + viewType;
        result = 31 * result + Objects.hashCode(targetLevel);
        return result;
    }

    private static int[] copy(int[] value) {
        return value != null ? value.clone() : null;
    }

    private static String[] copy(String[] value) {
        return value != null ? value.clone() : null;
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /**
     * MatchSpec 构建器 — 链式调用，构建不可变的 {@link MatchSpec} 实例。
     * <p>
     * Builder 保留所有原始字段，不在构造时执行运行时归一化。
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
        TargetLevel targetLevel;

        public Builder depth(int[] depth) { this.depth = depth != null ? depth.clone() : null; return this; }
        public Builder activityClass(String activityClass) { this.activityClass = activityClass; return this; }
        public Builder viewClass(String viewClass) { this.viewClass = viewClass; return this; }
        public Builder resourceName(String resourceName) { this.resourceName = resourceName; return this; }
        public Builder itemPath(String[] itemPath) { this.itemPath = itemPath != null ? itemPath.clone() : null; return this; }
        public Builder itemRootClass(String itemRootClass) { this.itemRootClass = itemRootClass; return this; }
        public Builder parentClass(String parentClass) { this.parentClass = parentClass; return this; }

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
