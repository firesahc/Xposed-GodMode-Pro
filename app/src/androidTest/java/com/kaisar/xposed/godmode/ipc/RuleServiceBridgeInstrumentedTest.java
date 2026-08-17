package com.kaisar.xposed.godmode.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.IBinder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.kaisar.xposed.godmode.ipc.contract.IRuleService;
import com.kaisar.xposed.godmode.ipc.contract.ILeaseOwner;
import com.kaisar.xposed.godmode.ipc.contract.OperationLeaseParcel;
import com.kaisar.xposed.godmode.ipc.contract.ServiceIdentityParcel;
import com.kaisar.xservicemanager.XServiceManager;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class RuleServiceBridgeInstrumentedTest {

    @Test
    public void liveBridgePublishesExpectedReadyService() throws Exception {
        requireLiveBridge();

        assertTrue("bridge ping failed: " + XServiceManager.getLastError(),
                XServiceManager.pingBridge());
        IBinder binder = XServiceManager.getService(RuleServiceContract.SERVICE_NAME);
        assertNotNull("service lookup failed: " + XServiceManager.getLastError(), binder);
        assertEquals(RuleServiceContract.DESCRIPTOR, binder.getInterfaceDescriptor());

        IRuleService service = IRuleService.Stub.asInterface(binder);
        ServiceIdentityParcel identity = service.getServiceIdentity();
        assertNotNull(identity);
        assertEquals(RuleServiceContract.PROTOCOL_VERSION, identity.protocolVersion);
        assertEquals(RuleServiceContract.BUILD_VERSION_CODE, identity.buildVersionCode);
        assertEquals(RuleServiceContract.CONTRACT_FINGERPRINT,
                identity.contractFingerprint);
        assertEquals(RuleServiceContract.READY, identity.serviceState);
        assertTrue("manager UID was rejected after service startup", service.hasLight());
    }

    @Test
    public void liveClientCompletesIdentityHandshake() {
        requireLiveBridge();

        RuleServiceClient client = RuleServiceClient.getDefault();
        assertTrue(client.getLastError(), client.awaitReady(5_000L));
        assertEquals(RuleServiceContract.READY, client.getServiceState());
        assertTrue(client.hasLight());
    }

    @Test
    public void liveMutationLeaseCloseIsIdempotent() throws Exception {
        requireLiveBridge();

        IRuleService service = IRuleService.Stub.asInterface(
                XServiceManager.getService(RuleServiceContract.SERVICE_NAME));
        assertNotNull(service);
        ILeaseOwner owner = new ILeaseOwner.Stub() {
            @Override public void onLeaseRevoked(int reason) { }
        };
        String managerPackage = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getPackageName();
        OperationLeaseParcel lease = service.openOperation(
                RuleServiceContract.OP_MUTATION, managerPackage, owner);
        assertNotNull(lease);
        assertEquals(RuleServiceContract.RESULT_COMMITTED, lease.status);

        OperationLeaseParcel first = service.closeOperation(lease.token, owner);
        OperationLeaseParcel second = service.closeOperation(lease.token, owner);
        assertEquals(RuleServiceContract.RESULT_COMMITTED, first.status);
        assertEquals(RuleServiceContract.RESULT_NO_CHANGE, second.status);
    }

    private static void requireLiveBridge() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("requires an LSPosed system_server bridge",
                Boolean.parseBoolean(arguments.getString("requireGodModeBridge", "false")));
    }
}
