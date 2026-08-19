package com.kaisar.xposed.godmode.control;

import android.util.Log;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GodModeLogTest {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS");

    @Test
    public void formatLineRetainsTimestampPidLevelAndEscapesNewlines() {
        long timestamp = 1_700_000_000_123L;
        String expectedTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp),
                ZoneId.systemDefault()).format(FORMATTER);

        String line = GodModeLog.formatLine(Log.DEBUG, "com.example.app", "TestTag",
                "line1\nline2\\path", timestamp, 4321);

        assertTrue(line.startsWith(expectedTime + " 4321 D/TestTag: [com.example.app] "));
        assertTrue(line.endsWith("line1\\nline2\\\\path"));
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
    }

    @Test
    public void formatLineUsesCanonicalLevelTags() {
        long timestamp = 1_700_000_000_123L;
        assertTrue(GodModeLog.formatLine(Log.INFO, "pkg", "tag", "msg",
                timestamp, 1).contains(" I/tag: "));
        assertTrue(GodModeLog.formatLine(Log.WARN, "pkg", "tag", "msg",
                timestamp, 1).contains(" W/tag: "));
        assertTrue(GodModeLog.formatLine(Log.ERROR, "pkg", "tag", "msg",
                timestamp, 1).contains(" E/tag: "));
        assertTrue(GodModeLog.formatLine(999, "pkg", "tag", "msg",
                timestamp, 1).contains(" ?/tag: "));
    }
}
