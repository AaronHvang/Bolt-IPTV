package io.opentv.live.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.opentv.live.App
import io.opentv.live.R
import io.opentv.live.data.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null

    private lateinit var content: android.widget.FrameLayout
    private lateinit var navPill: android.widget.FrameLayout
    private lateinit var navCursor: View
    private lateinit var navItems: List<LinearLayout>
    /** 顶部导航游标的滑动动画，连续切换时先取消上一段 */
    private var cursorAnim: ValueAnimator? = null

    private lateinit var channelView: ChannelListView
    private lateinit var epgView: EpgView
    private lateinit var sourcesView: SourcesView
    private lateinit var settingsView: SettingsView

    private var currentTab = 0
    /** 切页过程中页面隐藏会把焦点踢回导航栏，这时的焦点变化不应再触发自动切页 */
    private var suppressAutoSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        content = findViewById(R.id.content)
        navPill = findViewById(R.id.navPill)
        navCursor = findViewById(R.id.navCursor)
        navItems = listOf(
            findViewById(R.id.navChannels),
            findViewById(R.id.navFavorites),
            findViewById(R.id.navEpg),
            findViewById(R.id.navSettings)
        )

        channelView = ChannelListView(this).apply {
            onPlay = { openPlayer(it) }
            onRefreshRequest = { refreshAll(manual = true) }
            onOpenSources = { switchTab(TAB_SOURCES) }
        }
        epgView = EpgView(this).apply {
            onPlay = { openPlayer(it) }
            onRefreshRequest = { refreshEpg(manual = true) }
        }
        sourcesView = SourcesView(this).apply {
            onClose = { closeSources() }
            onRefreshRequest = { refreshAll(manual = true) }
            onChanged = {
                channelView.refresh()
                epgView.onChannelsChanged()
            }
        }
        settingsView = SettingsView(this).apply {
            onChanged = {
                App.get().channels.reloadFilters()
                channelView.refresh()
                epgView.onChannelsChanged()
            }
            onOpenSources = { switchTab(TAB_SOURCES) }
            onRefreshRequest = { refreshAll(manual = true); refreshEpg(manual = true) }
        }

        listOf(channelView, epgView, sourcesView, settingsView).forEach {
            it.visibility = View.GONE
            content.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
        }

        buildNav()
        switchTab(TAB_CHANNELS)
        // 首次布局完成后把游标放到当前页，此后只做平滑滑动
        navPill.post { updateNavCursor(animate = false) }

        // 首次进入：没有缓存时立即拉一次；有缓存时后台静默更新
        val repo = App.get().channels
        if (repo.channels.isEmpty()) {
            refreshAll(manual = false)
        } else if (App.get().prefs.autoUpdateOnStart) {
            refreshAll(manual = false, silent = true)
        }
        if (App.get().prefs.autoUpdateEpg && App.get().prefs.epgUrl.isNotBlank()) {
            refreshEpg(manual = false, silent = true)
        }
        App.get().epg.let { if (!it.loaded) it.loadCache() }
    }

    override fun onResume() {
        super.onResume()
        if (::channelView.isInitialized) channelView.refresh()
        if (::epgView.isInitialized) epgView.refreshNowLine()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun buildNav() {
        val tabs = listOf(TAB_CHANNELS, TAB_FAVORITES, TAB_EPG, TAB_SETTINGS)
        navItems.forEachIndexed { index, item ->
            item.setOnClickListener { switchTab(tabs[index]) }
            // 焦点（光标）移到哪一项就直接切到对应页面，不需要再按确定键
            item.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) switchTab(tabs[index], fromNav = true)
            }
        }
    }

    /** 游标按当前激活项的位置和宽度滑动，效果参考胶囊滑块 */
    private fun updateNavCursor(animate: Boolean) {
        val index = navItems.indexOfFirst { it.isActivated }
        if (index < 0) return
        val target = navItems[index]
        // 首次布局还没完成，等 onCreate 里的 post 定位
        if (target.width == 0) return
        val endX = (target.parent as View).left + target.left
        val endW = target.width

        cursorAnim?.cancel()
        if (!animate || navCursor.width == 0) {
            applyCursor(endX.toFloat(), endW.toFloat())
            return
        }
        val startX = navCursor.x
        val startW = navCursor.width.toFloat()
        cursorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                applyCursor(
                    startX + (endX - startX) * f,
                    startW + (endW - startW) * f
                )
            }
            start()
        }
    }

    private fun applyCursor(x: Float, width: Float) {
        navCursor.layoutParams = navCursor.layoutParams.apply { this.width = width.toInt() }
        navCursor.x = x
    }

    private fun switchTab(tab: Int, fromNav: Boolean = false) {
        if (tab == currentTab) return
        if (fromNav && suppressAutoSwitch) return
        suppressAutoSwitch = true
        currentTab = tab
        channelView.setFavoritesOnly(tab == TAB_FAVORITES)
        channelView.visibility =
            if (tab == TAB_CHANNELS || tab == TAB_FAVORITES) View.VISIBLE else View.GONE
        epgView.visibility = if (tab == TAB_EPG) View.VISIBLE else View.GONE
        sourcesView.visibility = if (tab == TAB_SOURCES) View.VISIBLE else View.GONE
        settingsView.visibility = if (tab == TAB_SETTINGS) View.VISIBLE else View.GONE

        navItems.forEachIndexed { index, item ->
            item.isActivated = index + 1 == tab || (tab == TAB_SOURCES && index + 1 == TAB_SETTINGS)
        }
        updateNavCursor(animate = navCursor.width > 0)
        content.post { suppressAutoSwitch = false }

        // 光标只是在导航栏上划过：内容已切换，但不要把焦点抢到列表里
        if (fromNav) return

        when (tab) {
            TAB_CHANNELS, TAB_FAVORITES -> channelView.requestInitialFocus()
            TAB_EPG -> epgView.onShow()
            TAB_SOURCES -> sourcesView.refresh()
            TAB_SETTINGS -> settingsView.refresh()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 播放源已并入设置，返回键回到设置页
        if (currentTab == TAB_SOURCES) {
            closeSources()
            return
        }
        super.onBackPressed()
    }

    private fun closeSources() {
        switchTab(TAB_SETTINGS)
        // 焦点落回「设置」，否则页面隐藏后焦点会掉到「频道」上把页面又切走
        navItems.getOrNull(3)?.requestFocus()
    }

    private fun openPlayer(channel: Channel) {
        App.get().prefs.lastChannelId = channel.id
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        })
    }

    private fun refreshAll(manual: Boolean, silent: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (!silent) showStatus("正在更新播放源…")
            val result = App.get().channels.refreshAll { msg ->
                // 进度回调发生在 IO 线程，切回主线程再更新 UI
                runOnUiThread { if (!silent) showStatus(msg) }
            }
            channelView.refresh()
            sourcesView.refresh()
            epgView.onChannelsChanged()
            if (!silent) {
                when {
                    result.ok > 0 && result.failed == 0 -> showStatus("已更新 ${App.get().channels.channels.size} 个频道")
                    result.ok > 0 -> showStatus("${result.ok} 个源更新成功，${result.failed} 个失败（已保留旧列表）")
                    else -> showStatus("更新失败：${App.get().channels.sources.firstOrNull()?.lastError.orEmpty()}")
                }
            }
        }
    }

    private fun refreshEpg(manual: Boolean, silent: Boolean = false) {
        scope.launch {
            if (!silent) showStatus("正在更新节目单…")
            val ok = App.get().epg.refresh(force = manual)
            epgView.onEpgUpdated()
            channelView.refresh()
            if (!silent) {
                showStatus(if (ok) "节目单已更新" else "节目单更新失败：${App.get().epg.lastError}")
            }
        }
    }

    private fun showStatus(msg: String) {
        if (msg.isBlank()) return
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAB_CHANNELS = 1
        private const val TAB_FAVORITES = 2
        private const val TAB_EPG = 3
        private const val TAB_SETTINGS = 4
        private const val TAB_SOURCES = 5
    }
}
