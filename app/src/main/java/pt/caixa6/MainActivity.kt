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

class MainActivity : Activity() {

    private lateinit var app: Caixa6App
    private lateinit var geckoView: GeckoView
    private lateinit var root: LinearLayout
    private lateinit var tabRow: LinearLayout
    private val accountButtons = linkedMapOf<String, Button>()

    private var currentSession: GeckoSession? = null
    private var currentAccountId: String? = null
    private var serviceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = application as Caixa6App
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
                    view.setPadding(0, bars.top, 0, bars.bottom)
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
                text = account.label.replace(" ", "\n")
                isAllCaps = false
                textSize = 10.5f
                gravity = Gravity.CENTER
                maxLines = 2
                includeFontPadding = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setTextColor(Color.parseColor("#333333"))
                setPadding(dp(2), dp(7), dp(2), dp(7))
                background = createButtonBackground(account.id, false)

                setOnClickListener {
                    if (account.id == "rita_gmail") {
                        openGmail()
                    } else {
                        openAccount(account)
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
    }

    private fun openAccount(account: Account) {
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

        session.setActive(true)
        session.setFocused(true)

        if (!app.loadedAccounts.contains(account.id)) {
            session.loadUri(account.url)
            app.loadedAccounts.add(account.id)
        } else if (currentAccountId != account.id) {
            session.reload()
        }

        currentAccountId = account.id
        app.selectedAccountId = account.id
        updateSelectedButton(account.id)
        startNotificationService()
    }

    private fun openGmail() {
        updateSelectedButton("rita_gmail")

        val gmailLaunch =
            packageManager.getLaunchIntentForPackage("com.google.android.gm")

        if (gmailLaunch != null) {
            gmailLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private fun updateSelectedButton(selectedId: String) {
        accountButtons.forEach { (id, button) ->
            button.background = createButtonBackground(
                id,
                id == selectedId
            )
        }
    }

    private fun startNotificationService() {
        if (serviceStarted) return

        val intent = Intent(this, KeepAliveService::class.java)

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        serviceStarted = true
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

    private fun createButtonBackground(
        id: String,
        selected: Boolean
    ): GradientDrawable {
        val base = buttonColor(id)
        val border = buttonBorderColor(id)

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (selected) darken(base, 0.93f) else base)
            cornerRadius = dp(13).toFloat()
            setStroke(
                if (selected) dp(3) else dp(1),
                if (selected) border else Color.argb(45, 80, 80, 80)
            )
        }
    }

    private fun buttonColor(id: String): Int {
        return when (id) {
            "rita_sapo" -> Color.parseColor("#E6D9F7")
            "rita_gmail" -> Color.parseColor("#D8EBF8")
            "mae_sapo" -> Color.parseColor("#F5DCE7")
            "pai_sapo" -> Color.parseColor("#F6D5D5")
            "daniela_sapo" -> Color.parseColor("#F8E1CD")
            "leonor_sapo" -> Color.parseColor("#DCEFD8")
            else -> Color.parseColor("#EEEEEE")
        }
    }

    private fun buttonBorderColor(id: String): Int {
        return when (id) {
            "rita_sapo" -> Color.parseColor("#9270C5")
            "rita_gmail" -> Color.parseColor("#669BC2")
            "mae_sapo" -> Color.parseColor("#C77B9E")
            "pai_sapo" -> Color.parseColor("#C97878")
            "daniela_sapo" -> Color.parseColor("#C99461")
            "leonor_sapo" -> Color.parseColor("#7EAE72")
            else -> Color.parseColor("#777777")
        }
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
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
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
