package com.kaisar.xposed.godmode.ui.preference;

import android.content.Context;
import android.util.AttributeSet;

import com.kaisar.xposed.godmode.R;


public final class ProgressPreference extends androidx.preference.Preference {

    public ProgressPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setLayoutResource(R.layout.preference_widget_progress);
    }

    public ProgressPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayoutResource(R.layout.preference_widget_progress);
    }

    public ProgressPreference(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ProgressPreference(Context context) {
        this(context, null);
    }

}
