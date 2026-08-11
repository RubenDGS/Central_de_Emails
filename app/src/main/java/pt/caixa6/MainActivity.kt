package pt.caixa6

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {

    private lateinit var app: Caixa6App
    private lateinit var geckoView: GeckoView
    private lateinit var root: LinearLayout
    private lateinit var tabRow: LinearLayout

    private var currentSession: GeckoSession? = null
    private var serviceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = application as Caixa6App

        requestNotificationPermission()

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

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

        /*
         * Barra das 6 contas.
         * Sem scroll: todos os botões ficam visíveis.
         */
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL

            setPadding(
                dp(3),
                dp(5),
                dp(3),
                dp(5)
            )

            setBackgroundColor(
                Color.argb(
                    80,
                    255,
                    255,
                    255
                )
            )
        }

        DEFAULT_ACCOUNTS.forEach { account ->

            val button = Button(this).apply {

                /*
                 * Rita
                 * Sapo
                 */
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

                setTextColor(
                    Color.parseColor("#333333")
                )

                setPadding(
                    dp(2),
                    dp(7),
                    dp(2),
                    dp(7)
                )

                background =
                    createButtonBackground(
                        buttonColor(account.id)
                    )

                setOnClickListener {
                    openAccount(account)
                }
            }

            val params =
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {

                    setMargins(
                        dp(1),
                        0,
                        dp(1),
                        0
                    )
                }

            tabRow.addView(
                button,
                params
            )
        }

        geckoView = GeckoView(this).apply {

            /*
             * Antes de escolher uma conta,
             * vê-se apenas o fundo pastel.
             */
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

    private fun openAccount(
        account: Account
    ) {

        geckoView.visibility = View.VISIBLE

        val session =
            app.getOrCreateSession(account)

        if (currentSession !== session) {

            /*
             * A sessão anterior deixa de ter foco,
             * mas continua ATIVA para poder receber
             * notificações.
             */
            currentSession?.let {

                it.setFocused(false)
                it.setActive(true)
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

        /*
         * Arranca o serviço apenas depois
         * de o utilizador abrir uma conta.
         */
        startNotificationService()
    }

    private fun startNotificationService() {

        if (serviceStarted) {
            return
        }

        val intent =
            Intent(
                this,
                KeepAliveService::class.java
            )

        if (Build.VERSION.SDK_INT >= 26) {

            startForegroundService(intent)

        } else {

            startService(intent)
        }

        serviceStarted = true
    }

    override fun onPause() {
        super.onPause()

        /*
         * Retira apenas o foco.
         * Não adormece a sessão.
         */
        currentSession?.setFocused(false)

        app.keepSessionsActive()
    }

    override fun onResume() {
        super.onResume()

        currentSession?.let {

            it.setActive(true)
            it.setFocused(true)
        }
    }

    private fun createButtonBackground(
        color: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.RECTANGLE

            setColor(color)

            cornerRadius =
                dp(13).toFloat()

            setStroke(
                dp(1),
                Color.argb(
                    50,
                    80,
                    80,
                    80
                )
            )
        }
    }

    private fun buttonColor(
        id: String
    ): Int {

        return when (id) {

            /*
             * Cores escolhidas exatamente
             * pela Rita.
             */

            "rita_sapo" ->
                Color.parseColor("#E6D9F7")

            "pai_sapo" ->
                Color.parseColor("#F6D5D5")

            "leonor_sapo" ->
                Color.parseColor("#DCEFD8")

            "daniela_sapo" ->
                Color.parseColor("#F8E1CD")

            "mae_sapo" ->
                Color.parseColor("#F5DCE7")

            "rita_gmail" ->
                Color.parseColor("#D8EBF8")

            else ->
                Color.parseColor("#EEEEEE")
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

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }
}
