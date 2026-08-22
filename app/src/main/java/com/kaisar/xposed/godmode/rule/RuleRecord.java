package com.kaisar.xposed.godmode.rule;

import android.content.res.Resources;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.RuleEffect;
import com.kaisar.xposed.godmode.engine.rule.RuleSlotKey;

import java.util.Objects;

/**
 * 持久化/IPC 兼容聚合对象。
 *
 * <p>稳定的匹配和效果职责由不可变 {@link MatchSpec}/{@link RuleEffect} 持有；
 * 本类只保留兼容、展示和采集字段。Gson 仍通过 {@link RuleRecordTypeAdapter}
 * 输出 v6.9 的扁平 JSON，Parcelable 仍按 v6.9 的旧槽位顺序展开。</p>
 */
@Keep
@JsonAdapter(RuleRecordTypeAdapter.class)
public final class RuleRecord implements Parcelable, Cloneable {

    // 兼容/展示/采集字段
    @SerializedName("label") public String label;
    @SerializedName("package_name") public String packageName;
    @SerializedName("match_version_name") public String matchVersionName;
    @SerializedName("match_version_code") public int matchVersionCode;
    @SerializedName("version_code") public int versionCode;
    @SerializedName("img_path") public String imagePath;
    @SerializedName("alias") public String alias;
    @SerializedName("x") public int x;
    @SerializedName("y") public int y;
    @SerializedName("width") public int width;
    @SerializedName("height") public int height;
    @SerializedName("timestamp") public long timestamp;
    @SerializedName("orig_width") public int origWidth;
    @SerializedName("orig_height") public int origHeight;
    @SerializedName("orig_alpha") public float origAlpha = 1f;
    @SerializedName("orig_text") public String origText;

    private final MatchSpec matchSpec;
    private final RuleEffect effect;

    /** Full constructor used by the flat codec and editor builders. */
    public RuleRecord(String label, String packageName, String matchVersionName,
                      int matchVersionCode, int versionCode, String imagePath, String alias,
                      int x, int y, int width, int height, long timestamp,
                      int origWidth, int origHeight, float origAlpha, String origText,
                      MatchSpec matchSpec, RuleEffect effect) {
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
        this.timestamp = timestamp;
        this.origWidth = origWidth;
        this.origHeight = origHeight;
        this.origAlpha = origAlpha;
        this.origText = origText;
        this.matchSpec = Objects.requireNonNull(matchSpec, "matchSpec must not be null");
        this.effect = Objects.requireNonNull(effect, "effect must not be null");
    }

    /** Legacy capture constructor retained while producers migrate to builders. */
    public RuleRecord(String label, String packageName, String matchVersionName, int matchVersionCode,
                      int versionCode, String imagePath, String alias, int x, int y, int width, int height,
                      int[] depth, String activityClass, String viewClass, String resourceName,
                      String text, String description, int visibility, long timestamp) {
        this(label, packageName, matchVersionName, matchVersionCode, versionCode, imagePath, alias,
                x, y, width, height, timestamp, 0, 0, 1f, null,
                new MatchSpec.Builder().depth(depth).activityClass(activityClass).viewClass(viewClass)
                        .resourceName(resourceName).text(text).description(description).build(),
                com.kaisar.xposed.godmode.engine.rule.RemoveEffect.of(visibility));
    }

    protected RuleRecord(Parcel in) {
        String ruleTag = in.readString();
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
        int[] depth = in.createIntArray();
        String activityClass = in.readString();
        String viewClass = in.readString();
        String resourceName = in.readString();
        String text = in.readString();
        String description = in.readString();
        String modeName = in.readString();
        MatchMode matchMode = MatchMode.fromName(modeName);
        int viewType = in.readInt();
        int visibility = in.readInt();
        timestamp = in.readLong();
        int modWidth = in.readInt();
        int modHeight = in.readInt();
        float modAlpha = in.readFloat();
        int modXOffset = in.readInt();
        int modYOffset = in.readInt();
        String modText = in.readString();
        String modImagePath = in.readString();
        origWidth = in.readInt();
        origHeight = in.readInt();
        origAlpha = in.readFloat();
        origText = in.readString();
        int origLeftMargin = in.readInt();
        int origTopMargin = in.readInt();
        String[] itemPath = in.createStringArray();
        String itemRootClass = in.readString();
        String parentClass = in.readString();
        boolean repeatable = in.readByte() != 0;
        String levelName = in.readString();
        TargetLevel targetLevel = TargetLevel.fromName(levelName);

        matchSpec = new MatchSpec.Builder().depth(depth).activityClass(activityClass).viewClass(viewClass)
                .resourceName(resourceName).itemPath(itemPath).itemRootClass(itemRootClass)
                .parentClass(parentClass).repeatable(repeatable).text(text).description(description)
                .matchMode(matchMode).viewType(viewType).targetLevel(targetLevel).build();
        RuleEffect.WireValues wire = new RuleEffect.WireValues.Builder().ruleTag(ruleTag)
                .visibility(visibility).modWidth(modWidth).modHeight(modHeight).modAlpha(modAlpha)
                .modXOffset(modXOffset).modYOffset(modYOffset).modText(modText)
                .modImagePath(modImagePath).origLeftMargin(origLeftMargin).origTopMargin(origTopMargin)
                .build();
        effect = RuleEffect.fromWireValues(wire);
    }

    public MatchSpec getMatchSpec() { return matchSpec; }
    public RuleEffect getEffect() { return effect; }
    public RuleSlotKey slotKey(String authoritativePackageName) {
        return RuleSlotKey.from(authoritativePackageName, matchSpec);
    }

    // Compatibility accessors for legacy callers; new Runtime code uses components directly.
    public String getRuleTag() { return effect.getRuleTag(); }
    public String getLabel() { return label; }
    public String getPackageName() { return packageName; }
    public String getMatchVersionName() { return matchVersionName; }
    public int getMatchVersionCode() { return matchVersionCode; }
    public int getVersionCode() { return versionCode; }
    public String getImagePath() { return imagePath; }
    public String getAlias() { return alias; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int[] getDepth() { return matchSpec.getDepth(); }
    public String getActivityClass() { return matchSpec.getActivityClass(); }
    public String getViewClass() { return matchSpec.getViewClass(); }
    public String getResourceName() { return matchSpec.getResourceName(); }
    public String[] getItemPath() { return matchSpec.getItemPath(); }
    public String getItemRootClass() { return matchSpec.getItemRootClass(); }
    public String getParentClass() { return matchSpec.getParentClass(); }
    public boolean isRepeatable() { return matchSpec.isRepeatable(); }
    public String getText() { return matchSpec.getText(); }
    public String getDescription() { return matchSpec.getDescription(); }
    public MatchMode getMatchMode() { return matchSpec.getMatchMode(); }
    public int getInfoFlowViewType() { return matchSpec.getInfoFlowViewType(); }
    public TargetLevel getTargetLevel() { return matchSpec.getTargetLevel(); }
    public int getVisibility() { return effect.toWireValues().getVisibility(); }
    public long getTimestamp() { return timestamp; }
    public int getModWidth() { return effect.toWireValues().getModWidth(); }
    public int getModHeight() { return effect.toWireValues().getModHeight(); }
    public float getModAlpha() { return effect.toWireValues().getModAlpha(); }
    public int getModXOffset() { return effect.toWireValues().getModXOffset(); }
    public int getModYOffset() { return effect.toWireValues().getModYOffset(); }
    public String getModText() { return effect.toWireValues().getModText(); }
    public String getModImagePath() { return effect.toWireValues().getModImagePath(); }
    public int getOrigWidth() { return origWidth; }
    public int getOrigHeight() { return origHeight; }
    public float getOrigAlpha() { return origAlpha; }
    public String getOrigText() { return origText; }
    public int getOrigLeftMargin() { return effect.toWireValues().getOrigLeftMargin(); }
    public int getOrigTopMargin() { return effect.toWireValues().getOrigTopMargin(); }

    public boolean isRemoveRule() { return effect.isRemove(); }
    public boolean isModifyRule() { return effect.isModify(); }
    public boolean isWidthModified() { return getModWidth() >= 0; }
    public boolean isHeightModified() { return getModHeight() >= 0; }
    public boolean isAlphaModified() { return getModAlpha() >= 0f; }
    public boolean isPositionModified() { return getModXOffset() != 0 || getModYOffset() != 0; }
    public boolean isTextModified() { return getModText() != null; }
    public boolean isImageModified() { return getModImagePath() != null; }
    public boolean hasModifications() {
        return isWidthModified() || isHeightModified() || isAlphaModified()
                || isPositionModified() || isTextModified() || isImageModified();
    }

    public int getViewId(Resources res) {
        if (!TextUtils.isEmpty(getResourceName())) {
            String[] start = getResourceName().split(":");
            if (start.length < 2) return View.NO_ID;
            String[] end = start[1].split("/");
            if (end.length < 2) return View.NO_ID;
            return res.getIdentifier(end[1], end[0], start[0]);
        }
        return View.NO_ID;
    }

    public RuleRecord withEffect(RuleEffect newEffect) {
        return copyWith(matchSpec, newEffect, label, packageName, matchVersionName,
                matchVersionCode, versionCode, imagePath, alias);
    }

    /** Replaces the immutable matching component without changing wire metadata. */
    public RuleRecord withMatchSpec(MatchSpec newMatchSpec) {
        return copyWith(Objects.requireNonNull(newMatchSpec, "matchSpec must not be null"), effect,
                label, packageName, matchVersionName, matchVersionCode, versionCode, imagePath, alias);
    }

    /** Rebinds a cloned record to the caller-authoritative package scope. */
    public RuleRecord withPackageName(String newPackageName) {
        return copyWith(matchSpec, effect, label, newPackageName, matchVersionName,
                matchVersionCode, versionCode, imagePath, alias);
    }

    public RuleRecord withAlias(String newAlias) {
        return copyWith(matchSpec, effect, label, packageName, matchVersionName,
                matchVersionCode, versionCode, imagePath, newAlias);
    }

    public RuleRecord withImagePath(String newImagePath) {
        return copyWith(matchSpec, effect, label, packageName, matchVersionName,
                matchVersionCode, versionCode, newImagePath, alias);
    }

    /** Returns a new record with the flat-wire replacement-image value updated. */
    public RuleRecord withModifyImagePath(String newModImagePath) {
        RuleEffect.WireValues wire = effect.toWireValues().toBuilder()
                .modImagePath(newModImagePath)
                .build();
        return withEffect(RuleEffect.fromWireValues(wire));
    }

    private RuleRecord copyWith(MatchSpec spec, RuleEffect newEffect, String newLabel,
                                String newPackage, String newVersionName, int newMatchVersionCode,
                                int newVersionCode, String newImagePath, String newAlias) {
        return new RuleRecord(newLabel, newPackage, newVersionName, newMatchVersionCode,
                newVersionCode, newImagePath, newAlias, x, y, width, height, timestamp,
                origWidth, origHeight, origAlpha, origText, spec, newEffect);
    }

    public void writeToParcel(Parcel dest, int flags) {
        RuleEffect.WireValues wire = effect.toWireValues();
        dest.writeString(wire.getRuleTag());
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
        dest.writeIntArray(matchSpec.getDepth());
        dest.writeString(matchSpec.getActivityClass());
        dest.writeString(matchSpec.getViewClass());
        dest.writeString(matchSpec.getResourceName());
        dest.writeString(matchSpec.getText());
        dest.writeString(matchSpec.getDescription());
        dest.writeString(matchSpec.getMatchMode() != null ? matchSpec.getMatchMode().name() : null);
        dest.writeInt(matchSpec.getInfoFlowViewType());
        dest.writeInt(wire.getVisibility());
        dest.writeLong(timestamp);
        dest.writeInt(wire.getModWidth());
        dest.writeInt(wire.getModHeight());
        dest.writeFloat(wire.getModAlpha());
        dest.writeInt(wire.getModXOffset());
        dest.writeInt(wire.getModYOffset());
        dest.writeString(wire.getModText());
        dest.writeString(wire.getModImagePath());
        dest.writeInt(origWidth);
        dest.writeInt(origHeight);
        dest.writeFloat(origAlpha);
        dest.writeString(origText);
        dest.writeInt(wire.getOrigLeftMargin());
        dest.writeInt(wire.getOrigTopMargin());
        dest.writeStringArray(matchSpec.getItemPath());
        dest.writeString(matchSpec.getItemRootClass());
        dest.writeString(matchSpec.getParentClass());
        dest.writeByte((byte) (matchSpec.isRepeatable() ? 1 : 0));
        dest.writeString(matchSpec.getTargetLevel() != null ? matchSpec.getTargetLevel().name() : null);
    }

    public int describeContents() { return 0; }

    public static final Creator<RuleRecord> CREATOR = new Creator<RuleRecord>() {
        public RuleRecord createFromParcel(Parcel in) { return new RuleRecord(in); }
        public RuleRecord[] newArray(int size) { return new RuleRecord[size]; }
    };

    @NonNull
    public RuleRecord clone() {
        return new RuleRecord(label, packageName, matchVersionName, matchVersionCode,
                versionCode, imagePath, alias, x, y, width, height, timestamp, origWidth,
                origHeight, origAlpha, origText, matchSpec.clone(), effect);
    }

    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof RuleRecord)) return false;
        RuleRecord other = (RuleRecord) object;
        return slotKey(packageName).equals(other.slotKey(other.packageName));
    }

    public int hashCode() { return slotKey(packageName).hashCode(); }

    public boolean contentEquals(@NonNull RuleRecord other) {
        return Objects.equals(alias, other.alias)
                && Objects.equals(matchSpec, other.matchSpec)
                && Objects.equals(effect, other.effect)
                && Objects.equals(imagePath, other.imagePath)
                && x == other.x && y == other.y && width == other.width && height == other.height;
    }

    @NonNull
    public String toString() {
        return "RuleRecord{" + slotKey(packageName) + ", alias='" + alias + '\''
                + ", imagePath='" + imagePath + '\'' + ", effect=" + effect.getKind() + '}';
    }
}
