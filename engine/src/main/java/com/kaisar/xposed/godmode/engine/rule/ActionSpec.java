package com.kaisar.xposed.godmode.engine.rule;

/**
 * 动作规格 — 定义规则应用（修改/移除）所需的所有字段。
 * <p>
 * 从 {@link RuleMatchSpec} 拆分的纯动作部分，只包含应用器（RuleApplier）需要的字段。
 * 不包含任何匹配字段。
 * <p>
 * 由 {@link RuleMatchSpec#getActionSpec()} 生成，或直接构造。
 */
public final class ActionSpec {

    /** 规则标签 — null 或空 = 移除规则，非空 = 修改规则 */
    public String ruleTag;

    // ===== 移除规则字段 =====
    /** 目标可见性（View.GONE / View.INVISIBLE） */
    public int visibility;

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

    public ActionSpec() {
    }

    /**
     * 是否为移除规则（ruleTag 为 null 或空字符串）。
     */
    public boolean isRemoveRule() {
        return ruleTag == null || ruleTag.isEmpty();
    }

    /**
     * 是否为修改规则（ruleTag 非空）。
     */
    public boolean isModifyRule() {
        return ruleTag != null && !ruleTag.isEmpty();
    }

    // ===== 便捷检查方法 =====

    public boolean isWidthModified() { return modWidth >= 0; }
    public boolean isHeightModified() { return modHeight >= 0; }
    public boolean isAlphaModified() { return modAlpha >= 0f; }
    public boolean isPositionModified() { return modXOffset != 0 || modYOffset != 0; }
    public boolean isTextModified() { return modText != null; }
    public boolean isImageModified() { return modImagePath != null; }

    /**
     * 从 RuleFields 提取修改字段构造。
     */
    public static ActionSpec from(RuleFields fields) {
        ActionSpec spec = new ActionSpec();
        spec.ruleTag = fields.getRuleTag();
        spec.visibility = fields.getVisibility();
        spec.modWidth = fields.getModWidth();
        spec.modHeight = fields.getModHeight();
        spec.modAlpha = fields.getModAlpha();
        spec.modXOffset = fields.getModXOffset();
        spec.modYOffset = fields.getModYOffset();
        spec.modText = fields.getModText();
        spec.modImagePath = fields.getModImagePath();
        spec.origWidth = fields.getOrigWidth();
        spec.origHeight = fields.getOrigHeight();
        spec.origAlpha = fields.getOrigAlpha();
        spec.origText = fields.getOrigText();
        spec.origLeftMargin = fields.getOrigLeftMargin();
        spec.origTopMargin = fields.getOrigTopMargin();
        return spec;
    }

    /**
     * 使用当前修改规则的值覆盖 target 的同名字段（仅覆盖已修改的维度）。
     * 用于从一个 ActionSpec 合并到另一个。
     */
    public void mergeInto(ActionSpec target) {
        if (target == null) return;
        if (isWidthModified()) target.modWidth = modWidth;
        if (isHeightModified()) target.modHeight = modHeight;
        if (isAlphaModified()) target.modAlpha = modAlpha;
        if (isPositionModified()) {
            target.modXOffset = modXOffset;
            target.modYOffset = modYOffset;
        }
        if (isTextModified()) target.modText = modText;
        if (isImageModified()) target.modImagePath = modImagePath;
    }
}
