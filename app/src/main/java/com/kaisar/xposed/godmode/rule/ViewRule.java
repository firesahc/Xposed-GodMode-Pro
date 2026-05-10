package com.kaisar.xposed.godmode.rule;

import android.content.res.Resources;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.google.gson.annotations.SerializedName;

import java.util.Arrays;

@Keep
public final class ViewRule implements Parcelable, Cloneable {

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
    @SerializedName("text")
    public String text;
    @SerializedName("description")
    public String description;
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

    // --- 原始值 (用于应用修改时计算) ---
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

    @SuppressWarnings("unused")
    private ViewRule() {
    }

    public ViewRule(String label, String packageName, String matchVersionName, int matchVersionCode,
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

    protected ViewRule(Parcel in) {
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
    }

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
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ViewRule> CREATOR = new Creator<ViewRule>() {
        @Override
        public ViewRule createFromParcel(Parcel in) {
            return new ViewRule(in);
        }

        @Override
        public ViewRule[] newArray(int size) {
            return new ViewRule[size];
        }
    };

    @NonNull
    @Override
    public ViewRule clone() {
        ViewRule v = new ViewRule(label, packageName, matchVersionName, matchVersionCode, versionCode,
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
        return v;
    }

    public int getViewId(Resources res) {
        if (!TextUtils.isEmpty(resourceName)) {
            String[] start = resourceName.split(":");
            String[] end = start[1].split("/");
            return res.getIdentifier(end[1], end[0], start[0]);
        }
        return View.NO_ID;
    }

    public void captureOriginals(View view) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            origWidth = lp.width;
            origHeight = lp.height;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                origLeftMargin = mlp.leftMargin;
                origTopMargin = mlp.topMargin;
            }
        }
        if (lp == null || origWidth <= 0) origWidth = view.getWidth();
        if (lp == null || origHeight <= 0) origHeight = view.getHeight();
        origAlpha = view.getAlpha();
        if (view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            origText = t != null ? t.toString() : "";
        }
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

    public boolean hasModifications() {
        return isWidthModified() || isHeightModified() || isAlphaModified()
                || isPositionModified() || isTextModified() || isImageModified();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ViewRule that = (ViewRule) o;
        if (!activityClass.equals(that.activityClass)) return false;
        if (!viewClass.equals(that.viewClass)) return false;
        return Arrays.equals(depth, that.depth);
    }

    @Override
    public int hashCode() {
        int result = activityClass.hashCode();
        result = 31 * result + viewClass.hashCode();
        result = 31 * result + Arrays.hashCode(depth);
        return result;
    }

    @NonNull
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ViewRule{");
        sb.append("ruleTag='").append(ruleTag).append('\'');
        sb.append(", label='").append(label).append('\'');
        sb.append(", packageName='").append(packageName).append('\'');
        sb.append(", activityClass='").append(activityClass).append('\'');
        sb.append(", viewClass='").append(viewClass).append('\'');
        sb.append(", depth=").append(Arrays.toString(depth));
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
