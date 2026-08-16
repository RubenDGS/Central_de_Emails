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
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import java.util.concurrent.TimeUnit

class Caixa6App : Application() {

    interface UnreadListener {
        fun onUnreadChanged(accountId: String, count: Int)
    }

    private var runtime: GeckoRuntime? = null
    private var sapoExtension: WebExtension? = null
    private val pendingMonitorSessions = mutableListOf<Pair<String, GeckoSession>>()
    private val handler = Handler(Looper.getMainLooper())

    val sessions = linkedMapOf<String, GeckoSession>()

    var selectedAccountId: String = "rita_sapo"
        private set

    private var uiVisible = false
    private var refreshRunning = false
    private var refreshIndex = 0

    private var notificationHintAccountId: String? = null
    private var notificationHintUntil = 0L
    private val lastWebNotificationAt = mutableMapOf<String, Long>()

    private val unreadCounts = linkedMapOf<String, Int>()
    private val unreadListeners = mutableSetOf<UnreadListener>()

    override fun onCreate() {
        super.onCreate()

        DEFAULT_ACCOUNTS.forEach {
            unreadCounts[it.id] = loadUnread(it.id)
        }

        createNotificationChannel()
        schedulePeriodicMailCheck()
    }

    private fun getRuntime(): GeckoRuntime {
        runtime?.let { return it }

        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .build()

        val created = GeckoRuntime.create(this, settings)

        created.setWebNotificationDelegate(
            object : WebNotificationDelegate {
                override fun onShowNotification(notification: WebNotification) {
                    handleWebNotification(notification)
                }

                override fun onCloseNotification(notification: WebNotification) {
                    notification.dismiss()
                }
            }
        )

        created.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/sapo-monitor/",
                "sapo-monitor@central-emails.local"
            )
            .accept(
                { extension ->
                    if (extension != null) {
                        sapoExtension = extension

                        val waiting = pendingMonitorSessions.toList()
                        pendingMonitorSessions.clear()

                        waiting.forEach { (accountId, session) ->
                            attachMonitor(accountId, session, extension)
                        }
                    }
                },
                { error ->
                    Log.e("CentralEmails", "Erro ao instalar monitor SAPO", error)
                }
            )

        runtime = created
        return created
    }

    fun getOrCreateSession(account: Account): GeckoSession {
        sessions[account.id]?.let { return it }

        val settings = GeckoSessionSettings.Builder()
            .contextId("central-emails-${account.id}")
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .build()

        val session = GeckoSession(settings)

        session.setContentDelegate(
            object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) {
                    forgetSession(account.id)
                }

                override fun onKill(session: GeckoSession) {
                    forgetSession(account.id)
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

        sapoExtension?.let {
            attachMonitor(account.id, session, it)
        } ?: pendingMonitorSessions.add(account.id to session)

        startFastLoop()
        return session
    }

    private fun attachMonitor(
        accountId: String,
        session: GeckoSession,
        extension: WebExtension
    ) {
        try {
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
                            message is JSONObject &&
                            message.optString("type") == "sapo_state" &&
                            message.optString("folder") == "INBOX" &&
                            message.has("unread") &&
                            !message.isNull("unread")
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
        } catch (error: Exception) {
            Log.e("CentralEmails", "Erro no monitor $accountId", error)
        }
    }

    private fun updateSapoUnread(accountId: String, newCount: Int) {
        val prefs = getSharedPreferences("central-emails", MODE_PRIVATE)
        val baselineKey = "baseline_inbox_unread_v3_$accountId"
        val hadBaseline = prefs.getBoolean(baselineKey, false)
        val oldCount = unreadCounts[accountId] ?: 0

        unreadCounts[accountId] = newCount
        saveUnread(accountId, newCount)
        notifyUnreadListeners(accountId, newCount)

        if (!hadBaseline) {
            prefs.edit().putBoolean(baselineKey, true).apply()
            return
        }

        if (newCount > oldCount) {
            val now = SystemClock.elapsedRealtime()
            val lastWeb = lastWebNotificationAt[accountId] ?: 0L

            if (now - lastWeb > 30_000L) {
                val difference = newCount - oldCount

                showAccountNotification(
                    accountId,
                    if (difference == 1) "1 novo email" else "$difference novos emails",
                    "Tens $newCount emails por ler na Caixa de Entrada."
                )
            }
        }
    }

    private fun handleWebNotification(notification: WebNotification) {
        val accountId = hintedAccount()

        lastWebNotificationAt[accountId] = SystemClock.elapsedRealtime()

        showAccountNotification(
            accountId,
            notification.title ?: "Novo email",
            notification.text ?: "Recebeste uma nova mensagem.",
            notification.tag
        )
    }

    private fun setHint(accountId: String, duration: Long = 30_000L) {
        notificationHintAccountId = accountId
        notificationHintUntil = SystemClock.elapsedRealtime() + duration
    }

    private fun hintedAccount(): String {
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

    private fun forgetSession(accountId: String) {
        handler.post {
            sessions.remove(accountId)
            pendingMonitorSessions.removeAll { it.first == accountId }
        }
    }

    fun selectAccount(accountId: String) {
        selectedAccountId = accountId
        setHint(accountId)
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (!visible) startFastLoop()
    }

    fun getUnread(accountId: String): Int =
        unreadCounts[accountId] ?: 0

    fun setGmailUnread(count: Int) {
        val safe = count.coerceAtLeast(0)
        unreadCounts["rita_gmail"] = safe
        saveUnread("rita_gmail", safe)
        notifyUnreadListeners("rita_gmail", safe)
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

    private fun startFastLoop() {
        if (refreshRunning) return
        refreshRunning = true
        handler.postDelayed(::refreshNextSession, 15_000L)
    }

    private fun refreshNextSession() {
        val accounts = DEFAULT_ACCOUNTS.filter { it.id != "rita_gmail" }

        if (accounts.isEmpty()) {
            refreshRunning = false
            return
        }

        if (refreshIndex >= accounts.size) {
            refreshIndex = 0
            handler.postDelayed(::refreshNextSession, 60_000L)
            return
        }

        val account = accounts[refreshIndex++]

        val session = try {
            getOrCreateSession(account)
        } catch (error: Exception) {
            Log.e("CentralEmails", "Erro sessão ${account.id}", error)
            handler.postDelayed(::refreshNextSession, 10_000L)
            return
        }

        if (uiVisible && account.id == selectedAccountId) {
            handler.postDelayed(::refreshNextSession, 5_000L)
            return
        }

        wake(account, session, 14_000L)

        handler.postDelayed(::refreshNextSession, 18_000L)
    }

    private fun wake(
        account: Account,
        session: GeckoSession,
        activeFor: Long
    ) {
        try {
            setHint(account.id, activeFor + 10_000L)

            session.setActive(true)
            session.loadUri(account.url)

            handler.postDelayed({
                try {
                    if (!(uiVisible && selectedAccountId == account.id)) {
                        session.setActive(false)
                    }
                } catch (_: Exception) {
                }
            }, activeFor)

        } catch (error: Exception) {
            Log.e("CentralEmails", "Erro atualização ${account.id}", error)
        }
    }

    /*
     * Usado pelo WorkManager quando a app não está aberta.
     * Acorda as 5 sessões SAPO sequencialmente.
     */
    fun refreshAllSapoForWorker(onFinished: () -> Unit) {
        handler.post {
            val accounts = DEFAULT_ACCOUNTS.filter { it.id != "rita_gmail" }

            fun next(index: Int) {
                if (index >= accounts.size) {
                    onFinished()
                    return
                }

                val account = accounts[index]

                try {
                    val session = getOrCreateSession(account)
                    wake(account, session, 9_000L)
                } catch (error: Exception) {
                    Log.e("CentralEmails", "Worker ${account.id}", error)
                }

                handler.postDelayed(
                    { next(index + 1) },
                    11_000L
                )
            }

            next(0)
        }
    }

    private fun schedulePeriodicMailCheck() {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val request =
            PeriodicWorkRequestBuilder<MailRefreshWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "central-emails-sapo-15min",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
    }

    private fun accountLabel(accountId: String): String =
        DEFAULT_ACCOUNTS.firstOrNull { it.id == accountId }?.label
            ?: "Central de Emails"

    private fun showAccountNotification(
        accountId: String,
        titleText: String,
        body: String,
        sourceTag: String = ""
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        val label = accountLabel(accountId)

        val intent =
            Intent(this, MainActivity::class.java).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP

                putExtra("open_account", accountId)
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                ("open_$accountId$sourceTag").hashCode(),
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

        val androidNotification =
            builder
                .setSmallIcon(R.drawable.ic_mail)
                .setContentTitle("$label — $titleText")
                .setContentText(body)
                .setSubText(label)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setGroup("central_mail_$accountId")
                .build()

        manager.notify(
            (
                "$accountId:$sourceTag:$titleText:$body:" +
                    System.currentTimeMillis()
            ).hashCode(),
            androidNotification
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
