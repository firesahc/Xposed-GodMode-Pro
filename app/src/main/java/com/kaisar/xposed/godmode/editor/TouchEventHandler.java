package com.kaisar.xposed.godmode.editor;

import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.kaisar.xposed.godmode.engine.EditorInteractionMode;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.util.ViewUtils;

import java.lang.reflect.Field;

/** Forwards taps to selection; rule creation is owned by the editor toolbar. */
public final class TouchEventHandler {

    private static final String TAG = "TouchEventHandler";
    private static Field sWindowAttributesField;

    public interface TouchCallback {
        boolean isKeySelecting();
        int getInteractionMode();
        void selectViewByTap(View view);
    }

    private final TouchCallback mCallback;

    public TouchEventHandler(TouchCallback callback) {
        mCallback = callback;
    }

    public boolean onTouchEvent(View view, MotionEvent event) {
        if (!isEditableWindow(view)) return false;
        if (mCallback.getInteractionMode() == EditorInteractionMode.INITIAL) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                && mCallback.isKeySelecting()) {
            mCallback.selectViewByTap(view);
        }
        return true;
    }

    private boolean isEditableWindow(View view) {
        WindowManager.LayoutParams attributes = getWindowLayoutParams(view);
        if (attributes == null) return false;
        int type = attributes.type;
        return type < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                || type > WindowManager.LayoutParams.LAST_SYSTEM_WINDOW;
    }

    private WindowManager.LayoutParams getWindowLayoutParams(View view) {
        Object viewRootImpl = ViewUtils.findViewRootImplByChildView(view.getParent());
        if (viewRootImpl == null) return null;
        try {
            if (sWindowAttributesField == null) {
                sWindowAttributesField = viewRootImpl.getClass()
                        .getDeclaredField("mWindowAttributes");
                sWindowAttributesField.setAccessible(true);
            }
            return (WindowManager.LayoutParams) sWindowAttributesField.get(viewRootImpl);
        } catch (Exception error) {
            Logger.e(TAG, "getWindowLayoutParams reflection failed", error);
            return null;
        }
    }
}
