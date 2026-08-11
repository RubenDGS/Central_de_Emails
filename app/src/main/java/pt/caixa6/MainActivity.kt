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

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        root = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setBackgroundResource(
                R.drawable.background_pastel
            )

            setOnApplyWindowInsetsListener { view, insets ->

                if (Build.VERSION.SDK_INT >= 30) {

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

        val scroll =
            HorizontalScrollView(this).apply {

                isHorizontalScrollBarEnabled = false

                setBackgroundColor(
                    Color.argb(
                        120,
                        255,
                        255,
                        255
                    )
                )
            }

        val tabRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                setPadding(
                    dp(6),
                    dp(6),
                    dp(6),
                    dp(6)
                )
            }

        DEFAULT_ACCOUNTS.forEach { account ->

            val button =
                Button(this).apply {

                    text = account.label

                    isAllCaps = false

                    textSize = 14f

                    minHeight = 0
                    minimumHeight = 0

                    setPadding(
                        dp(14),
                        dp(7),
                        dp(14),
                        dp(7)
                    )

                    setOnClickListener {
                        openAccount(account)
                    }
                }

            val params =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {

                    setMargins(
                        dp(3),
                        0,
                        dp(3),
                        0
                    )
                }

            tabRow.addView(
                button,
                params
            )
        }

        scroll.addView(tabRow)

        geckoView =
            GeckoView(this).apply {

                /*
                 * No início mostramos apenas
                 * o fundo pastel.
                 */
                visibility = View.GONE
            }

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
    }

    private fun openAccount(
        account: Account
    ) {

        /*
         * Ao escolher uma conta,
         * mostramos o browser.
         */
        geckoView.visibility =
            View.VISIBLE

        val session =
            app.getOrCreateSession(
                account
            )

        if (currentSession !== session) {

            currentSession?.let {

                it.setFocused(false)

                it.setActive(false)
            }

            if (
                geckoView.session != null
            ) {

                geckoView.releaseSession()
            }

            geckoView.setSession(
                session
            )

            currentSession =
                session
        }

        session.setActive(true)

        session.setFocused(true)

        if (
            !app.loadedAccounts.contains(
                account.id
            )
        ) {

            session.loadUri(
                account.url
            )

            app.loadedAccounts.add(
                account.id
            )
        }

        app.selectedAccountId =
            account.id
    }

    override fun onPause() {
        super.onPause()

        currentSession?.setFocused(
            false
        )
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

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }
}
