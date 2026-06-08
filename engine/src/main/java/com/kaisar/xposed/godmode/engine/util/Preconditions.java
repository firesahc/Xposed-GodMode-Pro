package com.kaisar.xposed.godmode.engine.util;

import android.text.TextUtils;

import androidx.annotation.NonNull;

/**
 * Simple static methods to verify correct arguments and state.
 */
public final class Preconditions {

    /**
     * Ensures that an object reference passed as a parameter to the calling
     * method is not null.
     *
     * @param reference an object reference
     * @return the non-null reference that was validated
     * @throws NullPointerException if {@code reference} is null
     */
    public static @NonNull
    <T> T checkNotNull(final T reference) {
        if (reference == null) {
            throw new NullPointerException();
        }
        return reference;
    }

    public static <T> T checkNotNull(T reference, String message) {
        if (reference == null)
            throw new NullPointerException(message);
        return reference;
    }

    public static <T> T optionDefault(T reference, T defaultValue){
        if (reference == null)
            return defaultValue;
        return reference;
    }

    public static CharSequence optionDefault(CharSequence reference, CharSequence defaultValue){
        if (TextUtils.isEmpty(reference))
            return defaultValue;
        return reference;
    }

}
