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
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate

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

    private var webNotificationHintAccountId: String? = null
    private var webNotificationHintUntil: Long = 0L
    private val lastWebNotificationAt = mutableMapOf<String, Long>()

    private val unreadCounts = linkedMapOf<String, Int>()
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

        newRuntime.setWebNotificationDelegate(
            object : WebNotificationDelegate {
                override fun onShowNotification(
                    notification: WebNotification
                ) {
                    handleWebNotification(notification)
                }

                override fun onCloseNotification(
                    notification: WebNotification
                ) {
                    notification.dismiss()
                }
            }
        )

        newRuntime.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/sapo-monitor/",
                "sapo-monitor@central-emails.local"
            )
            .accept(
                { extension ->
                    if (extension != null) {
                        sapoExtension = extension

                        val waiting =
                            pendingMonitorSessions.toList()

                        pendingMonitorSessions.clear()

                        waiting.forEach { (accountId, session) ->
                            attachSapoMonitor(
                                accountId,
                                session,
                                extension
                            )
                        }
                    }
                },
                { error ->
                    Log.e(
                        "CentralEmails",
                        "Falha ao instalar monitor SAPO",
                        error
                    )
                }
            )

        runtime = newRuntime
        return newRuntime
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
                .userAgentMode(
                    GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                )
                .build()

        val session =
            GeckoSession(sessionSettings)

        session.setContentDelegate(
            object : GeckoSession.ContentDelegate {

                override fun onCrash(
                    session: GeckoSession
                ) {
                    forgetBrokenSession(
                        account.id
                    )
                }

                override fun onKill(
                    session: GeckoSession
                ) {
                    forgetBrokenSession(
                        account.id
                    )
                }
            }
        )

        session.setPermissionDelegate(
            object : GeckoSession.PermissionDelegate {

                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm:
                    GeckoSession.PermissionDelegate.ContentPermission
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

        session.open(getRuntime())

        sessions[account.id] = session

        sapoExtension?.let {
            attachSapoMonitor(
                account.id,
                session,
                it
            )
        } ?: pendingMonitorSessions.add(
            account.id to session
        )

        startBackgroundRefreshLoop()

        return session
    }

    private fun attachSapoMonitor(
        accountId: String,
        session: GeckoSession,
        extension: WebExtension
    ) {
        try {
            session.webExtensionController
                .setMessageDelegate(
                    extension,
                    object : WebExtension.MessageDelegate {

                        override fun onMessage(
                            nativeApp: String,
                            message: Any,
                            sender:
                            WebExtension.MessageSender
                        ): GeckoResult<Any>? {

                            if (
                                nativeApp ==
                                "sapoMonitor" &&
                                message is JSONObject &&
                                message.optString(
                                    "type"
                                ) ==
                                "sapo_state" &&
                                message.optString(
                                    "folder"
                                ) ==
                                "INBOX"
                            ) {
                                if (
                                    message.has(
                                        "unread"
                                    ) &&
                                    !message.isNull(
                                        "unread"
                                    )
                                ) {
                                    val unread =
                                        message.optInt(
                                            "unread",
                                            -1
                                        )

                                    if (unread >= 0) {
                                        handler.post {
                                            updateSapoUnread(
                                                accountId,
                                                unread
                                            )
                                        }
                                    }
                                }
                            }

                            return null
                        }
                    },
                    "sapoMonitor"
                )
        } catch (error: Exception) {
            Log.e(
                "CentralEmails",
                "Falha ao ligar monitor à sessão $accountId",
                error
            )
        }
    }

    private fun updateSapoUnread(
        accountId: String,
        newCount: Int
    ) {
        val prefs =
            getSharedPreferences(
                "central-emails",
                MODE_PRIVATE
            )

        /*
         * Nova referência da versão 0.6.2.
         * Os números das versões anteriores eram inválidos,
         * por isso o primeiro valor correto de cada conta
         * nunca gera uma notificação.
         */
        val baselineKey =
            "baseline_inbox_unread_v2_$accountId"

        val hadBaseline =
            prefs.getBoolean(
                baselineKey,
                false
            )

        val oldCount =
            unreadCounts[accountId] ?: 0

        unreadCounts[accountId] =
            newCount

        saveUnread(
            accountId,
            newCount
        )

        notifyUnreadListeners(
            accountId,
            newCount
        )

        if (!hadBaseline) {
            prefs.edit()
                .putBoolean(
                    baselineKey,
                    true
                )
                .apply()

            return
        }

        if (newCount > oldCount) {
            val lastWeb =
                lastWebNotificationAt[
                    accountId
                ] ?: 0L

            val now =
                SystemClock.elapsedRealtime()

            if (
                now - lastWeb >
                30_000L
            ) {
                val difference =
                    newCount - oldCount

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
    }

    private fun handleWebNotification(
        notification: WebNotification
    ) {
        val accountId =
            notificationHintAccount()

        lastWebNotificationAt[accountId] =
            SystemClock.elapsedRealtime()

        showAccountNotification(
            accountId,
            notification.title
                ?: "Novo email",
            notification.text
                ?: "Recebeste uma nova mensagem.",
            notification.tag
        )
    }

    private fun notificationHintAccount():
        String {

        val hinted =
            webNotificationHintAccountId

        return if (
            hinted != null &&
            SystemClock.elapsedRealtime()
                <=
            webNotificationHintUntil
        ) {
            hinted
        } else {
            selectedAccountId
        }
    }

    private fun setNotificationHint(
        accountId: String,
        durationMs: Long = 30_000L
    ) {
        webNotificationHintAccountId =
            accountId

        webNotificationHintUntil =
            SystemClock.elapsedRealtime() +
                durationMs
    }

    private fun forgetBrokenSession(
        accountId: String
    ) {
        handler.post {
            sessions.remove(
                accountId
            )

            pendingMonitorSessions
                .removeAll {
                    it.first ==
                        accountId
                }
        }
    }

    fun selectAccount(
        accountId: String
    ) {
        selectedAccountId =
            accountId

        setNotificationHint(
            accountId
        )
    }

    fun setUiVisible(
        visible: Boolean
    ) {
        uiVisible = visible

        if (!visible) {
            startBackgroundRefreshLoop()
        }
    }

    fun getUnread(
        accountId: String
    ): Int =
        unreadCounts[accountId]
            ?: 0

    fun setGmailUnread(
        count: Int
    ) {
        val safe =
            count.coerceAtLeast(0)

        unreadCounts["rita_gmail"] =
            safe

        saveUnread(
            "rita_gmail",
            safe
        )

        notifyUnreadListeners(
            "rita_gmail",
            safe
        )
    }

    fun addUnreadListener(
        listener: UnreadListener
    ) {
        unreadListeners.add(
            listener
        )
    }

    fun removeUnreadListener(
        listener: UnreadListener
    ) {
        unreadListeners.remove(
            listener
        )
    }

    private fun notifyUnreadListeners(
        accountId: String,
        count: Int
    ) {
        unreadListeners
            .toList()
            .forEach {
                it.onUnreadChanged(
                    accountId,
                    count
                )
            }
    }

    private fun saveUnread(
        accountId: String,
        count: Int
    ) {
        getSharedPreferences(
            "central-emails",
            MODE_PRIVATE
        )
            .edit()
            .putInt(
                "unread_$accountId",
                count
            )
            .apply()
    }

    private fun loadUnread(
        accountId: String
    ): Int =
        getSharedPreferences(
            "central-emails",
            MODE_PRIVATE
        )
            .getInt(
                "unread_$accountId",
                0
            )

    private fun startBackgroundRefreshLoop() {
        if (refreshRunning) {
            return
        }

        refreshRunning = true

        handler.postDelayed(
            ::refreshNextSapoSession,
            15_000L
        )
    }

    private fun refreshNextSapoSession() {
        val accounts =
            DEFAULT_ACCOUNTS.filter {
                it.id !=
                    "rita_gmail"
            }

        if (accounts.isEmpty()) {
            refreshRunning = false
            return
        }

        if (
            refreshIndex >=
            accounts.size
        ) {
            refreshIndex = 0

            handler.postDelayed(
                ::refreshNextSapoSession,
                60_000L
            )

            return
        }

        val account =
            accounts[
                refreshIndex++
            ]

        val session =
            try {
                getOrCreateSession(
                    account
                )
            } catch (
                error: Exception
            ) {
                Log.e(
                    "CentralEmails",
                    "Falha ao criar sessão ${account.id}",
                    error
                )

                handler.postDelayed(
                    ::refreshNextSapoSession,
                    10_000L
                )

                return
            }

        if (
            uiVisible &&
            account.id ==
            selectedAccountId
        ) {
            handler.postDelayed(
                ::refreshNextSapoSession,
                5_000L
            )

            return
        }

        try {
            setNotificationHint(
                account.id,
                30_000L
            )

            session.setActive(
                true
            )

            session.reload()

            handler.postDelayed({
                try {
                    if (
                        !(
                            uiVisible &&
                            account.id ==
                            selectedAccountId
                        )
                    ) {
                        session.setActive(
                            false
                        )
                    }
                } catch (
                    _: Exception
                ) {
                }
            }, 15_000L)

        } catch (
            error: Exception
        ) {
            Log.e(
                "CentralEmails",
                "Falha ao atualizar ${account.id}",
                error
            )
        }

        handler.postDelayed(
            ::refreshNextSapoSession,
            20_000L
        )
    }

    private fun accountLabel(
        accountId: String
    ): String =
        DEFAULT_ACCOUNTS
            .firstOrNull {
                it.id ==
                    accountId
            }
            ?.label
            ?: "Central de Emails"

    private fun showAccountNotification(
        accountId: String,
        titleText: String,
        body: String,
        sourceTag: String = ""
    ) {
        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val label =
            accountLabel(
                accountId
            )

        val intent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP

                putExtra(
                    "open_account",
                    accountId
                )
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                (
                    "open_$accountId$sourceTag"
                    ).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val builder =
            if (
                Build.VERSION.SDK_INT >=
                26
            ) {
                Notification.Builder(
                    this,
                    "mail"
                )
            } else {
                Notification.Builder(
                    this
                )
            }

        val androidNotification =
            builder
                .setSmallIcon(
                    R.drawable.ic_mail
                )
                .setContentTitle(
                    "$label — $titleText"
                )
                .setContentText(
                    body
                )
                .setSubText(
                    label
                )
                .setStyle(
                    Notification
                        .BigTextStyle()
                        .bigText(
                            body
                        )
                )
                .setContentIntent(
                    pendingIntent
                )
                .setAutoCancel(
                    true
                )
                .setGroup(
                    "central_mail_$accountId"
                )
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
        if (
            Build.VERSION.SDK_INT >=
            26
        ) {
            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager
                .createNotificationChannel(
                    NotificationChannel(
                        "mail",
                        "Central de Emails",
                        NotificationManager
                            .IMPORTANCE_HIGH
                    ).apply {
                        description =
                            "Novos emails"
                    }
                )
        }
    }
}
