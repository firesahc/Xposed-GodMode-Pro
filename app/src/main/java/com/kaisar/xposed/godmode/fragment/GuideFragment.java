package com.kaisar.xposed.godmode.fragment;

import android.os.Bundle;
import android.text.Html;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kaisar.xposed.godmode.R;

public final class GuideFragment extends Fragment {

    public GuideFragment() {
        super(R.layout.fragment_guide);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView guideText = view.findViewById(R.id.guide_text);
        if (guideText != null) {
            guideText.setText(Html.fromHtml(getString(R.string.guide_content), Html.FROM_HTML_MODE_LEGACY));
        }
    }

}
