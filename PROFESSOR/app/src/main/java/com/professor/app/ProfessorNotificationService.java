package com.professor.app;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * خدمة التنبيهات الأمامية المستقلة (بلا جوجل ولا أي طرف ثالث):
 *  • خيط القناة الحية (SSE) يمسك خطاً مفتوحاً مع السيرفر — الإشعار يصل في اللحظة نفسها.
 *  • فحص احتياطي كل دقيقة يلتقط ما فات أثناء أي انقطاع مؤقت.
 *  • مؤقّت إيقاظ ضد Doze + إعادة تشغيل تلقائية عند القتل أو عند سحب التطبيق من القائمة.
 */
public class ProfessorNotificationService extends Service {

    public static final String ACTION_START = "com.professor.app.eco.START";
    public static final String ACTION_STOP = "com.professor.app.eco.STOP";
    public static volatile boolean running = false;

    private Handler handler;
    private boolean stoppedByUser = false;
    private Thread streamThread;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            ProfessorPushCore.pollOnce(ProfessorNotificationService.this);
            if (handler != null) handler.postDelayed(this, ProfessorPushCore.POLL_INTERVAL_MS);
        }
    };

    /** خيط القناة الحية (SSE): فحص فوري أولاً لالتقاط الفائت، ثم خط مفتوح دائم مع إعادة اتصال ذاتية. */
    private void startStreamWorker() {
        if (streamThread != null && streamThread.isAlive()) return;
        streamThread = new Thread(() -> {
            ProfessorPushCore.pollOnce(getApplicationContext());
            while (running) {
                try {
                    if (!ProfessorPushCore.isConfigured(getApplicationContext())) break;
                    ProfessorPushCore.streamOnce(getApplicationContext());
                } catch (Throwable ignored) {}
                if (!running) break;
                try { Thread.sleep(ProfessorPushCore.STREAM_RETRY_MS); } catch (InterruptedException ignored) {}
            }
        }, "professor-sse-stream");
        streamThread.setDaemon(true);
        streamThread.start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        stoppedByUser = false;
        ProfessorPushCore.ensureChannels(this);
        try {
            startForeground(ProfessorPushCore.STATUS_NOTIF_ID, ProfessorPushCore.buildStatusNotification(this));
        } catch (Exception ignored) {}
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, 3000);
        startStreamWorker();
        ProfessorPushCore.scheduleKeepAliveAlarm(this, ProfessorPushCore.KEEPALIVE_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stoppedByUser = true;
            shutdown();
            return START_NOT_STICKY;
        }
        if (!ProfessorPushCore.isConfigured(this)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (handler != null) {
            handler.removeCallbacks(pollRunnable);
            handler.post(pollRunnable);
        }
        return START_STICKY;
    }

    private void shutdown() {
        running = false;
        try { if (handler != null) handler.removeCallbacks(pollRunnable); } catch (Exception ignored) {}
        try { if (streamThread != null) streamThread.interrupt(); } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (handler != null) handler.removeCallbacks(pollRunnable); } catch (Exception ignored) {}
        try { if (streamThread != null) streamThread.interrupt(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // سحب التطبيق من قائمة الأخيرة: بعض الأنظمة تقتل العملية هنا — إعادة التسليح فوراً
        try {
            if (ProfessorPushCore.isConfigured(this) && !stoppedByUser) {
                ProfessorPushCore.scheduleKeepAliveAlarm(getApplicationContext(), 60000L);
            }
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
