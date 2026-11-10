package io.opentv.live.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.opentv.live.App
import io.opentv.live.R
import io.opentv.live.data.Channel
import io.opentv.live.data.Program
import java.util.Calendar

/**
 * 节目指南：纵向为频道，横向为节目时间轴，可切换日期与分组。
 * 每行的横向滚动保持同步，保证时间列对齐。
 */
class EpgView(context: Context) : LinearLayout(context) {

    var onPlay: ((Channel) -> Unit)? = null
    var onRefreshRequest: (() -> Unit)? = null

    private val epg = App.get().epg
    private val repo = App.get().channels
    private val inflater = LayoutInflater.from(context)

    private val timeline: RecyclerView
    private val status: TextView
    private val dayChips: LinearLayout
    private val groupChips: LinearLayout
    private val emptyView: TextView
    private val refreshBtn: TextView

    private var dayOffset = 0
    private var group: String? = null
    private var rows: List<Channel> = emptyList()
    private val adapter = RowAdapter()

    /** 所有行共享的横向偏移 */
    private var sharedScrollX = 0
    private val syncedScrolls = ArrayList<HorizontalScrollView>()

    init {
        orientation = VERTICAL
        inflater.inflate(R.layout.view_epg, this, true)

        timeline = findViewById(R.id.timeline)
        status = findViewById(R.id.epgStatus)
        dayChips = findViewById(R.id.dayChips)
        groupChips = findViewById(R.id.groupChips)
        emptyView = findViewById(R.id.epgEmpty)
        refreshBtn = findViewById(R.id.btnRefreshEpg)

        timeline.layoutManager = LinearLayoutManager(context)
        timeline.adapter = adapter
        timeline.itemAnimator = null
        timeline.clipToPadding = false

        refreshBtn.setOnClickListener { onRefreshRequest?.invoke() }

        buildDayChips()
        buildGroupChips()
        refresh()
    }

    private fun buildDayChips() {
        dayChips.removeAllViews()
        val labels = listOf("今天", "明天", "后天", "+3", "+4", "+5", "+6")
        labels.forEachIndexed { index, label ->
            val chip = Ui.chip(context, label)
            chip.isSelected = index == dayOffset
            chip.setOnClickListener {
                dayOffset = index
                buildDayChips()
                refresh()
            }
            dayChips.addView(chip)
        }
    }

    private fun buildGroupChips() {
        groupChips.removeAllViews()
        val all = Ui.chip(context, "全部频道")
        all.isSelected = group == null
        all.setOnClickListener { group = null; buildGroupChips(); refresh() }
        groupChips.addView(all)
        repo.groups.forEach { g ->
            val chip = Ui.chip(context, g)
            chip.isSelected = group == g
            chip.setOnClickListener { group = g; buildGroupChips(); refresh() }
            groupChips.addView(chip)
        }
    }

    fun onShow() {
        refresh()
        timeline.post {
            if (rows.isNotEmpty()) timeline.scrollToPosition(currentChannelIndex())
        }
    }

    private fun currentChannelIndex(): Int {
        val idx = rows.indexOfFirst { it.id == App.get().prefs.lastChannelId }
        return if (idx >= 0) idx else 0
    }

    fun onChannelsChanged() {
        buildGroupChips()
        refresh()
    }

    fun onEpgUpdated() {
        refresh()
    }

    fun refreshNowLine() {
        adapter.notifyDataSetChanged()
    }

    fun refresh() {
        rows = if (group == null) repo.channels else repo.channels.filter { it.groupName == group }
        adapter.submit(rows, dayOffset)
        val visible = epg.hasData && rows.isNotEmpty()
        timeline.visibility = if (visible) View.VISIBLE else View.GONE
        emptyView.visibility = if (!visible) View.VISIBLE else View.GONE
        status.text = when {
            epg.hasData && rows.isEmpty() -> "当前分组没有频道"
            epg.hasData -> "${rows.size} 个频道 · 更新于 ${Ui.hhmm(epg.updatedAt)}"
            App.get().prefs.epgUrl.isBlank() -> "未配置节目单地址 · 可在「设置 → 管理播放源」中添加 XMLTV 地址"
            else -> "节目单尚未加载 · ${App.get().prefs.epgUrl}"
        }
    }

    private fun startOfDay(offset: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offset)
    }.timeInMillis

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.RowHolder>() {

        private var items: List<Channel> = emptyList()
        private var offset = 0
        private var dayStart = 0L

        inner class RowHolder(
            val root: View,
            val name: TextView,
            val scroller: HorizontalScrollView,
            val blocks: LinearLayout
        ) : RecyclerView.ViewHolder(root)

        fun submit(list: List<Channel>, dayOffset: Int) {
            items = list
            offset = dayOffset
            dayStart = startOfDay(dayOffset)
            sharedScrollX = 0
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val container = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(-1, Ui.dpi(context, 78f))
                setPadding(0, Ui.dpi(context, 3f), 0, Ui.dpi(context, 3f))
            }
            val name = TextView(context).apply {
                layoutParams = LayoutParams(Ui.dpi(context, 190f), -1)
                gravity = Gravity.CENTER_VERTICAL
                textSize = 13f
                setTextColor(0xFF171A1F.toInt())
                setPadding(Ui.dpi(context, 8f), 0, Ui.dpi(context, 10f), 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val scroller = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                layoutParams = LayoutParams(0, -1, 1f)
            }
            val blocks = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(-2, -1)
            }
            scroller.addView(blocks)
            container.addView(name)
            container.addView(scroller)
            return RowHolder(container, name, scroller, blocks)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val channel = items[position]
            holder.root.isActivated = channel.id == App.get().prefs.lastChannelId
            holder.name.text = channel.name

            if (holder.scroller !in syncedScrolls) {
                syncedScrolls.removeAll { !it.isAttachedToWindow }
                syncedScrolls.add(holder.scroller)
                holder.scroller.setOnScrollChangeListener { v, _, scrollX, _, _ ->
                    sharedScrollX = scrollX
                    syncedScrolls.forEach { other ->
                        if (other !== v && other.isAttachedToWindow) other.scrollTo(scrollX, 0)
                    }
                }
            }

            holder.blocks.removeAllViews()
            val programmes = epg.programsFor(channel)
            if (programmes.isEmpty()) {
                holder.blocks.addView(placeholder())
            } else {
                buildBlocks(holder.blocks, channel, programmes)
            }
            holder.blocks.post { holder.scroller.scrollTo(sharedScrollX, 0) }
        }

        override fun onViewRecycled(holder: RowHolder) {
            super.onViewRecycled(holder)
            syncedScrolls.remove(holder.scroller)
        }

        private fun placeholder(): View = TextView(context).apply {
            text = "暂无节目信息"
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFF9AA3AF.toInt())
            setPadding(Ui.dpi(context, 14f), 0, Ui.dpi(context, 14f), 0)
            layoutParams = LayoutParams(Ui.dpi(context, 440f), -1).apply {
                topMargin = Ui.dpi(context, 4f)
                bottomMargin = Ui.dpi(context, 4f)
            }
        }

        private fun buildBlocks(parent: LinearLayout, channel: Channel, programmes: List<Program>) {
            val dayEnd = dayStart + 24 * 3600_000L
            val now = System.currentTimeMillis()
            val inWindow = programmes.filter { it.stop > dayStart && it.start < dayEnd }.sortedBy { it.start }
            if (inWindow.isEmpty()) {
                parent.addView(placeholder())
                return
            }
            if (inWindow.first().start > dayStart) {
                parent.addView(gap((inWindow.first().start - dayStart) / 60000))
            }
            inWindow.forEachIndexed { index, p ->
                val start = maxOf(p.start, dayStart)
                val stop = minOf(p.stop, dayEnd)
                val minutes = ((stop - start) / 60000).coerceAtLeast(5)
                val width = Ui.dpi(context, minutes.toFloat() * MINUTE_DP).coerceAtLeast(Ui.dpi(context, 70f))
                val isNow = now in start until stop
                val block = TextView(context).apply {
                    text = "${Ui.hhmm(start)}  ${p.title}"
                    textSize = 12f
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                    setPadding(Ui.dpi(context, 12f), 0, Ui.dpi(context, 8f), 0)
                    isFocusable = true
                    isClickable = true
                    layoutParams = LayoutParams(width, -1).apply {
                        topMargin = Ui.dpi(context, 4f)
                        bottomMargin = Ui.dpi(context, 4f)
                        rightMargin = Ui.dpi(context, 2f)
                    }
                }
                block.setBackgroundResource(R.drawable.bg_program)
                if (isNow && !block.isFocused) {
                    block.background.setTint(0xFFDCE9FB.toInt())
                    block.setTextColor(0xFF1D4E8F.toInt())
                } else {
                    block.setTextColor(0xFF4B5563.toInt())
                }
                block.setOnClickListener { onPlay?.invoke(channel) }
                block.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    val tv = v as TextView
                    tv.setTextColor(
                        if (hasFocus) 0xFFFFFFFF.toInt()
                        else if (isNow) 0xFF1D4E8F.toInt()
                        else 0xFF4B5563.toInt()
                    )
                }
                parent.addView(block)

                val nextStart = inWindow.getOrNull(index + 1)?.start ?: dayEnd
                if (nextStart > stop) parent.addView(gap((nextStart - stop) / 60000))
            }
        }

        private fun gap(minutes: Long): View = View(context).apply {
            layoutParams = LayoutParams(Ui.dpi(context, minutes.toFloat() * MINUTE_DP), -1)
        }
    }

    companion object {
        /** 每分钟对应的 dp 宽度，一小时 = 216dp */
        private const val MINUTE_DP = 3.6f
    }
}
