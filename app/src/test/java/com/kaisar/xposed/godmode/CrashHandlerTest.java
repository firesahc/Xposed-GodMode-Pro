package com.kaisar.xposed.godmode;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public final class CrashHandlerTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void restartMarkerSuppressesImmediateCrashLoop() {
        File marker = new File(temporaryFolder.getRoot(), "restart.marker");
        long firstCrash = 100_000L;

        assertTrue(CrashHandler.markRestartAttempt(marker, firstCrash));
        assertFalse(CrashHandler.markRestartAttempt(marker,
                firstCrash + CrashHandler.RESTART_LOOP_WINDOW_MS - 1L));
    }

    @Test
    public void restartMarkerAllowsLaterIndependentCrash() {
        File marker = new File(temporaryFolder.getRoot(), "restart.marker");
        long firstCrash = 100_000L;

        assertTrue(CrashHandler.markRestartAttempt(marker, firstCrash));
        assertTrue(CrashHandler.markRestartAttempt(marker,
                firstCrash + CrashHandler.RESTART_LOOP_WINDOW_MS));
    }

    @Test
    public void restartMarkerRejectsClockRollback() {
        File marker = new File(temporaryFolder.getRoot(), "restart.marker");

        assertTrue(CrashHandler.markRestartAttempt(marker, 100_000L));
        assertFalse(CrashHandler.markRestartAttempt(marker, 99_999L));
    }

    @Test
    public void restartMarkerRejectsNegativeTime() {
        File marker = new File(temporaryFolder.getRoot(), "restart.marker");

        assertFalse(CrashHandler.markRestartAttempt(marker, -1L));
    }

    @Test
    public void restartMarkerRejectsNonDirectoryParent() throws Exception {
        File parent = temporaryFolder.newFile("not-a-directory");
        File marker = new File(parent, "restart.marker");

        assertFalse(CrashHandler.markRestartAttempt(marker, 100_000L));
    }
}
