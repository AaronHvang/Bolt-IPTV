package io.opentv.live.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.opentv.live.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Ui {

    fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    fun dpi(context: Context, value: Float): Int = dp(context, value).toInt()

    fun hhmm(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    fun dateLabel(millis: Long): String =
        SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(Date(millis))

    fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

    /** 台标底色：按频道名生成稳定的颜色 */
    fun logoBackground(context: Context, name: String): GradientDrawable {
        val palette = intArrayOf(
            0xFF1D5C80.toInt(), 0xFF2A6E5C.toInt(), 0xFF694F86.toInt(),
            0xFF7A4A38.toInt(), 0xFF2C5D9B.toInt(), 0xFF8A5A2B.toInt(),
            0xFF39636F.toInt(), 0xFF6B3B54.toInt()
        )
        val color = palette[(name.hashCode() and Int.MAX_VALUE) % palette.size]
        return rounded(color, dp(context, 8f))
    }

    private val thumbPalette = arrayOf(
        intArrayOf(0xFF89A7C2.toInt(), 0xFF4B6C8C.toInt()),
        intArrayOf(0xFF93AE9B.toInt(), 0xFF587E68.toInt()),
        intArrayOf(0xFFB79E86.toInt(), 0xFF87654A.toInt()),
        intArrayOf(0xFF9A9AC0.toInt(), 0xFF66669B.toInt()),
        intArrayOf(0xFFB79B9B.toInt(), 0xFF8A5F66.toInt()),
        intArrayOf(0xFF87AAA6.toInt(), 0xFF527B77.toInt()),
        intArrayOf(0xFFA9A08B.toInt(), 0xFF7A6F55.toInt()),
        intArrayOf(0xFF8C9EB8.toInt(), 0xFF5D6E8C.toInt())
    )

    /** 全站统一圆角半径（与顶部导航游标一致） */
    const val CORNER_DP = 16f

    /** 频道缩略图占位渐变：按频道名生成稳定的双色块 */
    fun thumbBackground(context: Context, name: String): GradientDrawable {
        val pair = thumbPalette[(name.hashCode() and Int.MAX_VALUE) % thumbPalette.size]
        return GradientDrawable(GradientDrawable.Orientation.BR_TL, pair).apply {
            cornerRadius = dp(context, CORNER_DP)
        }
    }

    /** 按指定圆角裁切子视图，确保缩略图 / 预览图四角都被切圆 */
    fun clipRound(view: View, context: Context, radius: Float = CORNER_DP) {
        val radiusPx = dp(context, radius)
        view.clipToOutline = true
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.invalidateOutline()
    }

    /** 台标文字缩写：CCTV-1综合 -> C1，广东卫视 -> 广东卫 */
    fun shortName(name: String): String {
        val cctv = Regex("""(?i)cctv[-\s]?(\d+\+?|4k|8k)""").find(name)
        if (cctv != null) return "C" + cctv.groupValues[1].uppercase(Locale.ROOT)
        val clean = name.trim().replace(Regex("""\s+"""), "")
        return if (clean.length <= 3) clean else clean.take(3)
    }

    fun chip(context: Context, label: String): TextView {
        val colors = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_selected),
                intArrayOf()
            ),
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF4B5563.toInt())
        )
        return TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(colors)
            setBackgroundResource(R.drawable.bg_tab)
            isFocusable = true
            isClickable = true
            includeFontPadding = false
            setPadding(dpi(context, 16f), dpi(context, 9f), dpi(context, 16f), dpi(context, 9f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpi(context, 8f) }
        }
    }

    fun actionButton(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_focused),
                        intArrayOf()
                    ),
                    intArrayOf(0xFFFFFFFF.toInt(), 0xFF3C4450.toInt())
                )
            )
            setBackgroundResource(R.drawable.bg_player_action)
            isFocusable = true
            isClickable = true
            includeFontPadding = false
            setPadding(dpi(context, 16f), dpi(context, 9f), dpi(context, 16f), dpi(context, 9f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpi(context, 10f) }
        }

    fun label(context: Context, text: String, size: Float, color: Int): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }

    fun sectionTitle(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dpi(context, 4f), dpi(context, 18f), 0, dpi(context, 8f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    fun focusScale(view: View, scale: Float = 1.03f) {
        view.setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) scale else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(140).start()
            v.elevation = if (hasFocus) dp(v.context, 10f) else 0f
        }
    }

    fun parseColor(hex: String): Int = Color.parseColor(hex)

    private val resSuffix = Regex("""\s*[（(]?\s*(1080[PIpi]|720[PIpi]|2160[PIpi]|4K|8K|UHD|高清|超高清|标清|HD)\s*[)）]?\s*$""")

    /** 去掉频道名末尾的分辨率后缀，避免卡片里被截断 */
    fun displayName(name: String): String {
        val stripped = name.replace(resSuffix, "").trim()
        return stripped.ifBlank { name }
    }

    /** 分辨率标签，用于和同名不同分辨率的频道区分 */
    fun resolutionTag(name: String): String {
        val m = resSuffix.find(name) ?: return ""
        val raw = m.groupValues[1]
        return when {
            raw.contains("4K", true) || raw.contains("2160", true) -> "4K"
            raw.contains("1080", true) -> "1080P"
            raw.contains("720", true) -> "720P"
            raw.contains("标清", true) -> "标清"
            raw.contains("高清", true) || raw.contains("HD", true) -> "高清"
            else -> raw
        }
    }
}
