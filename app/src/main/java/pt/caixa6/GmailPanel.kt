package pt.caixa6

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
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

    private val status: TextView
    private val list: ListView
    private val progress: ProgressBar
    private val searchBox: EditText

    private var accessToken: String? = null
    private val rows = mutableListOf<GmailRow>()
    private val userLabels = mutableListOf<UserLabel>()

    private var currentFolder =
        Folder(
            title = "Caixa de Entrada",
            labelId = "INBOX"
        )

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(14, 10, 14, 10)

        val actions =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        val menu =
            Button(context).apply {
                text = "☰ Menu"
                isAllCaps = false
                setOnClickListener {
                    showFolderMenu()
                }
            }

        val compose =
            Button(context).apply {
                text = "Escrever"
                isAllCaps = false
                setOnClickListener {
                    showComposeDialog()
                }
            }

        val refresh =
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

        actions.addView(
            menu,
            LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        actions.addView(
            compose,
            LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        actions.addView(
            refresh,
            LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        )

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
                        searchBox.text
                            .toString()
                            .trim()
                    )
                }
            }

        searchRow.addView(
            searchBox,
            LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        searchRow.addView(
            searchButton,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        )

        status =
            TextView(context).apply {
                text = "Gmail"
                textSize = 15f
                setPadding(
                    5,
                    8,
                    5,
                    8
                )
            }

        progress =
            ProgressBar(context).apply {
                visibility = View.GONE
            }

        list =
            ListView(context)

        addView(actions)
        addView(searchRow)
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

        list.setOnItemClickListener {
                _,
                _,
                position,
                _ ->

            rows.getOrNull(position)
                ?.let {
                    loadMessage(it)
                }
        }

        list.setOnItemLongClickListener {
                _,
                _,
                position,
                _ ->

            rows.getOrNull(position)
                ?.let {
                    showMessageActions(it)
                }

            true
        }
    }

    fun start() {
        if (accessToken == null) {
            status.text =
                "A ligar ao Gmail…"

            requestAuthorization()

        } else {
            loadCurrentFolder()
        }
    }

    fun setAuthorizedToken(
        token: String
    ) {
        accessToken = token
        loadLabels()
        loadCurrentFolder()
    }

    fun showAuthorizationError(
        message: String
    ) {
        progress.visibility =
            View.GONE

        status.text =
            message
    }

    private fun loadLabels() {
        val token =
            accessToken
                ?: return

        Thread {
            try {
                val json =
                    apiRequest(
                        token,
                        "https://gmail.googleapis.com/gmail/v1/users/me/labels"
                    )

                val labels =
                    JSONObject(json)
                        .optJSONArray("labels")

                val loaded =
                    mutableListOf<UserLabel>()

                if (labels != null) {
                    for (
                        i in 0 until
                            labels.length()
                    ) {
                        val label =
                            labels.getJSONObject(i)

                        if (
                            label.optString(
                                "type"
                            ) ==
                            "user"
                        ) {
                            loaded.add(
                                UserLabel(
                                    id =
                                        label.getString(
                                            "id"
                                        ),
                                    name =
                                        label.optString(
                                            "name",
                                            "Etiqueta"
                                        )
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

            } catch (
                _: Exception
            ) {
                /*
                 * As etiquetas não são essenciais
                 * para abrir a Caixa de Entrada.
                 */
            }
        }.start()
    }

    private fun showFolderMenu() {
        val standard =
            mutableListOf(
                Folder(
                    "Caixa de Entrada",
                    labelId = "INBOX"
                ),
                Folder(
                    "Não lidos",
                    query = "is:unread"
                ),
                Folder(
                    "Com estrela",
                    labelId = "STARRED"
                ),
                Folder(
                    "Enviados",
                    labelId = "SENT"
                ),
                Folder(
                    "Rascunhos",
                    labelId = "DRAFT"
                ),
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
                Folder(
                    "Todo o correio"
                )
            )

        userLabels.forEach {
            standard.add(
                Folder(
                    title = "Etiqueta: ${it.name}",
                    labelId = it.id
                )
            )
        }

        val names =
            standard
                .map {
                    it.title
                }
                .toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Gmail")
            .setItems(names) {
                    _,
                    which ->

                currentFolder =
                    standard[which]

                searchBox.setText("")

                loadCurrentFolder()
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun loadCurrentFolder(
        search: String = ""
    ) {
        val token =
            accessToken
                ?: return

        progress.visibility =
            View.VISIBLE

        status.text =
            "A carregar ${currentFolder.title}…"

        Thread {
            try {
                if (
                    currentFolder.labelId ==
                    "INBOX" &&
                    search.isBlank()
                ) {
                    val inboxLabel =
                        apiRequest(
                            token,
                            "https://gmail.googleapis.com/gmail/v1/users/me/labels/INBOX"
                        )

                    val unread =
                        JSONObject(
                            inboxLabel
                        )
                            .optInt(
                                "messagesUnread",
                                0
                            )

                    app.setGmailUnread(
                        unread
                    )
                }

                val queryParts =
                    mutableListOf<String>()

                currentFolder.query
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        queryParts.add(it)
                    }

                if (search.isNotBlank()) {
                    queryParts.add(search)
                }

                val params =
                    mutableListOf(
                        "maxResults=50"
                    )

                currentFolder.labelId
                    ?.let {
                        params.add(
                            "labelIds=" +
                                URLEncoder.encode(
                                    it,
                                    "UTF-8"
                                )
                        )
                    }

                if (
                    queryParts.isNotEmpty()
                ) {
                    params.add(
                        "q=" +
                            URLEncoder.encode(
                                queryParts.joinToString(
                                    " "
                                ),
                                "UTF-8"
                            )
                    )
                }

                if (
                    currentFolder.includeSpamTrash
                ) {
                    params.add(
                        "includeSpamTrash=true"
                    )
                }

                val listJson =
                    apiRequest(
                        token,
                        "https://gmail.googleapis.com/gmail/v1/users/me/messages?" +
                            params.joinToString(
                                "&"
                            )
                    )

                val arr =
                    JSONObject(
                        listJson
                    )
                        .optJSONArray(
                            "messages"
                        )

                val newRows =
                    mutableListOf<GmailRow>()

                if (arr != null) {
                    for (
                        i in 0 until
                            arr.length()
                    ) {
                        val id =
                            arr
                                .getJSONObject(i)
                                .getString(
                                    "id"
                                )

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

                        newRows.add(
                            parseMetadata(
                                JSONObject(
                                    metadata
                                )
                            )
                        )
                    }
                }

                post {
                    progress.visibility =
                        View.GONE

                    rows.clear()
                    rows.addAll(
                        newRows
                    )

                    status.text =
                        buildString {
                            append(
                                currentFolder.title
                            )

                            if (
                                search.isNotBlank()
                            ) {
                                append(
                                    " — pesquisa"
                                )
                            }

                            append(
                                " — ${rows.size} mensagens"
                            )
                        }

                    list.adapter =
                        GmailMessageAdapter()
                }

            } catch (
                e: Exception
            ) {
                post {
                    showAuthorizationError(
                        "Erro do Gmail: " +
                            (
                                e.message
                                    ?: "erro desconhecido"
                                )
                    )
                }
            }
        }.start()
    }

    private fun parseMetadata(
        item: JSONObject
    ): GmailRow {

        val labels =
            item.optJSONArray(
                "labelIds"
            )

        var unread = false
        var starred = false

        if (labels != null) {
            for (
                i in 0 until
                    labels.length()
            ) {
                when (
                    labels.optString(i)
                ) {
                    "UNREAD" ->
                        unread = true

                    "STARRED" ->
                        starred = true
                }
            }
        }

        val headers =
            item
                .getJSONObject(
                    "payload"
                )
                .optJSONArray(
                    "headers"
                )

        var subject =
            "(sem assunto)"

        var from = ""
        var to = ""
        var cc = ""
        var replyTo = ""
        var messageId = ""

        if (headers != null) {
            for (
                h in 0 until
                    headers.length()
            ) {
                val header =
                    headers
                        .getJSONObject(h)

                when (
                    header
                        .optString(
                            "name"
                        )
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
                                ""
                            )

                    "to" ->
                        to =
                            header.optString(
                                "value",
                                ""
                            )

                    "cc" ->
                        cc =
                            header.optString(
                                "value",
                                ""
                            )

                    "reply-to" ->
                        replyTo =
                            header.optString(
                                "value",
                                ""
                            )

                    "message-id" ->
                        messageId =
                            header.optString(
                                "value",
                                ""
                            )
                }
            }
        }

        return GmailRow(
            id =
                item.getString(
                    "id"
                ),
            threadId =
                item.optString(
                    "threadId",
                    ""
                ),
            subject =
                subject,
            from =
                from,
            to =
                to,
            cc =
                cc,
            replyTo =
                replyTo,
            messageIdHeader =
                messageId,
            unread =
                unread,
            starred =
                starred,
            snippet =
                item.optString(
                    "snippet",
                    ""
                )
        )
    }

    private inner class GmailMessageAdapter :
        BaseAdapter() {

        override fun getCount():
            Int =
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

            val row =
                rows[position]

            val holder =
                (
                    convertView
                        as? LinearLayout
                    )
                    ?: LinearLayout(
                        context
                    ).apply {

                        orientation =
                            VERTICAL

                        setPadding(
                            28,
                            15,
                            20,
                            15
                        )

                        addView(
                            TextView(
                                context
                            ).apply {
                                tag =
                                    "sender"
                                textSize =
                                    16f
                                setTextColor(
                                    Color.rgb(
                                        35,
                                        35,
                                        35
                                    )
                                )
                            }
                        )

                        addView(
                            TextView(
                                context
                            ).apply {
                                tag =
                                    "subject"
                                textSize =
                                    16f
                                setTextColor(
                                    Color.rgb(
                                        35,
                                        35,
                                        35
                                    )
                                )
                            }
                        )

                        addView(
                            TextView(
                                context
                            ).apply {
                                tag =
                                    "snippet"
                                textSize =
                                    13f
                                maxLines =
                                    1
                                setTextColor(
                                    Color.rgb(
                                        110,
                                        110,
                                        110
                                    )
                                )
                            }
                        )
                    }

            val sender =
                holder.findViewWithTag<
                    TextView
                    >(
                    "sender"
                )

            val subject =
                holder.findViewWithTag<
                    TextView
                    >(
                    "subject"
                )

            val snippet =
                holder.findViewWithTag<
                    TextView
                    >(
                    "snippet"
                )

            sender.text =
                buildString {
                    if (row.starred) {
                        append(
                            "★ "
                        )
                    }

                    append(
                        row.from
                            .ifBlank {
                                "(sem remetente)"
                            }
                    )
                }

            subject.text =
                row.subject

            snippet.text =
                row.snippet

            val style =
                if (row.unread) {
                    Typeface.BOLD
                } else {
                    Typeface.NORMAL
                }

            /*
             * O indicador principal de "não lido"
             * passa a ser o texto em BOLD.
             */
            sender.setTypeface(
                sender.typeface,
                style
            )

            subject.setTypeface(
                subject.typeface,
                style
            )

            return holder
        }
    }

    private fun loadMessage(
        original: GmailRow
    ) {
        val token =
            accessToken
                ?: return

        progress.visibility =
            View.VISIBLE

        status.text =
            "A abrir email…"

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
                    parseMetadata(
                        message
                    )

                val payload =
                    message.getJSONObject(
                        "payload"
                    )

                val body =
                    extractBody(
                        payload
                    )
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
                        add =
                            emptyList(),
                        remove =
                            listOf(
                                "UNREAD"
                            )
                    )
                }

                val displayed =
                    row.copy(
                        unread =
                            false
                    )

                post {
                    progress.visibility =
                        View.GONE

                    showMessageDialog(
                        displayed,
                        body
                    )

                    loadCurrentFolder(
                        searchBox
                            .text
                            .toString()
                            .trim()
                    )
                }

            } catch (
                e: Exception
            ) {
                post {
                    progress.visibility =
                        View.GONE

                    status.text =
                        "Erro do Gmail: " +
                            (
                                e.message
                                    ?: "erro desconhecido"
                                )
                }
            }
        }.start()
    }

    private fun showMessageDialog(
        row: GmailRow,
        body: String
    ) {
        val container =
            LinearLayout(
                context
            ).apply {
                orientation =
                    VERTICAL
                setPadding(
                    28,
                    12,
                    28,
                    8
                )
            }

        val header =
            TextView(
                context
            ).apply {
                textSize =
                    16f

                setTextIsSelectable(
                    true
                )

                text =
                    buildString {
                        append(
                            row.subject
                        )
                        append(
                            "\n\nDe: "
                        )
                        append(
                            row.from
                        )

                        if (
                            row.to.isNotBlank()
                        ) {
                            append(
                                "\nPara: "
                            )
                            append(
                                row.to
                            )
                        }

                        if (
                            row.cc.isNotBlank()
                        ) {
                            append(
                                "\nCc: "
                            )
                            append(
                                row.cc
                            )
                        }
                    }
            }

        val bodyView =
            TextView(
                context
            ).apply {
                text =
                    body
                textSize =
                    16f
                setTextIsSelectable(
                    true
                )
                setPadding(
                    0,
                    18,
                    0,
                    18
                )
            }

        val scroll =
            ScrollView(
                context
            ).apply {
                addView(
                    bodyView
                )
            }

        container.addView(
            header
        )

        container.addView(
            scroll,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        AlertDialog.Builder(
            context
        )
            .setTitle(
                "Rita Gmail"
            )
            .setView(
                container
            )
            .setPositiveButton(
                "Responder"
            ) {
                    _,
                    _ ->
                showReplyDialog(
                    row,
                    false
                )
            }
            .setNeutralButton(
                "Ações"
            ) {
                    _,
                    _ ->
                showMessageActions(
                    row,
                    body
                )
            }
            .setNegativeButton(
                "Fechar",
                null
            )
            .show()
    }

    private fun showMessageActions(
        row: GmailRow,
        body: String = ""
    ) {
        val actions =
            mutableListOf<String>()

        actions.add(
            if (row.unread) {
                "Marcar como lido"
            } else {
                "Marcar como não lido"
            }
        )

        actions.add(
            if (row.starred) {
                "Retirar estrela"
            } else {
                "Adicionar estrela"
            }
        )

        actions.add(
            "Arquivar"
        )

        actions.add(
            "Eliminar (mover para Lixo)"
        )

        actions.add(
            "Marcar como Spam"
        )

        actions.add(
            "Responder"
        )

        actions.add(
            "Responder a todos"
        )

        actions.add(
            "Reencaminhar"
        )

        actions.add(
            "Mover para etiqueta…"
        )

        actions.add(
            "Aplicar etiqueta…"
        )

        AlertDialog.Builder(
            context
        )
            .setTitle(
                row.subject
            )
            .setItems(
                actions.toTypedArray()
            ) {
                    _,
                    which ->

                when (which) {

                    0 -> {
                        if (row.unread) {
                            updateLabelsAndRefresh(
                                row.id,
                                add =
                                    emptyList(),
                                remove =
                                    listOf(
                                        "UNREAD"
                                    )
                            )
                        } else {
                            updateLabelsAndRefresh(
                                row.id,
                                add =
                                    listOf(
                                        "UNREAD"
                                    ),
                                remove =
                                    emptyList()
                            )
                        }
                    }

                    1 -> {
                        if (row.starred) {
                            updateLabelsAndRefresh(
                                row.id,
                                add =
                                    emptyList(),
                                remove =
                                    listOf(
                                        "STARRED"
                                    )
                            )
                        } else {
                            updateLabelsAndRefresh(
                                row.id,
                                add =
                                    listOf(
                                        "STARRED"
                                    ),
                                remove =
                                    emptyList()
                            )
                        }
                    }

                    2 ->
                        updateLabelsAndRefresh(
                            row.id,
                            add =
                                emptyList(),
                            remove =
                                listOf(
                                    "INBOX"
                                )
                        )

                    3 ->
                        trashAndRefresh(
                            row.id
                        )

                    4 ->
                        updateLabelsAndRefresh(
                            row.id,
                            add =
                                listOf(
                                    "SPAM"
                                ),
                            remove =
                                listOf(
                                    "INBOX"
                                )
                        )

                    5 ->
                        showReplyDialog(
                            row,
                            false
                        )

                    6 ->
                        showReplyDialog(
                            row,
                            true
                        )

                    7 ->
                        showForwardDialog(
                            row,
                            body
                        )

                    8 ->
                        showMoveToLabelDialog(
                            row
                        )

                    9 ->
                        showApplyLabelDialog(
                            row
                        )
                }
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun updateLabelsAndRefresh(
        messageId: String,
        add: List<String>,
        remove: List<String>
    ) {
        val token =
            accessToken
                ?: return

        Thread {
            try {
                modifyLabels(
                    token,
                    messageId,
                    add,
                    remove
                )

                post {
                    loadCurrentFolder(
                        searchBox
                            .text
                            .toString()
                            .trim()
                    )
                }

            } catch (
                e: Exception
            ) {
                post {
                    toastError(
                        e
                    )
                }
            }
        }.start()
    }

    private fun trashAndRefresh(
        messageId: String
    ) {
        val token =
            accessToken
                ?: return

        Thread {
            try {
                apiRequest(
                    token,
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages/$messageId/trash",
                    method =
                        "POST",
                    body =
                        "{}"
                )

                post {
                    loadCurrentFolder(
                        searchBox
                            .text
                            .toString()
                            .trim()
                    )
                }

            } catch (
                e: Exception
            ) {
                post {
                    toastError(
                        e
                    )
                }
            }
        }.start()
    }

    private fun showMoveToLabelDialog(
        row: GmailRow
    ) {
        if (
            userLabels.isEmpty()
        ) {
            Toast.makeText(
                context,
                "Não encontrei etiquetas pessoais.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val names =
            userLabels
                .map {
                    it.name
                }
                .toTypedArray()

        AlertDialog.Builder(
            context
        )
            .setTitle(
                "Mover para"
            )
            .setItems(
                names
            ) {
                    _,
                    which ->

                val label =
                    userLabels[
                        which
                    ]

                updateLabelsAndRefresh(
                    row.id,
                    add =
                        listOf(
                            label.id
                        ),
                    remove =
                        listOf(
                            "INBOX"
                        )
                )
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun showApplyLabelDialog(
        row: GmailRow
    ) {
        if (
            userLabels.isEmpty()
        ) {
            Toast.makeText(
                context,
                "Não encontrei etiquetas pessoais.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val names =
            userLabels
                .map {
                    it.name
                }
                .toTypedArray()

        AlertDialog.Builder(
            context
        )
            .setTitle(
                "Aplicar etiqueta"
            )
            .setItems(
                names
            ) {
                    _,
                    which ->

                updateLabelsAndRefresh(
                    row.id,
                    add =
                        listOf(
                            userLabels[
                                which
                            ].id
                        ),
                    remove =
                        emptyList()
                )
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun showComposeDialog() {
        if (
            accessToken == null
        ) {
            Toast.makeText(
                context,
                "Primeiro autoriza o Gmail.",
                Toast.LENGTH_SHORT
            ).show()

            requestAuthorization()
            return
        }

        val form =
            LinearLayout(
                context
            ).apply {
                orientation =
                    VERTICAL
                setPadding(
                    26,
                    6,
                    26,
                    0
                )
            }

        val to =
            EditText(
                context
            ).apply {
                hint =
                    "Para"
            }

        val cc =
            EditText(
                context
            ).apply {
                hint =
                    "Cc"
            }

        val bcc =
            EditText(
                context
            ).apply {
                hint =
                    "Bcc"
            }

        val subject =
            EditText(
                context
            ).apply {
                hint =
                    "Assunto"
            }

        val body =
            EditText(
                context
            ).apply {
                hint =
                    "Mensagem"
                minLines =
                    7
                gravity =
                    Gravity.TOP
            }

        form.addView(to)
        form.addView(cc)
        form.addView(bcc)
        form.addView(subject)
        form.addView(body)

        AlertDialog.Builder(
            context
        )
            .setTitle(
                "Novo email"
            )
            .setView(
                form
            )
            .setPositiveButton(
                "Enviar"
            ) {
                    _,
                    _ ->

                sendMail(
                    to =
                        to.text
                            .toString()
                            .trim(),
                    cc =
                        cc.text
                            .toString()
                            .trim(),
                    bcc =
                        bcc.text
                            .toString()
                            .trim(),
                    subject =
                        subject.text
                            .toString(),
                    body =
                        body.text
                            .toString(),
                    threadId =
                        "",
                    inReplyTo =
                        ""
                )
            }
            .setNeutralButton(
                "Guardar rascunho"
            ) {
                    _,
                    _ ->

                createDraft(
                    to =
                        to.text
                            .toString()
                            .trim(),
                    cc =
                        cc.text
                            .toString()
                            .trim(),
                    bcc =
                        bcc.text
                            .toString()
                            .trim(),
                    subject =
                        subject.text
                            .toString(),
                    body =
                        body.text
                            .toString()
                )
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun showReplyDialog(
        row: GmailRow,
        replyAll: Boolean
    ) {
        val body =
            EditText(
                context
            ).apply {
                hint =
                    if (replyAll) {
                        "Responder a todos"
                    } else {
                        "Resposta"
                    }

                minLines =
                    7

                gravity =
                    Gravity.TOP

                setPadding(
                    28,
                    10,
                    28,
                    0
                )
            }

        val subject =
            if (
                row.subject.startsWith(
                    "Re:",
                    true
                )
            ) {
                row.subject
            } else {
                "Re: ${row.subject}"
            }

        val mainRecipient =
            row.replyTo
                .ifBlank {
                    row.from
                }

        val cc =
            if (replyAll) {
                listOf(
                    row.to,
                    row.cc
                )
                    .filter {
                        it.isNotBlank()
                    }
                    .joinToString(
                        ", "
                    )
            } else {
                ""
            }

        AlertDialog.Builder(
            context
        )
            .setTitle(
                if (replyAll) {
                    "Responder a todos"
                } else {
                    "Responder"
                }
            )
            .setView(
                body
            )
            .setPositiveButton(
                "Enviar"
            ) {
                    _,
                    _ ->

                sendMail(
                    to =
                        mainRecipient,
                    cc =
                        cc,
                    bcc =
                        "",
                    subject =
                        subject,
                    body =
                        body.text
                            .toString(),
                    threadId =
                        row.threadId,
                    inReplyTo =
                        row.messageIdHeader
                )
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun showForwardDialog(
        row: GmailRow,
        originalBody: String
    ) {
        val form =
            LinearLayout(
                context
            ).apply {
                orientation =
                    VERTICAL
                setPadding(
                    28,
                    8,
                    28,
                    0
                )
            }

        val to =
            EditText(
                context
            ).apply {
                hint =
                    "Para"
            }

        val body =
            EditText(
                context
            ).apply {
                minLines =
                    7
                gravity =
                    Gravity.TOP

                setText(
                    buildString {
                        append(
                            "\n\n---------- Mensagem reencaminhada ----------\n"
                        )
                        append(
                            "De: ${row.from}\n"
                        )
                        append(
                            "Assunto: ${row.subject}\n\n"
                        )
                        append(
                            originalBody
                        )
                    }
                )
            }

        form.addView(to)
        form.addView(body)

        val subject =
            if (
                row.subject.startsWith(
                    "Fwd:",
                    true
                )
            ) {
                row.subject
            } else {
                "Fwd: ${row.subject}"
            }

        AlertDialog.Builder(
            context
        )
            .setTitle(
                "Reencaminhar"
            )
            .setView(
                form
            )
            .setPositiveButton(
                "Enviar"
            ) {
                    _,
                    _ ->

                sendMail(
                    to =
                        to.text
                            .toString()
                            .trim(),
                    cc =
                        "",
                    bcc =
                        "",
                    subject =
                        subject,
                    body =
                        body.text
                            .toString(),
                    threadId =
                        "",
                    inReplyTo =
                        ""
                )
            }
            .setNegativeButton(
                "Cancelar",
                null
            )
            .show()
    }

    private fun createDraft(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String
    ) {
        val token =
            accessToken
                ?: return

        val raw =
            makeRawMessage(
                to,
                cc,
                bcc,
                subject,
                body,
                ""
            )

        val json =
            JSONObject()
                .put(
                    "message",
                    JSONObject()
                        .put(
                            "raw",
                            raw
                        )
                )

        Thread {
            try {
                apiRequest(
                    token,
                    "https://gmail.googleapis.com/gmail/v1/users/me/drafts",
                    method =
                        "POST",
                    body =
                        json.toString()
                )

                post {
                    Toast.makeText(
                        context,
                        "Rascunho guardado.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (
                e: Exception
            ) {
                post {
                    toastError(
                        e
                    )
                }
            }
        }.start()
    }

    private fun sendMail(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        threadId: String,
        inReplyTo: String
    ) {
        val token =
            accessToken
                ?: return

        if (to.isBlank()) {
            Toast.makeText(
                context,
                "Indica o destinatário.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        progress.visibility =
            View.VISIBLE

        status.text =
            "A enviar email…"

        val encoded =
            makeRawMessage(
                to,
                cc,
                bcc,
                subject,
                body,
                inReplyTo
            )

        val json =
            JSONObject()
                .put(
                    "raw",
                    encoded
                )

        if (
            threadId.isNotBlank()
        ) {
            json.put(
                "threadId",
                threadId
            )
        }

        Thread {
            try {
                apiRequest(
                    token,
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                    method =
                        "POST",
                    body =
                        json.toString()
                )

                post {
                    progress.visibility =
                        View.GONE

                    Toast.makeText(
                        context,
                        "Email enviado.",
                        Toast.LENGTH_SHORT
                    ).show()

                    loadCurrentFolder()
                }

            } catch (
                e: Exception
            ) {
                post {
                    progress.visibility =
                        View.GONE

                    toastError(
                        e
                    )
                }
            }
        }.start()
    }

    private fun makeRawMessage(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        inReplyTo: String
    ): String {

        val raw =
            buildString {
                append(
                    "To: $to\r\n"
                )

                if (
                    cc.isNotBlank()
                ) {
                    append(
                        "Cc: $cc\r\n"
                    )
                }

                if (
                    bcc.isNotBlank()
                ) {
                    append(
                        "Bcc: $bcc\r\n"
                    )
                }

                append(
                    "Subject: $subject\r\n"
                )

                if (
                    inReplyTo.isNotBlank()
                ) {
                    append(
                        "In-Reply-To: $inReplyTo\r\n"
                    )
                    append(
                        "References: $inReplyTo\r\n"
                    )
                }

                append(
                    "MIME-Version: 1.0\r\n"
                )

                append(
                    "Content-Type: text/plain; charset=UTF-8\r\n"
                )

                append(
                    "\r\n"
                )

                append(
                    body
                )
            }

        return Base64
            .encodeToString(
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
                    JSONArray(
                        add
                    )
                )
                .put(
                    "removeLabelIds",
                    JSONArray(
                        remove
                    )
                )

        apiRequest(
            token,
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/$messageId/modify",
            method =
                "POST",
            body =
                json.toString()
        )
    }

    private fun extractBody(
        part: JSONObject
    ): String {
        val mime =
            part.optString(
                "mimeType"
            )

        val body =
            part.optJSONObject(
                "body"
            )

        val data =
            body?.optString(
                "data",
                ""
            ) ?: ""

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
            part.optJSONArray(
                "parts"
            )

        if (parts != null) {
            var fallback =
                ""

            for (
                i in 0 until
                    parts.length()
            ) {
                val child =
                    parts.getJSONObject(i)

                val childMime =
                    child.optString(
                        "mimeType"
                    )

                val result =
                    extractBody(
                        child
                    )

                if (
                    result.isNotBlank() &&
                    childMime.equals(
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
                    fallback =
                        result
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

        connection.requestMethod =
            method

        connection.setRequestProperty(
            "Authorization",
            "Bearer $token"
        )

        connection.setRequestProperty(
            "Accept",
            "application/json"
        )

        if (body != null) {
            connection.doOutput =
                true

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
            if (
                code in 200..299
            ) {
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
            code !in 200..299
        ) {
            throw IllegalStateException(
                "HTTP $code: $result"
            )
        }

        return result
    }

    private fun toastError(
        error: Exception
    ) {
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
