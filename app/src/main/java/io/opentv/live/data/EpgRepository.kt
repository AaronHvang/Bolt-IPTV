package io.opentv.live.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * XMLTV 电子节目单。支持远程 URL 与本地文件，解析结果落盘缓存。
 */
class EpgRepository(private val context: Context, private val prefs: Prefs) {

    /** xmltv channel id -> 节目列表（按开始时间升序） */
    private var programmes: MutableMap<String, List<Program>> = HashMap()

    /** 归一化频道名 -> xmltv channel id */
    private var aliases: MutableMap<String, String> = HashMap()

    /** xmltv channel id -> 显示名 */
    private var displayNames: MutableMap<String, String> = HashMap()

    var loaded: Boolean = false
        private set
    var updatedAt: Long = 0L
        private set
    var lastError: String = ""
        private set

    private val cacheFile: File get() = File(context.filesDir, "epg.json")

    fun loadCache() {
        val f = cacheFile
        if (!f.exists()) {
            loaded = false
            return
        }
        runCatching {
            val root = JSONObject(f.readText())
            updatedAt = root.optLong("updatedAt")
            val ids = root.optJSONObject("channels") ?: JSONObject()
            val map = HashMap<String, List<Program>>()
            val keys = ids.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val arr = ids.getJSONArray(id)
                val list = ArrayList<Program>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val channelId = o.optString("c")
                    list.add(
                        Program(
                            channelKey = channelId,
                            title = o.getString("t"),
                            desc = o.optString("d"),
                            start = o.getLong("s"),
                            stop = o.getLong("e")
                        )
                    )
                }
                list.sortBy { it.start }
                map[id] = list
            }
            programmes = map

            val alias = HashMap<String, String>()
            val al = root.optJSONObject("alias") ?: JSONObject()
            val ak = al.keys()
            while (ak.hasNext()) {
                val k = ak.next()
                alias[k] = al.getString(k)
            }
            aliases = alias

            val dn = HashMap<String, String>()
            val dl = root.optJSONObject("names") ?: JSONObject()
            val dk = dl.keys()
            while (dk.hasNext()) {
                val k = dk.next()
                dn[k] = dl.getString(k)
            }
            displayNames = dn
            loaded = programmes.isNotEmpty()
        }.onFailure {
            loaded = false
            lastError = "节目单缓存损坏"
        }
    }

    val hasData: Boolean get() = loaded && programmes.isNotEmpty()

    suspend fun refresh(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val url = prefs.epgUrl.trim()
        if (url.isBlank()) {
            lastError = "未设置节目单地址"
            return@withContext false
        }
        if (!force && hasData && System.currentTimeMillis() - updatedAt < STALE_MS) return@withContext true

        return@withContext try {
            val stream = open(url)?.let { maybeGzip(url, it) }
            if (stream == null) {
                lastError = "无法打开节目单"
                false
            } else {
                val parsed = stream.use { parse(it) }
                if (parsed.programmes.isEmpty()) {
                    lastError = "节目单为空"
                    false
                } else {
                    programmes = parsed.programmes
                    aliases = parsed.aliases
                    displayNames = parsed.displayNames
                    updatedAt = System.currentTimeMillis()
                    lastError = ""
                    loaded = true
                    saveCache()
                    true
                }
            }
        } catch (e: Exception) {
            lastError = e.message ?: "节目单解析失败"
            false
        }
    }

    private fun open(url: String): java.io.InputStream? {
        if (url.startsWith("content://") || url.startsWith("/") || url.startsWith("file://")) {
            val u = url.removePrefix("file://")
            return if (u.startsWith("content://")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(u))
            } else {
                File(u).inputStream()
            }
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", prefs.defaultUserAgent)
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP ${conn.responseCode}")
        }
        return conn.inputStream
    }

    /** 兼容 .gz 节目单：按地址后缀或文件头判断，必要时包一层 GZIPInputStream */
    private fun maybeGzip(url: String, stream: java.io.InputStream): java.io.InputStream {
        if (url.lowercase().endsWith(".gz")) return GZIPInputStream(stream)
        return stream
    }

    private data class Parsed(
        val programmes: MutableMap<String, List<Program>>,
        val aliases: MutableMap<String, String>,
        val displayNames: MutableMap<String, String>
    )

    private fun parse(input: java.io.InputStream): Parsed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val map = HashMap<String, MutableList<Program>>()
        val alias = HashMap<String, String>()
        val names = HashMap<String, String>()
        val horizonStart = System.currentTimeMillis() - 12 * 3600_000L
        val horizonEnd = System.currentTimeMillis() + 7 * 24 * 3600_000L

        var event = parser.eventType
        var curChannelId: String? = null
        var curProgChannel: String? = null
        var curStart = 0L
        var curStop = 0L
        var curTitle: String? = null
        var curDesc: String? = null
        var inDisplayName = false
        var inTitle = false
        var inDesc = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> curChannelId = parser.getAttributeValue(null, "id")
                        "display-name" -> inDisplayName = curChannelId != null
                        "programme" -> {
                            curProgChannel = parser.getAttributeValue(null, "channel")
                            curStart = parseTime(parser.getAttributeValue(null, "start")) ?: 0L
                            curStop = parseTime(parser.getAttributeValue(null, "stop")) ?: 0L
                            curTitle = null
                            curDesc = null
                        }
                        "title" -> inTitle = curProgChannel != null
                        "desc" -> inDesc = curProgChannel != null
                    }
                }
                XmlPullParser.TEXT -> {
                    val txt = parser.text?.trim().orEmpty()
                    if (txt.isEmpty()) {
                        // 忽略空白
                    } else if (inDisplayName && curChannelId != null) {
                        names[curChannelId!!] = txt
                        alias[normalize(txt)] = curChannelId!!
                    } else if (inTitle) {
                        curTitle = txt
                    } else if (inDesc) {
                        if (curDesc == null) curDesc = txt
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "display-name" -> inDisplayName = false
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                        "channel" -> curChannelId = null
                        "programme" -> {
                            val ch = curProgChannel
                            val title = curTitle
                            if (ch != null && title != null && curStart > 0) {
                                val stop = if (curStop > curStart) curStop else curStart + 30 * 60_000L
                                if (stop > horizonStart && curStart < horizonEnd) {
                                    map.getOrPut(ch) { ArrayList() }.add(
                                        Program(ch, title, curDesc.orEmpty(), curStart, stop)
                                    )
                                }
                            }
                            curProgChannel = null
                        }
                    }
                }
            }
            event = parser.next()
        }

        val fixed = HashMap<String, List<Program>>()
        map.forEach { (k, v) ->
            v.sortBy { it.start }
            fixed[k] = v
        }
        // 没有 tvg-id 的频道靠名称兜底
        names.forEach { (id, dn) -> alias[normalize(dn)] = id }
        return Parsed(fixed, alias, names)
    }

    private fun parseTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
        val core = cleaned.substringBefore(' ').trim()
        val zone = cleaned.substringAfter(' ', "").trim()
        return runCatching {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            if (zone.matches(Regex("""[+-]\d{4}"""))) {
                fmt.timeZone = TimeZone.getTimeZone("GMT$zone")
            }
            fmt.parse(core)?.time
        }.getOrNull()
    }

    /** 归一化：去掉分辨率后缀、空格与常见修饰，便于和 xmltv 显示名匹配 */
    fun normalize(name: String): String {
        var s = name.lowercase(Locale.ROOT)
        s = s.replace(Regex("""\s+"""), "")
        s = s.replace(Regex("""1080[pi]|720[pi]|2160[pi]|480[pi]|576[pi]|4k|uhd|高清|超高清|标清|超清|高码"""), "")
        s = s.replace("-", "").replace("_", "").replace("·", "").replace(".", "")
        return s
    }

    fun programsFor(channel: Channel): List<Program> {
        val keys = buildList {
            if (channel.tvgId.isNotBlank()) add(channel.tvgId)
            resolveAlias(channel)?.let { add(it) }
        }
        for (k in keys) {
            programmes[k]?.let { return it }
        }
        return emptyList()
    }

    private fun resolveAlias(channel: Channel): String? {
        aliases[normalize(channel.name)]?.let { return it }
        // 去掉「综合/频道」等后缀再试一次
        val n2 = normalize(channel.name).replace("综合", "").replace("频道", "").replace("高清", "")
        aliases[n2]?.let { return it }
        return null
    }

    /** 当前节目与下一个节目 */
    fun nowNext(channel: Channel): Pair<Program?, Program?> {
        val list = programsFor(channel)
        if (list.isEmpty()) return null to null
        val now = System.currentTimeMillis()
        val idx = list.indexOfFirst { it.stop > now && it.start <= now }
            .let { if (it < 0) list.indexOfFirst { it.start > now } else it }
        if (idx < 0) return null to null
        val cur = list.getOrNull(idx)
        val next = list.getOrNull(idx + 1)
        return cur to next
    }

    fun programsForDay(channel: Channel, dayOffset: Int): List<Program> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }
        val start = cal.timeInMillis
        val end = start + 24 * 3600_000L
        return programsFor(channel).filter { it.stop > start && it.start < end }
    }

    private fun saveCache() {
        runCatching {
            val root = JSONObject()
            root.put("updatedAt", updatedAt)
            val chObj = JSONObject()
            programmes.forEach { (id, list) ->
                val arr = JSONArray()
                list.forEach { p ->
                    arr.put(
                        JSONObject()
                            .put("c", p.channelKey)
                            .put("t", p.title)
                            .put("d", p.desc)
                            .put("s", p.start)
                            .put("e", p.stop)
                    )
                }
                chObj.put(id, arr)
            }
            root.put("channels", chObj)
            val alObj = JSONObject()
            aliases.forEach { (k, v) -> alObj.put(k, v) }
            root.put("alias", alObj)
            val dnObj = JSONObject()
            displayNames.forEach { (k, v) -> dnObj.put(k, v) }
            root.put("names", dnObj)
            cacheFile.writeText(root.toString())
        }
    }

    companion object {
        private const val STALE_MS = 6 * 3600_000L
    }
}
