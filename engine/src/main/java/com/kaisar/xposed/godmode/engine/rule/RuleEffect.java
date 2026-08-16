package com.kaisar.xposed.godmode.engine.rule;

import java.util.Objects;

/** Persisted, immutable effect component of a rule. */
public abstract class RuleEffect {

    public enum Kind {
        REMOVE,
        MODIFY
    }

    private final WireValues wireValues;

    RuleEffect(WireValues wireValues) {
        this.wireValues = Objects.requireNonNull(wireValues, "wireValues must not be null");
    }

    public abstract Kind getKind();

    public final boolean isRemove() {
        return getKind() == Kind.REMOVE;
    }

    public final boolean isModify() {
        return getKind() == Kind.MODIFY;
    }

    /** Preserves the raw non-empty legacy discriminator instead of normalizing it. */
    public final String getRuleTag() {
        return wireValues.getRuleTag();
    }

    /**
     * Returns the immutable flat-wire values used exclusively by the v6.9 JSON/Parcel codec.
     * Runtime code should consume the concrete effect subtype instead.
     */
    public final WireValues toWireValues() {
        return wireValues;
    }

    /** Builds the correct semantic subtype from a lossless v6.9 flat-wire snapshot. */
    public static RuleEffect fromWireValues(WireValues values) {
        Objects.requireNonNull(values, "values must not be null");
        String ruleTag = values.getRuleTag();
        return ruleTag == null || ruleTag.isEmpty()
                ? new RemoveEffect(values) : new ModifyEffect(values);
    }

    /** Immutable compatibility snapshot of the eleven old flat effect slots. */
    public static final class WireValues {
        private final String ruleTag;
        private final int visibility;
        private final int modWidth;
        private final int modHeight;
        private final float modAlpha;
        private final int modXOffset;
        private final int modYOffset;
        private final String modText;
        private final String modImagePath;
        private final int origLeftMargin;
        private final int origTopMargin;

        private WireValues(Builder builder) {
            ruleTag = builder.ruleTag;
            visibility = builder.visibility;
            modWidth = builder.modWidth;
            modHeight = builder.modHeight;
            modAlpha = builder.modAlpha;
            modXOffset = builder.modXOffset;
            modYOffset = builder.modYOffset;
            modText = builder.modText;
            modImagePath = builder.modImagePath;
            origLeftMargin = builder.origLeftMargin;
            origTopMargin = builder.origTopMargin;
        }

        public String getRuleTag() { return ruleTag; }
        public int getVisibility() { return visibility; }
        public int getModWidth() { return modWidth; }
        public int getModHeight() { return modHeight; }
        public float getModAlpha() { return modAlpha; }
        public int getModXOffset() { return modXOffset; }
        public int getModYOffset() { return modYOffset; }
        public String getModText() { return modText; }
        public String getModImagePath() { return modImagePath; }
        public int getOrigLeftMargin() { return origLeftMargin; }
        public int getOrigTopMargin() { return origTopMargin; }

        public Builder toBuilder() {
            return new Builder()
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
                    .origTopMargin(origTopMargin);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof WireValues)) return false;
            WireValues other = (WireValues) object;
            return visibility == other.visibility
                    && modWidth == other.modWidth
                    && modHeight == other.modHeight
                    && Float.compare(modAlpha, other.modAlpha) == 0
                    && modXOffset == other.modXOffset
                    && modYOffset == other.modYOffset
                    && origLeftMargin == other.origLeftMargin
                    && origTopMargin == other.origTopMargin
                    && Objects.equals(ruleTag, other.ruleTag)
                    && Objects.equals(modText, other.modText)
                    && Objects.equals(modImagePath, other.modImagePath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ruleTag, visibility, modWidth, modHeight, modAlpha,
                    modXOffset, modYOffset, modText, modImagePath,
                    origLeftMargin, origTopMargin);
        }

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

            public WireValues build() {
                return new WireValues(this);
            }
        }
    }
}
