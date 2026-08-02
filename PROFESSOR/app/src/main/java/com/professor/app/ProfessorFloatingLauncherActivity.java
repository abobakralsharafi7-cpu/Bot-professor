package com.professor.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

public class ProfessorFloatingLauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!canDrawOverlays()) {
            Intent setupIntent = new Intent(this, MainActivity.class);
            setupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(setupIntent);
            finish();
            overridePendingTransition(0, 0);
            return;
        }

        Intent serviceIntent = new Intent(this, ProfessorBubbleService.class);
        serviceIntent.setAction(ProfessorBubbleService.ACTION_OPEN_PANEL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        finish();
        overridePendingTransition(0, 0);
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }
}
