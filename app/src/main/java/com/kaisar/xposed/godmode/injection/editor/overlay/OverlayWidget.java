package com.kaisar.xposed.godmode.injection.editor.overlay;

import android.view.View;
import android.view.ViewGroup;

public interface OverlayWidget {

    View getView();

    default void attachToContainer(ViewGroup container) {
        container.addView(getView());
    }

    default void detachFromContainer() {
        ViewGroup parent = (ViewGroup) getView().getParent();
        if (parent != null) {
            parent.removeView(getView());
        }
    }
}
