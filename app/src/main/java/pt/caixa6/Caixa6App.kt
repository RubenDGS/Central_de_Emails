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
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension

class Caixa6App : Application() {

    interface UnreadListener {
        fun onUnreadChanged(accountId: String, count: Int)
    }

    private var runtime: GeckoRuntime? = null
    private var sapoExtension: WebExtension? = null
    private val pendingMonitorSessions =
        mutableListOf<Pair<String, GeckoSession>>()

    private val handler = Handler(Looper.getMainLooper())

    val sessions = linkedMapOf<String, GeckoSession>()

    var selectedAccountId: String = "rita_sapo"
        private set

    private var uiVisible = false
    private var refreshRunning = false
    private var refreshIndex = 0

    private val unreadCounts = linkedMapOf<String, Int>()
    private val knownServerCounts = mutableSetOf<String>()
    private val unreadListeners = mutableSetOf<UnreadListener>()

    override fun onCreate() {
        super.onCreate()

        DEFAULT_ACCOUNTS.forEach { account ->
            unreadCounts[account.id] = loadUnread(account.id)
        }

        createNotificationChannel()
    }

    private fun getRuntime(): GeckoRuntime {
        runtime?.let { return it }

        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .build()

        val newRuntime = GeckoRuntime.create(this, settings)

        newRuntime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/sapo-monitor/",
                "sapo-monitor@central-emails.local"
            )
            .accept { extension ->
                if (extension != null) {
                    sapoExtension = extension

                    val copy = pendingMonitorSessions.toList()
                    pendingMonitorSessions.clear()

                    copy.forEach { (accountId, session) ->
                        attachSapoMonitor(accountId, session, extension)
                    }
                }
            }

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

        /*
         * As notificações SAPO passam a ser geradas pela própria app
         * através do contador real observado em cada sessão.
         * Assim conseguimos saber exatamente qual conta as originou.
         */
        session.setPermissionDelegate(
            object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission
                ): GeckoResult<Int> {
                    return GeckoResult.fromValue(
                        if (
                            perm.permission ==
                            GeckoSession.PermissionDelegate
                                .PERMISSION_DESKTOP_NOTIFICATION
                        ) {
                            GeckoSession.PermissionDelegate
                                .ContentPermission.VALUE_DENY
                        } else {
                            GeckoSession.PermissionDelegate
                                .ContentPermission.VALUE_DENY
                        }
                    )
                }
            }
        )

        session.open(getRuntime())
        sessions[account.id] = session

        sapoExtension?.let {
            attachSapoMonitor(account.id, session, it)
        } ?: pendingMonitorSessions.add(account.id to session)

        startBackgroundRefreshLoop()

        return session
    }

    private fun attachSapoMonitor(
        accountId: String,
        session: GeckoSession,
        extension: WebExtension
    ) {
        session.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender
                ): GeckoResult<Any>? {
                    if (
                        nativeApp == "sapoMonitor" &&
                        message is JSONObject
                    ) {
                        val unread = message.optInt("unread", -1)

                        if (unread >= 0) {
                            handler.post {
                                updateSapoUnread(accountId, unread)
                            }
                        }
                    }

                    return null
                }
            },
            "sapoMonitor"
        )
    }

    private fun updateSapoUnread(accountId: String, newCount: Int) {
        val oldCount = unreadCounts[accountId] ?: 0
        val hadBaseline = knownServerCounts.contains(accountId)

        unreadCounts[accountId] = newCount
        knownServerCounts.add(accountId)
        saveUnread(accountId, newCount)
        notifyUnreadListeners(accountId, newCount)

        /*
         * Na primeira leitura apenas estabelecemos a referência,
         * para não disparar dezenas de notificações antigas.
         */
        if (hadBaseline && newCount > oldCount) {
            val difference = newCount - oldCount

            showAccountNotification(
                accountId,
                if (difference == 1) {
                    "1 novo email"
                } else {
                    "$difference novos emails"
                },
                "Tens $newCount mensagens por ler na Caixa de Entrada."
            )
        }
    }

    private fun forgetBrokenSession(accountId: String) {
        handler.post {
            sessions.remove(accountId)
        }
    }

    fun selectAccount(accountId: String) {
        selectedAccountId = accountId
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible

        if (!visible) {
            startBackgroundRefreshLoop()
        }
    }

    fun getUnread(accountId: String): Int =
        unreadCounts[accountId] ?: 0

    fun setGmailUnread(count: Int) {
        unreadCounts["rita_gmail"] = count.coerceAtLeast(0)
        saveUnread("rita_gmail", count.coerceAtLeast(0))
        notifyUnreadListeners("rita_gmail", count.coerceAtLeast(0))
    }

    fun addUnreadListener(listener: UnreadListener) {
        unreadListeners.add(listener)
    }

    fun removeUnreadListener(listener: UnreadListener) {
        unreadListeners.remove(listener)
    }

    private fun notifyUnreadListeners(accountId: String, count: Int) {
        unreadListeners.toList().forEach {
            it.onUnreadChanged(accountId, count)
        }
    }

    private fun saveUnread(accountId: String, count: Int) {
        getSharedPreferences("central-emails", MODE_PRIVATE)
            .edit()
            .putInt("unread_$accountId", count)
            .apply()
    }

    private fun loadUnread(accountId: String): Int =
        getSharedPreferences("central-emails", MODE_PRIVATE)
            .getInt("unread_$accountId", 0)

    /*
     * Sem foreground service: não existe o envelope permanente
     * "A verificar novos emails".
     *
     * Enquanto o processo continuar vivo, atualizamos as sessões SAPO
     * uma a uma. O Android pode suspender/matar o processo em background.
     */
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
            handler.postDelayed(::refreshNextSapoSession, 75_000L)
            return
        }

        val (accountId, session) = sapoSessions[refreshIndex++]

        if (uiVisible && accountId == selectedAccountId) {
            handler.postDelayed(::refreshNextSapoSession, 5_000L)
            return
        }

        try {
            session.setActive(true)
            session.reload()

            handler.postDelayed({
                try {
                    if (!(uiVisible && accountId == selectedAccountId)) {
                        session.setActive(false)
                    }
                } catch (_: Exception) {
                }
            }, 15_000L)
        } catch (_: Exception) {
        }

        handler.postDelayed(::refreshNextSapoSession, 20_000L)
    }

    private fun accountLabel(accountId: String): String =
        DEFAULT_ACCOUNTS.firstOrNull { it.id == accountId }?.label
            ?: "Central de Emails"

    private fun showAccountNotification(
        accountId: String,
        titleText: String,
        body: String
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        val label = accountLabel(accountId)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_account", accountId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            ("open_$accountId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, "mail")
            } else {
                Notification.Builder(this)
            }

        val notification = builder
            .setSmallIcon(R.drawable.ic_mail)
            .setContentTitle("$label — $titleText")
            .setContentText(body)
            .setSubText(label)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(
            ("$accountId:${System.currentTimeMillis()}").hashCode(),
            notification
        )
    }

    private fun createNotificationChannel() {
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
