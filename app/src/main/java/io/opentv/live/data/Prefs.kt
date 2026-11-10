package io.opentv.live.data

import android.content.Context

/**
 * 应用设置。全部用 SharedPreferences 持久化，不依赖任何账号或会员。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("freetv", Context.MODE_PRIVATE)

    /** 启动时自动更新播放源 */
    var autoUpdateOnStart: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(value) = sp.edit().putBoolean("auto_update", value).apply()

    /** 启动时自动更新节目单 */
    var autoUpdateEpg: Boolean
        get() = sp.getBoolean("auto_update_epg", true)
        set(value) = sp.edit().putBoolean("auto_update_epg", value).apply()

    /** 过滤标清源（1080P 源不够用时可以关掉） */
    var filterSd: Boolean
        get() = sp.getBoolean("filter_sd", true)
        set(value) = sp.edit().putBoolean("filter_sd", value).apply()

    /** 过滤 AVS/AVS2 编码源 */
    var filterAvs: Boolean
        get() = sp.getBoolean("filter_avs", true)
        set(value) = sp.edit().putBoolean("filter_avs", value).apply()

    /** 换台时保留上一画面 */
    var keepLastFrame: Boolean
        get() = sp.getBoolean("keep_last_frame", true)
        set(value) = sp.edit().putBoolean("keep_last_frame", value).apply()

    /** 硬解优先，失败回落软解 */
    var hardwareFirst: Boolean
        get() = sp.getBoolean("hw_first", true)
        set(value) = sp.edit().putBoolean("hw_first", value).apply()

    /** 频道列表是否网格模式 */
    var gridMode: Boolean
        get() = sp.getBoolean("grid_mode", true)
        set(value) = sp.edit().putBoolean("grid_mode", value).apply()

    /** 上次选中的分组（空串表示"全部"） */
    var selectedGroup: String
        get() = sp.getString("selected_group", "") ?: ""
        set(value) = sp.edit().putString("selected_group", value).apply()

    /** 上次播放的频道 */
    var lastChannelId: String
        get() = sp.getString("last_channel", "") ?: ""
        set(value) = sp.edit().putString("last_channel", value).apply()

    /** 当前生效的 EPG 地址（来自播放源或手动设置） */
    var epgUrl: String
        get() = sp.getString("epg_url", "") ?: ""
        set(value) = sp.edit().putString("epg_url", value).apply()

    /** 默认请求头，用于部分需要 UA/Referer 的源 */
    var defaultUserAgent: String
        get() = sp.getString("ua", DEFAULT_UA) ?: DEFAULT_UA
        set(value) = sp.edit().putString("ua", value).apply()

    var defaultReferer: String
        get() = sp.getString("referer", "") ?: ""
        set(value) = sp.edit().putString("referer", value).apply()

    /** 播放源配置（JSON） */
    var sourcesJson: String
        get() = sp.getString("sources", "") ?: ""
        set(value) = sp.edit().putString("sources", value).apply()

    val favorites: Set<String>
        get() = sp.getStringSet("favorites", emptySet())?.toHashSet() ?: HashSet()

    fun isFavorite(channelId: String): Boolean = favorites.contains(channelId)

    /** 返回收藏后的状态 */
    fun toggleFavorite(channelId: String): Boolean {
        val set = HashSet(favorites)
        val added = if (set.contains(channelId)) {
            set.remove(channelId)
            false
        } else {
            set.add(channelId)
            true
        }
        sp.edit().putStringSet("favorites", set).apply()
        return added
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
