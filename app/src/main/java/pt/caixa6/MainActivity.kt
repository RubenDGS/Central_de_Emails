package pt.caixa6

import android.Manifest
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
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity :
    ComponentActivity(),
    Caixa6App.UnreadListener {

    private lateinit var app: Caixa6App
    private lateinit var geckoView: GeckoView
    private lateinit var root: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var contentHolder: LinearLayout
    private lateinit var gmailPanel: GmailPanel

    private val accountButtons =
        linkedMapOf<String, Button>()

    private var currentSession:
        GeckoSession? = null

    private val authorizationLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .StartIntentSenderForResult()
        ) { result ->

            val data =
                result.data

            if (data == null) {
                gmailPanel
                    .showAuthorizationError(
                        "A autorização do Gmail não devolveu resposta."
                    )
                return@registerForActivityResult
            }

            try {
                val authorization =
                    Identity
                        .getAuthorizationClient(
                            this
                        )
                        .getAuthorizationResultFromIntent(
                            data
                        )

                val token =
                    authorization
                        .accessToken

                if (
                    token.isNullOrBlank()
                ) {
                    gmailPanel
                        .showAuthorizationError(
                            "A Google autorizou a conta, mas não devolveu token de acesso."
                        )
                } else {
                    gmailPanel
                        .setAuthorizedToken(
                            token
                        )
                }

            } catch (
                e: ApiException
            ) {
                gmailPanel
                    .showAuthorizationError(
                        "Autorização Gmail falhou (código ${e.statusCode})."
                    )

            } catch (
                e: Exception
            ) {
                gmailPanel
                    .showAuthorizationError(
                        "Autorização Gmail falhou: " +
                            (
                                e.message
                                    ?: "erro desconhecido"
                                )
                    )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        app =
            application
                as Caixa6App

        app.addUnreadListener(
            this
        )

        requestNotificationPermission()

        window.statusBarColor =
            Color.TRANSPARENT

        window.navigationBarColor =
            Color.WHITE

        @Suppress("DEPRECATION")
        run {
            window.decorView
                .systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        root =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundResource(
                    R.drawable.background_pastel
                )

                setOnApplyWindowInsetsListener {
                        view,
                        insets ->

                    if (
                        Build.VERSION.SDK_INT >=
                        30
                    ) {
                        val bars =
                            insets.getInsets(
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

        tabRow =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.HORIZONTAL

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

        DEFAULT_ACCOUNTS
            .forEach {
                    account ->

                val button =
                    Button(
                        this
                    ).apply {

                        isAllCaps =
                            false

                        textSize =
                            9.8f

                        gravity =
                            Gravity.CENTER

                        maxLines =
                            2

                        includeFontPadding =
                            false

                        minWidth =
                            0

                        minimumWidth =
                            0

                        minHeight =
                            0

                        minimumHeight =
                            0

                        setTextColor(
                            Color.parseColor(
                                "#333333"
                            )
                        )

                        setPadding(
                            dp(1),
                            dp(7),
                            dp(1),
                            dp(7)
                        )

                        setOnClickListener {

                            if (
                                account.id ==
                                "rita_gmail"
                            ) {
                                openGmail()
                            } else {
                                openSapo(
                                    account
                                )
                            }
                        }
                    }

                accountButtons[
                    account.id
                ] =
                    button

                val params =
                    LinearLayout
                        .LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                        .apply {
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

        contentHolder =
            LinearLayout(
                this
            ).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        geckoView =
            GeckoView(
                this
            ).apply {
                visibility =
                    View.GONE
            }

        gmailPanel =
            GmailPanel(
                this,
                app
            ) {
                requestGmailAuthorization()
            }.apply {
                visibility =
                    View.GONE
            }

        contentHolder.addView(
            geckoView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        contentHolder.addView(
            gmailPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            tabRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            contentHolder,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(
            root
        )

        refreshAllButtons()

        intent
            .getStringExtra(
                "open_account"
            )
            ?.let {
                openAccountById(
                    it
                )
            }
    }

    override fun onNewIntent(
        intent: Intent
    ) {
        super.onNewIntent(
            intent
        )

        intent
            .getStringExtra(
                "open_account"
            )
            ?.let {
                openAccountById(
                    it
                )
            }
    }

    private fun openAccountById(
        accountId: String
    ) {
        val account =
            DEFAULT_ACCOUNTS
                .firstOrNull {
                    it.id ==
                        accountId
                }
                ?: return

        if (
            account.id ==
            "rita_gmail"
        ) {
            openGmail()
        } else {
            openSapo(
                account
            )
        }
    }

    private fun openSapo(
        account: Account
    ) {
        gmailPanel.visibility =
            View.GONE

        geckoView.visibility =
            View.VISIBLE

        val session =
            app.getOrCreateSession(
                account
            )

        if (
            currentSession !==
            session
        ) {
            currentSession
                ?.let {
                    it.setFocused(
                        false
                    )
                    it.setActive(
                        false
                    )
                }

            if (
                geckoView.session !=
                null
            ) {
                geckoView
                    .releaseSession()
            }

            geckoView
                .setSession(
                    session
                )

            currentSession =
                session
        }

        app.selectAccount(
            account.id
        )

        session.setActive(
            true
        )

        session.setFocused(
            true
        )

        session.loadUri(
            account.url
        )

        updateSelectedButton(
            account.id
        )
    }

    private fun openGmail() {
        currentSession
            ?.let {
                it.setFocused(
                    false
                )

                it.setActive(
                    false
                )
            }

        geckoView.visibility =
            View.GONE

        gmailPanel.visibility =
            View.VISIBLE

        app.selectAccount(
            "rita_gmail"
        )

        updateSelectedButton(
            "rita_gmail"
        )

        gmailPanel.start()
    }

    private fun requestGmailAuthorization() {
        val request =
            AuthorizationRequest
                .builder()
                .setRequestedScopes(
                    listOf(
                        Scope(
                            Caixa6App
                                .GMAIL_MODIFY
                        )
                    )
                )
                .build()

        Identity
            .getAuthorizationClient(
                this
            )
            .authorize(
                request
            )
            .addOnSuccessListener {
                    result ->

                if (
                    result.hasResolution()
                ) {
                    val pendingIntent =
                        result.pendingIntent

                    if (
                        pendingIntent ==
                        null
                    ) {
                        gmailPanel
                            .showAuthorizationError(
                                "A Google não devolveu a janela de autorização."
                            )

                        return@addOnSuccessListener
                    }

                    try {
                        authorizationLauncher
                            .launch(
                                IntentSenderRequest
                                    .Builder(
                                        pendingIntent
                                            .intentSender
                                    )
                                    .build()
                            )

                    } catch (
                        e: Exception
                    ) {
                        gmailPanel
                            .showAuthorizationError(
                                "Não foi possível abrir a autorização Google: " +
                                    (
                                        e.message
                                            ?: "erro desconhecido"
                                        )
                            )
                    }

                } else {
                    val token =
                        result
                            .accessToken

                    if (
                        token.isNullOrBlank()
                    ) {
                        gmailPanel
                            .showAuthorizationError(
                                "A Google não devolveu token de acesso."
                            )

                    } else {
                        gmailPanel
                            .setAuthorizedToken(
                                token
                            )
                    }
                }
            }
            .addOnFailureListener {
                    error ->

                gmailPanel
                    .showAuthorizationError(
                        "Não foi possível autorizar o Gmail: " +
                            (
                                error.message
                                    ?: "erro desconhecido"
                                )
                    )
            }
    }

    override fun onUnreadChanged(
        accountId: String,
        count: Int
    ) {
        runOnUiThread {
            refreshButton(
                accountId
            )
        }
    }

    private fun refreshAllButtons() {
        DEFAULT_ACCOUNTS
            .forEach {
                refreshButton(
                    it.id
                )
            }
    }

    private fun refreshButton(
        accountId: String
    ) {
        val account =
            DEFAULT_ACCOUNTS
                .firstOrNull {
                    it.id ==
                        accountId
                }
                ?: return

        val button =
            accountButtons[
                accountId
            ] ?: return

        val count =
            app.getUnread(
                accountId
            )

        val parts =
            account.label
                .split(
                    " ",
                    limit = 2
                )

        val first =
            parts.getOrElse(
                0
            ) {
                account.label
            }

        val second =
            parts.getOrElse(
                1
            ) {
                ""
            }

        button.text =
            if (
                count > 0
            ) {
                "$first\n$second ($count)"
            } else {
                "$first\n$second"
            }

        button.background =
            createButtonBackground(
                accountId,
                accountId ==
                    app.selectedAccountId
            )
    }

    private fun updateSelectedButton(
        selectedId: String
    ) {
        accountButtons.keys
            .forEach {
                    id ->

                val button =
                    accountButtons[
                        id
                    ] ?: return@forEach

                button.background =
                    createButtonBackground(
                        id,
                        id ==
                            selectedId
                    )
            }
    }

    override fun onResume() {
        super.onResume()

        app.setUiVisible(
            true
        )

        refreshAllButtons()

        if (
            app.selectedAccountId !=
            "rita_gmail"
        ) {
            currentSession
                ?.let {
                    it.setActive(
                        true
                    )

                    it.setFocused(
                        true
                    )
                }
        }
    }

    override fun onPause() {
        app.setUiVisible(
            false
        )

        currentSession
            ?.setFocused(
                false
            )

        super.onPause()
    }

    override fun onDestroy() {
        app.removeUnreadListener(
            this
        )

        super.onDestroy()
    }

    private fun createButtonBackground(
        id: String,
        selected: Boolean
    ): GradientDrawable {

        val base =
            buttonColor(
                id
            )

        val border =
            buttonBorderColor(
                id
            )

        return GradientDrawable()
            .apply {

                shape =
                    GradientDrawable
                        .RECTANGLE

                setColor(
                    if (
                        selected
                    ) {
                        darken(
                            base,
                            0.95f
                        )
                    } else {
                        base
                    }
                )

                cornerRadius =
                    dp(13)
                        .toFloat()

                setStroke(
                    if (
                        selected
                    ) {
                        dp(3)
                    } else {
                        dp(1)
                    },

                    if (
                        selected
                    ) {
                        border
                    } else {
                        Color.argb(
                            45,
                            80,
                            80,
                            80
                        )
                    }
                )
            }
    }

    private fun buttonColor(
        id: String
    ): Int =
        when (id) {
            "rita_sapo" ->
                Color.parseColor(
                    "#E6D9F7"
                )

            "rita_gmail" ->
                Color.parseColor(
                    "#D8EBF8"
                )

            "mae_sapo" ->
                Color.parseColor(
                    "#F5DCE7"
                )

            "pai_sapo" ->
                Color.parseColor(
                    "#F7D7D7"
                )

            "daniela_sapo" ->
                Color.parseColor(
                    "#F8E1CD"
                )

            "leonor_sapo" ->
                Color.parseColor(
                    "#DCEFD8"
                )

            else ->
                Color.parseColor(
                    "#EEEEEE"
                )
        }

    private fun buttonBorderColor(
        id: String
    ): Int =
        when (id) {
            "rita_sapo" ->
                Color.parseColor(
                    "#9270C5"
                )

            "rita_gmail" ->
                Color.parseColor(
                    "#669BC2"
                )

            "mae_sapo" ->
                Color.parseColor(
                    "#C77B9E"
                )

            "pai_sapo" ->
                Color.parseColor(
                    "#E46F78"
                )

            "daniela_sapo" ->
                Color.parseColor(
                    "#E7A15D"
                )

            "leonor_sapo" ->
                Color.parseColor(
                    "#7EAE72"
                )

            else ->
                Color.parseColor(
                    "#777777"
                )
        }

    private fun darken(
        color: Int,
        factor: Float
    ): Int {
        val r =
            (
                Color.red(
                    color
                ) *
                    factor
                )
                .toInt()
                .coerceIn(
                    0,
                    255
                )

        val g =
            (
                Color.green(
                    color
                ) *
                    factor
                )
                .toInt()
                .coerceIn(
                    0,
                    255
                )

        val b =
            (
                Color.blue(
                    color
                ) *
                    factor
                )
                .toInt()
                .coerceIn(
                    0,
                    255
                )

        return Color.rgb(
            r,
            g,
            b
        )
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >=
            33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) !=
            PackageManager.PERMISSION_GRANTED
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
    ): Int =
        (
            value *
                resources
                    .displayMetrics
                    .density
            )
            .toInt()
}
