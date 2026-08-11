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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = application as Caixa6App
        app.ensureSessions()

        requestNotificationPermission()

        startForegroundService(
            Intent(this, KeepAliveService::class.java)
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }

        tabRow = LinearLayout(this).apply {
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

            val button = Button(this).apply {
                text = account.label
                isAllCaps = false

                setOnClickListener {
                    showAccount(account)
                }
            }

            tabRow.addView(button)
        }

        showAccount(DEFAULT_ACCOUNTS.first())
    }

    private fun showAccount(account: Account) {

        val next = app.sessions[account.id] ?: return

        if (currentSession !== next) {

            currentSession?.apply {
                setFocused(false)
                setActive(true)
            }

            if (geckoView.session != null) {
                geckoView.releaseSession()
            }

            geckoView.setSession(next)

            currentSession = next
        }

        next.setActive(true)
        next.setFocused(true)

        /*
         * Forçamos a abertura da página quando mudamos de conta.
         * Os cookies continuam separados para cada conta.
         */
        if (currentAccountId != account.id) {
            next.loadUri(account.url)
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

        currentSession?.apply {
            setActive(true)
            setFocused(true)
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
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }
}
