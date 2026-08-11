package pt.caixa6

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()

        val intent =
            Intent(
                this,
                MainActivity::class.java
            )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {

                Notification.Builder(
                    this,
                    "keepalive"
                )

            } else {

                Notification.Builder(this)
            }

        val notification =
            builder
                .setSmallIcon(
                    R.drawable.ic_mail
                )
                .setContentTitle(
                    "Central de Emails"
                )
                .setContentText(
                    "Central de Emails ativa."
                )
                .setContentIntent(
                    pendingIntent
                )
                .setOngoing(true)
                .build()

        startForeground(
            60,
            notification
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        return START_STICKY
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
