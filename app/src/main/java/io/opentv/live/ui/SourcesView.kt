package io.opentv.live.ui

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.opentv.live.App
import io.opentv.live.R
import io.opentv.live.data.Source
import io.opentv.live.data.SourceWebServer
import java.io.File
import java.util.UUID

/**
 * 播放源管理页：添加/编辑/删除订阅源，导入本地播放列表。
 */
class SourcesView(context: Context) : LinearLayout(context) {

    var onRefreshRequest: (() -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val repo = App.get().channels
    private val prefs = App.get().prefs
    private val inflater = LayoutInflater.from(context)
    private val adapter = SourceAdapter()

    private val list: RecyclerView
    private val status: TextView

    private var editing: Source? = null

    /** 扫码添加时临时启动的局域网服务 */
    private var qrServer: SourceWebServer? = null
    private var qrDialog: AlertDialog? = null

    private val picker = (context as AppCompatActivity).registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onFilePicked(uri)
    }

    init {
        orientation = VERTICAL
        inflater.inflate(R.layout.view_sources, this, true)

        list = findViewById(R.id.sourceList)
        status = findViewById(R.id.sourceStatus)

        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.itemAnimator = null

        findViewById<TextView>(R.id.btnAddUrl).setOnClickListener { showEditDialog(null) }
        findViewById<TextView>(R.id.btnBack).setOnClickListener { onClose?.invoke() }
        findViewById<TextView>(R.id.btnAddQr).setOnClickListener { showQrDialog() }
        findViewById<TextView>(R.id.btnAddFile).setOnClickListener {
            runCatching { picker.launch(arrayOf("application/octet-stream", "text/*", "*/*")) }
                .onFailure { Toast.makeText(context, "当前设备没有文件选择器", Toast.LENGTH_SHORT).show() }
        }
        findViewById<TextView>(R.id.btnRefreshAll).setOnClickListener { onRefreshRequest?.invoke() }

        refresh()
    }

    fun refresh() {
        repo.loadSources()
        adapter.submit(repo.sources)
        val enabled = repo.sources.count { it.enabled }
        status.text = "${repo.sources.size} 个源（启用 $enabled 个） · 共 ${repo.channels.size} 个频道"
    }

    /** 电视上展示二维码，手机扫码后可粘贴订阅地址，也可上传 m3u / txt 文件 */
    private fun showQrDialog() {
        qrServer?.stop()
        val server = SourceWebServer(
            onAddUrl = { name, url, epg ->
                registerSource(
                    Source(
                        id = UUID.randomUUID().toString(),
                        name = name.ifBlank { "订阅源 ${repo.sources.size + 1}" },
                        url = url,
                        epgUrl = epg
                    )
                )
            },
            onAddFile = { name, content -> importUploadedFile(name, content) }
        )
        if (!server.start()) {
            Toast.makeText(context, "局域网服务启动失败，请改用「添加订阅地址」", Toast.LENGTH_LONG).show()
            return
        }
        qrServer = server
        android.util.Log.d("FreeTV", "扫码添加地址 ${server.address}")

        val density = resources.displayMetrics.density
        val qrSize = (250 * density).toInt()
        val pad = (24 * density).toInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        box.addView(ImageView(context).apply {
            setImageBitmap(QrCode.render(server.address, qrSize))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(qrSize, qrSize))

        box.addView(TextView(context).apply {
            text = server.address
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF171A1F.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt() }
        })

        box.addView(TextView(context).apply {
            text = "手机连同一个 Wi-Fi 扫码，可粘贴订阅地址\n也能直接上传手机里的 m3u / txt 播放列表"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF6B7280.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        })

        val dialog = AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
            .setTitle("扫码添加播放源")
            .setView(box)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnDismissListener {
            server.stop()
            if (qrServer === server) qrServer = null
            if (qrDialog === dialog) qrDialog = null
        }
        qrDialog = dialog
        dialog.show()
    }

    /** 入库并刷新界面，可在任意线程调用 */
    private fun registerSource(source: Source) {
        post {
            repo.upsertSource(source)
            refresh()
            onChanged?.invoke()
            onRefreshRequest?.invoke()
            Toast.makeText(context, "已添加：${source.name}", Toast.LENGTH_SHORT).show()
            qrDialog?.dismiss()
        }
    }

    /** 把手机上传的 m3u / txt 内容落盘成本地播放列表 */
    private fun importUploadedFile(name: String, content: String): Boolean {
        return try {
            val dir = File(context.filesDir, "imports").apply { mkdirs() }
            val safe = name.ifBlank { "手机上传" }
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(40)
            val file = File(dir, "${safe}_${System.currentTimeMillis()}.m3u")
            file.writeText(content)
            registerSource(
                Source(
                    id = UUID.randomUUID().toString(),
                    name = safe,
                    url = "file://${file.absolutePath}",
                    kind = Source.KIND_FILE
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun onFilePicked(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val name = queryName(uri) ?: "本地播放列表"
        val source = Source(
            id = UUID.randomUUID().toString(),
            name = name.substringBeforeLast('.').ifBlank { "本地播放列表" },
            url = uri.toString(),
            kind = Source.KIND_FILE
        )
        repo.upsertSource(source)
        refresh()
        onChanged?.invoke()
        onRefreshRequest?.invoke()
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    private fun showEditDialog(existing: Source?) {
        editing = existing
        val pad = Ui.dpi(context, 22f)
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        val nameInput = addField(container, "名称", existing?.name.orEmpty().ifBlank { "我的订阅源" })
        val urlInput = addField(container, "M3U 订阅地址（http/https）", existing?.url.orEmpty())
        val epgInput = addField(container, "XMLTV 节目单地址（可留空）", existing?.epgUrl.orEmpty())
        val uaInput = addField(container, "User-Agent（留空使用默认）", existing?.userAgent.orEmpty())
        val refererInput = addField(container, "Referer（可留空）", existing?.referer.orEmpty())
        val headersInput = addField(
            container,
            "自定义请求头（每行一条，如 Cookie: abc=1）",
            existing?.headers.orEmpty(),
            singleLine = false
        )

        if (existing?.isFile == true) {
            urlInput.setText(existing.url)
            urlInput.isEnabled = false
            urlInput.alpha = 0.6f
        }

        val scroll = ScrollView(context).apply { addView(container) }

        val dialog = AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
            .setTitle(if (existing == null) "添加播放源" else "编辑播放源")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (url.isBlank()) {
                    Toast.makeText(context, "请填写播放列表地址", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val src = Source(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name.ifBlank { "播放源" },
                    url = url,
                    kind = existing?.kind ?: Source.KIND_URL,
                    epgUrl = epgInput.text.toString().trim(),
                    userAgent = uaInput.text.toString().trim(),
                    referer = refererInput.text.toString().trim(),
                    headers = headersInput.text.toString().trim(),
                    enabled = existing?.enabled ?: true,
                    autoUpdate = existing?.autoUpdate ?: true,
                    lastUpdate = existing?.lastUpdate ?: 0L,
                    lastError = ""
                )
                repo.upsertSource(src)
                if (src.epgUrl.isNotBlank() && prefs.epgUrl.isBlank()) prefs.epgUrl = src.epgUrl
                dialog.dismiss()
                refresh()
                onChanged?.invoke()
                onRefreshRequest?.invoke()
            }
        }
        dialog.show()
    }

    private fun addField(
        parent: LinearLayout,
        label: String,
        value: String,
        singleLine: Boolean = true
    ): EditText {
        parent.addView(TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, Ui.dpi(context, 10f), 0, Ui.dpi(context, 4f))
        })
        val input = EditText(context).apply {
            setText(value)
            textSize = 13f
            setTextColor(0xFF171A1F.toInt())
            setBackgroundResource(R.drawable.bg_tab)
            if (singleLine) {
                maxLines = 1
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            } else {
                minLines = 3
                gravity = android.view.Gravity.TOP
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            setPadding(Ui.dpi(context, 14f), Ui.dpi(context, 10f), Ui.dpi(context, 14f), Ui.dpi(context, 10f))
        }
        parent.addView(input, LinearLayout.LayoutParams(-1, -2))
        return input
    }

    private fun showSourceMenu(source: Source) {
        val items = arrayListOf<String>()
        items.add("刷新该源")
        items.add(if (source.enabled) "停用该源" else "启用该源")
        items.add("编辑")
        if (!source.isFile) items.add("复制地址")
        items.add("删除")

        AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
            .setTitle(source.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "刷新该源" -> {
                        repo.upsertSource(source.copy(enabled = true))
                        onRefreshRequest?.invoke()
                    }
                    "停用该源", "启用该源" -> {
                        repo.upsertSource(source.copy(enabled = !source.enabled))
                        refresh()
                        onChanged?.invoke()
                    }
                    "编辑" -> showEditDialog(source)
                    "复制地址" -> {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("playlist", source.url))
                        Toast.makeText(context, "地址已复制", Toast.LENGTH_SHORT).show()
                    }
                    "删除" -> {
                        AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
                            .setTitle("删除播放源？")
                            .setMessage("删除后该源的频道会被移除，其它源不受影响。")
                            .setPositiveButton("删除") { _, _ ->
                                repo.removeSource(source.id)
                                refresh()
                                onChanged?.invoke()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private inner class SourceAdapter : RecyclerView.Adapter<SourceAdapter.Holder>() {

        private var items: List<Source> = emptyList()

        inner class Holder(val root: View) : RecyclerView.ViewHolder(root)

        fun submit(list: List<Source>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(inflater.inflate(R.layout.item_source, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = items[position]
            val v = holder.root
            v.findViewById<TextView>(R.id.name).text = s.name
            val state = v.findViewById<TextView>(R.id.state)
            state.text = when {
                !s.enabled -> "已停用"
                s.lastError.isNotBlank() -> "更新失败"
                else -> "${s.channelCount} 个频道"
            }
            state.setTextColor(
                when {
                    !s.enabled -> 0xFF9AA3AF.toInt()
                    s.lastError.isNotBlank() -> 0xFFD64545.toInt()
                    else -> 0xFF2F80ED.toInt()
                }
            )
            val detail = v.findViewById<TextView>(R.id.detail)
            detail.text = buildString {
                append(if (s.isFile) "本地文件" else "订阅地址")
                if (s.lastUpdate > 0) append(" · 更新于 ").append(Ui.hhmm(s.lastUpdate))
                if (s.epgUrl.isNotBlank()) append(" · 含节目单")
                if (s.lastError.isNotBlank()) append(" · ").append(s.lastError)
                append("\n").append(s.url)
            }
            v.alpha = if (s.enabled) 1f else 0.55f
            v.setOnClickListener { showSourceMenu(s) }
            Ui.focusScale(v)
        }
    }
}
