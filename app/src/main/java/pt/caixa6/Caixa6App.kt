package pt.caixa6

import android.app.*
import android.content.*
import android.os.Build
import org.mozilla.geckoview.*

class Caixa6App : Application() {

    lateinit var runtime: GeckoRuntime
        private set

    val sessions = linkedMapOf<String, GeckoSession>()

    val loadedAccounts = mutableSetOf<String>()

    var selectedAccountId: String = "rita_sapo"

    override fun onCreate() {
        super.onCreate()

        createChannels()

        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .build()

        runtime = GeckoRuntime.create(this, settings)

        runtime.setWebNotificationDelegate(
            object : WebNotificationDelegate {

                override fun onShowNotification(
                    notification: WebNotification
                ) {
                    showAndroidNotification(notification)
                }

                override fun onCloseNotification(
                    notification: WebNotification
                ) {
                    notification.dismiss()
                }
            }
        )

        ensureSessions()
    }

    fun ensureSessions() {

        if (sessions.isNotEmpty()) {
            return
        }

        DEFAULT_ACCOUNTS.forEach { account ->

            val sessionSettings =
                GeckoSessionSettings.Builder()
                    .contextId("central-emails-${account.id}")
                    .build()

            val session = GeckoSession(sessionSettings)

            session.setContentDelegate(
                object : GeckoSession.ContentDelegate {}
            )

            session.setPermissionDelegate(
                object : GeckoSession.PermissionDelegate {

                    override fun onContentPermissionRequest(
                        session: GeckoSession,
                        perm: GeckoSession.PermissionDelegate.ContentPermission
                    ): GeckoResult<Int> {

                        val allow =
                            perm.permission ==
                                GeckoSession.PermissionDelegate
                                    .PERMISSION_DESKTOP_NOTIFICATION

                        return GeckoResult.fromValue(
                            if (allow) {
                                GeckoSession.PermissionDelegate
                                    .ContentPermission.VALUE_ALLOW
                            } else {
                                GeckoSession.PermissionDelegate
                                    .ContentPermission.VALUE_DENY
                            }
                        )
                    }
                }
            )

            session.open(runtime)

            /*
             * Mantemos as sessões ativas depois de abertas.
             * Cada conta tem cookies e armazenamento separados.
             */
            session.setActive(true)

            sessions[account.id] = session
        }
    }

    private fun showAndroidNotification(
        notification: WebNotification
    ) {

        val manager =
            getSystemService(NotificationManager::class.java)

        val intent =
            Intent(this, MainActivity::class.java).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                notification.tag.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val title =
            notification.title ?: "Novo email"

        val text =
            notification.text ?: "Recebeste uma nova mensagem."

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, "mail")
            } else {
                Notification.Builder(this)
            }

        val androidNotification =
            builder
                .setSmallIcon(R.drawable.ic_mail)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText("Central de Emails")
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(text)
                )
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

        manager.notify(
            (
                notification.tag +
                    title +
                    text
                ).hashCode(),
            androidNotification
        )
    }

    private fun createChannels() {

        if (Build.VERSION.SDK_INT >= 26) {

            val manager =
                getSystemService(NotificationManager::class.java)

            val mailChannel =
                NotificationChannel(
                    "mail",
                    "Central de Emails",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description =
                        "Notificações de novos emails"
                }

            val keepAliveChannel =
                NotificationChannel(
                    "keepalive",
                    "Central de Emails",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description =
                        "Mantém as caixas de email ativas"
                }

            manager.createNotificationChannel(mailChannel)
            manager.createNotificationChannel(keepAliveChannel)
        }
    }
}
