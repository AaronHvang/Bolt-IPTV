package io.opentv.live.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 播放源仓库：负责订阅源的增删改、拉取、解析、本地缓存与合并后的频道列表。
 * 拉取失败时保留上一次的缓存，不会出现列表被清空的情况。
 */
class ChannelRepository(private val context: Context, private val prefs: Prefs) {

    /** 合并后的频道（已过滤） */
    var channels: List<Channel> = emptyList()
        private set

    /** 分组顺序（按首次出现顺序，4K 专区固定排在最后） */
    var groups: List<String> = emptyList()
        private set

    var sources: List<Source> = emptyList()
        private set

    /** 是否存在被过滤设置隐藏的频道，用于给出准确的空列表提示 */
    val hasUnfilteredChannels: Boolean get() = rawChannels.isNotEmpty()

    var lastEpgUrl: String = ""
        private set

    private val channelsFile: File get() = File(context.filesDir, "channels.json")

    /** 未经过滤的完整频道列表，用于切换过滤开关后立即生效 */
    private var rawChannels: List<Channel> = emptyList()

    // ---------------- 源管理 ----------------

    fun loadSources() {
        val json = prefs.sourcesJson
        if (json.isBlank()) {
            sources = emptyList()
            return
        }
        val parsed = runCatching { parseSources(json) }.getOrDefault(emptyList())
        // 老版本自带的内置演示源已经移除，这里顺手清掉遗留项，避免它一直报错
        val cleaned = parsed.filterNot { it.id == LEGACY_BUILTIN_ID || it.url.startsWith("asset://") }
        sources = cleaned
        if (cleaned.size != parsed.size) saveSources()
    }

    fun saveSources() {
        prefs.sourcesJson = buildSourcesJson(sources)
    }

    fun upsertSource(source: Source) {
        val idx = sources.indexOfFirst { it.id == source.id }
        sources = if (idx >= 0) {
            sources.toMutableList().also { it[idx] = source }
        } else {
            sources + source
        }
        saveSources()
    }

    fun removeSource(id: String) {
        sources = sources.filterNot { it.id == id }
        saveSources()
        File(context.filesDir, "pl_$id.m3u").delete()
    }

    // ---------------- 缓存 ----------------

    fun loadCache() {
        loadSources()
        val f = channelsFile
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            val list = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Channel(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        tvgId = o.optString("tvgId"),
                        logo = o.optString("logo"),
                        group = o.optString("group"),
                        url = o.getString("url"),
                        sourceId = o.optString("sourceId"),
                        number = o.optInt("number")
                    )
                )
            }
            applyChannels(list, persist = false)
            lastEpgUrl = prefs.epgUrl
        }
    }

    /** 过滤设置变化后重新应用 */
    fun reloadFilters() {
        applyChannels(rawChannels, persist = false)
    }

    private fun applyChannels(list: List<Channel>, persist: Boolean = true) {
        rawChannels = list
        val filtered = list.filter { c ->
            val name = c.name
            !(prefs.filterSd && isSd(name)) && !(prefs.filterAvs && isAvs(name))
        }.mapIndexed { i, c -> c.copy(number = i + 1) }

        channels = filtered
        val ordered = LinkedHashSet<String>()
        filtered.forEach { ordered.add(it.groupName) }
        val head = ordered.filter { it != M3uParser.GROUP_4K }
        groups = if (ordered.contains(M3uParser.GROUP_4K)) head + M3uParser.GROUP_4K else head
        if (persist) saveCache()
    }

    private fun saveCache() {
        runCatching {
            val arr = JSONArray()
            channels.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("tvgId", c.tvgId)
                        .put("logo", c.logo)
                        .put("group", c.group)
                        .put("url", c.url)
                        .put("sourceId", c.sourceId)
                        .put("number", c.number)
                )
            }
            channelsFile.writeText(arr.toString())
        }
    }

    // ---------------- 刷新 ----------------

    data class RefreshResult(val ok: Int, val failed: Int, val updated: List<Source>, val epgUrl: String)

    suspend fun refreshAll(onProgress: (String) -> Unit = {}): RefreshResult =
        withContext(Dispatchers.IO) {
            loadSources()
            val merged = ArrayList<Channel>()
            var ok = 0
            var failed = 0
            var epgUrl = ""
            val updated = ArrayList<Source>()

            sources.forEachIndexed { index, src ->
                if (!src.enabled) {
                    // 未启用但已有缓存时仍然展示旧数据
                    readSourceCache(src.id)?.let { merged.addAll(it) }
                    updated.add(src)
                    return@forEachIndexed
                }
                onProgress("正在更新 ${src.name} …")
                val result = fetchAndParse(src)
                if (result.channels.isNotEmpty()) {
                    ok++
                    writeSourceCache(src.id, result.text)
                    if (epgUrl.isBlank() && !result.epgUrl.isNullOrBlank()) epgUrl = result.epgUrl!!
                    merged.addAll(result.channels)
                    updated.add(
                        src.copy(
                            channelCount = result.channels.size,
                            lastUpdate = System.currentTimeMillis(),
                            lastError = "",
                            epgUrl = src.epgUrl.ifBlank { result.epgUrl.orEmpty() }
                        )
                    )
                } else {
                    failed++
                    // 失败保留旧列表
                    readSourceCache(src.id)?.let { merged.addAll(it) }
                    updated.add(
                        src.copy(
                            lastError = result.error.ifBlank { "获取失败" },
                            lastUpdate = System.currentTimeMillis()
                        )
                    )
                }
            }

            sources = updated
            saveSources()
            if (epgUrl.isNotBlank() && prefs.epgUrl.isBlank()) prefs.epgUrl = epgUrl
            lastEpgUrl = prefs.epgUrl
            applyChannels(merged)
            onProgress("")
            RefreshResult(ok, failed, updated, epgUrl)
        }

    private data class ParseOutcome(
        val channels: List<Channel>,
        val text: String,
        val error: String,
        val epgUrl: String?
    )

    private suspend fun fetchAndParse(src: Source): ParseOutcome {
        return try {
            val text = readSourceText(src)
            if (text.isBlank()) return ParseOutcome(emptyList(), "", "内容为空", null)
            val parsed = M3uParser.parse(text, src.id)
            if (parsed.channels.isEmpty()) {
                ParseOutcome(emptyList(), text, "解析到 0 个频道", parsed.epgUrl)
            } else {
                ParseOutcome(parsed.channels, text, "", parsed.epgUrl)
            }
        } catch (e: Exception) {
            ParseOutcome(emptyList(), "", e.message ?: "网络错误", null)
        }
    }

    private fun readSourceText(src: Source): String {
        val raw = src.url.trim()
        if (raw.startsWith("content://")) {
            return context.contentResolver.openInputStream(Uri.parse(raw))?.bufferedReader()
                ?.use { it.readText() } ?: throw IllegalException("无法读取本地文件")
        }
        if (raw.startsWith("/") || raw.startsWith("file://")) {
            val path = raw.removePrefix("file://")
            return File(path).readText()
        }
        return httpGet(raw, src.userAgent, src.referer, src.headers)
    }

    private fun httpGet(url: String, userAgent: String, referer: String, headers: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 25000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent.ifBlank { prefs.defaultUserAgent })
            if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "*/*")
            parseHeaders(headers).forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun writeSourceCache(id: String, text: String) {
        runCatching { File(context.filesDir, "pl_$id.m3u").writeText(text) }
    }

    private fun readSourceCache(id: String): List<Channel>? {
        val f = File(context.filesDir, "pl_$id.m3u")
        if (!f.exists()) return null
        return runCatching { M3uParser.parse(f.readText(), id).channels }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // ---------------- 查询 ----------------

    fun channelsIn(group: String?, favoritesOnly: Boolean, query: String): List<Channel> {
        val fav = if (favoritesOnly) prefs.favorites else null
        var list = channels
        if (group != null && group.isNotBlank() && group != GROUP_ALL) {
            list = list.filter { it.groupName == group }
        }
        if (fav != null) list = list.filter { fav.contains(it.id) }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase(Locale.ROOT)
            list = list.filter {
                it.name.lowercase(Locale.ROOT).contains(q) ||
                    it.groupName.lowercase(Locale.ROOT).contains(q) ||
                    it.number.toString() == q
            }
        }
        return list
    }

    fun findById(id: String): Channel? = channels.firstOrNull { it.id == id }

    fun indexOf(channel: Channel): Int = channels.indexOfFirst { it.id == channel.id }

    private fun isSd(name: String): Boolean {
        if (name.contains("1080") || name.contains("4K", true) || name.contains("超高清") || name.contains("高清")) return false
        return sdRegex.containsMatchIn(name) || name.contains("标清")
    }

    private fun isAvs(name: String): Boolean = name.contains("AVS", ignoreCase = true)

    private class IllegalException(message: String) : Exception(message)

    // ---------------- JSON ----------------

    private fun buildSourcesJson(list: List<Source>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("url", s.url)
                    .put("kind", s.kind)
                    .put("epgUrl", s.epgUrl)
                    .put("userAgent", s.userAgent)
                    .put("referer", s.referer)
                    .put("headers", s.headers)
                    .put("enabled", s.enabled)
                    .put("autoUpdate", s.autoUpdate)
                    .put("channelCount", s.channelCount)
                    .put("lastUpdate", s.lastUpdate)
                    .put("lastError", s.lastError)
            )
        }
        return arr.toString()
    }

    private fun parseSources(json: String): List<Source> {
        val arr = JSONArray(json)
        val out = ArrayList<Source>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Source(
                    id = o.getString("id"),
                    name = o.optString("name", "播放源"),
                    url = o.getString("url"),
                    kind = o.optString("kind", Source.KIND_URL),
                    epgUrl = o.optString("epgUrl"),
                    userAgent = o.optString("userAgent"),
                    referer = o.optString("referer"),
                    headers = o.optString("headers"),
                    enabled = o.optBoolean("enabled", true),
                    autoUpdate = o.optBoolean("autoUpdate", true),
                    channelCount = o.optInt("channelCount"),
                    lastUpdate = o.optLong("lastUpdate"),
                    lastError = o.optString("lastError")
                )
            )
        }
        return out
    }

    companion object {
        const val GROUP_ALL = "全部频道"

        /** 老版本内置演示源的固定 id，仅用于升级时清理 */
        private const val LEGACY_BUILTIN_ID = "builtin"

        /** 解析 "Key: Value" 每行一条的自定义请求头 */
        fun parseHeaders(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            val out = HashMap<String, String>()
            raw.lineSequence().forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
                }
            }
            return out
        }

        private val sdRegex = Regex("""(^|[^A-Za-z0-9])(SD|480[PI]|576[PI]|360[Pp])($|[^A-Za-z0-9])""")
    }
}
