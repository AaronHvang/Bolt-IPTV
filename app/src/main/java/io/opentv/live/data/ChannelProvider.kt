package io.opentv.live.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray
import java.io.File

/**
 * 只读频道接口，供启动器（Loom Launcher）的 Top Shelf 使用。
 *
 * content://com.bolt.iptv.search/channels[?limit=N]
 * 列：_id, name, category, logo_url, pkg, target_uri, number
 *
 * 数据来源为 channels.json 缓存，不依赖本进程内的 ChannelRepository 实例。
 */
class ChannelProvider : ContentProvider() {

    private data class Row(
        val name: String,
        val group: String,
        val logo: String,
        val url: String,
        val number: Int,
    )

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val ctx = context ?: return MatrixCursor(COLUMNS)
        var rows = readChannels(ctx.filesDir)

        // 关键词过滤：启动器传入 "query LIKE ?" + "%关键词%"
        selectionArgs?.firstOrNull()?.let { raw ->
            val q = raw.trim('%').trim()
            if (q.isNotEmpty()) {
                rows = rows.filter { it.name.contains(q, true) || it.group.contains(q, true) }
            }
        }

        val limit = uri.getQueryParameter("limit")?.toIntOrNull() ?: 0
        if (limit > 0 && rows.size > limit) rows = rows.take(limit)

        val cursor = MatrixCursor(COLUMNS)
        rows.forEachIndexed { i, ch ->
            cursor.addRow(
                arrayOf<Any>(
                    i + 1,
                    ch.name,
                    ch.group,
                    ch.logo,
                    ctx.packageName,
                    ch.url,
                    ch.number,
                )
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.bolt.channel"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    /** 读取本地频道缓存（与 ChannelRepository.saveCache 的 JSON 结构一致） */
    private fun readChannels(filesDir: File): List<Row> {
        val f = File(filesDir, "channels.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            ArrayList<Row>(arr.length()).apply {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name")
                    val url = o.optString("url")
                    if (name.isBlank() || url.isBlank()) continue
                    add(
                        Row(
                            name = name,
                            group = o.optString("group"),
                            logo = o.optString("logo"),
                            url = url,
                            number = o.optInt("number", i + 1),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private val COLUMNS = arrayOf("_id", "name", "category", "logo_url", "pkg", "target_uri", "number")
    }
}
