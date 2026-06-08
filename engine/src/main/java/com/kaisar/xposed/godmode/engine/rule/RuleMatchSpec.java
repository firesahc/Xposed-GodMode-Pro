package com.kaisar.xposed.godmode.engine.rule;

import java.util.Arrays;

/**
 * 寮曟搸鍖归厤瑙勮寖 鈥?View 鍖归厤 (computeScore) + 灞炴€у簲鐢?(ModifyApplier/RemoveApplier) + 缂撳瓨鍘婚噸 (娣?equals)銆?
 * <p>
 * 瀹炵幇 {@link RuleFields} 鎺ュ彛浠ユ彁渚涚紪璇戞湡瀹夊叏鐨勫瓧娈佃闂紝
 * 閰嶅悎 {@link com.kaisar.xposed.godmode.engine.rule.RuleMapper} 瀹炵幇绫诲瀷瀹夊叏鐨?app鈫抏ngine 杞崲銆?
 * <p>
 * 銆愬悓姝ヤ繚闅溿€戝鏂规枃浠? {@code app/.../rule/RuleRecord.java}锛圥arcelable 鐗堬紝甯?@SerializedName锛?
 * <br>寮曟搸瀛楁鎬绘暟: 37 &nbsp;|&nbsp; app 瀛楁鎬绘暟: 37
 * <br>鑻ユ澶勫鍑忓瓧娈碉紝璇峰悓姝ヤ慨鏀瑰鏂规枃浠剁殑鍚屽悕瀛楁銆丳arcel 璇诲啓銆乧lone() 鍜?equals()/hashCode()銆?
 * <p>
 * 鍖哄垎绉婚櫎瑙勫垯鍜屼慨鏀硅鍒欑殑鏂瑰紡锛歿@code ruleTag} 涓?null 鎴栫┖瀛楃涓?= 绉婚櫎瑙勫垯锛岄潪绌?= 淇敼瑙勫垯銆?
 *
 * @see RuleFields
 * @see com.kaisar.xposed.godmode.engine.rule.RuleMapper
 */
public final class RuleMatchSpec implements RuleFields, Cloneable {

    // ===== 瑙勫垯鏍囪瘑 =====
    /** 瑙勫垯鏍囩 鈥?null/绌?绉婚櫎瑙勫垯锛岄潪绌?淇敼瑙勫垯 */
    public String ruleTag;

    // ===== 绉婚櫎瑙勫垯瀛楁 =====
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
    public int visibility;
    public long timestamp;

    // ===== 淇敼瑙勫垯瀛楁 =====
    public int modWidth = -1;
    public int modHeight = -1;
    public float modAlpha = -1f;
    public int modXOffset;
    public int modYOffset;
    public String modText;
    public String modImagePath;

    // ===== 鍘熷鍊硷紙鐢ㄤ簬鎾ら攢淇敼锛?=====
    public int origWidth;
    public int origHeight;
    public float origAlpha = 1f;
    public String origText;
    public int origLeftMargin;
    public int origTopMargin;

    /** 鏃犲弬鏋勯€狅紙渚?FieldMapper / RuleMapper 浣跨敤锛?*/
    public RuleMatchSpec() {
    }

    // =========================================================================
    // RuleFields 鎺ュ彛瀹炵幇 鈥?37 涓?getter锛堝鎵樺埌 public 瀛楁锛?
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
    // hashCode / equals / clone锛堝畬鍏ㄤ笉鍔紝淇濇寔鍘熸湁璇箟锛?
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
