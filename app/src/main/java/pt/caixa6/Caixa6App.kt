package pt.caixa6

import android.app.*
import android.content.*
import android.os.Build
import org.mozilla.geckoview.*

class Caixa6App : Application() {
    lateinit var runtime: GeckoRuntime
        private set

    val sessions = linkedMapOf<String, GeckoSession>()
    var selectedAccountId: String = "Rita Sapo"

    override fun onCreate() {
        super.onCreate()
        createChannels()

        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .build()

        runtime = GeckoRuntime.create(this, settings)

        runtime.setWebNotificationDelegate(object : WebNotificationDelegate {
            override fun onShowNotification(notification: WebNotification) {
                showAndroidNotification(notification)
            }

            override fun onCloseNotification(notification: WebNotification) {
                notification.dismiss()
            }
        })

        ensureSessions()
    }

    fun ensureSessions() {
        if (sessions.isNotEmpty()) return

        DEFAULT_ACCOUNTS.forEach { account ->
            val sessionSettings = GeckoSessionSettings.Builder()
                .contextId("caixa6-${account.id}")
                .build()

            val session = GeckoSession(sessionSettings)

            session.setContentDelegate(object : GeckoSession.ContentDelegate {})

            session.setPermissionDelegate(object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission
                ): GeckoResult<Int> {
                    val allow =
                        perm.permission ==
                            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION

                    return GeckoResult.fromValue(
                        if (allow)
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        else
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                }
            })

            session.open(runtime)
            session.setActive(true)
            session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
            session.loadUri(account.url)

            sessions[account.id] = session
        }
    }

    private fun showAndroidNotification(n: WebNotification) {
        val manager = getSystemService(NotificationManager::class.java)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pi = PendingIntent.getActivity(
            this,
            n.tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val title = n.title ?: "Novo email"
        val text = n.text ?: "Recebeste uma nova mensagem."

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, "mail")
            } else {
                Notification.Builder(this)
            }

        val notif = builder
            .setSmallIcon(R.drawable.ic_mail)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        manager.notify(
            (n.tag + title + text).hashCode(),
            notif
        )
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(
                NotificationChannel(
                    "mail",
                    "Novos emails",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    "keepalive",
                    "Caixa6 ativa",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
