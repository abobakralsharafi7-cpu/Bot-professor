package com.professor.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * حارس WorkManager: يراجع كل 15 دقيقة (أقل فترة يسمح بها أندرويد للمهام الدورية)
 * ويعيد تشغيل خدمة التنبيهات إن وجدها ميتة — صمّام أمان ضد أي قتل للعملية.
 */
public class ProfessorWatchdogWorker extends Worker {

    public ProfessorWatchdogWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context ctx = getApplicationContext();
            if (ProfessorPushCore.isConfigured(ctx)) {
                ProfessorPushCore.ensureChannels(ctx);
                if (!ProfessorNotificationService.running) {
                    ProfessorPushCore.startNotificationService(ctx);
                }
                ProfessorPushCore.scheduleKeepAliveAlarm(ctx, ProfessorPushCore.KEEPALIVE_INTERVAL_MS);
            }
        } catch (Exception ignored) {}
        return Result.success();
    }
}
