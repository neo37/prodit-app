package online.magicbtn.player;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 3.9: долгие дела (отправка видео до 500 МБ, склейка «Моих просмотров») держат приложение живым,
 * даже если его свернули: на время работы — служба переднего плана с уведомлением.
 * begin() — дело началось, end() — закончилось; когда дел нет, служба уходит сама.
 */
public final class Busy extends Service {

    static final int UPLOAD = 1, RENDER = 2;
    private static final int NID = 0x5B05;
    private static int uploads, renders;

    static synchronized void begin(Context c, int kind) {
        if (kind == RENDER) renders++;
        else uploads++;
        update(c.getApplicationContext());
    }

    static synchronized void end(Context c, int kind) {
        if (kind == RENDER) renders = Math.max(0, renders - 1);
        else uploads = Math.max(0, uploads - 1);
        update(c.getApplicationContext());
    }

    private static void update(Context c) {
        Intent i = new Intent(c, Busy.class);
        try {
            if (uploads + renders == 0) c.stopService(i);
            else if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {   // запуск из фона запрещён — дело всё равно идёт, просто без защиты
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int u, r;
        synchronized (Busy.class) {
            u = uploads;
            r = renders;
        }
        if (u + r == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notify.channels(this);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, Notify.CH_UPLOAD) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(Ui.t(R.string.app_name))
                .setContentText(Ui.t(u > 0 ? R.string.busy_upload : R.string.busy_render))
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (u == 0 && Build.VERSION.SDK_INT >= 35) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
                startForeground(NID, b.build(), type);
            } else startForeground(NID, b.build());
        } catch (Exception e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {   // Android 15: суточный лимит фоновой работы исчерпан
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
