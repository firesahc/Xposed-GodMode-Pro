package com.kaisar.xposed.godmode.engine.rule;

import java.util.Arrays;
import java.util.Objects;

/**
 * A rule slot identity inside an authoritative package scope.
 * <p>The key deliberately excludes matcher refinements and effects. It answers only which
 * independently replaceable target slot a rule occupies.</p>
 */
public final class RuleSlotKey {

    public enum TargetKind {
        DIRECT,
        REPEATABLE
    }

    private final String packageName;
    private final String activityClass;
    private final String viewClass;
    private final TargetKind targetKind;
    private final int[] depth;
    private final String[] itemPath;

    private RuleSlotKey(String packageName, MatchSpec matchSpec) {
        this.packageName = packageName;
        this.activityClass = matchSpec.getActivityClass();
        this.viewClass = matchSpec.getViewClass();
        this.targetKind = matchSpec.isRepeatable()
                ? TargetKind.REPEATABLE : TargetKind.DIRECT;
        this.depth = targetKind == TargetKind.DIRECT ? matchSpec.getDepth() : null;
        this.itemPath = targetKind == TargetKind.REPEATABLE ? matchSpec.getItemPath() : null;
    }

    /**
     * Derives a slot key from the package scope supplied by the repository/service boundary.
     * The package stored in a legacy record must not override this argument.
     */
    public static RuleSlotKey from(String authoritativePackageName, MatchSpec matchSpec) {
        return new RuleSlotKey(authoritativePackageName,
                Objects.requireNonNull(matchSpec, "matchSpec must not be null"));
    }

    public String getPackageName() {
        return packageName;
    }

    public String getActivityClass() {
        return activityClass;
    }

    public String getViewClass() {
        return viewClass;
    }

    public TargetKind getTargetKind() {
        return targetKind;
    }

    public int[] getDepth() {
        return depth != null ? depth.clone() : null;
    }

    public String[] getItemPath() {
        return itemPath != null ? itemPath.clone() : null;
    }

    /** A malformed historical repeatable rule remains repeatable instead of becoming direct. */
    public boolean hasMissingRepeatableLocator() {
        return targetKind == TargetKind.REPEATABLE
                && (itemPath == null || itemPath.length == 0);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof RuleSlotKey)) return false;
        RuleSlotKey other = (RuleSlotKey) object;
        return Objects.equals(packageName, other.packageName)
                && Objects.equals(activityClass, other.activityClass)
                && Objects.equals(viewClass, other.viewClass)
                && targetKind == other.targetKind
                && Arrays.equals(depth, other.depth)
                && Arrays.equals(itemPath, other.itemPath);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(packageName, activityClass, viewClass, targetKind);
        result = 31 * result + Arrays.hashCode(depth);
        result = 31 * result + Arrays.hashCode(itemPath);
        return result;
    }

    @Override
    public String toString() {
        return "RuleSlotKey{"
                + "package='" + packageName + '\''
                + ", activity='" + activityClass + '\''
                + ", view='" + viewClass + '\''
                + ", kind=" + targetKind
                + ", locator=" + (targetKind == TargetKind.DIRECT
                        ? Arrays.toString(depth) : Arrays.toString(itemPath))
                + '}';
    }
}
