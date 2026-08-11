package pt.caixa6

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate

class Caixa6App : Application() {

    interface UnreadListener {
        fun onUnreadChanged(accountId: String, count: Int)
    }

    private var runtime: GeckoRuntime? = null
    private val handler = Handler(Looper.getMainLooper())

    val sessions = linkedMapOf<String, GeckoSession>()
    val loadedAccounts = mutableSetOf<String>()

    var selectedAccountId: String = "rita_sapo"
        private set

    private var uiVisible = false
    private var refreshRunning = false
    private var refreshIndex = 0

    private var notificationHintAccountId: String? = null
    private var notificationHintUntil: Long = 0L

    private val unreadCounts = linkedMapOf<String, Int>()
    private val unreadListeners = mutableSetOf<UnreadListener>()

    override fun onCreate() {
        super.onCreate()

        DEFAULT_ACCOUNTS.forEach { account ->
            unreadCounts[account.id] = loadUnread(account.id)
        }

        createChannels()
    }

    private fun getRuntime(): GeckoRuntime {
        runtime?.let { return it }

        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .build()

        val newRuntime = GeckoRuntime.create(this, settings)

        newRuntime.setWebNotificationDelegate(
            object : WebNotificationDelegate {
                override fun onShowNotification(notification: WebNotification) {
                    showAndroidNotification(notification)
                }

                override fun onCloseNotification(notification: WebNotification) {
                    notification.dismiss()
                }
            }
        )

        runtime = newRuntime
        return newRuntime
    }

    fun getOrCreateSession(account: Account): GeckoSession {
        sessions[account.id]?.let { return it }

        val sessionSettings = GeckoSessionSettings.Builder()
            .contextId("central-emails-${account.id}")
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .build()

        val session = GeckoSession(sessionSettings)

        session.setContentDelegate(
            object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) {
                    forgetBrokenSession(account.id)
                }

                override fun onKill(session: GeckoSession) {
                    forgetBrokenSession(account.id)
                }
            }
        )

        session.setPermissionDelegate(
            object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission
                ): GeckoResult<Int> {
                    val allow =
                        perm.permission ==
                            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION

                    return GeckoResult.fromValue(
                        if (allow) {
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        } else {
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                        }
                    )
                }
            }
        )

        session.open(getRuntime())
        sessions[account.id] = session

        if (account.id != "rita_gmail") {
            startBackgroundRefreshLoop()
        }

        return session
    }

    private fun forgetBrokenSession(accountId: String) {
        handler.post {
            sessions.remove(accountId)
            loadedAccounts.remove(accountId)
        }
    }

    fun selectAccount(accountId: String) {
        selectedAccountId = accountId
        setNotificationHint(accountId, 30_000L)
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (!visible) startBackgroundRefreshLoop()
    }

    fun markAccountSeen(accountId: String) {
        setUnread(accountId, 0)
    }

    fun getUnread(accountId: String): Int =
        unreadCounts[accountId] ?: 0

    fun addUnreadListener(listener: UnreadListener) {
        unreadListeners.add(listener)
    }

    fun removeUnreadListener(listener: UnreadListener) {
        unreadListeners.remove(listener)
    }

    private fun setUnread(accountId: String, count: Int) {
        val safe = count.coerceAtLeast(0)
        unreadCounts[accountId] = safe

        getSharedPreferences("central-emails", MODE_PRIVATE)
            .edit()
            .putInt("unread_$accountId", safe)
            .apply()

        unreadListeners.toList().forEach {
            it.onUnreadChanged(accountId, safe)
        }
    }

    private fun incrementUnread(accountId: String) {
        setUnread(accountId, getUnread(accountId) + 1)
    }

    private fun loadUnread(accountId: String): Int =
        getSharedPreferences("central-emails", MODE_PRIVATE)
            .getInt("unread_$accountId", 0)

    private fun setNotificationHint(accountId: String, durationMs: Long) {
        notificationHintAccountId = accountId
        notificationHintUntil = SystemClock.elapsedRealtime() + durationMs
    }

    private fun notificationAccountId(): String {
        val hinted = notificationHintAccountId

        return if (
            hinted != null &&
            SystemClock.elapsedRealtime() <= notificationHintUntil
        ) {
            hinted
        } else {
            selectedAccountId
        }
    }

    private fun startBackgroundRefreshLoop() {
        if (refreshRunning) return

        refreshRunning = true
        handler.postDelayed(::refreshNextSapoSession, 20_000L)
    }

    private fun refreshNextSapoSession() {
        val sapoSessions = DEFAULT_ACCOUNTS
            .filter { it.id != "rita_gmail" }
            .mapNotNull { account ->
                sessions[account.id]?.let { account.id to it }
            }

        if (sapoSessions.isEmpty()) {
            refreshRunning = false
            return
        }

        if (refreshIndex >= sapoSessions.size) {
            refreshIndex = 0
            handler.postDelayed(::refreshNextSapoSession, 90_000L)
            return
        }

        val (accountId, session) = sapoSessions[refreshIndex++]

        if (uiVisible && accountId == selectedAccountId) {
            handler.postDelayed(::refreshNextSapoSession, 6_000L)
            return
        }

        try {
            setNotificationHint(accountId, 35_000L)
            session.setActive(true)
            session.reload()

            handler.postDelayed({
                try {
                    if (!(uiVisible && accountId == selectedAccountId)) {
                        session.setActive(false)
                    }
                } catch (_: Exception) {
                }
            }, 12_000L)
        } catch (_: Exception) {
        }

        handler.postDelayed(::refreshNextSapoSession, 18_000L)
    }

    private fun accountLabel(accountId: String): String =
        DEFAULT_ACCOUNTS.firstOrNull { it.id == accountId }?.label
            ?: "Central de Emails"

    private fun showAndroidNotification(notification: WebNotification) {
        val accountId = notificationAccountId()
        val accountLabel = accountLabel(accountId)

        incrementUnread(accountId)

        val manager = getSystemService(NotificationManager::class.java)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP

            putExtra("open_account", accountId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            (accountId + notification.tag).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val originalTitle = notification.title ?: "Novo email"
        val text = notification.text ?: "Recebeste uma nova mensagem."
        val title = "$accountLabel — $originalTitle"

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, "mail")
            } else {
                Notification.Builder(this)
            }

        val androidNotification = builder
            .setSmallIcon(R.drawable.ic_mail)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(accountLabel)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup("mail_$accountId")
            .build()

        manager.notify(
            (accountId + notification.tag + originalTitle + text).hashCode(),
            androidNotification
        )
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(
                    "mail",
                    "Central de Emails",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Novos emails"
                }
            )
        }
    }
}
