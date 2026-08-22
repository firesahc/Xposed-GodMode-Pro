package com.kaisar.xposed.godmode.rule;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.kaisar.xposed.godmode.engine.matcher.MatchMode;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.ModifyEffect;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Locks the legacy parcel slot order while RuleRecord stores internal components. */
@RunWith(AndroidJUnit4.class)
public final class RuleRecordParcelInstrumentedTest {

    @Test
    public void writeToParcelKeepsV69FlatSlotOrder() {
        RuleRecord record = record();
        Parcel parcel = Parcel.obtain();
        try {
            record.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);

            assertEquals("modify", parcel.readString());
            assertEquals("label", parcel.readString());
            assertEquals("com.example", parcel.readString());
            assertEquals("1", parcel.readString());
            assertEquals(1, parcel.readInt());
            assertEquals(69, parcel.readInt());
            assertEquals("preview.png", parcel.readString());
            assertEquals("alias", parcel.readString());
            assertEquals(1, parcel.readInt());
            assertEquals(2, parcel.readInt());
            assertEquals(3, parcel.readInt());
            assertEquals(4, parcel.readInt());
            assertArrayEquals(new int[] {1, 2}, parcel.createIntArray());
            assertEquals("ExampleActivity", parcel.readString());
            assertEquals("TextView", parcel.readString());
            assertEquals("com.example:id/title", parcel.readString());
            assertEquals("raw text", parcel.readString());
            assertEquals("raw description", parcel.readString());
            assertEquals("CONTAINS", parcel.readString());
            assertEquals(7, parcel.readInt());
            assertEquals(4, parcel.readInt());
            assertEquals(5L, parcel.readLong());
            assertEquals(80, parcel.readInt());
            assertEquals(81, parcel.readInt());
            assertEquals(.5f, parcel.readFloat(), 0f);
            assertEquals(3, parcel.readInt());
            assertEquals(4, parcel.readInt());
            assertEquals("replacement", parcel.readString());
            assertEquals("replacement.png", parcel.readString());
            assertEquals(30, parcel.readInt());
            assertEquals(40, parcel.readInt());
            assertEquals(.7f, parcel.readFloat(), 0f);
            assertEquals("original", parcel.readString());
            assertEquals(17, parcel.readInt());
            assertEquals(18, parcel.readInt());
            assertArrayEquals(new String[] {"row", "title"}, parcel.createStringArray());
            assertEquals("FrameLayout", parcel.readString());
            assertEquals("LinearLayout", parcel.readString());
            assertEquals(1, parcel.readByte());
            assertEquals("CARD", parcel.readString());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void creatorReadsAnOldFlatParcelFixture() {
        Parcel parcel = Parcel.obtain();
        try {
            writeV69Fixture(parcel);
            parcel.setDataPosition(0);
            RuleRecord record = RuleRecord.CREATOR.createFromParcel(parcel);

            assertEquals("modify", record.getRuleTag());
            assertEquals("ExampleActivity", record.getActivityClass());
            assertArrayEquals(new String[] {"row", "title"}, record.getItemPath());
            assertEquals(MatchMode.CONTAINS, record.getMatchMode());
            assertEquals(TargetLevel.CARD, record.getTargetLevel());
            assertEquals("replacement.png", record.getModImagePath());
            assertEquals(17, record.getOrigLeftMargin());
            assertEquals(18, record.getOrigTopMargin());
        } finally {
            parcel.recycle();
        }
    }

    /** Unknown enum names degrade to null (defaulted semantics per MatchFields), never throw. */
    @Test
    public void creatorToleratesUnknownEnumNames() {
        Parcel parcel = Parcel.obtain();
        try {
            writeV69Fixture(parcel, "BOGUS_MODE", "BOGUS_LEVEL");
            parcel.setDataPosition(0);
            RuleRecord record = RuleRecord.CREATOR.createFromParcel(parcel);

            assertNull(record.getMatchMode());
            assertNull(record.getTargetLevel());
            // 其余槽位不受枚举降级影响
            assertEquals("modify", record.getRuleTag());
            assertEquals("ExampleActivity", record.getActivityClass());
            assertArrayEquals(new String[] {"row", "title"}, record.getItemPath());
        } finally {
            parcel.recycle();
        }
    }

    private static void writeV69Fixture(Parcel parcel) {
        writeV69Fixture(parcel, "CONTAINS", "CARD");
    }

    private static void writeV69Fixture(Parcel parcel, String modeName, String levelName) {
        parcel.writeString("modify");
        parcel.writeString("label");
        parcel.writeString("com.example");
        parcel.writeString("1");
        parcel.writeInt(1);
        parcel.writeInt(69);
        parcel.writeString("preview.png");
        parcel.writeString("alias");
        parcel.writeInt(1);
        parcel.writeInt(2);
        parcel.writeInt(3);
        parcel.writeInt(4);
        parcel.writeIntArray(new int[] {1, 2});
        parcel.writeString("ExampleActivity");
        parcel.writeString("TextView");
        parcel.writeString("com.example:id/title");
        parcel.writeString("raw text");
        parcel.writeString("raw description");
        parcel.writeString(modeName);
        parcel.writeInt(7);
        parcel.writeInt(4);
        parcel.writeLong(5L);
        parcel.writeInt(80);
        parcel.writeInt(81);
        parcel.writeFloat(.5f);
        parcel.writeInt(3);
        parcel.writeInt(4);
        parcel.writeString("replacement");
        parcel.writeString("replacement.png");
        parcel.writeInt(30);
        parcel.writeInt(40);
        parcel.writeFloat(.7f);
        parcel.writeString("original");
        parcel.writeInt(17);
        parcel.writeInt(18);
        parcel.writeStringArray(new String[] {"row", "title"});
        parcel.writeString("FrameLayout");
        parcel.writeString("LinearLayout");
        parcel.writeByte((byte) 1);
        parcel.writeString(levelName);
    }

    private static RuleRecord record() {
        MatchSpec match = new MatchSpec.Builder()
                .depth(new int[] {1, 2}).activityClass("ExampleActivity").viewClass("TextView")
                .resourceName("com.example:id/title").itemPath(new String[] {"row", "title"})
                .itemRootClass("FrameLayout").parentClass("LinearLayout").repeatable(true)
                .text("raw text").description("raw description").matchMode(MatchMode.CONTAINS)
                .viewType(7).targetLevel(TargetLevel.CARD).build();
        ModifyEffect effect = new ModifyEffect.Builder().ruleTag("modify").visibility(4)
                .modWidth(80).modHeight(81).modAlpha(.5f).modXOffset(3).modYOffset(4)
                .modText("replacement").modImagePath("replacement.png")
                .origLeftMargin(17).origTopMargin(18).build();
        return new RuleRecord("label", "com.example", "1", 1, 69, "preview.png", "alias",
                1, 2, 3, 4, 5L, 30, 40, .7f, "original", match, effect);
    }
}
