package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.RemoteException;

import com.kaisar.xposed.godmode.ipc.RuleServiceContract;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PermissionEnforcerTest {
    private static final String MODULE = "com.example.manager";
    private static final String PACKAGE_A = "com.example.a";
    private static final String PACKAGE_B = "com.example.b";

    @Test
    public void managerMayUseGlobalOrPackageScope() throws Exception {
        PermissionEnforcer enforcer = enforcer(10001, packages(
                10001, MODULE,
                20001, PACKAGE_A));

        enforcer.enforcePackageOrManager(RuleServiceContract.GLOBAL_SCOPE, true, "denied");
        enforcer.enforcePackageOrManager(PACKAGE_B, false, "denied");
        assertTrue(enforcer.isModuleUid(10001));
    }

    @Test
    public void targetMayUseOnlyPackagesOwnedByItsUid() throws Exception {
        PermissionEnforcer enforcer = enforcer(20001, packages(
                10001, MODULE,
                20001, PACKAGE_A));

        enforcer.enforcePackageOrManager(PACKAGE_A, false, "denied");
        assertRejected(() -> enforcer.enforcePackageOrManager(
                PACKAGE_B, false, "denied"));
        assertRejected(() -> enforcer.enforcePackageOrManager(
                RuleServiceContract.GLOBAL_SCOPE, true, "denied"));
    }

    @Test
    public void sharedUidOwnsEveryPackageReportedForThatUid() throws Exception {
        Map<Integer, String[]> packages = new HashMap<>();
        packages.put(20001, new String[]{PACKAGE_A, PACKAGE_B});
        PermissionEnforcer enforcer = enforcer(20001, packages);

        assertTrue(enforcer.uidOwnsPackage(20001, PACKAGE_A));
        assertTrue(enforcer.uidOwnsPackage(20001, PACKAGE_B));
        assertFalse(enforcer.uidOwnsPackage(20001, "com.example.other"));
        enforcer.enforcePackageOrManager(PACKAGE_B, false, "denied");
    }

    @Test
    public void packageLookupIsDeferredUntilPermissionCheckAndFailsClosed() {
        AtomicInteger sourceCalls = new AtomicInteger();
        AtomicReference<PermissionEnforcer.PackageLookup> current = new AtomicReference<>();
        PermissionEnforcer.PackageLookup lookup = PermissionEnforcer.deferredPackageLookup(() -> {
            sourceCalls.incrementAndGet();
            return current.get();
        });
        PermissionEnforcer enforcer = new PermissionEnforcer(lookup, () -> 20001, MODULE);

        assertEquals(0, sourceCalls.get());
        assertFalse(enforcer.uidOwnsPackage(20001, PACKAGE_A));
        assertEquals(1, sourceCalls.get());

        current.set(uid -> uid == 20001 ? new String[]{PACKAGE_A} : null);
        assertTrue(enforcer.uidOwnsPackage(20001, PACKAGE_A));
        assertEquals(2, sourceCalls.get());
    }

    private static PermissionEnforcer enforcer(int callingUid,
                                               Map<Integer, String[]> packages) {
        return new PermissionEnforcer(packages::get, () -> callingUid, MODULE);
    }

    private static Map<Integer, String[]> packages(Object... entries) {
        Map<Integer, String[]> result = new HashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((Integer) entries[index], new String[]{(String) entries[index + 1]});
        }
        return result;
    }

    private static void assertRejected(RemoteCall call) throws Exception {
        try {
            call.run();
        } catch (RemoteException expected) {
            return;
        }
        throw new AssertionError("Expected permission rejection");
    }

    private interface RemoteCall {
        void run() throws RemoteException;
    }
}
