package com.kaisar.xposed.godmode.ui.glide;

import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.Objects;

/** Immutable UI-only source and cache identity for a captured rule preview. */
public final class RulePreviewSpec {
    public final String imagePath;
    public final int x;
    public final int y;
    public final int width;
    public final int height;

    private RulePreviewSpec(String imagePath, int x, int y, int width, int height) {
        this.imagePath = imagePath;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public static RulePreviewSpec from(RuleRecord rule) {
        return new RulePreviewSpec(rule.imagePath, rule.x, rule.y, rule.width, rule.height);
    }

    @Override public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof RulePreviewSpec)) return false;
        RulePreviewSpec other = (RulePreviewSpec) object;
        return x == other.x && y == other.y && width == other.width && height == other.height
                && Objects.equals(imagePath, other.imagePath);
    }

    @Override public int hashCode() { return Objects.hash(imagePath, x, y, width, height); }
}
