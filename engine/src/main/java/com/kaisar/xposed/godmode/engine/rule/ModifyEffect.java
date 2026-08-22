package com.kaisar.xposed.godmode.engine.rule;

import java.util.Objects;

/** A modify rule effect with only the values consumed by ModifyApplier. */
public final class ModifyEffect extends RuleEffect {

    ModifyEffect(WireValues wireValues) {
        super(wireValues);
        if (wireValues.getRuleTag() == null || wireValues.getRuleTag().isEmpty()) {
            throw new IllegalArgumentException("ModifyEffect requires a non-empty ruleTag");
        }
    }

    @Override
    public Kind getKind() {
        return Kind.MODIFY;
    }

    public int getModWidth() { return toWireValues().getModWidth(); }
    public int getModHeight() { return toWireValues().getModHeight(); }
    public float getModAlpha() { return toWireValues().getModAlpha(); }
    public int getModXOffset() { return toWireValues().getModXOffset(); }
    public int getModYOffset() { return toWireValues().getModYOffset(); }
    public String getModText() { return toWireValues().getModText(); }
    public String getModImagePath() { return toWireValues().getModImagePath(); }
    public int getOrigLeftMargin() { return toWireValues().getOrigLeftMargin(); }
    public int getOrigTopMargin() { return toWireValues().getOrigTopMargin(); }

    public boolean isWidthModified() { return getModWidth() >= 0; }
    public boolean isHeightModified() { return getModHeight() >= 0; }
    public boolean isAlphaModified() { return getModAlpha() >= 0f; }
    public boolean isPositionModified() { return getModXOffset() != 0 || getModYOffset() != 0; }
    public boolean isTextModified() { return getModText() != null; }
    public boolean isImageModified() { return getModImagePath() != null; }

    /**
     * 运行时效果相等 — 仅比较 ModifyApplier 实际消费的 mod/orig 字段。
     * 有意排除 ruleTag（wire 序列化判别器）：仅 ruleTag 不同的两个效果在运行时不可区分，
     * runtime diff 不会因 tag 变化触发 revoke/reapply（见 RuntimeRuleComparator 契约）。
     */
    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof ModifyEffect)) return false;
        ModifyEffect other = (ModifyEffect) object;
        return getModWidth() == other.getModWidth()
                && getModHeight() == other.getModHeight()
                && Float.compare(getModAlpha(), other.getModAlpha()) == 0
                && getModXOffset() == other.getModXOffset()
                && getModYOffset() == other.getModYOffset()
                && getOrigLeftMargin() == other.getOrigLeftMargin()
                && getOrigTopMargin() == other.getOrigTopMargin()
                && Objects.equals(getModText(), other.getModText())
                && Objects.equals(getModImagePath(), other.getModImagePath());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getModWidth(), getModHeight(), getModAlpha(),
                getModXOffset(), getModYOffset(), getModText(), getModImagePath(),
                getOrigLeftMargin(), getOrigTopMargin());
    }

    public static final class Builder {
        private String ruleTag = "modify";
        private int visibility;
        private int modWidth = -1;
        private int modHeight = -1;
        private float modAlpha = -1f;
        private int modXOffset;
        private int modYOffset;
        private String modText;
        private String modImagePath;
        private int origLeftMargin;
        private int origTopMargin;

        public Builder ruleTag(String value) { ruleTag = value; return this; }
        public Builder visibility(int value) { visibility = value; return this; }
        public Builder modWidth(int value) { modWidth = value; return this; }
        public Builder modHeight(int value) { modHeight = value; return this; }
        public Builder modAlpha(float value) { modAlpha = value; return this; }
        public Builder modXOffset(int value) { modXOffset = value; return this; }
        public Builder modYOffset(int value) { modYOffset = value; return this; }
        public Builder modText(String value) { modText = value; return this; }
        public Builder modImagePath(String value) { modImagePath = value; return this; }
        public Builder origLeftMargin(int value) { origLeftMargin = value; return this; }
        public Builder origTopMargin(int value) { origTopMargin = value; return this; }

        public ModifyEffect build() {
            return new ModifyEffect(new WireValues.Builder()
                    .ruleTag(ruleTag)
                    .visibility(visibility)
                    .modWidth(modWidth)
                    .modHeight(modHeight)
                    .modAlpha(modAlpha)
                    .modXOffset(modXOffset)
                    .modYOffset(modYOffset)
                    .modText(modText)
                    .modImagePath(modImagePath)
                    .origLeftMargin(origLeftMargin)
                    .origTopMargin(origTopMargin)
                    .build());
        }
    }
}
