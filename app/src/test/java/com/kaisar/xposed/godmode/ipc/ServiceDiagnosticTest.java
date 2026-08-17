package com.kaisar.xposed.godmode.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ServiceDiagnosticTest {

    @Test
    public void everyFailureTypeProvidesSummaryAndAction() {
        for (ServiceDiagnostic.Type type : ServiceDiagnostic.Type.values()) {
            ServiceDiagnostic diagnostic = ServiceDiagnostic.of(type, "binder internal detail");

            assertEquals(type, diagnostic.getType());
            assertFalse(diagnostic.getSummary().trim().isEmpty());
            assertFalse(diagnostic.getAction().trim().isEmpty());
            assertTrue(diagnostic.getUserMessage().contains(diagnostic.getSummary()));
            assertTrue(diagnostic.getUserMessage().contains(diagnostic.getAction()));
            assertFalse(diagnostic.getUserMessage().contains("binder internal detail"));
            assertEquals("binder internal detail", diagnostic.getTechnicalDetail());
        }
    }

    @Test
    public void serviceStateMapsToStableUserCategory() {
        assertEquals(ServiceDiagnostic.Type.SERVICE_STARTING,
                ServiceDiagnostic.forServiceState(RuleServiceContract.STARTING, "starting")
                        .getType());
        assertEquals(ServiceDiagnostic.Type.CONTRACT_MISMATCH,
                ServiceDiagnostic.forServiceState(RuleServiceContract.REBOOT_REQUIRED, "reboot")
                        .getType());
        assertEquals(ServiceDiagnostic.Type.UNKNOWN,
                ServiceDiagnostic.forServiceState(RuleServiceContract.FAILED, "failed")
                        .getType());
    }

    @Test
    public void resultStatusDistinguishesBusyPermissionAndContractFailures() {
        assertEquals(ServiceDiagnostic.Type.OPERATION_BUSY,
                ServiceDiagnostic.forResultStatus(RuleServiceContract.RESULT_BUSY, "busy")
                        .getType());
        assertEquals(ServiceDiagnostic.Type.PERMISSION_REJECTED,
                ServiceDiagnostic.forResultStatus(RuleServiceContract.RESULT_REJECTED, "denied")
                        .getType());
        assertEquals(ServiceDiagnostic.Type.CONTRACT_MISMATCH,
                ServiceDiagnostic.forResultStatus(
                        RuleServiceContract.RESULT_REBOOT_REQUIRED, "reboot").getType());
        assertEquals(ServiceDiagnostic.Type.UNKNOWN,
                ServiceDiagnostic.forResultStatus(RuleServiceContract.RESULT_WRITE_FAILED, "io")
                        .getType());
        assertEquals(ServiceDiagnostic.Type.COMMIT_UNCERTAIN,
                ServiceDiagnostic.forResultStatus(RuleServiceContract.RESULT_UNCERTAIN, "lost")
                        .getType());
    }

    @Test
    public void onlyContractCategoriesClaimVersionMismatch() {
        assertTrue(ServiceDiagnostic.of(ServiceDiagnostic.Type.DESCRIPTOR_MISMATCH, null)
                .getSummary().contains("版本不匹配"));
        assertTrue(ServiceDiagnostic.of(ServiceDiagnostic.Type.CONTRACT_MISMATCH, null)
                .getSummary().contains("合同不匹配"));

        assertFalse(ServiceDiagnostic.of(ServiceDiagnostic.Type.BRIDGE_UNAVAILABLE, null)
                .getUserMessage().contains("版本不匹配"));
        assertFalse(ServiceDiagnostic.of(ServiceDiagnostic.Type.SERVICE_STARTING, null)
                .getUserMessage().contains("版本不匹配"));
        assertFalse(ServiceDiagnostic.of(ServiceDiagnostic.Type.BINDER_DIED, null)
                .getUserMessage().contains("版本不匹配"));
        assertFalse(ServiceDiagnostic.of(ServiceDiagnostic.Type.PERMISSION_REJECTED, null)
                .getUserMessage().contains("版本不匹配"));
        assertFalse(ServiceDiagnostic.of(ServiceDiagnostic.Type.OPERATION_BUSY, null)
                .getUserMessage().contains("版本不匹配"));
        assertFalse(ServiceDiagnostic.of(ServiceDiagnostic.Type.COMMIT_UNCERTAIN, null)
                .getUserMessage().contains("版本不匹配"));
        assertFalse(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN, null)
                .getUserMessage().contains("版本不匹配"));
    }
}
