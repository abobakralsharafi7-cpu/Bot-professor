package com.professor.app;

import android.Manifest;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 44;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermissionIfNeeded();

        if (canDrawOverlays()) {
            showMainAppScreen();
        } else {
            showSetupScreen();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (canDrawOverlays()) {
            showMainAppScreen();
        }
    }

    private void showMainAppScreen() {
        Intent hideIntent = new Intent(this, ProfessorBubbleService.class);
        hideIntent.setAction(ProfessorBubbleService.ACTION_HIDE_BUBBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(hideIntent);
        } else {
            startService(hideIntent);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 10, 10));

        // التحقق من صلاحية الإشعارات
        boolean hasNotificationPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || 
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;

        // إذا لم تعطَ الصلاحية، نظهر الزر، وإذا أُعطيت، يختفي الشريط بالكامل وتظهر الشاشة كاملة!
        if (!hasNotificationPerm) {
            LinearLayout topBar = new LinearLayout(this);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER);
            topBar.setPadding(dp(12), dp(12), dp(12), dp(12));
            topBar.setBackgroundColor(Color.rgb(8, 8, 8));

            Button tileButton = new Button(this);
            tileButton.setText("تفعيل الإشعارات للتطبيق");
            tileButton.setTextColor(Color.BLACK);
            tileButton.setTextSize(13);
            tileButton.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(Color.rgb(255, 215, 0));
            btnBg.setCornerRadius(dp(8));
            tileButton.setBackground(btnBg);
            tileButton.setOnClickListener(v -> requestNotificationPermissionIfNeeded());
            topBar.addView(tileButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

            root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        WebView webView = ProfessorBubbleService.getSharedWebView(this);
        if (webView.getParent() != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
        }
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (canDrawOverlays()) {
            Intent showIntent = new Intent(this, ProfessorBubbleService.class);
            showIntent.setAction(ProfessorBubbleService.ACTION_SHOW_BUBBLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(showIntent);
            } else {
                startService(showIntent);
            }
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (canDrawOverlays()) {
            ProfessorBubbleService.getSharedWebView(getApplicationContext());
        }
        super.onDestroy();
    }

    private void showSetupScreen() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.setBackgroundColor(Color.rgb(5, 5, 5));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) { root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); }

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.professor_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(150), dp(150));
        logoParams.setMargins(0, 0, 0, dp(18));
        root.addView(logo, logoParams);

        TextView title = new TextView(this); title.setText("PROFESSOR"); title.setTextColor(Color.rgb(255, 215, 0)); title.setTextSize(30); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER); root.addView(title, matchWrap());

        TextView body = new TextView(this); body.setText(getString(R.string.overlay_permission_body)); body.setTextColor(Color.rgb(220, 220, 220)); body.setTextSize(16); body.setGravity(Gravity.CENTER); body.setLineSpacing(6, 1); LinearLayout.LayoutParams bodyParams = matchWrap(); bodyParams.setMargins(0, dp(14), 0, dp(22)); root.addView(body, bodyParams);

        Button permissionButton = makeButton("منح صلاحية الظهور فوق التطبيقات"); permissionButton.setOnClickListener(view -> openOverlaySettings()); root.addView(permissionButton, buttonParams());

        Button tileButton = makeButton("إضافة اختصار بجانب الواي فاي والبلوتوث"); tileButton.setOnClickListener(view -> requestQuickSettingsTile()); root.addView(tileButton, buttonParams());

        TextView hint = new TextView(this); hint.setText("بعد منح الصلاحية افتح التطبيق مرة أخرى."); hint.setTextColor(Color.rgb(150, 150, 150)); hint.setTextSize(13); hint.setGravity(Gravity.CENTER); LinearLayout.LayoutParams hintParams = matchWrap(); hintParams.setMargins(0, dp(18), 0, 0); root.addView(hint, hintParams);

        scrollView.addView(root); setContentView(scrollView);
    }

    private void openOverlaySettings() { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }

    private void requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            StatusBarManager statusBarManager = getSystemService(StatusBarManager.class);
            if (statusBarManager == null) return;
            statusBarManager.requestAddTileService(new ComponentName(this, ProfessorTileService.class), getString(R.string.app_name), Icon.createWithResource(this, R.drawable.ic_qs_professor), getMainExecutor(), result -> Toast.makeText(this, "تم إرسال طلب إضافة الاختصار.", Toast.LENGTH_SHORT).show());
        } else { Toast.makeText(this, "افتح لوحة الاختصارات وأضف PROFESSOR يدويًا.", Toast.LENGTH_LONG).show(); }
    }

    private boolean canDrawOverlays() { return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this); }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private Button makeButton(String text) { Button button = new Button(this); button.setText(text); button.setTextColor(Color.BLACK); button.setTextSize(15); button.setTypeface(Typeface.DEFAULT_BOLD); button.setAllCaps(false); GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(255, 215, 0)); bg.setCornerRadius(dp(12)); button.setBackground(bg); return button; }
    private LinearLayout.LayoutParams buttonParams() { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)); params.setMargins(0, dp(10), 0, 0); return params; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}