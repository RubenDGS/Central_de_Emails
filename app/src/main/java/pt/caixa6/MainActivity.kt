package pt.caixa6

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {

    private lateinit var app: Caixa6App
    private lateinit var geckoView: GeckoView
    private lateinit var root: LinearLayout

    private var currentSession: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = application as Caixa6App

        requestNotificationPermission()

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)

            setOnApplyWindowInsetsListener { view, insets ->

                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(
                        WindowInsets.Type.statusBars() or
                            WindowInsets.Type.navigationBars()
                    )

                    view.setPadding(
                        0,
                        bars.top,
                        0,
                        bars.bottom
                    )
                }

                insets
            }
        }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }

        DEFAULT_ACCOUNTS.forEach { account ->

            val button = Button(this).apply {
                text = account.label
                isAllCaps = false

                setOnClickListener {
                    openAccount(account)
                }
            }

            tabRow.addView(button)
        }

        scroll.addView(tabRow)

        val status = TextView(this).apply {
            text = "Escolhe uma conta acima."
            textSize = 18f
            setPadding(24, 24, 24, 24)
        }

        geckoView = GeckoView(this)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            status,
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

        /*
         * IMPORTANTE:
         * não abrimos nenhuma conta automaticamente.
         */
    }

    private fun openAccount(account: Account) {

        val session = app.getOrCreateSession(account)

        if (currentSession !== session) {

            currentSession?.let {
                it.setFocused(false)
                it.setActive(false)
            }

            if (geckoView.session != null) {
                geckoView.releaseSession()
            }

            geckoView.setSession(session)

            currentSession = session
        }

        session.setActive(true)
        session.setFocused(true)

        if (!app.loadedAccounts.contains(account.id)) {

            session.loadUri(account.url)

            app.loadedAccounts.add(account.id)
        }

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
