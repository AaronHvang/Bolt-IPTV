package io.opentv.live.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.opentv.live.App
import io.opentv.live.BuildConfig
import io.opentv.live.R

/**
 * 设置页：左侧插画，右侧分组卡片列表。
 */
class SettingsView(context: Context) : LinearLayout(context) {

    var onChanged: (() -> Unit)? = null
    var onOpenSources: (() -> Unit)? = null
    var onRefreshRequest: (() -> Unit)? = null

    private val prefs = App.get().prefs
    private val container: LinearLayout
    private lateinit var uncleAnim: ImageView
    private var enterAnim: AnimationDrawable? = null
    private var thinkAnim: AnimationDrawable? = null

    private var rowColor: Int = 0xFF4C8DFF.toInt()
    private var rowGlyph: String = "设"

    private companion object {
        /** 弹入动画总时长：15 帧 × 139ms */
        const val ENTER_DURATION_MS = 15L * 139L
    }

    init {
        orientation = HORIZONTAL
        android.view.LayoutInflater.from(context).inflate(R.layout.view_settings, this, true)
        container = findViewById(R.id.settingsContainer)
        uncleAnim = findViewById(R.id.uncleAnim)
        refresh()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) syncUncleAnim()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncUncleAnim()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncUncleAnim()
    }

    override fun onDetachedFromWindow() {
        stopUncle()
        super.onDetachedFromWindow()
    }

    /** 弹入动画播完（15 帧 × 139ms）后切到思考循环 */
    private val toThinking = Runnable {
        enterAnim?.stop()
        enterAnim = null
        val think =
            ContextCompat.getDrawable(context, R.drawable.anim_uncle_think) as? AnimationDrawable
                ?: return@Runnable
        thinkAnim = think
        uncleAnim.setImageDrawable(think)
        if (isShown && isAttachedToWindow) think.start()
    }

    private fun playUncleEnter() {
        uncleAnim.removeCallbacks(toThinking)
        thinkAnim?.stop()
        thinkAnim = null
        val enter =
            ContextCompat.getDrawable(context, R.drawable.anim_uncle) as? AnimationDrawable ?: return
        enterAnim = enter
        uncleAnim.setImageDrawable(enter)
        enter.start()
        uncleAnim.postDelayed(toThinking, ENTER_DURATION_MS)
    }

    private fun stopUncle() {
        uncleAnim.removeCallbacks(toThinking)
        enterAnim?.stop()
        thinkAnim?.stop()
        enterAnim = null
        thinkAnim = null
    }

    /** 只在设置页可见时播放：每次进入先弹入一次，然后循环思考 */
    private fun syncUncleAnim() {
        if (isShown && isAttachedToWindow) {
            if (enterAnim == null && thinkAnim == null) {
                playUncleEnter()
            } else if (thinkAnim?.isRunning == false) {
                thinkAnim?.start()
            }
        } else if (enterAnim != null || thinkAnim != null) {
            stopUncle()
        }
    }

    fun refresh() {
        container.removeAllViews()
        val repo = App.get().channels

        group("节目单", 0xFFE8875A.toInt(), "单")
        row("节目单地址", if (prefs.epgUrl.isBlank()) "未设置" else "已设置") { showEpgDialog() }
        row("立即更新节目单", "最近 7 天") { onRefreshRequest?.invoke() }

        group("频道列表", 0xFF4C8DFF.toInt(), "道")
        switchRow("隐藏标清 / 低清晰度频道", "只保留 720P 以上", prefs.filterSd) { checked ->
            prefs.filterSd = checked
            applyFilterAndNotify()
        }
        switchRow("隐藏 AVS / AVS2 编码频道", "多数盒子无法硬解", prefs.filterAvs) { checked ->
            prefs.filterAvs = checked
            applyFilterAndNotify()
        }
        switchRow("频道网格视图", "关闭后使用单列列表", prefs.gridMode) { checked ->
            prefs.gridMode = checked
            onChanged?.invoke()
            refresh()
        }
        row("管理播放源", "${repo.sources.size} 个源") { onOpenSources?.invoke() }

        group("更新", 0xFF4FB286.toInt(), "更")
        switchRow("启动时更新播放源", "失败保留旧列表", prefs.autoUpdateOnStart) { checked ->
            prefs.autoUpdateOnStart = checked
        }
        switchRow("启动时更新节目单", "需已设置节目单地址", prefs.autoUpdateEpg) { checked ->
            prefs.autoUpdateEpg = checked
        }

        group("播放", 0xFF9A73E6.toInt(), "播")
        switchRow("换台保留上一画面", "切换时不闪黑屏", prefs.keepLastFrame) { checked ->
            prefs.keepLastFrame = checked
            Toast.makeText(context, "下次进入播放页生效", Toast.LENGTH_SHORT).show()
        }
        switchRow("下一频道预热", "提前缓冲下一台，关闭可减少流量", prefs.preloadNext) { checked ->
            prefs.preloadNext = checked
            refresh()
        }
        row("默认 UA / Referer", if (prefs.defaultReferer.isNotBlank()) "已自定义" else "默认") { showUaDialog() }

        group("关于", 0xFFE05C6E.toInt(), "i")
        row("版本", "v${BuildConfig.VERSION_NAME}", chevron = false)
        row("播放引擎", "Media3 / ExoPlayer", chevron = false)
        row("开源说明", "独立实现 · 无会员", chevron = false)

        container.addView(TextView(context).apply {
            text = "全部功能免费 · ${repo.channels.size} 个频道"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_disabled))
            setPadding(0, Ui.dpi(context, 18f), 0, 0)
        })
    }

    private fun group(title: String, color: Int, glyph: String) {
        rowColor = color
        rowGlyph = glyph
        container.addView(TextView(context).apply {
            text = title
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(Ui.dpi(context, 8f), Ui.dpi(context, 18f), 0, Ui.dpi(context, 8f))
        })
        container.addView(LinearLayout(context).apply {
            orientation = VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_card)
            clipToOutline = true
            setPadding(Ui.dpi(context, 6f), Ui.dpi(context, 6f), Ui.dpi(context, 6f), Ui.dpi(context, 6f))
            layoutParams = LayoutParams(-1, -2)
        })
    }

    private fun currentCard(): LinearLayout = container.getChildAt(container.childCount - 1) as LinearLayout

    private fun row(
        title: String,
        value: String,
        chevron: Boolean = true,
        action: (() -> Unit)? = null
    ) {
        val card = currentCard()
        if (card.childCount > 0) card.addView(divider())
        val v = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_setting_row)
            setPadding(Ui.dpi(context, 10f), 0, Ui.dpi(context, 12f), 0)
            layoutParams = LayoutParams(-1, Ui.dpi(context, 52f))
            isFocusable = action != null
            isClickable = action != null
            if (action != null) setOnClickListener { action() }
        }
        v.addView(iconCircle())
        v.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LayoutParams(0, -2, 1f).apply { marginStart = Ui.dpi(context, 14f) }
        })
        v.addView(TextView(context).apply {
            text = value
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            maxWidth = Ui.dpi(context, 150f)
        })
        if (chevron) {
            v.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                layoutParams = LayoutParams(Ui.dpi(context, 16f), Ui.dpi(context, 16f)).apply {
                    marginStart = Ui.dpi(context, 6f)
                }
            })
        }
        card.addView(v)
    }

    private fun switchRow(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val card = currentCard()
        if (card.childCount > 0) card.addView(divider())
        val v = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_setting_row)
            setPadding(Ui.dpi(context, 10f), 0, Ui.dpi(context, 12f), 0)
            layoutParams = LayoutParams(-1, Ui.dpi(context, 52f))
            isFocusable = true
            isClickable = true
            setOnClickListener { onToggle(!checked) }
        }
        v.addView(iconCircle())
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, -2, 1f).apply { marginStart = Ui.dpi(context, 14f) }
        }
        texts.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        texts.addView(TextView(context).apply {
            text = subtitle
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_disabled))
            setPadding(0, Ui.dpi(context, 2f), 0, 0)
        })
        v.addView(texts)
        v.addView(TextView(context).apply {
            text = if (checked) "已开启" else "已关闭"
            textSize = 12f
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (checked) R.color.accent else R.color.text_disabled
                )
            )
        })
        card.addView(v)
    }

    private fun iconCircle(): TextView = TextView(context).apply {
        text = rowGlyph
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        background = Ui.rounded(rowColor, Ui.dp(context, 11f))
        layoutParams = LayoutParams(Ui.dpi(context, 22f), Ui.dpi(context, 22f))
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
        layoutParams = LayoutParams(-1, Ui.dpi(context, 1f)).apply {
            marginStart = Ui.dpi(context, 46f)
            marginEnd = Ui.dpi(context, 8f)
        }
    }

    private fun applyFilterAndNotify() {
        App.get().channels.reloadFilters()
        onChanged?.invoke()
        refresh()
    }

    private fun showEpgDialog() {
        val input = EditTextFactory.urlInput(context, prefs.epgUrl)
        AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
            .setTitle("节目单（XMLTV）地址")
            .setView(EditTextFactory.wrap(context, input, "支持 http/https 的 xmltv 地址，留空表示不使用节目单"))
            .setPositiveButton("保存") { _, _ ->
                prefs.epgUrl = input.text.toString().trim()
                App.get().epg.loadCache()
                onChanged?.invoke()
                refresh()
                if (prefs.epgUrl.isNotBlank()) onRefreshRequest?.invoke()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showUaDialog() {
        val ua = EditTextFactory.urlInput(context, prefs.defaultUserAgent)
        val ref = EditTextFactory.urlInput(context, prefs.defaultReferer)
        val box = EditTextFactory.wrap(
            context, ua, "默认 User-Agent",
            ref, "默认 Referer（可留空）"
        )
        AlertDialog.Builder(context, R.style.Theme_FreeTV_Dialog)
            .setTitle("默认请求头")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                prefs.defaultUserAgent = ua.text.toString().trim().ifBlank {
                    io.opentv.live.data.Prefs.DEFAULT_UA
                }
                prefs.defaultReferer = ref.text.toString().trim()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

/** 简单输入框构造工具，减少重复代码 */
private object EditTextFactory {

    fun urlInput(context: Context, value: String): EditText =
        EditText(context).apply {
            setText(value)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setBackgroundResource(R.drawable.bg_tab)
            maxLines = 1
            setSingleLine(true)
            setPadding(Ui.dpi(context, 14f), Ui.dpi(context, 10f), Ui.dpi(context, 14f), Ui.dpi(context, 10f))
        }

    fun wrap(
        context: Context,
        input1: View,
        caption1: String,
        input2: View? = null,
        caption2: String? = null
    ): LinearLayout {
        val pad = Ui.dpi(context, 22f)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dpi(context, 12f), pad, 0)
            addView(TextView(context).apply {
                text = caption1
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, 0, 0, Ui.dpi(context, 6f))
            })
            addView(input1, LinearLayout.LayoutParams(-1, -2))
            if (input2 != null) {
                addView(TextView(context).apply {
                    text = caption2.orEmpty()
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(0, Ui.dpi(context, 14f), 0, Ui.dpi(context, 6f))
                })
                addView(input2, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }
}
