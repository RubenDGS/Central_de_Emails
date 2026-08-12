package pt.caixa6

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentSender
import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GmailActivity : Activity() {

    companion object {
        private const val REQUEST_AUTHORIZE = 8401
        private const val GMAIL_READONLY =
            "https://www.googleapis.com/auth/gmail.readonly"
    }

    private lateinit var app: Caixa6App
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var progress: ProgressBar

    private var accessToken: String? = null
    private val messageIds = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = application as Caixa6App
        app.selectAccount("rita_gmail")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(20, 20, 20, 20)
        }

        val title = TextView(this).apply {
            text = "Rita Gmail"
            textSize = 22f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(4, 4, 4, 14)
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = Button(this).apply {
            text = "Voltar"
            isAllCaps = false
            setOnClickListener { finish() }
        }

        val refresh = Button(this).apply {
            text = "Atualizar"
            isAllCaps = false
            setOnClickListener { authorizeAndLoad() }
        }

        actions.addView(
            back,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        actions.addView(
            refresh,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        status = TextView(this).apply {
            text = "A ligar ao Gmail…"
            textSize = 15f
            setPadding(4, 12, 4, 12)
        }

        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }

        list = ListView(this)

        root.addView(title)
        root.addView(actions)
        root.addView(status)
        root.addView(progress)

        root.addView(
            list,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        list.setOnItemClickListener { _, _, position, _ ->
            val id = messageIds.getOrNull(position) ?: return@setOnItemClickListener
            loadMessage(id)
        }

        authorizeAndLoad()
    }

    private fun authorizeAndLoad() {
        progress.visibility = View.VISIBLE
        status.text = "A autorizar o acesso ao Gmail…"

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope(GMAIL_READONLY)
                )
            )
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    try {
                        startIntentSenderForResult(
                            result.pendingIntent!!.intentSender,
                            REQUEST_AUTHORIZE,
                            null,
                            0,
                            0,
                            0
                        )
                    } catch (e: IntentSender.SendIntentException) {
                        showError("Não foi possível abrir a autorização Google.")
                    }
                } else {
                    accessToken = result.accessToken
                    loadInbox()
                }
            }
            .addOnFailureListener {
                showError(
                    "Não foi possível autorizar o Gmail. " +
                        "Confirma a configuração OAuth no Google Cloud."
                )
            }
    }

    @Deprecated("Compatibilidade com o fluxo Google Identity Services")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_AUTHORIZE) return

        try {
            val result =
                Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(data)

            accessToken = result.accessToken
            loadInbox()

        } catch (_: ApiException) {
            showError("A autorização do Gmail foi cancelada ou falhou.")
        }
    }

    private fun loadInbox() {
        val token = accessToken ?: run {
            showError("O Gmail não devolveu um token de acesso.")
            return
        }

        progress.visibility = View.VISIBLE
        status.text = "A carregar a Caixa de Entrada…"

        Thread {
            try {
                val inboxLabel = apiGet(
                    token,
                    "https://gmail.googleapis.com/gmail/v1/users/me/labels/INBOX"
                )

                val unread =
                    JSONObject(inboxLabel)
                        .optInt("messagesUnread", 0)

                app.setGmailUnread(unread)

                val listJson = apiGet(
                    token,
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages" +
                        "?labelIds=INBOX&maxResults=30"
                )

                val root = JSONObject(listJson)
                val arr = root.optJSONArray("messages")

                val ids = mutableListOf<String>()
                val lines = mutableListOf<String>()

                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val id =
                            arr.getJSONObject(i)
                                .getString("id")

                        val metadata = apiGet(
                            token,
                            "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id" +
                                "?format=metadata" +
                                "&metadataHeaders=Subject" +
                                "&metadataHeaders=From" +
                                "&metadataHeaders=Date"
                        )

                        val item = JSONObject(metadata)
                        val headers =
                            item.getJSONObject("payload")
                                .optJSONArray("headers")

                        var subject = "(sem assunto)"
                        var from = ""

                        if (headers != null) {
                            for (h in 0 until headers.length()) {
                                val header = headers.getJSONObject(h)

                                when (
                                    header.optString("name")
                                        .lowercase()
                                ) {
                                    "subject" ->
                                        subject =
                                            header.optString("value", "(sem assunto)")

                                    "from" ->
                                        from =
                                            header.optString("value", "")
                                }
                            }
                        }

                        ids.add(id)
                        lines.add(
                            if (from.isBlank()) {
                                subject
                            } else {
                                "$subject\n$from"
                            }
                        )
                    }
                }

                runOnUiThread {
                    progress.visibility = View.GONE

                    messageIds.clear()
                    messageIds.addAll(ids)

                    status.text =
                        if (unread == 1) {
                            "Caixa de Entrada — 1 email por ler"
                        } else {
                            "Caixa de Entrada — $unread emails por ler"
                        }

                    list.adapter =
                        ArrayAdapter(
                            this,
                            android.R.layout.simple_list_item_1,
                            lines
                        )
                }

            } catch (e: Exception) {
                runOnUiThread {
                    showError(
                        "Não foi possível carregar o Gmail: " +
                            (e.message ?: "erro desconhecido")
                    )
                }
            }
        }.start()
    }

    private fun loadMessage(messageId: String) {
        val token = accessToken ?: return

        progress.visibility = View.VISIBLE
        status.text = "A abrir email…"

        Thread {
            try {
                val json = apiGet(
                    token,
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages/$messageId" +
                        "?format=full"
                )

                val message = JSONObject(json)
                val payload = message.getJSONObject("payload")
                val headers = payload.optJSONArray("headers")

                var subject = "(sem assunto)"
                var from = ""

                if (headers != null) {
                    for (i in 0 until headers.length()) {
                        val header = headers.getJSONObject(i)

                        when (
                            header.optString("name")
                                .lowercase()
                        ) {
                            "subject" ->
                                subject =
                                    header.optString("value", "(sem assunto)")

                            "from" ->
                                from =
                                    header.optString("value", "")
                        }
                    }
                }

                val body =
                    extractBody(payload)
                        .ifBlank {
                            message.optString(
                                "snippet",
                                "Sem conteúdo de texto disponível."
                            )
                        }

                runOnUiThread {
                    progress.visibility = View.GONE
                    showMessageDialog(subject, from, body)
                }

            } catch (e: Exception) {
                runOnUiThread {
                    showError(
                        "Não foi possível abrir o email: " +
                            (e.message ?: "erro desconhecido")
                    )
                }
            }
        }.start()
    }

    private fun extractBody(part: JSONObject): String {
        val mime = part.optString("mimeType")
        val body = part.optJSONObject("body")
        val data = body?.optString("data", "") ?: ""

        if (
            data.isNotBlank() &&
            (
                mime.equals("text/plain", true) ||
                mime.equals("text/html", true)
            )
        ) {
            val decoded =
                String(
                    Base64.decode(
                        data,
                        Base64.URL_SAFE or
                            Base64.NO_WRAP or
                            Base64.NO_PADDING
                    ),
                    Charsets.UTF_8
                )

            return if (mime.equals("text/html", true)) {
                Html.fromHtml(
                    decoded,
                    Html.FROM_HTML_MODE_LEGACY
                ).toString()
            } else {
                decoded
            }
        }

        val parts = part.optJSONArray("parts")

        if (parts != null) {
            var htmlFallback = ""

            for (i in 0 until parts.length()) {
                val child = parts.getJSONObject(i)
                val childMime =
                    child.optString("mimeType")

                val result = extractBody(child)

                if (
                    result.isNotBlank() &&
                    childMime.equals("text/plain", true)
                ) {
                    return result
                }

                if (result.isNotBlank() && htmlFallback.isBlank()) {
                    htmlFallback = result
                }
            }

            return htmlFallback
        }

        return ""
    }

    private fun showMessageDialog(
        subject: String,
        from: String,
        body: String
    ) {
        val text = TextView(this).apply {
            setPadding(36, 20, 36, 20)
            textSize = 16f
            setTextIsSelectable(true)
            text =
                buildString {
                    append(subject)
                    append("\n\n")
                    if (from.isNotBlank()) {
                        append(from)
                        append("\n\n")
                    }
                    append(body)
                }
        }

        AlertDialog.Builder(this)
            .setTitle("Rita Gmail")
            .setView(text)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun apiGet(
        token: String,
        url: String
    ): String {
        val connection =
            (URL(url).openConnection() as HttpURLConnection)

        connection.requestMethod = "GET"
        connection.setRequestProperty(
            "Authorization",
            "Bearer $token"
        )
        connection.setRequestProperty(
            "Accept",
            "application/json"
        )

        val code = connection.responseCode

        val stream =
            if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

        val result =
            stream.bufferedReader()
                .use { it.readText() }

        connection.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException(
                "Google API devolveu HTTP $code"
            )
        }

        return result
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        status.text = message
    }
}
