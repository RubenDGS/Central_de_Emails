package pt.caixa6

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build

import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate

class Caixa6App : Application() {

    lateinit var runtime: GeckoRuntime
        private set

    val sessions =
        linkedMapOf<String, GeckoSession>()

    val loadedAccounts =
        mutableSetOf<String>()

    var selectedAccountId =
        "rita_sapo"

    override fun onCreate() {
        super.onCreate()

        createChannels()

        val runtimeSettings =
            GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .build()

        runtime =
            GeckoRuntime.create(
                this,
                runtimeSettings
            )

        runtime.setWebNotificationDelegate(
            object : WebNotificationDelegate {

                override fun onShowNotification(
                    notification: WebNotification
                ) {
                    showAndroidNotification(
                        notification
                    )
                }

                override fun onCloseNotification(
                    notification: WebNotification
                ) {
                    notification.dismiss()
                }
            }
        )
    }

    fun getOrCreateSession(
        account: Account
    ): GeckoSession {

        sessions[account.id]?.let {
            return it
        }

        val sessionSettings =
            GeckoSessionSettings.Builder()
                .contextId(
                    "central-emails-${account.id}"
                )
                .build()

        val session =
            GeckoSession(sessionSettings)

        session.setContentDelegate(
            object :
                GeckoSession.ContentDelegate {}
        )

        session.setPermissionDelegate(
            object :
                GeckoSession.PermissionDelegate {

                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm:
                        GeckoSession.PermissionDelegate
                            .ContentPermission
                ): GeckoResult<Int> {

                    val isNotification =
                        perm.permission ==
                            GeckoSession.PermissionDelegate
                                .PERMISSION_DESKTOP_NOTIFICATION

                    return GeckoResult.fromValue(
                        if (isNotification) {
                            GeckoSession.PermissionDelegate
                                .ContentPermission
                                .VALUE_ALLOW
                        } else {
                            GeckoSession.PermissionDelegate
                                .ContentPermission
                                .VALUE_DENY
                        }
                    )
                }
            }
        )

        session.open(runtime)

        sessions[account.id] =
            session

        return session
    }

    private fun showAndroidNotification(
        notification: WebNotification
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val intent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {

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
            notification.title
                ?: "Novo email"

        val text =
            notification.text
                ?: "Recebeste uma nova mensagem."

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(
                    this,
                    "mail"
                )
            } else {
                Notification.Builder(this)
            }

        val androidNotification =
            builder
                .setSmallIcon(
                    R.drawable.ic_mail
                )
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(
                    "Central de Emails"
                )
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(text)
                )
                .setContentIntent(
                    pendingIntent
                )
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
                getSystemService(
                    NotificationManager::class.java
                )

            val mailChannel =
                NotificationChannel(
                    "mail",
                    "Central de Emails",
                    NotificationManager
                        .IMPORTANCE_HIGH
                ).apply {

                    description =
                        "Notificações de novos emails"
                }

            val keepAliveChannel =
                NotificationChannel(
                    "keepalive",
                    "Central de Emails",
                    NotificationManager
                        .IMPORTANCE_LOW
                ).apply {

                    description =
                        "Serviço da Central de Emails"
                }

            manager.createNotificationChannel(
                mailChannel
            )

            manager.createNotificationChannel(
                keepAliveChannel
            )
        }
    }
}
