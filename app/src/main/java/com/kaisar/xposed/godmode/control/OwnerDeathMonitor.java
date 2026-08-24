package com.kaisar.xposed.godmode.control;

/** Adapter boundary for registering owner-death callbacks without coupling JVM tests to Binder. */
interface OwnerDeathMonitor {
    Registration link(Object owner, Runnable callback) throws OwnerAlreadyDeadException;

    interface Registration {
        void unlink();
    }

    final class OwnerAlreadyDeadException extends Exception {
        OwnerAlreadyDeadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
