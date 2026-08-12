package com.professor.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * مستقبل مؤقّت الإيقاظ: كل 5 دقائق يوقظ الجهاز (يكسر Doze)،
 * يتأكد أن خدمة التنبيهات حية، يفحص السيرفر فوراً، ثم يعيد تسليح المؤقّت التالي.
 */
public class ProfessorKeepAliveReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (!ProfessorPushCore.isConfigured(context)) return;
            ProfessorPushCore.ensureChannels(context);
            ProfessorPushCore.startNotificationService(context);
            ProfessorPushCore.pollOnce(context);
            ProfessorPushCore.scheduleKeepAliveAlarm(context, ProfessorPushCore.KEEPALIVE_INTERVAL_MS);
        } catch (Exception ignored) {}
    }
}
