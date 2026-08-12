package com.kaisar.xposed.godmode.engine.applier;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

public final class ModifyApplierTestActivity extends Activity {

    private FrameLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        setContentView(root);
    }

    FrameLayout getRoot() {
        return root;
    }
}
