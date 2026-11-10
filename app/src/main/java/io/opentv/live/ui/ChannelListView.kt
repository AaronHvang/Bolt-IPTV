package io.opentv.live.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.opentv.live.App
import io.opentv.live.R
import io.opentv.live.data.Channel

/**
 * 频道页：顶部标题 + 分类下拉 + 搜索/随机/刷新，下方为 4 列缩略图网格。
 */
class ChannelListView(context: Context) : LinearLayout(context) {

    var onPlay: ((Channel) -> Unit)? = null
    var onRefreshRequest: (() -> Unit)? = null
    var onOpenSources: (() -> Unit)? = null

    private val repo = App.get().channels
    private val prefs = App.get().prefs

    private val channelList: RecyclerView
    private val title: TextView
    private val subtitle: TextView
    private val btnCategory: TextView
    private val emptyPanel: View
    private val emptyTitle: TextView
    private val emptyDesc: TextView

    private val channelAdapter = ChannelAdapter(context, { channel -> onPlay?.invoke(channel) }, { channel -> showChannelMenu(channel) })

    /** 后台抓首帧，给小窗口生成预览图 */
    private val capturer: PreviewCapturer? = (context as? android.app.Activity)?.let { activity ->
        PreviewCapturer(activity) { id -> channelAdapter.notifyChannel(id) }
    }

    private var selectedKey = KEY_ALL
    private var favoritesOnly = false
    private var autoFocusDone = false
    private var visibleChannels: List<Channel> = emptyList()

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_channel_list, this, true)

        channelList = findViewById(R.id.channelList)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        btnCategory = findViewById(R.id.btnCategory)
        emptyPanel = findViewById(R.id.emptyPanel)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptyDesc = findViewById(R.id.emptyDesc)

        channelList.adapter = channelAdapter
        channelList.itemAnimator = null

        btnCategory.setOnClickListener { showCategoryDialog() }
        findViewById<TextView>(R.id.btnRandom).setOnClickListener {
            visibleChannels.randomOrNull()?.let { onPlay?.invoke(it) }
                ?: Toast.makeText(context, "当前分类没有频道", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageView>(R.id.btnRefresh).setOnClickListener { onRefreshRequest?.invoke() }
        Ui.focusScale(findViewById(R.id.btnRandom))
        Ui.focusScale(findViewById(R.id.btnCategory))

        refresh()
    }

    private fun applyLayout() {
        val gridMode = prefs.gridMode
        val span = if (gridMode) 4 else 1
        channelList.layoutManager = if (gridMode) {
            GridLayoutManager(context, span)
        } else {
            LinearLayoutManager(context)
        }
        while (channelList.itemDecorationCount > 0) {
            channelList.removeItemDecorationAt(0)
        }
        channelList.addItemDecoration(GridSpacing(span, Ui.dpi(context, 16f)))
    }

    fun requestInitialFocus() {
        channelList.post { focusFirstChannel() }
    }

    /** 页面可见时才抓预览帧；切到别的页或进入播放页立刻停下 */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE && windowVisibility == View.VISIBLE) {
            capturer?.start()
        } else {
            capturer?.stop()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && this.visibility == View.VISIBLE) {
            capturer?.start()
        } else {
            capturer?.stop()
        }
    }

    override fun onDetachedFromWindow() {
        capturer?.release()
        super.onDetachedFromWindow()
    }

    private fun focusFirstChannel(retry: Int = 3) {
        val lm = channelList.layoutManager as? LinearLayoutManager ?: return
        lm.scrollToPosition(0)
        channelList.post {
            val target = lm.findViewByPosition(0) ?: channelList.getChildAt(0)
            if (target != null) {
                target.requestFocus()
            } else if (retry > 0) {
                // 首次显示时列表可能还没完成布局，焦点会落空，稍后重试
                channelList.postDelayed({ focusFirstChannel(retry - 1) }, 120)
            }
        }
    }

    private fun showCategoryDialog() {
        val groups = buildGroups()
        val labels = groups.map { "${it.label}（${it.count}）" }.toTypedArray()
        val checked = groups.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
            .setTitle("频道分类")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                selectedKey = groups[which].key
                refresh()
                channelList.post { focusFirstChannel() }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 收藏已独立成顶部导航页，这里只切换数据来源 */
    fun setFavoritesOnly(on: Boolean) {
        if (favoritesOnly == on) return
        favoritesOnly = on
        selectedKey = KEY_ALL
        refresh()
    }

    /** 数据变化后整体刷新（刷新播放源、收藏变化、设置变化时调用） */
    fun refresh() {
        applyLayout()
        val groups = buildGroups()
        if (groups.none { it.key == selectedKey }) selectedKey = KEY_ALL
        refreshChannels()
    }

    private fun buildGroups(): List<GroupEntry> {
        val out = ArrayList<GroupEntry>()
        out.add(GroupEntry(KEY_ALL, "全部频道", repo.channels.size))
        repo.groups.forEach { g ->
            val count = repo.channels.count { it.groupName == g }
            if (count > 0) out.add(GroupEntry(g, g, count))
        }
        return out
    }

    private fun refreshChannels() {
        val group = when (selectedKey) {
            KEY_ALL -> null
            else -> selectedKey
        }
        val list = repo.channelsIn(group, favoritesOnly, "")
        visibleChannels = list

        channelAdapter.submit(list, prefs.gridMode, prefs.favorites)

        title.text = when {
            favoritesOnly -> "我的收藏"
            selectedKey == KEY_ALL -> "全部频道"
            else -> selectedKey
        }
        btnCategory.visibility = if (favoritesOnly) View.GONE else View.VISIBLE
        btnCategory.text = "频道分类：" + if (selectedKey == KEY_ALL) "全部频道" else selectedKey
        subtitle.text = if (favoritesOnly) "共 ${list.size} 个收藏" else "共 ${list.size} 个频道"

        val empty = list.isEmpty()
        emptyPanel.visibility = if (empty) View.VISIBLE else View.GONE
        channelList.visibility = if (empty) View.INVISIBLE else View.VISIBLE

        capturer?.enqueue(list)

        // 首次拿到频道后把焦点落到第一张卡片，避免启动时停在搜索框并弹出键盘
        if (!autoFocusDone && list.isNotEmpty()) {
            autoFocusDone = true
            channelList.post { focusFirstChannel() }
        }
        when {
            repo.channels.isEmpty() -> {
                emptyTitle.text = "还没有频道"
                emptyDesc.text = "在「设置 → 管理播放源」里添加 M3U 订阅地址，或导入本地播放列表后即可观看。"
            }
            favoritesOnly -> {
                emptyTitle.text = "还没有收藏"
                emptyDesc.text = "在频道上长按遥控器确认键，选择「收藏」即可加入收藏夹。"
            }
            else -> {
                emptyTitle.text = "这个分类还没有频道"
                emptyDesc.text = "换一个频道分类看看，或者到设置里更新播放源。"
            }
        }
    }

    private fun showChannelMenu(channel: Channel) {
        val faved = prefs.isFavorite(channel.id)
        val items = arrayOf(
            "播放",
            if (faved) "取消收藏" else "收藏该频道",
            "复制频道地址",
            "频道信息"
        )
        AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
            .setTitle(channel.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> onPlay?.invoke(channel)
                    1 -> {
                        val added = prefs.toggleFavorite(channel.id)
                        Toast.makeText(context, if (added) "已加入收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    2 -> {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("channel", channel.url))
                        Toast.makeText(context, "地址已复制", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        val (now, next) = App.get().epg.nowNext(channel)
                        val msg = buildString {
                            append("频道号：").append(String.format("%03d", channel.number)).append("\n")
                            append("分组：").append(channel.groupName).append("\n")
                            append("地址：").append(channel.url).append("\n")
                            if (now != null) append("\n正在播出：").append(now.title)
                            if (next != null) append("\n接下来：").append(next.title)
                        }
                        AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
                            .setTitle("频道信息")
                            .setMessage(msg)
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                }
            }
            .show()
    }
}

/** 分类条目 */
data class GroupEntry(val key: String, val label: String, val count: Int)

const val KEY_ALL = "__all__"
