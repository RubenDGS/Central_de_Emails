
package pt.caixa6

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GmailActivity : ComponentActivity() {

    companion object {
        private const val GMAIL_MODIFY =
            "https://www.googleapis.com/auth/gmail.modify"
    }

    data class GmailRow(
        val id: String,
        val threadId: String,
        val subject: String,
        val from: String,
        val messageIdHeader: String,
        val unread: Boolean
    )

    private lateinit var app: Caixa6App
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var progress: ProgressBar

    private var accessToken: String? = null
    private val rows = mutableListOf<GmailRow>()

    /*
     * Fluxo recomendado atualmente pelo Google:
     * PendingIntent -> ActivityResultLauncher -> getAuthorizationResultFromIntent().
     */
    private val authorizationLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { activityResult ->

            val data = activityResult.data

            if (data == null) {
                showError(
                    "A autorização do Gmail não devolveu resposta."
                )
                return@registerForActivityResult
            }

            try {
                val result =
                    Identity.getAuthorizationClient(this)
                        .getAuthorizationResultFromIntent(data)

                val token = result.accessToken

                if (token.isNullOrBlank()) {
                    showError(
                        "A Google autorizou a conta, mas não devolveu token de acesso."
                    )
                    return@registerForActivityResult
                }

                accessToken = token
                loadInbox()

            } catch (e: ApiException) {
                showError(
                    "Autorização Gmail falhou " +
                        "(código ${e.statusCode})."
                )
            } catch (e: Exception) {
                showError(
                    "Autorização Gmail falhou: " +
                        (e.message ?: "erro desconhecido")
                )
            }
        }

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

        val compose = Button(this).apply {
            text = "Escrever"
            isAllCaps = false
            setOnClickListener { showComposeDialog() }
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
            compose,
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
            val row = rows.getOrNull(position)
                ?: return@setOnItemClickListener

            loadMessage(row)
        }

        authorizeAndLoad()
    }

    private fun authorizeAndLoad() {
        progress.visibility = View.VISIBLE
        status.text = "A autorizar o acesso ao Gmail…"

        val request =
            AuthorizationRequest.builder()
                .setRequestedScopes(
                    listOf(
                        Scope(GMAIL_MODIFY)
                    )
                )
                .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->

                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent

                    if (pendingIntent == null) {
                        showError(
                            "A Google não devolveu a janela de autorização."
                        )
                        return@addOnSuccessListener
                    }

                    try {
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(
                                pendingIntent.intentSender
                            ).build()
                        )
                    } catch (e: Exception) {
                        showError(
                            "Não foi possível abrir a autorização Google: " +
                                (e.message ?: "erro desconhecido")
                        )
                    }

                } else {
                    val token = result.accessToken

                    if (token.isNullOrBlank()) {
                        showError(
                            "A Google não devolveu token de acesso."
                        )
                    } else {
                        accessToken = token
                        loadInbox()
                    }
                }
            }
            .addOnFailureListener { error ->
                showError(
                    "Não foi possível autorizar o Gmail: " +
                        (error.message ?: "erro desconhecido")
                )
            }
    }

    private fun loadInbox() {
        val token = accessToken ?: run {
            showError(
                "O Gmail não devolveu um token de acesso."
            )
            return
        }

        progress.visibility = View.VISIBLE
        status.text = "A carregar a Caixa de Entrada…"

        Thread {
            try {
                val inboxLabel = apiRequest(
                    token,
                    "https://gmail.googleapis.com/gmail/v1/users/me/labels/INBOX"
                )

                val unread =
                    JSONObject(inboxLabel)
                        .optInt("messagesUnread", 0)

                app.setGmailUnread(unread)

                val listJson = apiRequest(
                    token,
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages" +
                        "?labelIds=INBOX&maxResults=40"
                )

                val root = JSONObject(listJson)
                val arr = root.optJSONArray("messages")

                val newRows = mutableListOf<GmailRow>()
                val lines = mutableListOf<String>()

                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val id =
                            arr.getJSONObject(i)
                                .getString("id")

                        val metadata = apiRequest(
                            token,
                            "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id" +
                                "?format=metadata" +
                                "&metadataHeaders=Subject" +
                                "&metadataHeaders=From" +
                                "&metadataHeaders=Message-ID"
                        )

                        val item = JSONObject(metadata)
                        val labels = item.optJSONArray("labelIds")

                        var unreadMessage = false

                        if (labels != null) {
                            for (l in 0 until labels.length()) {
                                if (labels.optString(l) == "UNREAD") {
                                    unreadMessage = true
                                    break
                                }
                            }
                        }

                        val headers =
                            item.getJSONObject("payload")
                                .optJSONArray("headers")

                        var subject = "(sem assunto)"
                        var from = ""
                        var messageIdHeader = ""

                        if (headers != null) {
                            for (h in 0 until headers.length()) {
                                val header =
                                    headers.getJSONObject(h)

                                when (
                                    header.optString("name")
                                        .lowercase()
                                ) {
                                    "subject" ->
                                        subject =
                                            header.optString(
                                                "value",
                                                "(sem assunto)"
                                            )

                                    "from" ->
                                        from =
                                            header.optString(
                                                "value",
                                                ""
                                            )

                                    "message-id" ->
                                        messageIdHeader =
                                            header.optString(
                                                "value",
                                                ""
                                            )
                                }
                            }
                        }

                        val row =
                            GmailRow(
                                id = id,
                                threadId =
                                    item.optString(
                                        "threadId",
                                        ""
                                    ),
                                subject = subject,
                                from = from,
                                messageIdHeader = messageIdHeader,
                                unread = unreadMessage
                            )

                        newRows.add(row)

                        lines.add(
                            buildString {
                                if (unreadMessage) {
                                    append("● ")
                                }

                                append(subject)

                                if (from.isNotBlank()) {
                                    append("\n")
                                    append(from)
                                }
                            }
                        )
                    }
                }

                runOnUiThread {
                    progress.visibility = View.GONE

                    rows.clear()
                    rows.addAll(newRows)

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
                    handleApiFailure(token, e)
                }
            }
        }.start()
    }

    private fun loadMessage(row: GmailRow) {
        val token = accessToken ?: return

        progress.visibility = View.VISIBLE
        status.text = "A abrir email…"

        Thread {
            try {
                val json = apiRequest(
                    token,
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages/${row.id}" +
                        "?format=full"
                )

                val message = JSONObject(json)
                val payload = message.getJSONObject("payload")
                val headers = payload.optJSONArray("headers")

                var subject = row.subject
                var from = row.from
                var messageIdHeader = row.messageIdHeader

                if (headers != null) {
                    for (i in 0 until headers.length()) {
                        val header = headers.getJSONObject(i)

                        when (
                            header.optString("name")
                                .lowercase()
                        ) {
                            "subject" ->
                                subject =
                                    header.optString(
                                        "value",
                                        subject
                                    )

                            "from" ->
                                from =
                                    header.optString(
                                        "value",
                                        from
                                    )

                            "message-id" ->
                                messageIdHeader =
                                    header.optString(
                                        "value",
                                        messageIdHeader
                                    )
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

                if (row.unread) {
                    modifyLabels(
                        token,
                        row.id,
                        add = emptyList(),
                        remove = listOf("UNREAD")
                    )
                }

                val finalRow =
                    row.copy(
                        subject = subject,
                        from = from,
                        messageIdHeader = messageIdHeader,
                        unread = false
                    )

                runOnUiThread {
                    progress.visibility = View.GONE
                    showMessageDialog(finalRow, body)
                    loadInbox()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    handleApiFailure(token, e)
                }
            }
        }.start()
    }

    private fun showMessageDialog(
        row: GmailRow,
        body: String
    ) {
        val textView = TextView(this).apply {
            setPadding(36, 20, 36, 20)
            textSize = 16f
            setTextIsSelectable(true)

            text =
                buildString {
                    append(row.subject)
                    append("\n\n")

                    if (row.from.isNotBlank()) {
                        append(row.from)
                        append("\n\n")
                    }

                    append(body)
                }
        }

        AlertDialog.Builder(this)
            .setTitle("Rita Gmail")
            .setView(textView)
            .setPositiveButton("Responder") { _, _ ->
                showReplyDialog(row)
            }
            .setNeutralButton("Não lido") { _, _ ->
                markUnread(row.id)
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showComposeDialog() {
        if (accessToken == null) {
            Toast.makeText(
                this,
                "Primeiro autoriza o Gmail.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 10, 30, 0)
        }

        val to = EditText(this).apply {
            hint = "Para"
        }

        val subject = EditText(this).apply {
            hint = "Assunto"
        }

        val body = EditText(this).apply {
            hint = "Mensagem"
            minLines = 6
            gravity = Gravity.TOP
        }

        container.addView(to)
        container.addView(subject)
        container.addView(body)

        AlertDialog.Builder(this)
            .setTitle("Novo email")
            .setView(container)
            .setPositiveButton("Enviar") { _, _ ->
                sendMail(
                    to.text.toString().trim(),
                    subject.text.toString(),
                    body.text.toString(),
                    threadId = "",
                    inReplyTo = ""
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showReplyDialog(row: GmailRow) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 10, 30, 0)
        }

        val body = EditText(this).apply {
            hint = "Resposta"
            minLines = 6
            gravity = Gravity.TOP
        }

        container.addView(body)

        val subject =
            if (
                row.subject.startsWith(
                    "Re:",
                    ignoreCase = true
                )
            ) {
                row.subject
            } else {
                "Re: ${row.subject}"
            }

        AlertDialog.Builder(this)
            .setTitle("Responder")
            .setView(container)
            .setPositiveButton("Enviar") { _, _ ->
                sendMail(
                    row.from,
                    subject,
                    body.text.toString(),
                    row.threadId,
                    row.messageIdHeader
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun markUnread(messageId: String) {
        val token = accessToken ?: return

        Thread {
            try {
                modifyLabels(
                    token,
                    messageId,
                    add = listOf("UNREAD"),
                    remove = emptyList()
                )

                runOnUiThread {
                    loadInbox()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    handleApiFailure(token, e)
                }
            }
        }.start()
    }

    private fun sendMail(
        to: String,
        subject: String,
        body: String,
        threadId: String,
        inReplyTo: String
    ) {
        val token = accessToken ?: return

        if (to.isBlank()) {
            Toast.makeText(
                this,
                "Indica o destinatário.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        progress.visibility = View.VISIBLE
        status.text = "A enviar email…"

        Thread {
            try {
                val raw =
                    buildString {
                        append("To: $to\r\n")
                        append("Subject: $subject\r\n")

                        if (inReplyTo.isNotBlank()) {
                            append("In-Reply-To: $inReplyTo\r\n")
                            append("References: $inReplyTo\r\n")
                        }

                        append("Content-Type: text/plain; charset=UTF-8\r\n")
                        append("\r\n")
                        append(body)
                    }

                val encoded =
                    Base64.encodeToString(
                        raw.toByteArray(Charsets.UTF_8),
                        Base64.URL_SAFE or
                            Base64.NO_WRAP or
                            Base64.NO_PADDING
                    )

                val json =
                    JSONObject()
                        .put("raw", encoded)

                if (threadId.isNotBlank()) {
                    json.put("threadId", threadId)
                }

                apiRequest(
                    token,
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                    method = "POST",
                    body = json.toString()
                )

                runOnUiThread {
                    progress.visibility = View.GONE

                    Toast.makeText(
                        this,
                        "Email enviado.",
                        Toast.LENGTH_SHORT
                    ).show()

                    loadInbox()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    handleApiFailure(token, e)
                }
            }
        }.start()
    }

    private fun modifyLabels(
        token: String,
        messageId: String,
        add: List<String>,
        remove: List<String>
    ) {
        val json =
            JSONObject()
                .put(
                    "addLabelIds",
                    JSONArray(add)
                )
                .put(
                    "removeLabelIds",
                    JSONArray(remove)
                )

        apiRequest(
            token,
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/$messageId/modify",
            method = "POST",
            body = json.toString()
        )
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
            var fallback = ""

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

                if (
                    result.isNotBlank() &&
                    fallback.isBlank()
                ) {
                    fallback = result
                }
            }

            return fallback
        }

        return ""
    }

    private fun apiRequest(
        token: String,
        url: String,
        method: String = "GET",
        body: String? = null
    ): String {
        val connection =
            URL(url).openConnection() as HttpURLConnection

        connection.requestMethod = method
        connection.setRequestProperty(
            "Authorization",
            "Bearer $token"
        )
        connection.setRequestProperty(
            "Accept",
            "application/json"
        )

        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            connection.outputStream.use {
                it.write(
                    body.toByteArray(Charsets.UTF_8)
                )
            }
        }

        val code = connection.responseCode

        val stream =
            if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

        val result =
            stream
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""

        connection.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException(
                "HTTP $code: $result"
            )
        }

        return result
    }

    private fun handleApiFailure(
        token: String,
        error: Exception
    ) {
        progress.visibility = View.GONE

        val message =
            error.message ?: "erro desconhecido"

        if (message.contains("HTTP 401")) {
            Identity.getAuthorizationClient(this)
                .clearToken(
                    ClearTokenRequest.builder()
                        .setToken(token)
                        .build()
                )
                .addOnCompleteListener {
                    accessToken = null

                    status.text =
                        "A sessão Google expirou. Carrega em Atualizar para voltar a autorizar."
                }
        } else {
            showError(
                "Erro do Gmail: $message"
            )
        }
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        status.text = message
    }
}
