package com.kaisar.xposed.godmode.engine.rule;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;

import java.util.Arrays;

/**
 * 引擎规则匹配规范 — 统一 DTO，同时实现 {@link RuleFields} 接口（与 RuleRecord 互转）。
 * <p>
 * 内部包含两个职责清晰的子规格：
 * <ul>
 *   <li>{@link #getMatchSpec()} — 纯匹配字段，供 {@link com.kaisar.xposed.godmode.engine.matcher.IMatcher} 使用</li>
 *   <li>{@link #getActionSpec()} — 纯修改字段，供 {@link com.kaisar.xposed.godmode.engine.applier.RuleApplier} 使用</li>
 * </ul>
 * 新代码应优先使用 MatchSpec / ActionSpec，而非直接操作 RuleMatchSpec。
 * </p>
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

    // ===== 匹配配置 =====
    /** 匹配模式，null 等价于 EXACT（精确匹配） */
    public MatchMode matchMode;
    /** 信息流模式下 RecyclerView 的 getItemViewType() 值，用于过滤匹配项类型（0=不过滤） */
    public int viewType;
    /** 匹配目标层级，null 等价于 ELEMENT（向后兼容） */
    public TargetLevel targetLevel;

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

    // ===== 原始值（用于撤销修改）=====
    public int origWidth;
    public int origHeight;
    public float origAlpha = 1f;
    public String origText;
    public int origLeftMargin;
    public int origTopMargin;

    /** 无参构造（供 FieldMapper / RuleMapper 使用）*/
    public RuleMatchSpec() {
    }

    // =========================================================================
    // RuleFields 接口实现 — 40 个 getter（委托到 public 字段）
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
    @Override public MatchMode getMatchMode() { return matchMode; }
    @Override public int getInfoFlowViewType() { return viewType; }
    @Override public TargetLevel getTargetLevel() { return targetLevel; }
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
    // 导出为 MatchSpec / ActionSpec（职责分离视图）
    // =========================================================================

    /**
     * 导出为纯匹配规格，供 IMatcher 使用。
     * 返回的 MatchSpec 是独立副本，修改不影响原 RuleMatchSpec。
     */
    public MatchSpec getMatchSpec() {
        return MatchSpec.from(this);
    }

    /**
     * 导出为纯动作规格，供 RuleApplier 使用。
     * 返回的 ActionSpec 是独立副本，修改不影响原 RuleMatchSpec。
     */
    public ActionSpec getActionSpec() {
        return ActionSpec.from(this);
    }

    // =========================================================================
    // hashCode / equals / clone（完全不改，保持原有语义）
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
