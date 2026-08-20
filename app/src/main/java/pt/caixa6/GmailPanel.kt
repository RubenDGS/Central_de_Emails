package pt.caixa6

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.UUID

class GmailPanel(
    context: Context,
    private val app: Caixa6App,
    private val requestAuthorization: () -> Unit,
    private val requestAttachments: () -> Unit
) : LinearLayout(context) {

    private class GmailUnauthorizedException(
        val expiredToken: String
    ) : IllegalStateException("Sessão Google expirada")


    data class GmailRow(
        val id: String,
        val threadId: String,
        val subject: String,
        val from: String,
        val to: String,
        val cc: String,
        val replyTo: String,
        val messageIdHeader: String,
        val unread: Boolean,
        val starred: Boolean,
        val snippet: String
    )

    data class Folder(
        val title: String,
        val labelId: String? = null,
        val query: String? = null,
        val includeSpamTrash: Boolean = false
    )

    data class UserLabel(
        val id: String,
        val name: String
    )

    data class AttachmentInfo(
        val uri: Uri,
        val name: String,
        val mime: String
    )

    private val status: TextView
    private val list: ListView
    private val progress: ProgressBar
    private val searchBox: EditText
    private val selectButton: Button
    private val bulkButton: Button

    private var accessToken: String? = null
    private val rows = mutableListOf<GmailRow>()
    private val userLabels = mutableListOf<UserLabel>()

    private var selectionMode = false
    private val selectedIds = linkedSetOf<String>()

    private val composeAttachments =
        mutableListOf<AttachmentInfo>()

    private var activeAttachmentLabel: TextView? = null

    private var currentFolder =
        Folder(
            title = "Caixa de Entrada",
            labelId = "INBOX"
        )

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(12, 8, 12, 8)

        val topActions =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        val menuButton =
            Button(context).apply {
                text = "☰ Menu"
                isAllCaps = false
                setOnClickListener { showFolderMenu() }
            }

        val composeButton =
            Button(context).apply {
                text = "Escrever"
                isAllCaps = false
                setOnClickListener { showComposer() }
            }

        val refreshButton =
            Button(context).apply {
                text = "Atualizar"
                isAllCaps = false
                setOnClickListener {
                    if (accessToken == null) {
                        requestAuthorization()
                    } else {
                        loadCurrentFolder()
                    }
                }
            }

        topActions.addView(menuButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        topActions.addView(composeButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        topActions.addView(refreshButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val searchRow =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        searchBox =
            EditText(context).apply {
                hint = "Pesquisar no Gmail"
                maxLines = 1
            }

        val searchButton =
            Button(context).apply {
                text = "Pesquisar"
                isAllCaps = false
                setOnClickListener {
                    loadCurrentFolder(
                        searchBox.text.toString().trim()
                    )
                }
            }

        searchRow.addView(searchBox, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(searchButton)

        val selectionRow =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        selectButton =
            Button(context).apply {
                text = "Selecionar"
                isAllCaps = false
                setOnClickListener {
                    selectionMode = !selectionMode
                    selectedIds.clear()
                    updateSelectionControls()
                    (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
            }

        bulkButton =
            Button(context).apply {
                text = "Ações (0)"
                isAllCaps = false
                visibility = View.GONE
                setOnClickListener { showBulkActions() }
            }

        selectionRow.addView(selectButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        selectionRow.addView(bulkButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        status =
            TextView(context).apply {
                text = "Gmail"
                textSize = 15f
                setPadding(5, 7, 5, 7)
            }

        progress =
            ProgressBar(context).apply {
                visibility = View.GONE
            }

        list = ListView(context)

        addView(topActions)
        addView(searchRow)
        addView(selectionRow)
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
            val row = rows.getOrNull(position)
                ?: return@setOnItemClickListener

            if (selectionMode) {
                toggleSelection(row.id)
            } else {
                loadMessage(row)
            }
        }

        list.setOnItemLongClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position)
                ?: return@setOnItemLongClickListener true

            if (!selectionMode) {
                selectionMode = true
                selectedIds.clear()
            }

            toggleSelection(row.id)
            true
        }
    }

    fun start() {
        if (accessToken == null) {
            status.text = "A ligar ao Gmail…"
            requestAuthorization()
        } else {
            loadCurrentFolder()
        }
    }

    fun setAuthorizedToken(token: String) {
        accessToken = token
        app.rememberGmailAccessToken(token)
        loadLabels()
        loadCurrentFolder()
    }

    fun showAuthorizationError(message: String) {
        progress.visibility = View.GONE
        status.text = message
    }

    fun onAttachmentsSelected(uris: List<Uri>) {
        for (uri in uris) {
            val name = queryDisplayName(uri)
            val mime =
                context.contentResolver.getType(uri)
                    ?: "application/octet-stream"

            if (composeAttachments.none { it.uri == uri }) {
                composeAttachments.add(
                    AttachmentInfo(
                        uri = uri,
                        name = name,
                        mime = mime
                    )
                )
            }
        }

        activeAttachmentLabel?.text =
            attachmentSummary()
    }

    private fun queryDisplayName(uri: Uri): String {
        var result = "anexo"

        context.contentResolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            ?.use { cursor ->
                if (
                    cursor.moveToFirst() &&
                    cursor.columnCount > 0
                ) {
                    result = cursor.getString(0)
                }
            }

        return result
    }

    private fun attachmentSummary(): String =
        if (composeAttachments.isEmpty()) {
            "Sem anexos"
        } else {
            composeAttachments.joinToString(
                separator = "\n"
            ) {
                "📎 ${it.name}"
            }
        }

    private fun toggleSelection(messageId: String) {
        if (!selectedIds.add(messageId)) {
            selectedIds.remove(messageId)
        }

        updateSelectionControls()
        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun updateSelectionControls() {
        selectButton.text =
            if (selectionMode) {
                "Cancelar seleção"
            } else {
                "Selecionar"
            }

        bulkButton.visibility =
            if (selectionMode) {
                View.VISIBLE
            } else {
                View.GONE
            }

        bulkButton.text =
            "Ações (${selectedIds.size})"
    }

    private fun loadLabels() {
        val token = accessToken ?: return

        Thread {
            try {
                val json =
                    apiRequest(
                        token,
                        "https://gmail.googleapis.com/gmail/v1/users/me/labels"
                    )

                val arr =
                    JSONObject(json)
                        .optJSONArray("labels")

                val loaded = mutableListOf<UserLabel>()

                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)

                        if (item.optString("type") == "user") {
                            loaded.add(
                                UserLabel(
                                    id = item.getString("id"),
                                    name = item.optString("name", "Etiqueta")
                                )
                            )
                        }
                    }
                }

                post {
                    userLabels.clear()
                    userLabels.addAll(
                        loaded.sortedBy {
                            it.name.lowercase()
                        }
                    )
                }

            } catch (_: Exception) {
            }
        }.start()
    }

    private fun showFolderMenu() {
        val folders =
            mutableListOf(
                Folder("Caixa de Entrada", labelId = "INBOX"),
                Folder("Não lidos", query = "is:unread"),
                Folder("Com estrela", labelId = "STARRED"),
                Folder("Enviados", labelId = "SENT"),
                Folder("Rascunhos", labelId = "DRAFT"),
                Folder(
                    "Spam",
                    labelId = "SPAM",
                    includeSpamTrash = true
                ),
                Folder(
                    "Lixo",
                    labelId = "TRASH",
                    includeSpamTrash = true
                ),
                Folder("Todo o correio")
            )

        userLabels.forEach {
            folders.add(
                Folder(
                    title = "Etiqueta: ${it.name}",
                    labelId = it.id
                )
            )
        }

        AlertDialog.Builder(context)
            .setTitle("Gmail")
            .setItems(
                folders.map { it.title }.toTypedArray()
            ) { _, which ->
                currentFolder = folders[which]
                searchBox.setText("")
                selectionMode = false
                selectedIds.clear()
                updateSelectionControls()
                loadCurrentFolder()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadCurrentFolder(
        search: String = ""
    ) {
        val token = accessToken ?: return

        progress.visibility = View.VISIBLE
        status.text = "A carregar ${currentFolder.title}…"

        Thread {
            try {
                if (
                    currentFolder.labelId == "INBOX" &&
                    search.isBlank()
                ) {
                    val labelJson =
                        apiRequest(
                            token,
                            "https://gmail.googleapis.com/gmail/v1/users/me/labels/INBOX"
                        )

                    app.setGmailUnread(
                        JSONObject(labelJson)
                            .optInt("messagesUnread", 0)
                    )
                }

                val params =
                    mutableListOf(
                        "maxResults=50"
                    )

                currentFolder.labelId?.let {
                    params.add(
                        "labelIds=" +
                            URLEncoder.encode(it, "UTF-8")
                    )
                }

                val query =
                    listOfNotNull(
                        currentFolder.query,
                        search.takeIf {
                            it.isNotBlank()
                        }
                    )
                        .filter { it.isNotBlank() }
                        .joinToString(" ")

                if (query.isNotBlank()) {
                    params.add(
                        "q=" +
                            URLEncoder.encode(query, "UTF-8")
                    )
                }

                if (currentFolder.includeSpamTrash) {
                    params.add("includeSpamTrash=true")
                }

                val listJson =
                    apiRequest(
                        token,
                        "https://gmail.googleapis.com/gmail/v1/users/me/messages?" +
                            params.joinToString("&")
                    )

                val arr =
                    JSONObject(listJson)
                        .optJSONArray("messages")

                val loaded = mutableListOf<GmailRow>()

                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val id =
                            arr.getJSONObject(i)
                                .getString("id")

                        val metadata =
                            apiRequest(
                                token,
                                "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id" +
                                    "?format=metadata" +
                                    "&metadataHeaders=Subject" +
                                    "&metadataHeaders=From" +
                                    "&metadataHeaders=To" +
                                    "&metadataHeaders=Cc" +
                                    "&metadataHeaders=Reply-To" +
                                    "&metadataHeaders=Message-ID"
                            )

                        loaded.add(
                            parseMetadata(
                                JSONObject(metadata)
                            )
                        )
                    }
                }

                post {
                    progress.visibility = View.GONE
                    rows.clear()
                    rows.addAll(loaded)
                    selectedIds.retainAll(
                        rows.map { it.id }.toSet()
                    )

                    status.text =
                        "${currentFolder.title} — ${rows.size} mensagens"

                    list.adapter =
                        GmailMessageAdapter()
                }

            } catch (e: Exception) {
                post {
                    progress.visibility = View.GONE
                    handleApiError(e)
                }
            }
        }.start()
    }

    private fun parseMetadata(
        item: JSONObject
    ): GmailRow {
        val labels =
            item.optJSONArray("labelIds")

        var unread = false
        var starred = false

        if (labels != null) {
            for (i in 0 until labels.length()) {
                when (labels.optString(i)) {
                    "UNREAD" -> unread = true
                    "STARRED" -> starred = true
                }
            }
        }

        val headers =
            item.getJSONObject("payload")
                .optJSONArray("headers")

        var subject = "(sem assunto)"
        var from = ""
        var to = ""
        var cc = ""
        var replyTo = ""
        var messageId = ""

        if (headers != null) {
            for (i in 0 until headers.length()) {
                val header =
                    headers.getJSONObject(i)

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
                            header.optString("value", "")

                    "to" ->
                        to =
                            header.optString("value", "")

                    "cc" ->
                        cc =
                            header.optString("value", "")

                    "reply-to" ->
                        replyTo =
                            header.optString("value", "")

                    "message-id" ->
                        messageId =
                            header.optString("value", "")
                }
            }
        }

        return GmailRow(
            id = item.getString("id"),
            threadId =
                item.optString("threadId", ""),
            subject = subject,
            from = from,
            to = to,
            cc = cc,
            replyTo = replyTo,
            messageIdHeader = messageId,
            unread = unread,
            starred = starred,
            snippet =
                item.optString("snippet", "")
        )
    }

    private inner class GmailMessageAdapter :
        BaseAdapter() {

        override fun getCount(): Int =
            rows.size

        override fun getItem(
            position: Int
        ): GmailRow =
            rows[position]

        override fun getItemId(
            position: Int
        ): Long =
            position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup?
        ): View {
            val row = rows[position]

            val outer =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(10, 10, 12, 10)
                }

            val check =
                CheckBox(context).apply {
                    visibility =
                        if (selectionMode) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }

                    isChecked =
                        selectedIds.contains(
                            row.id
                        )

                    setOnClickListener {
                        toggleSelection(
                            row.id
                        )
                    }
                }

            val textBox =
                LinearLayout(context).apply {
                    orientation = VERTICAL
                }

            val sender =
                TextView(context).apply {
                    textSize = 16f
                    setTextColor(
                        Color.rgb(35, 35, 35)
                    )
                    text =
                        buildString {
                            if (row.starred) {
                                append("★ ")
                            }

                            append(
                                row.from.ifBlank {
                                    "(sem remetente)"
                                }
                            )
                        }
                }

            val subject =
                TextView(context).apply {
                    textSize = 16f
                    setTextColor(
                        Color.rgb(35, 35, 35)
                    )
                    text = row.subject
                }

            val snippet =
                TextView(context).apply {
                    textSize = 13f
                    setTextColor(
                        Color.rgb(105, 105, 105)
                    )
                    maxLines = 1
                    text = row.snippet
                }

            val style =
                if (row.unread) {
                    Typeface.BOLD
                } else {
                    Typeface.NORMAL
                }

            sender.setTypeface(
                sender.typeface,
                style
            )

            subject.setTypeface(
                subject.typeface,
                style
            )

            textBox.addView(sender)
            textBox.addView(subject)
            textBox.addView(snippet)

            outer.addView(check)
            outer.addView(
                textBox,
                LayoutParams(
                    0,
                    LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            return outer
        }
    }

    private fun loadMessage(
        original: GmailRow
    ) {
        val token =
            accessToken ?: return

        progress.visibility = View.VISIBLE
        status.text = "A abrir email…"

        Thread {
            try {
                val json =
                    apiRequest(
                        token,
                        "https://gmail.googleapis.com/gmail/v1/users/me/messages/${original.id}?format=full"
                    )

                val message =
                    JSONObject(json)

                val row =
                    parseMetadata(message)

                val payload =
                    message.getJSONObject(
                        "payload"
                    )

                val htmlBody =
                    extractHtmlBody(payload)

                val body =
                    extractBody(payload)
                        .ifBlank {
                            if (htmlBody.isNotBlank()) {
                                Html.fromHtml(
                                    htmlBody,
                                    Html.FROM_HTML_MODE_LEGACY
                                ).toString()
                            } else {
                                message.optString(
                                    "snippet",
                                    "Sem conteúdo disponível."
                                )
                            }
                        }

                if (row.unread) {
                    modifyLabels(
                        token,
                        row.id,
                        emptyList(),
                        listOf("UNREAD")
                    )
                }

                post {
                    progress.visibility = View.GONE
                    showMessageDialog(
                        row.copy(unread = false),
                        body,
                        htmlBody
                    )
                    loadCurrentFolder(
                        searchBox.text
                            .toString()
                            .trim()
                    )
                }

            } catch (e: Exception) {
                post {
                    progress.visibility = View.GONE
                    toastError(e)
                }
            }
        }.start()
    }

    private fun showMessageDialog(
        row: GmailRow,
        body: String,
        htmlBody: String
    ) {
        val content =
            LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(20, 8, 20, 8)
            }

        val header =
            TextView(context).apply {
                textSize = 15f
                setTextIsSelectable(true)
                setPadding(4, 4, 4, 10)
                text =
                    buildString {
                        append(row.subject)
                        append("\n\nDe: ${row.from}")

                        if (row.to.isNotBlank()) {
                            append("\nPara: ${row.to}")
                        }

                        if (row.cc.isNotBlank()) {
                            append("\nCc: ${row.cc}")
                        }
                    }
            }

        content.addView(header)

        if (htmlBody.isNotBlank()) {
            /*
             * Mostra o HTML original do email em vez de o converter
             * todo para texto simples. Assim preservamos negritos,
             * itálicos, tamanhos, tabelas, cores e a maior parte do
             * aspeto original da mensagem.
             *
             * JavaScript fica desligado por segurança.
             */
            val webView =
                WebView(context).apply {
                    setBackgroundColor(Color.WHITE)

                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode =
                        WebSettings.MIXED_CONTENT_NEVER_ALLOW

                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false

                    webViewClient =
                        object : WebViewClient() {

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri =
                                    request?.url
                                        ?: return false

                                return openEmailLink(
                                    uri.toString()
                                )
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                url: String?
                            ): Boolean {
                                val target =
                                    url
                                        ?: return false

                                return openEmailLink(
                                    target
                                )
                            }
                        }

                    loadDataWithBaseURL(
                        "https://mail.google.com/",
                        prepareEmailHtml(htmlBody),
                        "text/html",
                        "UTF-8",
                        null
                    )
                }

            content.addView(
                webView,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

        } else {
            val bodyView =
                TextView(context).apply {
                    textSize = 16f
                    setTextIsSelectable(true)
                    setPadding(4, 10, 4, 16)
                    text = body
                }

            val scroll =
                ScrollView(context).apply {
                    addView(bodyView)
                }

            content.addView(
                scroll,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        AlertDialog.Builder(context)
            .setTitle("Rita Gmail")
            .setView(content)
            .setPositiveButton("Responder") { _, _ ->
                showComposer(
                    replyRow = row,
                    replyAll = false
                )
            }
            .setNeutralButton("Ações") { _, _ ->
                showSingleActions(
                    row,
                    body
                )
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun prepareEmailHtml(
        html: String
    ): String {
        val viewport =
            if (
                html.contains(
                    "name=\"viewport\"",
                    ignoreCase = true
                ) ||
                html.contains(
                    "name='viewport'",
                    ignoreCase = true
                )
            ) {
                ""
            } else {
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
            }

        val safetyCss =
            """
            <style>
                html, body {
                    max-width: 100% !important;
                    overflow-wrap: anywhere;
                }
                img {
                    max-width: 100% !important;
                    height: auto !important;
                }
                table {
                    max-width: 100% !important;
                }
            </style>
            """.trimIndent()

        val clickableHtml =
            html
                .replace(
                    Regex(
                        """target\s*=\s*["']_blank["']""",
                        RegexOption.IGNORE_CASE
                    ),
                    "target=\"_self\""
                )
                .replace(
                    Regex(
                        """target\s*=\s*["']_new["']""",
                        RegexOption.IGNORE_CASE
                    ),
                    "target=\"_self\""
                )

        return if (
            clickableHtml.contains(
                "<head",
                ignoreCase = true
            )
        ) {
            val headRegex =
                Regex(
                    "<head[^>]*>",
                    RegexOption.IGNORE_CASE
                )

            val match =
                headRegex.find(clickableHtml)

            if (match != null) {
                clickableHtml.replaceRange(
                    match.range,
                    "${match.value}$viewport$safetyCss"
                )
            } else {
                "<html><head>$viewport$safetyCss</head><body>$clickableHtml</body></html>"
            }
        } else {
            "<html><head>$viewport$safetyCss</head><body>$clickableHtml</body></html>"
        }
    }

    private fun openEmailLink(
        url: String
    ): Boolean {
        return try {
            val uri =
                Uri.parse(url)

            when (
                uri.scheme
                    ?.lowercase()
            ) {
                "http",
                "https",
                "mailto",
                "tel" -> {
                    val intent =
                        Intent(
                            Intent.ACTION_VIEW,
                            uri
                        )

                    context.startActivity(
                        intent
                    )

                    true
                }

                else ->
                    false
            }

        } catch (error: Exception) {
            Toast.makeText(
                context,
                "Não foi possível abrir esta ligação.",
                Toast.LENGTH_SHORT
            ).show()

            true
        }
    }

    private fun showSingleActions(
        row: GmailRow,
        body: String
    ) {
        val options =
            arrayOf(
                if (row.unread) {
                    "Marcar como lido"
                } else {
                    "Marcar como não lido"
                },
                if (row.starred) {
                    "Retirar estrela"
                } else {
                    "Adicionar estrela"
                },
                "Arquivar",
                "Eliminar (Lixo)",
                "Marcar como Spam",
                "Responder",
                "Responder a todos",
                "Reencaminhar",
                "Mover para etiqueta…",
                "Aplicar etiqueta…"
            )

        AlertDialog.Builder(context)
            .setTitle(row.subject)
            .setItems(options) { _, which ->
                when (which) {
                    0 ->
                        updateLabelsAndRefresh(
                            listOf(row.id),
                            add =
                                if (row.unread) {
                                    emptyList()
                                } else {
                                    listOf("UNREAD")
                                },
                            remove =
                                if (row.unread) {
                                    listOf("UNREAD")
                                } else {
                                    emptyList()
                                }
                        )

                    1 ->
                        updateLabelsAndRefresh(
                            listOf(row.id),
                            add =
                                if (row.starred) {
                                    emptyList()
                                } else {
                                    listOf("STARRED")
                                },
                            remove =
                                if (row.starred) {
                                    listOf("STARRED")
                                } else {
                                    emptyList()
                                }
                        )

                    2 ->
                        updateLabelsAndRefresh(
                            listOf(row.id),
                            emptyList(),
                            listOf("INBOX")
                        )

                    3 ->
                        trashMessages(
                            listOf(row.id)
                        )

                    4 ->
                        updateLabelsAndRefresh(
                            listOf(row.id),
                            listOf("SPAM"),
                            listOf("INBOX")
                        )

                    5 ->
                        showComposer(
                            replyRow = row,
                            replyAll = false
                        )

                    6 ->
                        showComposer(
                            replyRow = row,
                            replyAll = true
                        )

                    7 ->
                        showComposer(
                            forwardRow = row,
                            forwardedBody = body
                        )

                    8 ->
                        showLabelChooser(
                            listOf(row.id),
                            move = true
                        )

                    9 ->
                        showLabelChooser(
                            listOf(row.id),
                            move = false
                        )
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showBulkActions() {
        val ids =
            selectedIds.toList()

        if (ids.isEmpty()) {
            Toast.makeText(
                context,
                "Seleciona pelo menos um email.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val options =
            arrayOf(
                "Marcar como lido",
                "Marcar como não lido",
                "Adicionar estrela",
                "Retirar estrela",
                "Arquivar",
                "Eliminar (Lixo)",
                "Marcar como Spam",
                "Mover para etiqueta…",
                "Aplicar etiqueta…"
            )

        AlertDialog.Builder(context)
            .setTitle("${ids.size} emails selecionados")
            .setItems(options) { _, which ->
                when (which) {
                    0 ->
                        updateLabelsAndRefresh(
                            ids,
                            emptyList(),
                            listOf("UNREAD")
                        )

                    1 ->
                        updateLabelsAndRefresh(
                            ids,
                            listOf("UNREAD"),
                            emptyList()
                        )

                    2 ->
                        updateLabelsAndRefresh(
                            ids,
                            listOf("STARRED"),
                            emptyList()
                        )

                    3 ->
                        updateLabelsAndRefresh(
                            ids,
                            emptyList(),
                            listOf("STARRED")
                        )

                    4 ->
                        updateLabelsAndRefresh(
                            ids,
                            emptyList(),
                            listOf("INBOX")
                        )

                    5 ->
                        trashMessages(ids)

                    6 ->
                        updateLabelsAndRefresh(
                            ids,
                            listOf("SPAM"),
                            listOf("INBOX")
                        )

                    7 ->
                        showLabelChooser(
                            ids,
                            move = true
                        )

                    8 ->
                        showLabelChooser(
                            ids,
                            move = false
                        )
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showLabelChooser(
        ids: List<String>,
        move: Boolean
    ) {
        if (userLabels.isEmpty()) {
            Toast.makeText(
                context,
                "Não encontrei etiquetas pessoais.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        AlertDialog.Builder(context)
            .setTitle(
                if (move) {
                    "Mover para"
                } else {
                    "Aplicar etiqueta"
                }
            )
            .setItems(
                userLabels
                    .map { it.name }
                    .toTypedArray()
            ) { _, which ->
                val label =
                    userLabels[which]

                updateLabelsAndRefresh(
                    ids,
                    listOf(label.id),
                    if (move) {
                        listOf("INBOX")
                    } else {
                        emptyList()
                    }
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateLabelsAndRefresh(
        ids: List<String>,
        add: List<String>,
        remove: List<String>
    ) {
        val token =
            accessToken ?: return

        Thread {
            try {
                if (ids.size == 1) {
                    modifyLabels(
                        token,
                        ids.first(),
                        add,
                        remove
                    )
                } else {
                    batchModify(
                        token,
                        ids,
                        add,
                        remove
                    )
                }

                post {
                    finishSelectionAndReload()
                }

            } catch (e: Exception) {
                post {
                    toastError(e)
                }
            }
        }.start()
    }

    private fun batchModify(
        token: String,
        ids: List<String>,
        add: List<String>,
        remove: List<String>
    ) {
        val json =
            JSONObject()
                .put("ids", JSONArray(ids))
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
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/batchModify",
            "POST",
            json.toString()
        )
    }

    private fun trashMessages(
        ids: List<String>
    ) {
        val token =
            accessToken ?: return

        Thread {
            try {
                for (id in ids) {
                    apiRequest(
                        token,
                        "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id/trash",
                        "POST",
                        "{}"
                    )
                }

                post {
                    finishSelectionAndReload()
                }

            } catch (e: Exception) {
                post {
                    toastError(e)
                }
            }
        }.start()
    }

    private fun finishSelectionAndReload() {
        selectionMode = false
        selectedIds.clear()
        updateSelectionControls()

        loadCurrentFolder(
            searchBox.text
                .toString()
                .trim()
        )
    }

    private fun showComposer(
        replyRow: GmailRow? = null,
        replyAll: Boolean = false,
        forwardRow: GmailRow? = null,
        forwardedBody: String = ""
    ) {
        if (accessToken == null) {
            requestAuthorization()
            return
        }

        composeAttachments.clear()

        val root =
            LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(24, 6, 24, 0)
            }

        val to =
            EditText(context).apply {
                hint = "Para"
            }

        val cc =
            EditText(context).apply {
                hint = "Cc"
            }

        val bcc =
            EditText(context).apply {
                hint = "Bcc"
            }

        val subject =
            EditText(context).apply {
                hint = "Assunto"
            }

        val formatRow =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        val body =
            EditText(context).apply {
                hint = "Mensagem"
                minLines = 8
                gravity = Gravity.TOP
                setTextIsSelectable(true)
            }

        fun formatButton(
            label: String,
            action: () -> Unit
        ): Button =
            Button(context).apply {
                text = label
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    action()
                }
            }

        formatRow.addView(
            formatButton("B") {
                applySpan(
                    body,
                    StyleSpan(
                        Typeface.BOLD
                    )
                )
            }
        )

        formatRow.addView(
            formatButton("I") {
                applySpan(
                    body,
                    StyleSpan(
                        Typeface.ITALIC
                    )
                )
            }
        )

        formatRow.addView(
            formatButton("U") {
                applySpan(
                    body,
                    UnderlineSpan()
                )
            }
        )

        formatRow.addView(
            formatButton("A−") {
                applySpan(
                    body,
                    RelativeSizeSpan(
                        0.85f
                    )
                )
            }
        )

        formatRow.addView(
            formatButton("A+") {
                applySpan(
                    body,
                    RelativeSizeSpan(
                        1.25f
                    )
                )
            }
        )

        val attachmentRow =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        val attachButton =
            Button(context).apply {
                text = "📎 Anexar"
                isAllCaps = false
                setOnClickListener {
                    requestAttachments()
                }
            }

        val attachmentLabel =
            TextView(context).apply {
                text = "Sem anexos"
                setPadding(10, 0, 0, 0)
            }

        activeAttachmentLabel =
            attachmentLabel

        attachmentRow.addView(attachButton)
        attachmentRow.addView(
            attachmentLabel,
            LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        if (replyRow != null) {
            val recipient =
                replyRow.replyTo.ifBlank {
                    replyRow.from
                }

            to.setText(recipient)

            if (replyAll) {
                cc.setText(
                    listOf(
                        replyRow.to,
                        replyRow.cc
                    )
                        .filter {
                            it.isNotBlank()
                        }
                        .joinToString(", ")
                )
            }

            subject.setText(
                if (
                    replyRow.subject
                        .startsWith(
                            "Re:",
                            true
                        )
                ) {
                    replyRow.subject
                } else {
                    "Re: ${replyRow.subject}"
                }
            )
        }

        if (forwardRow != null) {
            subject.setText(
                if (
                    forwardRow.subject
                        .startsWith(
                            "Fwd:",
                            true
                        )
                ) {
                    forwardRow.subject
                } else {
                    "Fwd: ${forwardRow.subject}"
                }
            )

            body.setText(
                buildString {
                    append("\n\n")
                    append("---------- Mensagem reencaminhada ----------\n")
                    append("De: ${forwardRow.from}\n")
                    append("Assunto: ${forwardRow.subject}\n\n")
                    append(forwardedBody)
                }
            )
        }

        root.addView(to)
        root.addView(cc)
        root.addView(bcc)
        root.addView(subject)
        root.addView(formatRow)
        root.addView(attachmentRow)
        root.addView(
            body,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val dialog =
            AlertDialog.Builder(context)
                .setTitle(
                    when {
                        forwardRow != null ->
                            "Reencaminhar"

                        replyRow != null &&
                            replyAll ->
                            "Responder a todos"

                        replyRow != null ->
                            "Responder"

                        else ->
                            "Novo email"
                    }
                )
                .setView(root)
                .setPositiveButton(
                    "Enviar",
                    null
                )
                .setNeutralButton(
                    "Guardar rascunho",
                    null
                )
                .setNegativeButton(
                    "Cancelar",
                    null
                )
                .create()

        dialog.setOnShowListener {
            dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener {
                sendRichMail(
                    to = to.text.toString().trim(),
                    cc = cc.text.toString().trim(),
                    bcc = bcc.text.toString().trim(),
                    subject =
                        subject.text.toString(),
                    body = body,
                    threadId =
                        replyRow?.threadId ?: "",
                    inReplyTo =
                        replyRow?.messageIdHeader ?: "",
                    asDraft = false,
                    onSuccess = {
                        dialog.dismiss()
                    }
                )
            }

            dialog.getButton(
                AlertDialog.BUTTON_NEUTRAL
            ).setOnClickListener {
                sendRichMail(
                    to = to.text.toString().trim(),
                    cc = cc.text.toString().trim(),
                    bcc = bcc.text.toString().trim(),
                    subject =
                        subject.text.toString(),
                    body = body,
                    threadId =
                        replyRow?.threadId ?: "",
                    inReplyTo =
                        replyRow?.messageIdHeader ?: "",
                    asDraft = true,
                    onSuccess = {
                        dialog.dismiss()
                    }
                )
            }
        }

        dialog.setOnDismissListener {
            activeAttachmentLabel = null
            composeAttachments.clear()
        }

        dialog.show()
    }

    private fun applySpan(
        editor: EditText,
        span: Any
    ) {
        val start =
            editor.selectionStart

        val end =
            editor.selectionEnd

        if (
            start < 0 ||
            end <= start
        ) {
            Toast.makeText(
                context,
                "Seleciona primeiro o texto a formatar.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        editor.text.setSpan(
            span,
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun sendRichMail(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: EditText,
        threadId: String,
        inReplyTo: String,
        asDraft: Boolean,
        onSuccess: () -> Unit
    ) {
        val token =
            accessToken ?: return

        if (
            !asDraft &&
            to.isBlank()
        ) {
            Toast.makeText(
                context,
                "Indica o destinatário.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val html =
            Html.toHtml(
                body.text as Spanned,
                Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE
            )

        val plain =
            body.text.toString()

        val attachments =
            composeAttachments.toList()

        progress.visibility = View.VISIBLE

        Thread {
            try {
                val raw =
                    buildMimeMessage(
                        to = to,
                        cc = cc,
                        bcc = bcc,
                        subject = subject,
                        plain = plain,
                        html = html,
                        inReplyTo = inReplyTo,
                        attachments = attachments
                    )

                if (asDraft) {
                    val message =
                        JSONObject()
                            .put(
                                "raw",
                                raw
                            )

                    if (
                        threadId.isNotBlank()
                    ) {
                        message.put(
                            "threadId",
                            threadId
                        )
                    }

                    val json =
                        JSONObject()
                            .put(
                                "message",
                                message
                            )

                    apiRequest(
                        token,
                        "https://gmail.googleapis.com/gmail/v1/users/me/drafts",
                        "POST",
                        json.toString()
                    )

                } else {
                    val json =
                        JSONObject()
                            .put(
                                "raw",
                                raw
                            )

                    if (
                        threadId.isNotBlank()
                    ) {
                        json.put(
                            "threadId",
                            threadId
                        )
                    }

                    apiRequest(
                        token,
                        "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                        "POST",
                        json.toString()
                    )
                }

                post {
                    progress.visibility = View.GONE

                    Toast.makeText(
                        context,
                        if (asDraft) {
                            "Rascunho guardado."
                        } else {
                            "Email enviado."
                        },
                        Toast.LENGTH_SHORT
                    ).show()

                    onSuccess()
                    loadCurrentFolder()
                }

            } catch (e: Exception) {
                post {
                    progress.visibility = View.GONE
                    toastError(e)
                }
            }
        }.start()
    }

    private fun buildMimeMessage(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        plain: String,
        html: String,
        inReplyTo: String,
        attachments: List<AttachmentInfo>
    ): String {
        val mixed =
            "mixed-${UUID.randomUUID()}"

        val alternative =
            "alt-${UUID.randomUUID()}"

        val raw =
            buildString {
                if (to.isNotBlank()) {
                    append("To: $to\r\n")
                }

                if (cc.isNotBlank()) {
                    append("Cc: $cc\r\n")
                }

                if (bcc.isNotBlank()) {
                    append("Bcc: $bcc\r\n")
                }

                append("Subject: $subject\r\n")

                if (inReplyTo.isNotBlank()) {
                    append("In-Reply-To: $inReplyTo\r\n")
                    append("References: $inReplyTo\r\n")
                }

                append("MIME-Version: 1.0\r\n")
                append(
                    "Content-Type: multipart/mixed; boundary=\"$mixed\"\r\n"
                )
                append("\r\n")

                append("--$mixed\r\n")
                append(
                    "Content-Type: multipart/alternative; boundary=\"$alternative\"\r\n\r\n"
                )

                append("--$alternative\r\n")
                append(
                    "Content-Type: text/plain; charset=UTF-8\r\n"
                )
                append(
                    "Content-Transfer-Encoding: 8bit\r\n\r\n"
                )
                append(plain)
                append("\r\n")

                append("--$alternative\r\n")
                append(
                    "Content-Type: text/html; charset=UTF-8\r\n"
                )
                append(
                    "Content-Transfer-Encoding: 8bit\r\n\r\n"
                )
                append(html)
                append("\r\n")
                append("--$alternative--\r\n")

                for (attachment in attachments) {
                    val bytes =
                        context.contentResolver
                            .openInputStream(
                                attachment.uri
                            )
                            ?.use {
                                it.readBytes()
                            }
                            ?: ByteArray(0)

                    val encoded =
                        Base64.encodeToString(
                            bytes,
                            Base64.NO_WRAP
                        )

                    append("--$mixed\r\n")
                    append(
                        "Content-Type: ${attachment.mime}; name=\"${attachment.name}\"\r\n"
                    )
                    append(
                        "Content-Disposition: attachment; filename=\"${attachment.name}\"\r\n"
                    )
                    append(
                        "Content-Transfer-Encoding: base64\r\n\r\n"
                    )
                    append(encoded)
                    append("\r\n")
                }

                append("--$mixed--\r\n")
            }

        return Base64.encodeToString(
            raw.toByteArray(
                Charsets.UTF_8
            ),
            Base64.URL_SAFE or
                Base64.NO_WRAP or
                Base64.NO_PADDING
        )
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
            "POST",
            json.toString()
        )
    }

    private fun extractHtmlBody(
        part: JSONObject
    ): String {
        val mime =
            part.optString("mimeType")

        val data =
            part.optJSONObject("body")
                ?.optString(
                    "data",
                    ""
                )
                ?: ""

        if (
            data.isNotBlank() &&
            mime.equals(
                "text/html",
                true
            )
        ) {
            return String(
                Base64.decode(
                    data,
                    Base64.URL_SAFE or
                        Base64.NO_WRAP or
                        Base64.NO_PADDING
                ),
                Charsets.UTF_8
            )
        }

        val parts =
            part.optJSONArray("parts")
                ?: return ""

        for (i in 0 until parts.length()) {
            val result =
                extractHtmlBody(
                    parts.getJSONObject(i)
                )

            if (result.isNotBlank()) {
                return result
            }
        }

        return ""
    }

    private fun extractBody(
        part: JSONObject
    ): String {
        val mime =
            part.optString("mimeType")

        val data =
            part.optJSONObject("body")
                ?.optString(
                    "data",
                    ""
                )
                ?: ""

        if (
            data.isNotBlank() &&
            (
                mime.equals(
                    "text/plain",
                    true
                ) ||
                    mime.equals(
                        "text/html",
                        true
                    )
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

            return if (
                mime.equals(
                    "text/html",
                    true
                )
            ) {
                Html.fromHtml(
                    decoded,
                    Html.FROM_HTML_MODE_LEGACY
                ).toString()
            } else {
                decoded
            }
        }

        val parts =
            part.optJSONArray("parts")

        if (parts != null) {
            var fallback = ""

            for (i in 0 until parts.length()) {
                val child =
                    parts.getJSONObject(i)

                val result =
                    extractBody(child)

                if (
                    result.isNotBlank() &&
                    child.optString("mimeType")
                        .equals(
                            "text/plain",
                            true
                        )
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
            URL(url)
                .openConnection()
                as HttpURLConnection

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

            connection.outputStream
                .use {
                    it.write(
                        body.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }
        }

        val code =
            connection.responseCode

        val stream =
            if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

        val result =
            stream
                ?.bufferedReader()
                ?.use {
                    it.readText()
                }
                ?: ""

        connection.disconnect()

        if (
            code == 401
        ) {
            throw GmailUnauthorizedException(token)
        }

        if (
            code !in 200..299
        ) {
            throw IllegalStateException(
                "HTTP $code: $result"
            )
        }

        return result
    }

    private fun handleApiError(
        error: Exception
    ) {
        if (
            error is GmailUnauthorizedException
        ) {
            val expiredToken =
                error.expiredToken

            accessToken = null

            status.text =
                "A renovar a autorização do Gmail…"

            /*
             * O 401 significa token expirado/inválido.
             * Limpamos a cache do Google Identity Services antes
             * de pedir um novo token; assim evitamos receber de novo
             * o mesmo token já inválido.
             */
            app.clearGmailAccessToken(
                expiredToken
            ) {
                post {
                    requestAuthorization()
                }
            }

            return
        }

        toastError(error)
    }

    private fun toastError(
        error: Exception
    ) {
        if (
            error is GmailUnauthorizedException
        ) {
            handleApiError(error)
            return
        }

        Toast.makeText(
            context,
            "Erro Gmail: " +
                (
                    error.message
                        ?: "erro desconhecido"
                    ),
            Toast.LENGTH_LONG
        ).show()
    }
}
