package pt.caixa6

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val refreshTask =
        object : Runnable {
            override fun run() {
                val app = application as Caixa6App
                app.refreshBackgroundSessions()
                handler.postDelayed(this, 120_000L)
            }
        }

    override fun onCreate() {
        super.onCreate()

        val intent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, "keepalive")
            } else {
                Notification.Builder(this)
            }

        val notification = builder
            .setSmallIcon(R.drawable.ic_mail)
            .setContentTitle("Central de Emails")
            .setContentText("A verificar novos emails.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(60, notification)
        handler.postDelayed(refreshTask, 30_000L)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
