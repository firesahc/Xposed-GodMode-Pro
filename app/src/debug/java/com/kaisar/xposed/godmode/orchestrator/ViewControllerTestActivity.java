package com.kaisar.xposed.godmode.orchestrator;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

public final class ViewControllerTestActivity extends Activity {

    private FrameLayout root;
    private TextView target;
    private TextView secondTarget;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setSaveEnabled(false);
        target = new TextView(this);
        target.setSaveEnabled(false);
        target.setText("host");
        target.setAlpha(0.65f);
        root.addView(target, new FrameLayout.LayoutParams(100, 50));
        secondTarget = new TextView(this);
        secondTarget.setSaveEnabled(false);
        secondTarget.setText("host-2");
        FrameLayout.LayoutParams secondParams = new FrameLayout.LayoutParams(100, 50);
        secondParams.topMargin = 60;
        root.addView(secondTarget, secondParams);
        setContentView(root);
    }

    FrameLayout getRoot() {
        return root;
    }

    TextView getTarget() {
        return target;
    }

    TextView getSecondTarget() {
        return secondTarget;
    }
}
