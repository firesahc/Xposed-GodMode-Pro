package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PackageNameValidatorTest {

    @Test
    public void acceptsAndroidStylePackageNames() {
        assertTrue(PackageNameValidator.isValid("com.example.app"));
        assertTrue(PackageNameValidator.isValid("com.example_2.App"));
        assertTrue(PackageNameValidator.isValid("_private.scope"));
    }

    @Test
    public void rejectsFilesystemTraversalAndMalformedNames() {
        assertFalse(PackageNameValidator.isValid(null));
        assertFalse(PackageNameValidator.isValid(""));
        assertFalse(PackageNameValidator.isValid("../outside"));
        assertFalse(PackageNameValidator.isValid("com.example/other"));
        assertFalse(PackageNameValidator.isValid("com..example"));
        assertFalse(PackageNameValidator.isValid("com.example."));
        assertFalse(PackageNameValidator.isValid("com.example-app"));
    }
}
