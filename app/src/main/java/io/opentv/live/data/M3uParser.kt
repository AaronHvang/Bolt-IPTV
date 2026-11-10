package io.opentv.live.data

import java.util.Locale

/**
 * m3u / m3u8 / txt 播放列表解析。
 * 支持 #EXTINF 属性（tvg-id / tvg-name / tvg-logo / group-title）以及 "频道名,地址" 的 txt 格式。
 */
object M3uParser {

    data class Result(val channels: List<Channel>, val epgUrl: String?)

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
    private val sdRegex = Regex("""(?:^|[^A-Z0-9])(SD|480[PI]|576[PI])(?:$|[^A-Z0-9])""")

    fun parse(raw: String, sourceId: String): Result {
        val text = raw.removePrefix("\uFEFF")
        val channels = ArrayList<Channel>()
        val seen = HashSet<String>()
        var epgUrl: String? = null
        var pending: Pending? = null

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttrs(line)
                    epgUrl = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending = parseExtInf(line)
                }

                line.startsWith("#") -> {
                    // 其它标签（#EXTVLCOPT 等）忽略
                }

                else -> {
                    val info = pending
                    if (info != null) {
                        pending = null
                        addChannel(channels, seen, info, line, sourceId)
                    } else {
                        // txt 格式：频道名,地址（允许后面跟多个地址，取第一个）
                        val comma = line.indexOf(',')
                        if (comma in 1 until line.length - 1) {
                            val name = line.substring(0, comma).trim()
                            val link = line.substring(comma + 1).trim().substringBefore('#').trim()
                            if (isUrl(link)) {
                                addChannel(channels, seen, Pending(name, "", name, "", ""), link, sourceId)
                            }
                        } else if (isUrl(line)) {
                            addChannel(channels, seen, Pending("频道 ${channels.size + 1}", "", "", "", ""), line, sourceId)
                        }
                    }
                }
            }
        }

        return Result(channels, epgUrl?.takeIf { it.isNotBlank() })
    }

    private fun addChannel(
        out: MutableList<Channel>,
        seen: MutableSet<String>,
        pending: Pending,
        url: String,
        sourceId: String
    ) {
        val name = cleanName(if (pending.name.isNotBlank()) pending.name else pending.tvgName)
        if (name.isBlank() || !isUrl(url)) return
        val key = name + "|" + url
        if (!seen.add(key)) return

        val group = normalizeGroup(pending.group, name)
        val id = channelId(sourceId, name, url)
        out.add(
            Channel(
                id = id,
                name = name,
                tvgId = pending.tvgId,
                logo = pending.logo,
                group = group,
                url = url,
                sourceId = sourceId
            )
        )
    }

    private fun parseExtInf(line: String): Pending {
        val attrs = parseAttrs(line)
        // 频道名称在第一个逗号之后（属性里不会有逗号）
        val name = line.substringAfter(',', "").trim()
        return Pending(
            name = name,
            tvgId = attrs["tvg-id"].orEmpty(),
            tvgName = attrs["tvg-name"].orEmpty(),
            logo = attrs["tvg-logo"].orEmpty().ifBlank { attrs["logo"].orEmpty() },
            group = attrs["group-title"].orEmpty()
        )
    }

    private fun parseAttrs(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in attrRegex.findAll(line)) {
            map[m.groupValues[1].lowercase(Locale.ROOT)] = m.groupValues[2].trim()
        }
        return map
    }

    private fun cleanName(name: String): String = name.trim().trimEnd(',').trim()

    /** 4K 频道统一归到「4K专区」，保证 4K 单独一组 */
    private fun normalizeGroup(group: String, name: String): String {
        val g = group.trim()
        val is4k = name.contains("4K", ignoreCase = true) || name.contains("UHD", ignoreCase = true)
        if (is4k && g != GROUP_4K) return GROUP_4K
        if (g.isBlank()) return Channel.DEFAULT_GROUP
        return g
    }

    private fun isUrl(v: String): Boolean {
        val s = v.trim()
        if (s.isEmpty() || s.contains(' ')) return false
        return s.startsWith("http://", true) ||
            s.startsWith("https://", true) ||
            s.startsWith("rtp://", true) ||
            s.startsWith("rtsp://", true) ||
            s.startsWith("udp://", true) ||
            s.startsWith("rtmp://", true) ||
            s.startsWith("rtmp", true) && s.contains("://") ||
            s.startsWith("/") ||
            s.startsWith("file://", true)
    }

    fun channelId(sourceId: String, name: String, url: String): String =
        Integer.toHexString((sourceId + "|" + name + "|" + url).hashCode())

    const val GROUP_4K = "4K专区"

    private data class Pending(
        val name: String,
        val tvgId: String,
        val tvgName: String,
        val logo: String,
        val group: String
    )
}
