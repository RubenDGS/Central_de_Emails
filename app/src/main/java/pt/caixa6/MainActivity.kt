package pt.caixa6

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity(), Caixa6App.UnreadListener {

    private lateinit var app: Caixa6App
    private lateinit var geckoView: GeckoView
    private lateinit var root: LinearLayout
    private lateinit var tabRow: LinearLayout

    private val accountButtons = linkedMapOf<String, Button>()

    private var currentSession: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = application as Caixa6App
        app.addUnreadListener(this)

        requestNotificationPermission()

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE

        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.background_pastel)

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
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        0,
                        insets.systemWindowInsetTop,
                        0,
                        insets.systemWindowInsetBottom
                    )
                }

                insets
            }
        }

        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(5), dp(3), dp(5))
            setBackgroundColor(Color.argb(80, 255, 255, 255))
        }

        DEFAULT_ACCOUNTS.forEach { account ->
            val button = Button(this).apply {
                isAllCaps = false
                textSize = 10.1f
                gravity = Gravity.CENTER
                maxLines = 2
                includeFontPadding = false

                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0

                setTextColor(Color.parseColor("#333333"))
                setPadding(dp(1), dp(7), dp(1), dp(7))

                setOnClickListener {
                    if (account.id == "rita_gmail") {
                        openGmail()
                    } else {
                        openSapo(account)
                    }
                }
            }

            accountButtons[account.id] = button

            val params = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(dp(1), 0, dp(1), 0)
            }

            tabRow.addView(button, params)
        }

        geckoView = GeckoView(this).apply {
            visibility = View.GONE
        }

        root.addView(
            tabRow,
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

        refreshAllButtons()

        intent.getStringExtra("open_account")?.let { accountId ->
            DEFAULT_ACCOUNTS
                .firstOrNull { it.id == accountId }
                ?.let { account ->
                    if (account.id == "rita_gmail") {
                        openGmail()
                    } else {
                        openSapo(account)
                    }
                }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        val accountId = intent?.getStringExtra("open_account") ?: return

        DEFAULT_ACCOUNTS
            .firstOrNull { it.id == accountId }
            ?.let { account ->
                if (account.id == "rita_gmail") {
                    openGmail()
                } else {
                    openSapo(account)
                }
            }
    }

    private fun openSapo(account: Account) {
        geckoView.visibility = View.VISIBLE

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

        app.selectAccount(account.id)
        app.markAccountSeen(account.id)

        session.setActive(true)
        session.setFocused(true)

        // Sempre abre a Caixa de Entrada do SAPO.
        session.loadUri(account.url)
        app.loadedAccounts.add(account.id)

        updateSelectedButton(account.id)
    }

    private fun openGmail() {
        app.selectAccount("rita_gmail")
        app.markAccountSeen("rita_gmail")
        updateSelectedButton("rita_gmail")

        // O Gmail embebido ficou preto/branco após login.
        // Mantemos o botão no topo, mas abrimos a app Gmail oficial.
        val gmailLaunch =
            packageManager.getLaunchIntentForPackage("com.google.android.gm")

        if (gmailLaunch != null) {
            startActivity(gmailLaunch)
        } else {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://mail.google.com/")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Não foi possível abrir o Gmail.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onUnreadChanged(accountId: String, count: Int) {
        runOnUiThread {
            refreshButton(accountId)
        }
    }

    private fun refreshAllButtons() {
        DEFAULT_ACCOUNTS.forEach {
            refreshButton(it.id)
        }
    }

    private fun refreshButton(accountId: String) {
        val account =
            DEFAULT_ACCOUNTS.firstOrNull { it.id == accountId }
                ?: return

        val button = accountButtons[accountId] ?: return
        val count = app.getUnread(accountId)

        val parts = account.label.split(" ", limit = 2)
        val first = parts.getOrElse(0) { account.label }
        val second = parts.getOrElse(1) { "" }

        button.text =
            if (count > 0 && accountId != "rita_gmail") {
                "$first\n$second ($count)"
            } else {
                "$first\n$second"
            }

        button.background =
            createButtonBackground(
                accountId,
                accountId == app.selectedAccountId
            )
    }

    private fun updateSelectedButton(selectedId: String) {
        accountButtons.keys.forEach { id ->
            val button = accountButtons[id] ?: return@forEach

            button.background =
                createButtonBackground(
                    id,
                    id == selectedId
                )
        }
    }

    override fun onResume() {
        super.onResume()
        app.setUiVisible(true)

        currentSession?.let {
            it.setActive(true)
            it.setFocused(true)
        }
    }

    override fun onPause() {
        app.setUiVisible(false)
        currentSession?.setFocused(false)
        super.onPause()
    }

    override fun onDestroy() {
        app.removeUnreadListener(this)
        super.onDestroy()
    }

    private fun createButtonBackground(
        id: String,
        selected: Boolean
    ): GradientDrawable {

        val base = buttonColor(id)
        val border = buttonBorderColor(id)

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE

            setColor(
                if (selected) darken(base, 0.95f)
                else base
            )

            cornerRadius = dp(13).toFloat()

            setStroke(
                if (selected) dp(3) else dp(1),
                if (selected) border
                else Color.argb(45, 80, 80, 80)
            )
        }
    }

    private fun buttonColor(id: String): Int {
        return when (id) {
            "rita_sapo" ->
                Color.parseColor("#E6D9F7")

            "rita_gmail" ->
                Color.parseColor("#D8EBF8")

            "mae_sapo" ->
                Color.parseColor("#F5DCE7")

            "pai_sapo" ->
                Color.parseColor("#F7D7D7")

            "daniela_sapo" ->
                Color.parseColor("#F8E1CD")

            "leonor_sapo" ->
                Color.parseColor("#DCEFD8")

            else ->
                Color.parseColor("#EEEEEE")
        }
    }

    private fun buttonBorderColor(id: String): Int {
        return when (id) {
            "rita_sapo" ->
                Color.parseColor("#9270C5")

            "rita_gmail" ->
                Color.parseColor("#669BC2")

            "mae_sapo" ->
                Color.parseColor("#C77B9E")

            "pai_sapo" ->
                Color.parseColor("#E46F78")

            "daniela_sapo" ->
                Color.parseColor("#D89A67")

            "leonor_sapo" ->
                Color.parseColor("#7EAE72")

            else ->
                Color.parseColor("#777777")
        }
    }

    private fun darken(color: Int, factor: Float): Int {
        val r =
            (Color.red(color) * factor)
                .toInt()
                .coerceIn(0, 255)

        val g =
            (Color.green(color) * factor)
                .toInt()
                .coerceIn(0, 255)

        val b =
            (Color.blue(color) * factor)
                .toInt()
                .coerceIn(0, 255)

        return Color.rgb(r, g, b)
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density)
            .toInt()
}
