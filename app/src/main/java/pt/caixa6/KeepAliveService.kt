package pt.caixa6

import android.app.*
import android.content.Intent
import android.os.IBinder

class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()

        (application as Caixa6App).ensureSessions()

        val intent = Intent(this, MainActivity::class.java)

        val pi = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification =
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, "keepalive")
                    .setSmallIcon(R.drawable.ic_mail)
                    .setContentTitle("Central de Emails")
                    .setContentText("A verificar as suas caixas de correio.")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build()
            } else {
                Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_mail)
                    .setContentTitle("Central de Emails")
                    .setContentText("A verificar as suas caixas de correio.")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build()
            }

        startForeground(60, notification)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
