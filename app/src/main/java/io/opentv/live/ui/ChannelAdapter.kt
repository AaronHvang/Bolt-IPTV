package io.opentv.live.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.opentv.live.App
import io.opentv.live.R
import io.opentv.live.data.Channel

/**
 * 频道卡片适配器，支持网格与列表两种排版。
 */
class ChannelAdapter(
    private val context: Context,
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    private var items: List<Channel> = emptyList()
    private var grid: Boolean = true
    private var favorites: Set<String> = emptySet()
    private val epg = App.get().epg
    private val inflater = LayoutInflater.from(context)
    /** 网格模式下 thumb 固定高度（dp 换算后的像素值），由外部在 applyLayout 时计算 */
    private var gridThumbHeight = 0

    class Holder(val root: View) : RecyclerView.ViewHolder(root)

    /** 网格尺寸变化时更新 thumb 高度，避免每个卡片绑定后 post 改高度导致重排 */
    fun setGridThumbHeight(px: Int) {
        if (gridThumbHeight == px) return
        gridThumbHeight = px
        if (grid) notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Channel>, grid: Boolean, favorites: Set<String>) {
        val oldItems = items
        val oldFavorites = this.favorites
        this.items = list
        this.favorites = favorites
        // 网格/列表切换会改变条目类型，只能整体重建
        if (this.grid != grid) {
            this.grid = grid
            notifyDataSetChanged()
            return
        }
        // 差量刷新：切换分类、收藏或设置时只更新变化的卡片，避免整墙重绘和焦点丢失
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size

            override fun getNewListSize(): Int = list.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldItems[oldPos].id == list[newPos].id

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val new = list[newPos]
                return oldItems[oldPos] == new &&
                    oldFavorites.contains(new.id) == favorites.contains(new.id)
            }
        })
        diff.dispatchUpdatesTo(this)
    }

    fun itemAt(position: Int): Channel? = items.getOrNull(position)

    /** 预览图抓好后刷新对应卡片 */
    fun notifyChannel(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = if (grid) TYPE_GRID else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == TYPE_GRID) R.layout.item_channel_grid else R.layout.item_channel_row
        val holder = Holder(inflater.inflate(layout, parent, false))
        // 网格卡片的 thumb 高度在创建时就固定好，避免 bind 时 post 改高度导致整墙重排
        if (viewType == TYPE_GRID && gridThumbHeight > 0) {
            holder.root.findViewById<View>(R.id.thumb).layoutParams =
                holder.root.findViewById<View>(R.id.thumb).layoutParams.apply { height = gridThumbHeight }
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val c = items[position]
        if (grid) {
            bindGrid(holder.root, c)
        } else {
            bindRow(holder.root, c)
        }

        holder.root.setOnClickListener { onClick(c) }
        holder.root.setOnLongClickListener {
            onLongClick(c)
            true
        }
        Ui.focusScale(holder.root, if (grid) 1.04f else 1.02f)
    }

    private fun bindGrid(v: View, c: Channel) {
        val faved = favorites.contains(c.id)
        val shortName = Ui.shortName(c.name)
        v.findViewById<TextView>(R.id.name).text = Ui.displayName(c.name)
        v.findViewById<TextView>(R.id.logo).apply {
            text = shortName
            background = Ui.logoBackground(context, c.name)
        }
        v.findViewById<View>(R.id.thumb).apply {
            background = Ui.thumbBackground(context, c.name)
            Ui.clipRound(this, context)
        }
        v.findViewById<TextView>(R.id.fav).visibility = if (faved) View.VISIBLE else View.GONE

        // 小窗口优先显示抓到的实时画面预览，其次是播放源台标，最后回落到首字色块
        val letter = v.findViewById<TextView>(R.id.thumbLetter)
        val thumbLogo = v.findViewById<ImageView>(R.id.thumbLogo)
        letter.text = shortName
        val preview = PreviewStore.get(c.id)
        if (preview != null) {
            thumbLogo.tag = null
            thumbLogo.setPadding(0, 0, 0, 0)
            thumbLogo.scaleType = ImageView.ScaleType.CENTER_CROP
            thumbLogo.setImageBitmap(preview)
            thumbLogo.visibility = View.VISIBLE
            letter.visibility = View.GONE
        } else {
            val pad = Ui.dpi(context, 10f)
            thumbLogo.setPadding(pad, pad, pad, pad)
            thumbLogo.scaleType = ImageView.ScaleType.FIT_CENTER
            thumbLogo.visibility = View.GONE
            letter.visibility = View.VISIBLE
            if (c.logo.isNotBlank()) {
                LogoLoader.load(context, c.logo, thumbLogo) { ok ->
                    thumbLogo.visibility = if (ok) View.VISIBLE else View.GONE
                    letter.visibility = if (ok) View.INVISIBLE else View.VISIBLE
                }
            } else {
                thumbLogo.tag = null
            }
        }

        val badge = v.findViewById<TextView>(R.id.badge)
        val res = Ui.resolutionTag(c.name)
        badge.visibility = if (res.isBlank()) View.GONE else View.VISIBLE
        badge.text = res

        val title = v.findViewById<TextView>(R.id.thumbTitle)
        val (now, _) = epg.nowNext(c)
        title.text = when {
            now != null -> "${Ui.hhmm(now.start)}  ${now.title}"
            epg.hasData -> "暂无节目信息"
            else -> c.groupName
        }
    }

    private fun bindRow(v: View, c: Channel) {
        val faved = favorites.contains(c.id)
        v.findViewById<TextView>(R.id.name).text = Ui.displayName(c.name)
        v.findViewById<TextView>(R.id.number).text = String.format("%03d", c.number)

        val tag = v.findViewById<TextView>(R.id.resTag)
        val res = Ui.resolutionTag(c.name)
        tag.visibility = if (res.isBlank()) View.GONE else View.VISIBLE
        tag.text = res

        v.findViewById<TextView>(R.id.logo).apply {
            text = Ui.shortName(c.name)
            background = Ui.logoBackground(context, c.name)
        }
        v.findViewById<TextView>(R.id.fav).visibility = if (faved) View.VISIBLE else View.GONE

        val epgText = v.findViewById<TextView>(R.id.epgText)
        val (now, next) = epg.nowNext(c)
        epgText.text = when {
            now != null -> "${Ui.hhmm(now.start)}  ${now.title}"
            next != null -> "接下来 ${Ui.hhmm(next.start)}  ${next.title}"
            epg.hasData -> "暂无节目信息"
            else -> c.groupName
        }
    }

    companion object {
        private const val TYPE_GRID = 0
        private const val TYPE_ROW = 1
    }
}

/** 网格间距 */
class GridSpacing(private val spanCount: Int, private val space: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val column = position % spanCount
        outRect.left = if (column == 0) 0 else space / 2
        outRect.right = if (column == spanCount - 1) 0 else space / 2
        outRect.top = space / 2
        outRect.bottom = space / 2
    }
}
