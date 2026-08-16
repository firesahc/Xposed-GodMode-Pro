package com.kaisar.xposed.godmode.engine.rule;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;

/**
 * 匹配字段契约 — 限定 Matcher 能访问的字段。
 * <p>
 * 实现类：{@link MatchSpec}。
 * <p>
 * 【编译期安全】Matcher 的方法参数限制为此接口，编译期无法访问 mod / orig 等动作字段。
 *
 * @see MatchSpec
 */
public interface MatchFields {

    /** 深度路径 — 视图树中从 DecorView 到目标 View 的 childIndex 链 */
    int[] getDepth();

    /** 目标 Activity 完整类名 */
    String getActivityClass();

    /** 目标 View 完整类名 */
    String getViewClass();

    /** android:resourceName (R.id.xxx) */
    String getResourceName();

    /** RecyclerView item 路径（repeatable 规则） */
    String[] getItemPath();

    /** RecyclerView item 根 View 类名 */
    String getItemRootClass();

    /** 父 View 完整类名 */
    String getParentClass();

    /** 是否为可重复规则（在 RecyclerView/ListView 中匹配多个同类元素） */
    boolean isRepeatable();

    /** TextView 文本内容 */
    String getText();

    /** contentDescription 无障碍描述 */
    String getDescription();

    /** 匹配模式（精确/包含/前缀/后缀/正则），null 等价于 EXACT */
    MatchMode getMatchMode();

    /** 信息流模式下 RecyclerView 的 getItemViewType() 值，用于过滤匹配项类型（0=不过滤） */
    int getInfoFlowViewType();

    /** 匹配目标层级，null 等价于 ELEMENT（向后兼容） */
    TargetLevel getTargetLevel();

}
