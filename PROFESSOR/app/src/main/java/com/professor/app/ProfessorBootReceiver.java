package com.professor.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * تشغيل تلقائي بعد إعادة إقلاع الهاتف: يعيد كل طبقات نظام التنبيهات للعمل
 * بدون أن يحتاج المستخدم لفتح التطبيق يدوياً.
 */
public class ProfessorBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent != null ? intent.getAction() : "";
            if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                    && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                    && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
                return;
            }
            if (!ProfessorPushCore.isConfigured(context)) return;
            ProfessorPushCore.onAppLaunched(context);
        } catch (Exception ignored) {}
    }
}
