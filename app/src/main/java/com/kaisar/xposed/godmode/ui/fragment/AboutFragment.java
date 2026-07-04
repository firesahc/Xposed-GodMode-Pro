package com.kaisar.xposed.godmode.ui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.util.Logger;

public final class AboutFragment extends Fragment {

    private static final String TAG = "AboutFragment";

    private static final String GITHUB_URL = "https://github.com/firesahc/Xposed-GodMode-Pro";

    public AboutFragment() {
        super(R.layout.fragment_about);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView versionText = view.findViewById(R.id.about_version);
        if (versionText != null) {
            versionText.setText(getString(R.string.about_version) + ": " + BuildConfig.VERSION_NAME);
        }

        TextView githubLink = view.findViewById(R.id.about_github);
        if (githubLink != null) {
            githubLink.setText(getString(R.string.about_github) + "\n" + GITHUB_URL);
            githubLink.setOnClickListener(v -> openUrl(GITHUB_URL));
        }

        TextView originalLink = view.findViewById(R.id.about_original);
        if (originalLink != null) {
            String originalUrl = "https://github.com/kaisar945/Xposed-GodMode";
            originalLink.setText(getString(R.string.about_original) + "\n" + originalUrl);
            originalLink.setOnClickListener(v -> openUrl(originalUrl));
        }

        TextView issuesLink = view.findViewById(R.id.about_issues);
        if (issuesLink != null) {
            String issuesUrl = GITHUB_URL + "/issues";
            issuesLink.setText(getString(R.string.about_issues) + "\n" + issuesUrl);
            issuesLink.setOnClickListener(v -> openUrl(issuesUrl));
        }
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Logger.w(TAG, "openUrl failed: " + url, e);
        }
    }
}
