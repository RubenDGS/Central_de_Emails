package pt.caixa6

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GmailPanel(
    context: Context,
    private val app: Caixa6App,
    private val requestAuthorization: () -> Unit
) : LinearLayout(context) {

    data class GmailRow(
        val id: String,
        val threadId: String,
        val subject: String,
        val from: String,
        val messageIdHeader: String,
        val unread: Boolean
    )

    private val status: TextView
    private val list: ListView
    private val progress: ProgressBar

    private var accessToken: String? = null
    private val rows = mutableListOf<GmailRow>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(20, 16, 20, 16)

        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val compose = Button(context).apply {
            text = "Escrever"
            isAllCaps = false
            setOnClickListener { showComposeDialog() }
        }

        val refresh = Button(context).apply {
            text = "Atualizar"
            isAllCaps = false
            setOnClickListener {
                if (accessToken == null) {
                    requestAuthorization()
                } else {
                    loadInbox()
                }
            }
        }

        actions.addView(
            compose,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )

        actions.addView(
            refresh,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )

        status = TextView(context).apply {
            text = "Gmail"
            textSize = 15f
            setPadding(4, 10, 4, 10)
        }

        progress = ProgressBar(context).apply {
            visibility = View.GONE
        }

        list = ListView(context)

        addView(actions)
        addView(status)
        addView(progress)

        addView(
            list,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        list.setOnItemClickListener { _, _, position, _ ->
            rows.getOrNull(position)?.let {
                loadMessage(it)
            }
        }
    }

    fun start() {
        if (accessToken == null) {
            status.text = "A ligar ao Gmail…"
            requestAuthorization()
        } else {
            loadInbox()
        }
    }

    fun setAuthorizedToken(token: String) {
        accessToken = token
        loadInbox()
    }

    fun showAuthorizationError(message: String) {
        progress.visibility = View.GONE
        status.text = message
    }

    private fun loadInbox() {
        val token = accessToken ?: return

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

                val arr =
                    JSONObject(listJson)
                        .optJSONArray("messages")

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
                                val header = headers.getJSONObject(h)

                                when (header.optString("name").lowercase()) {
                                    "subject" ->
                                        subject = header.optString("value", "(sem assunto)")
                                    "from" ->
                                        from = header.optString("value", "")
                                    "message-id" ->
                                        messageIdHeader = header.optString("value", "")
                                }
                            }
                        }

                        val row = GmailRow(
                            id = id,
                            threadId = item.optString("threadId", ""),
                            subject = subject,
                            from = from,
                            messageIdHeader = messageIdHeader,
                            unread = unreadMessage
                        )

                        newRows.add(row)

                        lines.add(
                            buildString {
                                if (unreadMessage) append("● ")
                                append(subject)
                                if (from.isNotBlank()) {
                                    append("\n")
                                    append(from)
                                }
                            }
                        )
                    }
                }

                post {
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
                            context,
                            android.R.layout.simple_list_item_1,
                            lines
                        )
                }

            } catch (e: Exception) {
                post {
                    showAuthorizationError(
                        "Erro do Gmail: " +
                            (e.message ?: "erro desconhecido")
                    )
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
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages/${row.id}?format=full"
                )

                val message = JSONObject(json)
                val payload = message.getJSONObject("payload")
                val headers = payload.optJSONArray("headers")

                var subject = row.subject
                var from = row.from
                var messageId = row.messageIdHeader

                if (headers != null) {
                    for (i in 0 until headers.length()) {
                        val header = headers.getJSONObject(i)

                        when (header.optString("name").lowercase()) {
                            "subject" -> subject = header.optString("value", subject)
                            "from" -> from = header.optString("value", from)
                            "message-id" -> messageId = header.optString("value", messageId)
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

                val finalRow = row.copy(
                    subject = subject,
                    from = from,
                    messageIdHeader = messageId,
                    unread = false
                )

                post {
                    progress.visibility = View.GONE
                    showMessageDialog(finalRow, body)
                    loadInbox()
                }

            } catch (e: Exception) {
                post {
                    progress.visibility = View.GONE
                    status.text =
                        "Erro do Gmail: " +
                            (e.message ?: "erro desconhecido")
                }
            }
        }.start()
    }

    private fun showMessageDialog(
        row: GmailRow,
        body: String
    ) {
        val textView = TextView(context).apply {
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

        AlertDialog.Builder(context)
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
                context,
                "Primeiro autoriza o Gmail.",
                Toast.LENGTH_SHORT
            ).show()
            requestAuthorization()
            return
        }

        val form = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(30, 10, 30, 0)
        }

        val to = EditText(context).apply { hint = "Para" }
        val subject = EditText(context).apply { hint = "Assunto" }
        val body = EditText(context).apply {
            hint = "Mensagem"
            minLines = 6
            gravity = Gravity.TOP
        }

        form.addView(to)
        form.addView(subject)
        form.addView(body)

        AlertDialog.Builder(context)
            .setTitle("Novo email")
            .setView(form)
            .setPositiveButton("Enviar") { _, _ ->
                sendMail(
                    to.text.toString().trim(),
                    subject.text.toString(),
                    body.text.toString(),
                    "",
                    ""
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showReplyDialog(row: GmailRow) {
        val body = EditText(context).apply {
            hint = "Resposta"
            minLines = 6
            gravity = Gravity.TOP
            setPadding(30, 10, 30, 0)
        }

        val subject =
            if (row.subject.startsWith("Re:", true)) {
                row.subject
            } else {
                "Re: ${row.subject}"
            }

        AlertDialog.Builder(context)
            .setTitle("Responder")
            .setView(body)
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

                post { loadInbox() }

            } catch (e: Exception) {
                post {
                    status.text =
                        "Erro do Gmail: " +
                            (e.message ?: "erro desconhecido")
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
                context,
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

                val json = JSONObject().put("raw", encoded)

                if (threadId.isNotBlank()) {
                    json.put("threadId", threadId)
                }

                apiRequest(
                    token,
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                    "POST",
                    json.toString()
                )

                post {
                    progress.visibility = View.GONE

                    Toast.makeText(
                        context,
                        "Email enviado.",
                        Toast.LENGTH_SHORT
                    ).show()

                    loadInbox()
                }

            } catch (e: Exception) {
                post {
                    progress.visibility = View.GONE
                    status.text =
                        "Erro do Gmail: " +
                            (e.message ?: "erro desconhecido")
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
                .put("addLabelIds", JSONArray(add))
                .put("removeLabelIds", JSONArray(remove))

        apiRequest(
            token,
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/$messageId/modify",
            "POST",
            json.toString()
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
                val childMime = child.optString("mimeType")
                val result = extractBody(child)

                if (
                    result.isNotBlank() &&
                    childMime.equals("text/plain", true)
                ) {
                    return result
                }

                if (result.isNotBlank() && fallback.isBlank()) {
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
                it.write(body.toByteArray(Charsets.UTF_8))
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
}
