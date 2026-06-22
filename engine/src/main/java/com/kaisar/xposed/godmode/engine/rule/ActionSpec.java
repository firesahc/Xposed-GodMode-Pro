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

    // =========================================================================
    // Builder — 链式构造不可变 ActionSpec（编辑器层使用）
    // =========================================================================

    /**
     * ActionSpec 构建器 — 编辑器层通过 Builder 构造只含所需字段的 ActionSpec。
     * <p>
     * 仅填充与当前操作相关的字段，未设置的字段保持默认值。
     * BlockHandler 只能设置 visibility/ruleTag，编译期无法访问 modify 字段。
     */
    public static final class Builder {
        private String ruleTag;
        private int visibility;
        private int modWidth = -1;
        private int modHeight = -1;
        private float modAlpha = -1f;
        private int modXOffset;
        private int modYOffset;
        private String modText;
        private String modImagePath;
        private int origWidth;
        private int origHeight;
        private float origAlpha = 1f;
        private String origText;
        private int origLeftMargin;
        private int origTopMargin;

        public Builder ruleTag(String tag) { this.ruleTag = tag; return this; }
        public Builder visibility(int v) { this.visibility = v; return this; }
        public Builder modWidth(int w) { this.modWidth = w; return this; }
        public Builder modHeight(int h) { this.modHeight = h; return this; }
        public Builder modAlpha(float a) { this.modAlpha = a; return this; }
        public Builder modXOffset(int x) { this.modXOffset = x; return this; }
        public Builder modYOffset(int y) { this.modYOffset = y; return this; }
        public Builder modText(String t) { this.modText = t; return this; }
        public Builder modImagePath(String p) { this.modImagePath = p; return this; }
        public Builder origWidth(int w) { this.origWidth = w; return this; }
        public Builder origHeight(int h) { this.origHeight = h; return this; }
        public Builder origAlpha(float a) { this.origAlpha = a; return this; }
        public Builder origText(String t) { this.origText = t; return this; }
        public Builder origLeftMargin(int m) { this.origLeftMargin = m; return this; }
        public Builder origTopMargin(int m) { this.origTopMargin = m; return this; }

        public ActionSpec build() {
            ActionSpec spec = new ActionSpec();
            spec.ruleTag = this.ruleTag;
            spec.visibility = this.visibility;
            spec.modWidth = this.modWidth;
            spec.modHeight = this.modHeight;
            spec.modAlpha = this.modAlpha;
            spec.modXOffset = this.modXOffset;
            spec.modYOffset = this.modYOffset;
            spec.modText = this.modText;
            spec.modImagePath = this.modImagePath;
            spec.origWidth = this.origWidth;
            spec.origHeight = this.origHeight;
            spec.origAlpha = this.origAlpha;
            spec.origText = this.origText;
            spec.origLeftMargin = this.origLeftMargin;
            spec.origTopMargin = this.origTopMargin;
            return spec;
        }
    }

}
