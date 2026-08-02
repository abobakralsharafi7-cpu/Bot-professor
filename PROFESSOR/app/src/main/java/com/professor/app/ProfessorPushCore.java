package com.professor.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.net.Uri;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * قلب نظام التنبيهات الفورية لمنظومة PROFESSOR.
 *
 * طبقتان تعملان معاً (كما في التطبيقات العالمية):
 *  1) FCM: قناة الدفع الرسمية من جوجل — تصل حتى والتطبيق مقفول والجهاز نائم.
 *  2) الخدمة الاحتياطية: خدمة أمامية + مؤقّت إيقاظ + حارس WorkManager + إقلاع تلقائي،
 *     لأجهزة بلا خدمات جوجل أو كشبكة أمان إضافية.
 *
 * كل الدوال محمية بـ try/catch: أي فشل هنا يجب ألا يكسر التطبيق أبداً.
 */
public final class ProfessorPushCore {

    public static final String PREFS = "professor_ecosystem";
    public static final String ALERT_CHANNEL_ID = "professor_ecosystem_alerts";
    public static final String STATUS_CHANNEL_ID = "professor_service_status";
    public static final int STATUS_NOTIF_ID = 9001;
    public static final long POLL_INTERVAL_MS = 60000L;          // فحص احتياطي كل دقيقة
    public static final long KEEPALIVE_INTERVAL_MS = 5 * 60 * 1000L; // مؤقّت إيقاظ كل 5 دقائق
    private static final Object POLL_LOCK = new Object();

    private ProfessorPushCore() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isConfigured(Context ctx) {
        try {
            SharedPreferences sp = prefs(ctx);
            String url = sp.getString("serverUrl", "");
            String key = sp.getString("apiKey", "");
            return url != null && !url.trim().isEmpty() && key != null && !key.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static void ensureChannels(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
            NotificationManager manager = (NotificationManager) ctx.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                NotificationChannel alerts = new NotificationChannel(ALERT_CHANNEL_ID, "تنبيهات منظومة PROFESSOR", NotificationManager.IMPORTANCE_HIGH);
                alerts.setDescription("طلبات الاشتراك والمسوقين والسحب والتنبيهات المهمة");
                alerts.enableVibration(true);
                manager.createNotificationChannel(alerts);
            }
            if (manager.getNotificationChannel(STATUS_CHANNEL_ID) == null) {
                NotificationChannel status = new NotificationChannel(STATUS_CHANNEL_ID, "حالة خدمة التنبيهات", NotificationManager.IMPORTANCE_MIN);
                status.setDescription("إشعار دائم صغير يحافظ على خدمة التنبيهات تعمل");
                status.setShowBadge(false);
                manager.createNotificationChannel(status);
            }
        } catch (Exception ignored) {}
    }

    /** إشعار نظام عادي (تنبيه إداري) — الضغط عليه يفتح التطبيق على شاشة الإشعارات. */
    public static void showAlertNotification(Context ctx, String title, String message) {
        try {
            Context app = ctx.getApplicationContext();
            ensureChannels(app);
            NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            Intent intent = new Intent(app, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("open_notifications", true);
            PendingIntent pendingIntent = PendingIntent.getActivity(app, (int) (System.currentTimeMillis() & 0xfffffff), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(app, ALERT_CHANNEL_ID) : new Notification.Builder(app);
            Notification notification = builder
                    .setContentTitle(title == null || title.trim().isEmpty() ? "PROFESSOR" : title)
                    .setContentText(message == null ? "" : message)
                    .setStyle(new Notification.BigTextStyle().bigText(message == null ? "" : message))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
            manager.notify((int) (System.currentTimeMillis() & 0x7fffffff), notification);
        } catch (Exception ignored) {}
    }

    /** الإشعار الدائم الصغير الخاص بالخدمة الأمامية (غير مزعج — أهمية دنيا). */
    public static Notification buildStatusNotification(Context ctx) {
        ensureChannels(ctx);
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(ctx, STATUS_CHANNEL_ID) : new Notification.Builder(ctx);
        return builder
                .setContentTitle("PROFESSOR")
                .setContentText("خدمة التنبيهات تعمل — تصلك الطلبات فوراً")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    // ───────────────────────── الخدمة والحُرّاس ─────────────────────────

    public static void startNotificationService(Context ctx) {
        try {
            if (!isConfigured(ctx)) return;
            Context app = ctx.getApplicationContext();
            Intent intent = new Intent(app, ProfessorNotificationService.class);
            intent.setAction(ProfessorNotificationService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Exception ignored) {}
    }

    public static void stopNotificationService(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            Intent intent = new Intent(app, ProfessorNotificationService.class);
            intent.setAction(ProfessorNotificationService.ACTION_STOP);
            app.startService(intent);
        } catch (Exception ignored) {}
        cancelKeepAliveAlarm(ctx);
        cancelWatchdog(ctx);
    }

    /** حارس WorkManager: كل 15 دقيقة يتأكد أن الخدمة حية ويعيد تشغيلها إن قتلها النظام. */
    public static void scheduleWatchdog(Context ctx) {
        try {
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(ProfessorWatchdogWorker.class, 15, TimeUnit.MINUTES).build();
            WorkManager.getInstance(ctx.getApplicationContext())
                    .enqueueUniquePeriodicWork("professor_notify_watchdog", ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Exception ignored) {}
    }

    public static void cancelWatchdog(Context ctx) {
        try {
            WorkManager.getInstance(ctx.getApplicationContext()).cancelUniqueWork("professor_notify_watchdog");
        } catch (Exception ignored) {}
    }

    /** مؤقّت إيقاظ يكسر وضع Doze: يوقظ الجهاز ليفحص ويتأكد من حياة الخدمة. */
    public static void scheduleKeepAliveAlarm(Context ctx, long delayMs) {
        try {
            Context app = ctx.getApplicationContext();
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, keepAlivePendingIntent(app));
        } catch (Exception ignored) {}
    }

    public static void cancelKeepAliveAlarm(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(keepAlivePendingIntent(app));
        } catch (Exception ignored) {}
    }

    private static PendingIntent keepAlivePendingIntent(Context app) {
        Intent intent = new Intent(app, ProfessorKeepAliveReceiver.class);
        return PendingIntent.getBroadcast(app, 7001, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ───────────────────────── الفحص الاحتياطي للسيرفر ─────────────────────────

    /** فحص واحد للإشعارات الجديدة من السيرفر وإظهارها كإشعارات نظام (مع منع تكرار عبر lastNotificationId المشترك). */
    public static void pollOnce(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            synchronized (POLL_LOCK) {
                try {
                    SharedPreferences sp = prefs(app);
                    String serverUrl = sp.getString("serverUrl", "");
                    String apiKey = sp.getString("apiKey", "");
                    int lastId = sp.getInt("lastNotificationId", 0);
                    if (serverUrl == null || serverUrl.trim().isEmpty() || apiKey == null || apiKey.trim().isEmpty()) return;
                    String url = serverUrl.replaceAll("/+$", "") + "/api/notifications?after=" + lastId;
                    org.json.JSONObject body = httpJson(app, "GET", url, null, null, "");
                    if (body == null) return;
                    org.json.JSONArray rows = body.optJSONArray("rows");
                    if (rows == null) rows = body.optJSONArray("notifications");
                    if (rows == null) return;
                    int maxId = lastId;
                    for (int i = 0; i < rows.length(); i++) {
                        org.json.JSONObject item = rows.optJSONObject(i);
                        if (item == null) continue;
                        int id = item.optInt("id", 0);
                        if (id > maxId) maxId = id;
                        if (id > lastId && !item.optBoolean("read", false)) {
                            showAlertNotification(app, item.optString("title", "PROFESSOR"), item.optString("message", item.optString("text", "إشعار جديد")));
                        }
                    }
                    sp.edit().putInt("lastNotificationId", maxId).apply();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /** طلب HTTP موحّد بنفس أسلوب بقية التطبيق (X-API-Key / Bearer / X-Client-ID). */
    static org.json.JSONObject httpJson(Context ctx, String method, String urlString, String apiKeyOverride, String clientIdOverride, String body) {
        java.net.HttpURLConnection conn = null;
        try {
            SharedPreferences sp = prefs(ctx);
            String apiKey = apiKeyOverride != null ? apiKeyOverride : sp.getString("apiKey", "");
            String clientId = clientIdOverride != null ? clientIdOverride : sp.getString("clientId", "");
            String safeMethod = method == null || method.trim().isEmpty() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
            java.net.URL url = new java.net.URL(urlString);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestMethod(safeMethod);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                conn.setRequestProperty("X-API-Key", apiKey.trim());
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            if (clientId != null && !clientId.trim().isEmpty()) {
                conn.setRequestProperty("X-Client-ID", clientId.trim());
            }
            boolean hasBody = !("GET".equals(safeMethod) || "DELETE".equals(safeMethod)) && body != null && !body.isEmpty();
            if (hasBody) {
                conn.setDoOutput(true);
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                java.io.OutputStream os = conn.getOutputStream();
                try { os.write(bytes); os.flush(); } finally { os.close(); }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            java.io.InputStream is = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new org.json.JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ───────────────────────── Firebase Cloud Messaging ─────────────────────────

    public static void saveFcmToken(Context ctx, String token) {
        try {
            if (token != null && !token.trim().isEmpty()) prefs(ctx).edit().putString("fcmToken", token).apply();
        } catch (Exception ignored) {}
    }

    public static void registerFcmToken(final Context ctx, final String token) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                if (!isConfigured(app) || token == null || token.trim().isEmpty()) return;
                SharedPreferences sp = prefs(app);
                String serverUrl = sp.getString("serverUrl", "");
                String clientId = sp.getString("clientId", "android");
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("token", token.trim());
                body.put("client_id", clientId == null || clientId.trim().isEmpty() ? "android" : clientId.trim());
                body.put("platform", "android");
                httpJson(app, "POST", serverUrl.replaceAll("/+$", "") + "/api/fcm/register", null, clientId, body.toString());
                sp.edit().putBoolean("fcmRegistered", true).apply();
            } catch (Exception ignored) {}
        }).start();
    }

    /** إلغاء تسجيل سريع بقيم صريحة (يُستدعى قبل مسح الإعدادات مباشرة عند فصل المنظومة). */
    public static void unregisterFcmTokenNow(Context ctx, String serverUrl, String apiKey, String clientId, String token) {
        try {
            if (token == null || token.trim().isEmpty() || serverUrl == null || serverUrl.trim().isEmpty()) return;
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("token", token.trim());
            httpJson(ctx, "POST", serverUrl.replaceAll("/+$", "") + "/api/fcm/unregister", apiKey, clientId, body.toString());
        } catch (Exception ignored) {}
    }

    /** جلب رمز FCM الحالي وتسجيله في السيرفر (آمن تماماً بلا خدمات جوجل أو بدون ملف google-services حقيقي). */
    public static void refreshAndRegisterFcmToken(final Context ctx) {
        try {
            final Context app = ctx.getApplicationContext();
            if (!isConfigured(app)) return;
            com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                try {
                    if (!task.isSuccessful()) return;
                    String token = task.getResult();
                    if (token == null || token.trim().isEmpty()) return;
                    saveFcmToken(app, token);
                    String known = prefs(app).getString("fcmRegisteredToken", "");
                    if (!token.equals(known)) {
                        prefs(app).edit().putString("fcmRegisteredToken", token).apply();
                        registerFcmToken(app, token);
                    }
                } catch (Exception ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    // ───────────────────────── نقاط الربط بالدورة الحياتية للتطبيق ─────────────────────────

    /** عند ربط المنظومة من التطبيق: شغّل كل طبقات التنبيه. */
    public static void onEcosystemConnected(Context ctx) {
        ensureChannels(ctx);
        startNotificationService(ctx);
        scheduleWatchdog(ctx);
        scheduleKeepAliveAlarm(ctx, KEEPALIVE_INTERVAL_MS);
        refreshAndRegisterFcmToken(ctx);
    }

    /** عند فصل المنظومة: أوقف كل شيء (إلغاء تسجيل FCM يتم يدوياً قبل مسح الإعدادات). */
    public static void onEcosystemDisconnected(Context ctx) {
        stopNotificationService(ctx);
    }

    /** عند فتح التطبيق: تأكد أن كل الطبقات تعمل إذا كانت المنظومة مربوطة. */
    public static void onAppLaunched(Context ctx) {
        ensureChannels(ctx);
        if (!isConfigured(ctx)) return;
        startNotificationService(ctx);
        scheduleWatchdog(ctx);
        refreshAndRegisterFcmToken(ctx);
    }

    /** طلب استثناء تحسين البطارية مرة واحدة — يمنع قتل النظام/الشركة للخدمة. */
    public static void maybeRequestBatteryExemption(android.app.Activity activity) {
        try {
            if (activity == null) return;
            SharedPreferences sp = prefs(activity);
            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (pm.isIgnoringBatteryOptimizations(activity.getPackageName())) return;
            if (sp.getBoolean("askedBatteryExemption", false)) return;
            sp.edit().putBoolean("askedBatteryExemption", true).apply();
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception ignored) {}
    }
}
