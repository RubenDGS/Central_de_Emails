package pt.caixa6

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.*
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {

    private lateinit var app: Caixa6App

    private lateinit var geckoView: GeckoView

    private lateinit var tabRow: LinearLayout

    private var currentSession: GeckoSession? = null

    private var currentAccountId: String? = null

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        app = application as Caixa6App

        app.ensureSessions()

        requestNotificationPermission()

        startForegroundService(
            Intent(
                this,
                KeepAliveService::class.java
            )
        )

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
            }

        val scroll =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
            }

        tabRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 8, 8, 8)
            }

        scroll.addView(tabRow)

        geckoView = GeckoView(this)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            geckoView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        DEFAULT_ACCOUNTS.forEach { account ->

            val button =
                Button(this).apply {

                    text = account.label

                    isAllCaps = false

                    setOnClickListener {
                        showAccount(account)
                    }
                }

            tabRow.addView(button)
        }

        val firstAccount =
            DEFAULT_ACCOUNTS.firstOrNull {
                it.id == app.selectedAccountId
            } ?: DEFAULT_ACCOUNTS.first()

        showAccount(firstAccount)
    }

    private fun showAccount(
        account: Account
    ) {

        val nextSession =
            app.sessions[account.id]
                ?: return

        /*
         * Se estamos a mudar de conta,
         * desligamos visualmente a sessão anterior.
         */
        if (currentSession !== nextSession) {

            currentSession?.let { oldSession ->

                oldSession.setFocused(false)

                /*
                 * Continua ativa em background.
                 */
                oldSession.setActive(true)
            }

            /*
             * GeckoView só pode ter uma sessão
             * associada visualmente de cada vez.
             */
            if (geckoView.session != null) {
                geckoView.releaseSession()
            }

            geckoView.setSession(nextSession)

            currentSession = nextSession
        }

        nextSession.setActive(true)

        nextSession.setFocused(true)

        /*
         * Na primeira vez que se toca nesta conta,
         * abre o respetivo webmail.
         *
         * Depois disso NÃO voltamos a carregar
         * automaticamente o endereço, para não
         * perderes a página onde estavas.
         */
        if (!app.loadedAccounts.contains(account.id)) {

            nextSession.loadUri(account.url)

            app.loadedAccounts.add(account.id)
        }

        currentAccountId = account.id

        app.selectedAccountId = account.id
    }

    override fun onPause() {
        super.onPause()

        currentSession?.setFocused(false)
    }

    override fun onResume() {
        super.onResume()

        currentSession?.let {
            it.setActive(true)
            it.setFocused(true)
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    private fun requestNotificationPermission() {

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissions(
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                100
            )
        }
    }
}
