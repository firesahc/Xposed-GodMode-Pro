package com.kaisar.xposed.godmode.engine.rule;

import androidx.annotation.NonNull;

/**
 * 类型安全的字段转换器 — 替代废弃的 FieldMapper 反射拷贝方案。
 * <p>
 * 编译期安全：每行 {@code dst.field = src.getField()} 都是编译器检查——
 * 字段名错或 getter 不存在立刻报错。
 * <p>
 * 新增字段时，{@link RuleFields} 接口新增 getter → 此类的 toEngine() 编译报错 → 强制开发者更新。
 * <p>
 * @see RuleFields
 */
public final class RuleMapper {

    private RuleMapper() {
    }

    /**
     * 将 RuleFields 转换为引擎模块的 RuleMatchSpec。
     *
     * @param source 源数据（来自 app 模块的 RuleRecord 等实现类）
     * @return 填充完成的引擎 RuleMatchSpec
     */
    @NonNull
    public static RuleMatchSpec toEngine(@NonNull RuleFields source) {
        RuleMatchSpec dst = new RuleMatchSpec();
        copyTo(source, dst);
        return dst;
    }

    /**
     * 将 RuleFields 的字段逐个拷贝到目标 RuleMatchSpec。
     * <p>
     * 每行一个字段，全部显式赋值。新增字段时编译器强制更新此处。
     *
     * @param src 源数据
     * @param dst 目标引擎 RuleMatchSpec
     */
    public static void copyTo(@NonNull RuleFields src, @NonNull RuleMatchSpec dst) {
        // ===== 规则标识 =====
        dst.ruleTag = src.getRuleTag();

        // ===== 移除规则字段 =====
        dst.label = src.getLabel();
        dst.packageName = src.getPackageName();
        dst.matchVersionName = src.getMatchVersionName();
        dst.matchVersionCode = src.getMatchVersionCode();
        dst.versionCode = src.getVersionCode();
        dst.imagePath = src.getImagePath();
        dst.alias = src.getAlias();
        dst.x = src.getX();
        dst.y = src.getY();
        dst.width = src.getWidth();
        dst.height = src.getHeight();
        dst.depth = src.getDepth() != null ? src.getDepth().clone() : null;
        dst.activityClass = src.getActivityClass();
        dst.viewClass = src.getViewClass();
        dst.resourceName = src.getResourceName();
        dst.itemPath = src.getItemPath() != null ? src.getItemPath().clone() : null;
        dst.itemRootClass = src.getItemRootClass();
        dst.parentClass = src.getParentClass();
        dst.repeatable = src.isRepeatable();
        dst.text = src.getText();
        dst.description = src.getDescription();
        dst.matchMode = src.getMatchMode();
        dst.matchThreshold = src.getMatchThreshold();
        dst.visibility = src.getVisibility();
        dst.timestamp = src.getTimestamp();

        // ===== 修改规则字段 =====
        dst.modWidth = src.getModWidth();
        dst.modHeight = src.getModHeight();
        dst.modAlpha = src.getModAlpha();
        dst.modXOffset = src.getModXOffset();
        dst.modYOffset = src.getModYOffset();
        dst.modText = src.getModText();
        dst.modImagePath = src.getModImagePath();

        // ===== 原始值（用于撤销修改） =====
        dst.origWidth = src.getOrigWidth();
        dst.origHeight = src.getOrigHeight();
        dst.origAlpha = src.getOrigAlpha();
        dst.origText = src.getOrigText();
        dst.origLeftMargin = src.getOrigLeftMargin();
        dst.origTopMargin = src.getOrigTopMargin();
    }
}
