package com.kaisar.xposed.godmode.orchestrator;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.rule.RuleFields;

import java.util.Arrays;
import java.util.Objects;

/** Runtime-significant equality, deliberately independent of Android classes. */
final class RuntimeRuleComparator {

    private RuntimeRuleComparator() {}

    static boolean contentEquals(RuleFields left, RuleFields right) {
        if (left == right) return true;
        if (left == null || right == null) return false;

        return Objects.equals(left.getRuleTag(), right.getRuleTag())
                && Objects.equals(left.getPackageName(), right.getPackageName())
                && Objects.equals(left.getMatchVersionName(), right.getMatchVersionName())
                && left.getMatchVersionCode() == right.getMatchVersionCode()
                && left.getVersionCode() == right.getVersionCode()
                && Objects.equals(left.getImagePath(), right.getImagePath())
                && left.getX() == right.getX()
                && left.getY() == right.getY()
                && left.getWidth() == right.getWidth()
                && left.getHeight() == right.getHeight()
                && Arrays.equals(left.getDepth(), right.getDepth())
                && Objects.equals(left.getActivityClass(), right.getActivityClass())
                && Objects.equals(left.getViewClass(), right.getViewClass())
                && Objects.equals(left.getResourceName(), right.getResourceName())
                && Arrays.equals(left.getItemPath(), right.getItemPath())
                && Objects.equals(left.getItemRootClass(), right.getItemRootClass())
                && Objects.equals(left.getParentClass(), right.getParentClass())
                && left.isRepeatable() == right.isRepeatable()
                && Objects.equals(left.getText(), right.getText())
                && Objects.equals(left.getDescription(), right.getDescription())
                && matchMode(left) == matchMode(right)
                && left.getInfoFlowViewType() == right.getInfoFlowViewType()
                && targetLevel(left) == targetLevel(right)
                && left.getVisibility() == right.getVisibility()
                && left.getModWidth() == right.getModWidth()
                && left.getModHeight() == right.getModHeight()
                && Float.compare(left.getModAlpha(), right.getModAlpha()) == 0
                && left.getModXOffset() == right.getModXOffset()
                && left.getModYOffset() == right.getModYOffset()
                && Objects.equals(left.getModText(), right.getModText())
                && Objects.equals(left.getModImagePath(), right.getModImagePath())
                && left.getOrigWidth() == right.getOrigWidth()
                && left.getOrigHeight() == right.getOrigHeight()
                && Float.compare(left.getOrigAlpha(), right.getOrigAlpha()) == 0
                && Objects.equals(left.getOrigText(), right.getOrigText())
                && left.getOrigLeftMargin() == right.getOrigLeftMargin()
                && left.getOrigTopMargin() == right.getOrigTopMargin();
    }

    private static MatchMode matchMode(RuleFields rule) {
        return rule.getMatchMode() != null ? rule.getMatchMode() : MatchMode.EXACT;
    }

    private static TargetLevel targetLevel(RuleFields rule) {
        return rule.getTargetLevel() != null ? rule.getTargetLevel() : TargetLevel.ELEMENT;
    }
}
