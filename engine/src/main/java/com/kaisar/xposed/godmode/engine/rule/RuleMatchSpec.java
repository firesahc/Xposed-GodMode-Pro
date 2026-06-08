package com.kaisar.xposed.godmode.engine.rule;

import java.util.Arrays;

/**
 * 引擎匹配规范 — View 匹配 (computeScore) + 属性应用 (ModifyApplier/RemoveApplier) + 缓存去重 (深 equals)。
 * <p>
 * 实现 {@link RuleFields} 接口以提供编译期安全的字段访问，
 * 配合 {@link com.kaisar.xposed.godmode.engine.util.RuleMapper} 实现类型安全的 app→engine 转换。
 * <p>
 * 【同步保障】对方文件: {@code app/.../rule/ViewRule.java}（Parcelable 版，带 @SerializedName）
 * <br>引擎字段总数: 37 &nbsp;|&nbsp; app 字段总数: 37
 * <br>若此处增减字段，请同步修改对方文件的同名字段、Parcel 读写、clone() 和 equals()/hashCode()。
 * <p>
 * 区分移除规则和修改规则的方式：{@code ruleTag} 为 null 或空字符串 = 移除规则，非空 = 修改规则。
 *
 * @see RuleFields
 * @see com.kaisar.xposed.godmode.engine.util.RuleMapper
 */
public final class RuleMatchSpec implements RuleFields, Cloneable {

    // ===== 规则标识 =====
    /** 规则标签 — null/空=移除规则，非空=修改规则 */
    public String ruleTag;

    // ===== 移除规则字段 =====
    public String label;
    public String packageName;
    public String matchVersionName;
    public int matchVersionCode;
    public int versionCode;
    public String imagePath;
    public String alias;
    public int x;
    public int y;
    public int width;
    public int height;
    public int[] depth;
    public String activityClass;
    public String viewClass;
    public String resourceName;
    public String[] itemPath;
    public String itemRootClass;
    public String parentClass;
    public boolean repeatable;
    public String text;
    public String description;
    public int visibility;
    public long timestamp;

    // ===== 修改规则字段 =====
    public int modWidth = -1;
    public int modHeight = -1;
    public float modAlpha = -1f;
    public int modXOffset;
    public int modYOffset;
    public String modText;
    public String modImagePath;

    // ===== 原始值（用于撤销修改） =====
    public int origWidth;
    public int origHeight;
    public float origAlpha = 1f;
    public String origText;
    public int origLeftMargin;
    public int origTopMargin;

    /** 无参构造（供 FieldMapper / RuleMapper 使用） */
    public RuleMatchSpec() {
    }

    // =========================================================================
    // RuleFields 接口实现 — 37 个 getter（委托到 public 字段）
    // =========================================================================

    @Override public String getRuleTag() { return ruleTag; }
    @Override public String getLabel() { return label; }
    @Override public String getPackageName() { return packageName; }
    @Override public String getMatchVersionName() { return matchVersionName; }
    @Override public int getMatchVersionCode() { return matchVersionCode; }
    @Override public int getVersionCode() { return versionCode; }
    @Override public String getImagePath() { return imagePath; }
    @Override public String getAlias() { return alias; }
    @Override public int getX() { return x; }
    @Override public int getY() { return y; }
    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }
    @Override public int[] getDepth() { return depth; }
    @Override public String getActivityClass() { return activityClass; }
    @Override public String getViewClass() { return viewClass; }
    @Override public String getResourceName() { return resourceName; }
    @Override public String[] getItemPath() { return itemPath; }
    @Override public String getItemRootClass() { return itemRootClass; }
    @Override public String getParentClass() { return parentClass; }
    @Override public boolean isRepeatable() { return repeatable; }
    @Override public String getText() { return text; }
    @Override public String getDescription() { return description; }
    @Override public int getVisibility() { return visibility; }
    @Override public long getTimestamp() { return timestamp; }
    @Override public int getModWidth() { return modWidth; }
    @Override public int getModHeight() { return modHeight; }
    @Override public float getModAlpha() { return modAlpha; }
    @Override public int getModXOffset() { return modXOffset; }
    @Override public int getModYOffset() { return modYOffset; }
    @Override public String getModText() { return modText; }
    @Override public String getModImagePath() { return modImagePath; }
    @Override public int getOrigWidth() { return origWidth; }
    @Override public int getOrigHeight() { return origHeight; }
    @Override public float getOrigAlpha() { return origAlpha; }
    @Override public String getOrigText() { return origText; }
    @Override public int getOrigLeftMargin() { return origLeftMargin; }
    @Override public int getOrigTopMargin() { return origTopMargin; }

    // =========================================================================
    // hashCode / equals / clone（完全不动，保持原有语义）
    // =========================================================================

    @Override
    public int hashCode() {
        int result = activityClass != null ? activityClass.hashCode() : 0;
        result = 31 * result + (viewClass != null ? viewClass.hashCode() : 0);
        result = 31 * result + (resourceName != null ? resourceName.hashCode() : 0);
        result = 31 * result + (text != null ? text.hashCode() : 0);
        result = 31 * result + (description != null ? description.hashCode() : 0);
        result = 31 * result + (ruleTag != null ? ruleTag.hashCode() : 0);
        result = 31 * result + x;
        result = 31 * result + y;
        result = 31 * result + width;
        result = 31 * result + height;
        result = 31 * result + Arrays.hashCode(depth);
        result = 31 * result + Boolean.hashCode(repeatable);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleMatchSpec other = (RuleMatchSpec) o;

        if (repeatable != other.repeatable) return false;
        if (!equalsNullable(activityClass, other.activityClass)) return false;
        if (!equalsNullable(viewClass, other.viewClass)) return false;
        if (!equalsNullable(resourceName, other.resourceName)) return false;
        if (!equalsNullable(text, other.text)) return false;
        if (!equalsNullable(description, other.description)) return false;
        if (!equalsNullable(ruleTag, other.ruleTag)) return false;

        if (!repeatable) return Arrays.equals(depth, other.depth);
        return Arrays.equals(itemPath, other.itemPath)
                && equalsNullable(itemRootClass, other.itemRootClass)
                && equalsNullable(parentClass, other.parentClass);
    }

    @Override
    @SuppressWarnings("MethodDoesntCallSuperMethod")
    public RuleMatchSpec clone() {
        try {
            RuleMatchSpec cloned = (RuleMatchSpec) super.clone();
            if (depth != null) cloned.depth = depth.clone();
            if (itemPath != null) cloned.itemPath = itemPath.clone();
            return cloned;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean equalsNullable(Object a, Object b) {
        return (a == null) ? (b == null) : a.equals(b);
    }
}
