package com.kaisar.xposed.godmode.engine.matcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

/**
 * {@link CompositeMatcherInstrumentedTest} 的宿主 Activity。
 * <p>
 * 与 {@code ModifyApplierTestActivity} 同构：onCreate 中代码构造 FrameLayout 根容器，
 * 不 inflate 任何 layout；额外提供可编程挂载 helper，供测试在主线程内动态装配视图树。
 */
public final class CompositeMatcherTestActivity extends Activity {

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

    /** 可编程挂载入口 — 将任意子树追加到根容器下 */
    void mount(View subtree) {
        root.addView(subtree, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
    }
}
