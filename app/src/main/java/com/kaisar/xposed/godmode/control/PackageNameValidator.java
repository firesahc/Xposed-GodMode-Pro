package com.kaisar.xposed.godmode.control;

import java.util.regex.Pattern;

/** Validates package names before they are used as filesystem or IPC scopes. */
final class PackageNameValidator {

    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private PackageNameValidator() {
    }

    static boolean isValid(String packageName) {
        return packageName != null
                && packageName.length() <= 255
                && PACKAGE_NAME.matcher(packageName).matches();
    }
}
