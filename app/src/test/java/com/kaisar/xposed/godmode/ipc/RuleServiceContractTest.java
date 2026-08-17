package com.kaisar.xposed.godmode.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kaisar.xposed.godmode.ipc.contract.ServiceIdentityParcel;

import org.junit.Test;

public final class RuleServiceContractTest {

    @Test
    public void canonicalIdentityIsStableForTheRelease() {
        assertEquals("godmode", RuleServiceContract.SERVICE_NAME);
        assertEquals(
                "com.kaisar.xposed.godmode.ipc.contract.IRuleService",
                RuleServiceContract.DESCRIPTOR);
        assertEquals(61000, RuleServiceContract.PROTOCOL_VERSION);
        assertEquals(61000, RuleServiceContract.BUILD_VERSION_CODE);
        assertEquals("iruleservice-61000-fd-mutate-v2",
                RuleServiceContract.CONTRACT_FINGERPRINT);
    }

    @Test
    public void mixedFdMutateContractIsRejectedBeforeUse() {
        assertTrue(RuleServiceClient.isExpectedIdentity(new ServiceIdentityParcel(
                61000, 61000, "iruleservice-61000-fd-mutate-v2",
                RuleServiceContract.READY)));
        assertFalse(RuleServiceClient.isExpectedIdentity(new ServiceIdentityParcel(
                61000, 61000, "iruleservice-61000-global-edit-mutation-v1",
                RuleServiceContract.READY)));
    }

    @Test
    public void readinessStatesRemainWireStable() {
        assertEquals(0, RuleServiceContract.STARTING);
        assertEquals(1, RuleServiceContract.READY);
        assertEquals(2, RuleServiceContract.REBOOT_REQUIRED);
        assertEquals(3, RuleServiceContract.FAILED);
    }

    @Test
    public void mutationAndLeaseContractsHaveSingleCanonicalValues() {
        assertEquals(1, RuleServiceContract.OP_EDIT);
        assertEquals(2, RuleServiceContract.OP_RESTORE);
        assertEquals(3, RuleServiceContract.OP_MUTATION);
        assertEquals(4, RuleServiceContract.OP_BACKUP);
        assertEquals(0, RuleServiceContract.RESULT_COMMITTED);
        assertEquals(2, RuleServiceContract.RESULT_BUSY);
        assertEquals(5, RuleServiceContract.RESULT_REBOOT_REQUIRED);
        assertEquals(7, RuleServiceContract.RESULT_UNCERTAIN);
        org.junit.Assert.assertFalse(
                RuleServiceContract.DESCRIPTOR.contains("IGodModeManager"));
        org.junit.Assert.assertFalse(
                RuleServiceContract.DESCRIPTOR.contains("IObserver"));
    }
}
