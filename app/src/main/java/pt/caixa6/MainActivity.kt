package pt.caixa6

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {
    private lateinit var app: Caixa6App
    private lateinit var geckoView: GeckoView
    private lateinit var tabRow: LinearLayout
    private var currentSession: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as Caixa6App
        app.ensureSessions()

        requestNotificationPermission()
        startService(Intent(this, KeepAliveService::class.java))

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
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(geckoView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        DEFAULT_ACCOUNTS.forEach { account ->
            val b = Button(this).apply {
                text = account.label
                isAllCaps = false
                setOnClickListener { showAccount(account.id) }
            }
            tabRow.addView(b)
        }

        showAccount(app.selectedAccountId)
    }

    private fun showAccount(id: String) {
        val next = app.sessions[id] ?: return
        if (currentSession === next) return

        currentSession?.let {
            it.setFocused(false)
            // Continua ativa para que o SAPO a trate como webmail aberto.
            it.setActive(true)
        }

        if (geckoView.session != null) geckoView.releaseSession()
        geckoView.setSession(next)
        next.setActive(true)
        next.setFocused(true)

        currentSession = next
        app.selectedAccountId = id
    }

    override fun onPause() {
        super.onPause()
        // Não desativar sessões: o serviço mantém o processo vivo.
        currentSession?.setFocused(false)
    }

    override fun onResume() {
        super.onResume()
        currentSession?.setActive(true)
        currentSession?.setFocused(true)
    }

 override fun onBackPressed() {
    super.onBackPressed()
}

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
