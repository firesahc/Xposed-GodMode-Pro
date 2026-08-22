package com.kaisar.xposed.godmode.ui;


import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.core.PlatformCapabilities;

public class SettingsActivity extends AppCompatActivity {

    private final ActivityResultLauncher<String> mNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                startNotificationService();
                if (!granted) {
                    Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applyWindowInsets();
    }

    private void applyWindowInsets() {
        View rootView = findViewById(R.id.coordinator);
        if (rootView == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        Toolbar toolbar = findViewById(R.id.main_toolbar);
        setSupportActionBar(toolbar);
        NavigationUI.setupWithNavController(toolbar, navController, appBarConfiguration);
        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (PlatformCapabilities.canRequestNotificationPermission()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                mNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        startNotificationService();
    }

    private void startNotificationService() {
        EditModeController.startNotificationService(this);
    }

}
