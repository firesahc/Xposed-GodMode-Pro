package com.kaisar.xposed.godmode.rule;

import android.content.res.Resources;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.google.gson.annotations.SerializedName;
import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.rule.RuleFields;

import java.util.Arrays;
import java.util.Objects;

/**
 * app 模块的规则记录 — Parcelable（IPC 序列化）+ Gson @SerializedName（持久化）。
 * <p>
 * 实现 {@link RuleFields} 接口以提供编译期安全的字段访问，
 * 配合 {@link com.kaisar.xposed.godmode.engine.rule.RuleMapper} 实现类型安全的 app→engine 转换。
 * <p>
 * 【同步保障】对方文件 {@code engine/.../engine/rule/RuleMatchSpec.java}（纯 POJO 版）
 * <br>引擎字段总数: 38 &nbsp;|&nbsp; app 字段总数: 38
 * <br>若此处增减字段，请同步修改对方文件的同名字段。Parcel 读写、clone() 和 equals()/hashCode()。
 *
 * @see RuleFields
 * @see com.kaisar.xposed.godmode.engine.rule.RuleMapper
 */
@Keep
public final class RuleRecord implements RuleFields, Parcelable, Cloneable {

    // 规则标识字段: 非空=修改规则，null/空=移除规则
    @SerializedName("rule_tag")
    public String ruleTag;

    // --- 移除规则字段 ---
    @SerializedName("label")
    public String label;
    @SerializedName("package_name")
    public String packageName;
    @SerializedName("match_version_name")
    public String matchVersionName;
    @SerializedName("match_version_code")
    public int matchVersionCode;
    @SerializedName("version_code")
    public int versionCode;
    @SerializedName("img_path")
    public String imagePath;
    @SerializedName("alias")
    public String alias;
    @SerializedName("x")
    public int x;
    @SerializedName("y")
    public int y;
    @SerializedName("width")
    public int width;
    @SerializedName("height")
    public int height;
    @SerializedName("depth")
    public int[] depth;
    @SerializedName("act_class")
    public String activityClass;
    @SerializedName("view_class")
    public String viewClass;
    @SerializedName("res_name")
    public String resourceName;
    @SerializedName("item_path")
    public String[] itemPath;
    @SerializedName("item_root_class")
    public String itemRootClass;
    @SerializedName("parent_class")
    public String parentClass;
    @SerializedName("repeatable")
    public boolean repeatable;
    @SerializedName("text")
    public String text;
    @SerializedName("description")
    public String description;

    // ===== 匹配配置 =====
    @SerializedName("match_mode")
    public MatchMode matchMode;
    @SerializedName("match_threshold")
    public int matchThreshold;
    @SerializedName("target_level")
    public TargetLevel targetLevel;

    @SerializedName("visibility")
    public int visibility;
    @SerializedName("timestamp")
    public long timestamp;

    // --- 修改规则字段 ---
    @SerializedName("mod_width")
    public int modWidth = -1;
    @SerializedName("mod_height")
    public int modHeight = -1;
    @SerializedName("mod_alpha")
    public float modAlpha = -1f;
    @SerializedName("mod_x_offset")
    public int modXOffset;
    @SerializedName("mod_y_offset")
    public int modYOffset;
    @SerializedName("mod_text")
    public String modText;
    @SerializedName("mod_img_path")
    public String modImagePath;

    // --- 原始值(用于应用修改时计算) ---
    @SerializedName("orig_width")
    public int origWidth;
    @SerializedName("orig_height")
    public int origHeight;
    @SerializedName("orig_alpha")
    public float origAlpha = 1f;
    @SerializedName("orig_text")
    public String origText;
    @SerializedName("orig_left_margin")
    public int origLeftMargin;
    @SerializedName("orig_top_margin")
    public int origTopMargin;

    // =========================================================================
    // RuleFields 接口实现 — 37 个 getter（委托到 public 字段）
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
    @Override public MatchMode getMatchMode() { return matchMode; }
    @Override public int getMatchThreshold() { return matchThreshold; }
    @Override public TargetLevel getTargetLevel() { return targetLevel; }
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
    // 构造方法
    // =========================================================================

    @SuppressWarnings("unused")
    private RuleRecord() {
    }

    public RuleRecord(String label, String packageName, String matchVersionName, int matchVersionCode,
                      int versionCode, String imagePath, String alias, int x, int y, int width, int height,
                      int[] depth, String activityClass, String viewClass, String resourceName,
                      String text, String description, int visibility, long timestamp) {
        this.label = label;
        this.packageName = packageName;
        this.matchVersionName = matchVersionName;
        this.matchVersionCode = matchVersionCode;
        this.versionCode = versionCode;
        this.imagePath = imagePath;
        this.alias = alias;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.activityClass = activityClass;
        this.viewClass = viewClass;
        this.resourceName = resourceName;
        this.text = text;
        this.description = description;
        this.visibility = visibility;
        this.timestamp = timestamp;
    }

    protected RuleRecord(Parcel in) {
        ruleTag = in.readString();
        label = in.readString();
        packageName = in.readString();
        matchVersionName = in.readString();
        matchVersionCode = in.readInt();
        versionCode = in.readInt();
        imagePath = in.readString();
        alias = in.readString();
        x = in.readInt();
        y = in.readInt();
        width = in.readInt();
        height = in.readInt();
        depth = in.createIntArray();
        activityClass = in.readString();
        viewClass = in.readString();
        resourceName = in.readString();
        text = in.readString();
        description = in.readString();
        String modeName = in.readString();
        matchMode = modeName != null ? MatchMode.valueOf(modeName) : null;
        matchThreshold = in.readInt();
        visibility = in.readInt();
        timestamp = in.readLong();
        modWidth = in.readInt();
        modHeight = in.readInt();
        modAlpha = in.readFloat();
        modXOffset = in.readInt();
        modYOffset = in.readInt();
        modText = in.readString();
        modImagePath = in.readString();
        origWidth = in.readInt();
        origHeight = in.readInt();
        origAlpha = in.readFloat();
        origText = in.readString();
        origLeftMargin = in.readInt();
        origTopMargin = in.readInt();
        itemPath = in.createStringArray();
        itemRootClass = in.readString();
        parentClass = in.readString();
        repeatable = in.readByte() != 0;
        String levelName = in.readString();
        targetLevel = levelName != null ? TargetLevel.valueOf(levelName) : null;
    }

    // =========================================================================
    // Parcelable
    // =========================================================================

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(ruleTag);
        dest.writeString(label);
        dest.writeString(packageName);
        dest.writeString(matchVersionName);
        dest.writeInt(matchVersionCode);
        dest.writeInt(versionCode);
        dest.writeString(imagePath);
        dest.writeString(alias);
        dest.writeInt(x);
        dest.writeInt(y);
        dest.writeInt(width);
        dest.writeInt(height);
        dest.writeIntArray(depth);
        dest.writeString(activityClass);
        dest.writeString(viewClass);
        dest.writeString(resourceName);
        dest.writeString(text);
        dest.writeString(description);
        dest.writeString(matchMode != null ? matchMode.name() : null);
        dest.writeInt(matchThreshold);
        dest.writeInt(visibility);
        dest.writeLong(timestamp);
        dest.writeInt(modWidth);
        dest.writeInt(modHeight);
        dest.writeFloat(modAlpha);
        dest.writeInt(modXOffset);
        dest.writeInt(modYOffset);
        dest.writeString(modText);
        dest.writeString(modImagePath);
        dest.writeInt(origWidth);
        dest.writeInt(origHeight);
        dest.writeFloat(origAlpha);
        dest.writeString(origText);
        dest.writeInt(origLeftMargin);
        dest.writeInt(origTopMargin);
        dest.writeStringArray(itemPath);
        dest.writeString(itemRootClass);
        dest.writeString(parentClass);
        dest.writeByte((byte) (repeatable ? 1 : 0));
        dest.writeString(targetLevel != null ? targetLevel.name() : null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RuleRecord> CREATOR = new Creator<RuleRecord>() {
        @Override
        public RuleRecord createFromParcel(Parcel in) {
            return new RuleRecord(in);
        }

        @Override
        public RuleRecord[] newArray(int size) {
            return new RuleRecord[size];
        }
    };

    // =========================================================================
    // Clone
    // =========================================================================

    @NonNull
    @Override
    public RuleRecord clone() {
        RuleRecord v = new RuleRecord(label, packageName, matchVersionName, matchVersionCode, versionCode,
                imagePath, alias, x, y, width, height, depth, activityClass, viewClass,
                resourceName, text, description, visibility, timestamp);
        v.ruleTag = ruleTag;
        v.modWidth = modWidth;
        v.modHeight = modHeight;
        v.modAlpha = modAlpha;
        v.modXOffset = modXOffset;
        v.modYOffset = modYOffset;
        v.modText = modText;
        v.modImagePath = modImagePath;
        v.origWidth = origWidth;
        v.origHeight = origHeight;
        v.origAlpha = origAlpha;
        v.origText = origText;
        v.origLeftMargin = origLeftMargin;
        v.origTopMargin = origTopMargin;
        v.itemPath = itemPath != null ? itemPath.clone() : null;
        v.itemRootClass = itemRootClass;
        v.parentClass = parentClass;
        v.repeatable = repeatable;
        v.matchMode = matchMode;
        v.matchThreshold = matchThreshold;
        v.targetLevel = targetLevel;
        return v;
    }

    // =========================================================================
    // 业务方法
    // =========================================================================

    public int getViewId(Resources res) {
        if (!TextUtils.isEmpty(resourceName)) {
            String[] start = resourceName.split(":");
            if (start.length < 2) return View.NO_ID;
            String[] end = start[1].split("/");
            if (end.length < 2) return View.NO_ID;
            return res.getIdentifier(end[1], end[0], start[0]);
        }
        return View.NO_ID;
    }

    public boolean isRemoveRule() {
        return ruleTag == null || ruleTag.isEmpty();
    }

    public boolean isModifyRule() {
        return ruleTag != null && !ruleTag.isEmpty();
    }

    public boolean isWidthModified() { return modWidth >= 0; }
    public boolean isHeightModified() { return modHeight >= 0; }
    public boolean isAlphaModified() { return modAlpha >= 0f; }
    public boolean isPositionModified() { return modXOffset != 0 || modYOffset != 0; }
    public boolean isTextModified() { return modText != null; }
    public boolean isImageModified() { return modImagePath != null; }
    /** @deprecated 使用 {@link #isRepeatable()} 替代 */
    @Deprecated public boolean isRepeatableRule() { return repeatable; }

    public boolean hasModifications() {
        return isWidthModified() || isHeightModified() || isAlphaModified()
                || isPositionModified() || isTextModified() || isImageModified();
    }

    // =========================================================================
    // equals / hashCode（完全不动，保持原有窄匹配语义）
    // =========================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleRecord that = (RuleRecord) o;
        if (!Objects.equals(activityClass, that.activityClass)) return false;
        if (!Objects.equals(viewClass, that.viewClass)) return false;
        if (repeatable && that.repeatable) {
            return Arrays.equals(itemPath, that.itemPath);
        }
        return Arrays.equals(depth, that.depth);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(activityClass);
        result = 31 * result + Objects.hashCode(viewClass);
        if (repeatable && itemPath != null) {
            result = 31 * result + Arrays.hashCode(itemPath);
        } else {
            result = 31 * result + Arrays.hashCode(depth);
        }
        return result;
    }

    // =========================================================================
    // toString
    // =========================================================================

    @NonNull
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("RuleRecord{");
        sb.append("ruleTag='").append(ruleTag).append('\'');
        sb.append(", label='").append(label).append('\'');
        sb.append(", packageName='").append(packageName).append('\'');
        sb.append(", activityClass='").append(activityClass).append('\'');
        sb.append(", viewClass='").append(viewClass).append('\'');
        sb.append(", depth=").append(Arrays.toString(depth));
        if (itemPath != null) sb.append(", itemPath=").append(Arrays.toString(itemPath));
        if (itemRootClass != null) sb.append(", itemRootClass='").append(itemRootClass).append('\'');
        if (parentClass != null) sb.append(", parentClass='").append(parentClass).append('\'');
        if (repeatable) sb.append(", repeatable=true");
        sb.append(", alias='").append(alias).append('\'');
        if (isRemoveRule()) {
            sb.append(", x=").append(x).append(", y=").append(y);
            sb.append(", width=").append(width).append(", height=").append(height);
        }
        if (isModifyRule()) {
            if (isWidthModified()) sb.append(", modWidth=").append(modWidth);
            if (isHeightModified()) sb.append(", modHeight=").append(modHeight);
            if (isAlphaModified()) sb.append(", modAlpha=").append(modAlpha);
            if (isPositionModified()) sb.append(", pos=(").append(modXOffset).append(",").append(modYOffset).append(")");
            if (isTextModified()) sb.append(", modText='").append(modText).append('\'');
            if (isImageModified()) sb.append(", modImagePath='").append(modImagePath).append('\'');
        }
        sb.append(", visibility=").append(visibility);
        sb.append(", timestamp=").append(timestamp);
        sb.append('}');
        return sb.toString();
    }
}
