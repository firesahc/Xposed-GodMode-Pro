package com.kaisar.xposed.godmode.engine.rule;

import java.util.Arrays;

/**
 * 引擎轻量版 ViewRule — 纯 POJO，不依赖 Parcelable 或 Gson 注解。
 * <p>
 * 字段与 app 模块的 {@code com.kaisar.xposed.godmode.rule.ViewRule} 对齐，
 * 通过 {@link com.kaisar.xposed.godmode.engine.util.FieldMapper} 实现双向转换。
 * <p>
 * 区分移除规则和修改规则的方式：{@code ruleTag} 为 null 或空字符串 = 移除规则，非空 = 修改规则。
 */
public final class ViewRule implements Cloneable {

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

    /** 无参构造（供 FieldMapper 使用） */
    public ViewRule() {
    }

    /**
     * 判断此规则是否为可重复匹配规则（如 RecyclerView 列表项）。
     */
    public boolean isRepeatable() {
        return repeatable;
    }

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
        ViewRule other = (ViewRule) o;

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
    public ViewRule clone() {
        try {
            ViewRule cloned = (ViewRule) super.clone();
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
