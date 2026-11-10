package io.opentv.live.data

/** 一个直播频道 */
data class Channel(
    val id: String,
    val name: String,
    val tvgId: String,
    val logo: String,
    val group: String,
    val url: String,
    val sourceId: String,
    val number: Int = 0
) {
    val groupName: String
        get() = group.ifBlank { DEFAULT_GROUP }

    companion object {
        const val DEFAULT_GROUP = "未分组"
    }
}

/** 播放源。kind = url 表示远程订阅地址，file 表示本地导入的文件 */
data class Source(
    val id: String,
    val name: String,
    val url: String,
    val kind: String = KIND_URL,
    val epgUrl: String = "",
    val userAgent: String = "",
    val referer: String = "",
    /** 自定义请求头，每行 "Key: Value" */
    val headers: String = "",
    val enabled: Boolean = true,
    val autoUpdate: Boolean = true,
    val channelCount: Int = 0,
    val lastUpdate: Long = 0L,
    val lastError: String = ""
) {
    val isFile: Boolean get() = kind == KIND_FILE

    companion object {
        const val KIND_URL = "url"
        const val KIND_FILE = "file"
    }
}

/** 节目单中的一条节目 */
data class Program(
    val channelKey: String,
    val title: String,
    val desc: String,
    val start: Long,
    val stop: Long
)
