package online.magicbtn.player

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Production IT: служба переднего плана на время звонка (микрофон и камера работают в свёрнутом приложении).
 * Уведомление «Идёт звонок» — вернуться в звонок или завершить.
 */
class LkCallService : Service() {

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "hangup") {
            LkCall.hangup(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("call_ongoing", Prodit.t("chats"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, LkCall.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this, 2, Intent(this, LkCallService::class.java).setAction("hangup"),
            PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "call_ongoing") else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Production IT")
            .setContentText(LkCall.title.ifEmpty { LkCall.roomName })
            .setContentIntent(open)
            .setOngoing(true)
            .setColor(0xFF5E27D4.toInt())
            .addAction(Notification.Action.Builder(null, "✕", end).build())
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                type = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()
            try {
                if (type == 0) startForeground(77, n) else startForeground(77, n, type)
            } catch (_: Throwable) {   // Android 14: нет разрешения на тип — звонок идёт, но только на экране
                stopSelf()
            }
        } else startForeground(77, n)
        return START_NOT_STICKY
    }

    companion object {
        fun start(c: Context) {
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(Intent(c, LkCallService::class.java))
                else c.startService(Intent(c, LkCallService::class.java))
            } catch (_: Throwable) {
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, LkCallService::class.java))
        }
    }
}
