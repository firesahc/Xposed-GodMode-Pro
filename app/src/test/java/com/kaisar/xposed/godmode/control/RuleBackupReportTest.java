package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public final class RuleBackupReportTest {

    @Test
    public void restoreReportCountsPartialSuccessWithoutChangingWireData() {
        RuleBackupManager.RestoreReport report = new RuleBackupManager.RestoreReport(
                2, 1, Arrays.asList(
                new RuleBackupManager.EntryResult(0,
                        RuleBackupManager.EntryResult.Status.COMMITTED, "committed"),
                new RuleBackupManager.EntryResult(1,
                        RuleBackupManager.EntryResult.Status.REJECTED, "image is invalid")));

        assertEquals(2, report.total);
        assertEquals(1, report.committed);
        assertEquals(1, report.failed());
        assertTrue(report.entries.get(1).message.contains("invalid"));
    }
}
