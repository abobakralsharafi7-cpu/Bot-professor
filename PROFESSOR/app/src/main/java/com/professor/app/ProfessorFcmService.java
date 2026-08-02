package com.professor.app;

import android.content.SharedPreferences;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * مستقبل قناة الدفع الرسمية من جوجل (FCM).
 * يصله الإشعار حتى والتطبيق مقفول تماماً والجهاز نائم — مثل واتساب وتيليجرام.
 */
public class ProfessorFcmService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        try {
            ProfessorPushCore.saveFcmToken(this, token);
            if (ProfessorPushCore.isConfigured(this)) {
                ProfessorPushCore.registerFcmToken(this, token);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        try {
            Map<String, String> data = message.getData();
            if (data == null || data.isEmpty()) return;
            String title = data.get("title");
            String body = data.get("message");
            if (body == null) body = data.get("body");
            boolean hasTitle = title != null && !title.trim().isEmpty();
            boolean hasBody = body != null && !body.trim().isEmpty();
            if (!hasTitle && !hasBody) return;

            // منع التكرار مع الفحص الاحتياطي: تجاهل ما سبق عرضه عبر آخر آيدي معروف
            SharedPreferences sp = ProfessorPushCore.prefs(this);
            int lastId = sp.getInt("lastNotificationId", 0);
            int notifId = 0;
            try { notifId = Integer.parseInt(String.valueOf(data.get("id") == null ? "0" : data.get("id"))); } catch (Exception ignored) {}
            if (notifId > 0 && notifId <= lastId) return;

            ProfessorPushCore.showAlertNotification(this, hasTitle ? title : "PROFESSOR", hasBody ? body : "");
            if (notifId > lastId) sp.edit().putInt("lastNotificationId", notifId).apply();
        } catch (Exception ignored) {}
    }
}
