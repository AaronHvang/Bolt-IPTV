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
    private val emptyAction: TextView

    private val channelAdapter = ChannelAdapter(context, { channel -> onPlay?.invoke(channel) }, { channel -> showChannelMenu(channel) })

    /** 后台抓首帧，给小窗口生成预览图 */
    private val capturer: PreviewCapturer? = (context as? android.app.Activity)?.let { activity ->
        PreviewCapturer(activity) { id -> channelAdapter.notifyChannel(id) }
    }

    private var selectedKey = KEY_ALL
    private var favoritesOnly = false
    private var autoFocusDone = false
    private var visibleChannels: List<Channel> = emptyList()
    private var gridSpan = 1
    /** 已经生效的排版，用于避免重复重建 LayoutManager */
    private var appliedGrid: Boolean? = null
    private var appliedSpan = 0
    /** 上次 refresh 时的分类 key，用于判断是否需要恢复滚动位置 */
    private var lastRefreshCategory: String? = null

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
        emptyAction = findViewById(R.id.emptyAction)
        emptyAction.setOnClickListener { onOpenSources?.invoke() }
        Ui.focusScale(emptyAction)

        channelList.adapter = channelAdapter
        channelList.itemAnimator = null
        channelList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                enqueueVisiblePreviews()
            }
        })
        channelList.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            enqueueVisiblePreviews()
        }

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
        val widthDp = resources.configuration.screenWidthDp
        gridSpan = if (gridMode) {
            when {
                widthDp in 1 until 720 -> 2
                widthDp < 900 -> 3
                else -> 4
            }
        } else 1
        // 排版没有变化就不要重建：换 LayoutManager 会清空滚动位置和焦点，
        // 从播放页返回时会跳回列表顶部
        if (appliedGrid == gridMode && appliedSpan == gridSpan) return
        appliedGrid = gridMode
        appliedSpan = gridSpan
        channelList.layoutManager = if (gridMode) {
            GridLayoutManager(context, gridSpan)
        } else {
            LinearLayoutManager(context)
        }
        while (channelList.itemDecorationCount > 0) {
            channelList.removeItemDecorationAt(0)
        }
        channelList.addItemDecoration(GridSpacing(gridSpan, Ui.dpi(context, 16f)))

        // 网格模式：提前算好 thumb 的 16:9 固定高度，避免每张卡 bind 时 post 改高度导致整墙重排
        if (gridMode) {
            channelList.post {
                val listWidth = channelList.width - channelList.paddingStart - channelList.paddingEnd
                val itemPadding = Ui.dpi(context, 6f) * 2  // item 根 LinearLayout 的左右 padding 各 6dp
                val spacing = Ui.dpi(context, 16f) * (gridSpan - 1)
                val itemWidth = (listWidth - spacing) / gridSpan - itemPadding
                val thumbHeight = (itemWidth * 9 / 16).coerceAtLeast(Ui.dpi(context, 80f))
                channelAdapter.setGridThumbHeight(thumbHeight)
            }
        } else {
            channelAdapter.setGridThumbHeight(0)
        }
    }

    fun requestInitialFocus() {
        channelList.post {
            if (emptyPanel.visibility == View.VISIBLE && emptyAction.visibility == View.VISIBLE) {
                emptyAction.requestFocus()
            } else {
                focusFirstChannel()
            }
        }
    }

    /** 从播放页或后台返回时，把焦点还给刚才观看的频道，必要时滚到该卡片 */
    fun restoreFocus(channelId: String?) {
        if (!isShown || visibility != View.VISIBLE) return
        if (visibleChannels.isEmpty()) {
            if (emptyAction.visibility == View.VISIBLE) emptyAction.requestFocus()
            return
        }
        val lm = channelList.layoutManager as? LinearLayoutManager ?: return
        val index = visibleChannels.indexOfFirst { it.id == channelId }
            .takeIf { it >= 0 } ?: 0
        channelList.post {
            lm.scrollToPosition(index)
            // scrollToPosition 只保证目标可见，等这一帧布局完成后再取视图
            channelList.post {
                channelList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            }
        }
    }

    /** Tab 切换回来时：LinearLayoutManager 已经保留了滚动位置，
     * 只需要把焦点还给当前可见的卡片，不要强制滚动 */
    fun focusCurrentVisible() {
        if (!isShown || visibility != View.VISIBLE) return
        if (visibleChannels.isEmpty()) {
            if (emptyAction.visibility == View.VISIBLE) emptyAction.requestFocus()
            return
        }
        val lm = channelList.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = lm.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: return
        channelList.post {
            channelList.findViewHolderForAdapterPosition(firstVisible)?.itemView?.requestFocus()
        }
    }

    /** 只抓取当前屏幕和前后一行/两项的预览，滚动后再补充新的可见卡片 */
    private fun enqueueVisiblePreviews() {
        if (!isShown || visibleChannels.isEmpty()) return
        val lm = channelList.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        val nearby = if (prefs.gridMode) gridSpan else 2
        val from = (first - nearby).coerceAtLeast(0)
        val to = (last + nearby).coerceAtMost(visibleChannels.lastIndex)
        capturer?.enqueue(visibleChannels.subList(from, to + 1))
    }

    /** 焦点是否在频道网格内；头部按钮区按上键时要回导航胶囊，网格内仍交给系统逐级上移 */
    fun isGridFocused(): Boolean = channelList.hasFocus()

    /** 页面可见时才抓预览帧；切到别的页或进入播放页立刻停下 */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE && windowVisibility == View.VISIBLE) {
            capturer?.start()
            enqueueVisiblePreviews()
        } else {
            capturer?.stop()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && this.visibility == View.VISIBLE) {
            capturer?.start()
            enqueueVisiblePreviews()
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
        // 在 submit 改变 adapter 数据之前，记住滚动位置；
        // DiffUtil.dispatchUpdatesTo 会破坏 LinearLayoutManager 的偏移
        val lm = channelList.layoutManager as? LinearLayoutManager
        val savedFirst = lm?.findFirstVisibleItemPosition() ?: -1
        val savedOffset = lm?.findViewByPosition(savedFirst)?.top ?: 0

        applyLayout()
        val groups = buildGroups()
        if (groups.none { it.key == selectedKey }) selectedKey = KEY_ALL
        refreshChannels()

        // 如果分类没变化，恢复之前的滚动位置
        if (savedFirst >= 0 && selectedKey == lastRefreshCategory) {
            channelList.post {
                val currentLm = channelList.layoutManager as? LinearLayoutManager ?: return@post
                currentLm.scrollToPositionWithOffset(savedFirst, savedOffset)
                channelList.post {
                    channelList.findViewHolderForAdapterPosition(savedFirst)?.itemView?.requestFocus()
                }
            }
        }
        lastRefreshCategory = selectedKey
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

        channelList.post { enqueueVisiblePreviews() }

        // 首次拿到频道后把焦点落到第一张卡片，避免启动时停在搜索框并弹出键盘
        if (!autoFocusDone && list.isNotEmpty()) {
            autoFocusDone = true
            channelList.post { focusFirstChannel() }
        }
        when {
            repo.sources.isEmpty() -> {
                emptyTitle.text = "还没有频道"
                emptyDesc.text = "添加 M3U 订阅地址，或导入本地播放列表后即可观看。"
            }
            repo.channels.isEmpty() && repo.hasUnfilteredChannels -> {
                emptyTitle.text = "频道已被设置过滤"
                emptyDesc.text = "当前过滤设置隐藏了全部频道，可在「设置 → 频道列表」中关闭相应过滤项。"
            }
            repo.channels.isEmpty() -> {
                emptyTitle.text = "还没有可用频道"
                emptyDesc.text = "请检查播放源是否已启用并能正常访问，然后重新更新。"
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
        emptyAction.visibility =
            if (repo.channels.isEmpty() && !repo.hasUnfilteredChannels) View.VISIBLE else View.GONE
        emptyAction.text = if (repo.sources.isEmpty()) "添加播放源" else "管理播放源"
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
