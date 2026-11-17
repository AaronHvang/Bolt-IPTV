package io.opentv.live.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.opentv.live.App
import io.opentv.live.BuildConfig
import io.opentv.live.R
import io.opentv.live.data.Channel
import io.opentv.live.data.ChannelRepository
import io.opentv.live.data.Program
import io.opentv.live.player.FreeTVRenderersFactory
import java.util.Locale

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private val repo = App.get().channels
    private val prefs = App.get().prefs
    private val epg = App.get().epg

    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var errorText: TextView
    private lateinit var bottomBar: View
    private lateinit var channelLogo: TextView
    private lateinit var channelNumber: TextView
    private lateinit var channelName: TextView
    private lateinit var channelGroup: TextView
    private lateinit var clock: TextView
    private lateinit var dateText: TextView
    private lateinit var techInfo: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowTime: TextView
    private lateinit var progress: ProgressBar
    private lateinit var sidePanel: View
    private lateinit var sideList: RecyclerView
    private lateinit var sideCats: LinearLayout
    /** 二级菜单第一级：当前选中的频道分类，null 表示全部 */
    private var sideGroup: String? = null
    private var sideGroupInited = false
    /** 分类芯片只在首次打开时构建，之后仅刷新选中态 */
    private var sideCatsBuilt = false
    private lateinit var numberBadge: TextView
    private lateinit var toastText: TextView
    private lateinit var actions: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val sideAdapter = SideAdapter()

    private var current: Channel? = null
    private var overlayVisible = false
    private var sideVisible = false
    /** 换台发起时间戳，用来判断 loading 是否应该闪出来 */
    private var tuneStartMs = 0L
    /** loading 真正 show 的时间戳，用来保证显示最少持续一会儿 */
    private var loadingShownAt = 0L
    /** 预热接管时标记，跳过 STATE_BUFFERING 闪一下 */
    private var warmTune = false
    /** 换台原因：决定 STATE_BUFFERING 时要不要显示 loading overlay */
    private enum class TuneReason { USER_SWITCH, STARTUP, ERROR_RETRY }
    private var tuneReason = TuneReason.STARTUP
    private var retryCount = 0
    private var digits = StringBuilder()
    private var aspectIndex = 0
    /** 确认键按下后焦点会移到侧栏或按钮区，抬键不能再点击新焦点 */
    private var consumeCenterUp = false

    /** 后台备机：提前把下一个频道的流缓冲好，换台时直接接管，省掉等关键帧的时间 */
    private var spare: ExoPlayer? = null
    private var spareChannelId: String? = null

    private val preloadTask = Runnable { startPreload() }

    /** 挂在当前活动播放器上；换台接管时要在新旧播放器间转移 */
    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    // 预热接管时如果备机还差一丢丢到 READY，跳过这次 BUFFERING 的闪一下
                    if (warmTune) return
                    if (tuneReason == TuneReason.USER_SWITCH) {
                        // 用户主动换台：保持上一帧，永远不弹 loading overlay
                        errorText.visibility = View.GONE
                        toastText.visibility = View.GONE
                    } else {
                        // 首次启动 / 错误重试：没有上一帧可保持，500ms 后弹 loading 避免纯黑屏
                        val sinceTune = android.os.SystemClock.elapsedRealtime() - tuneStartMs
                        handler.removeCallbacks(loadingDelayedCheck)
                        if (sinceTune < 500) {
                            handler.postDelayed(loadingDelayedCheck, 500 - sinceTune)
                        } else {
                            showLoading()
                        }
                        errorText.visibility = View.GONE
                        toastText.visibility = View.GONE
                    }
                }
                Player.STATE_READY -> {
                    warmTune = false
                    hideLoading()
                    errorText.visibility = View.GONE
                    retryCount = 0
                    if (BuildConfig.DEBUG && tuneStartMs > 0) {
                        val cost = android.os.SystemClock.elapsedRealtime() - tuneStartMs
                        android.util.Log.d(
                            "FreeTV",
                            if (warmTune) "预热接管 ${cost}ms" else "换台起播 ${cost}ms"
                        )
                    }
                    updateBars()
                    dumpTracks()
                    // 起播稳定后再去预热下一个台，避免抢带宽/CPU
                    schedulePreload()
                }
                Player.STATE_ENDED -> {
                    hideLoading()
                    showToast("直播信号已断开，正在重连…")
                    handler.postDelayed({ current?.let { tune(it, false, TuneReason.ERROR_RETRY) } }, 1500)
                }
            }
        }

        override fun onRenderedFirstFrame() {
            if (BuildConfig.DEBUG && tuneStartMs > 0) {
                val cost = android.os.SystemClock.elapsedRealtime() - tuneStartMs
                android.util.Log.d(
                    "FreeTV",
                    (if (warmTune) "预热首帧 " else "首帧显示 ") + "${cost}ms"
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            hideLoading()
            if (retryCount < 2) {
                retryCount++
                showToast("连接中断，正在重试（$retryCount/2）")
                handler.postDelayed({ current?.let { tune(it, false, TuneReason.ERROR_RETRY) } }, 1200)
            } else {
                showError(error)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        hideSystemUi()

        playerView = findViewById(R.id.player)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        errorText = findViewById(R.id.errorText)
        bottomBar = findViewById(R.id.bottomBar)
        channelLogo = findViewById(R.id.channelLogo)
        channelNumber = findViewById(R.id.channelNumber)
        channelName = findViewById(R.id.channelName)
        channelGroup = findViewById(R.id.channelGroup)
        clock = findViewById(R.id.clock)
        dateText = findViewById(R.id.dateText)
        techInfo = findViewById(R.id.techInfo)
        nowTitle = findViewById(R.id.nowTitle)
        nowTime = findViewById(R.id.nowTime)
        progress = findViewById(R.id.progress)
        sidePanel = findViewById(R.id.sidePanel)
        sideList = findViewById(R.id.sideList)
        sideCats = findViewById(R.id.sideCats)
        numberBadge = findViewById(R.id.numberBadge)
        toastText = findViewById(R.id.toastText)
        actions = findViewById(R.id.actions)

        sideList.layoutManager = LinearLayoutManager(this)
        sideList.adapter = sideAdapter
        sideList.itemAnimator = null

        playerView.setKeepContentOnPlayerReset(prefs.keepLastFrame)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        // 触屏设备单击画面唤起/收起控制条；侧栏打开时单击画面只关闭侧栏
        playerView.setOnClickListener {
            when {
                sideVisible -> hideSide()
                overlayVisible -> hideBars()
                else -> showBars(focusActions = false)
            }
        }
        // 侧栏保持电视端的 400dp 上限，窄屏则使用扣除边距后的可用宽度
        sidePanel.layoutParams = sidePanel.layoutParams.apply {
            width = minOf(
                Ui.dpi(this@PlayerActivity, 400f),
                resources.displayMetrics.widthPixels - Ui.dpi(this@PlayerActivity, 32f)
            )
        }

        player = buildPlayer()
        playerView.player = player
        player.addListener(playerListener)

        buildActions()
        hideBars()

        val id = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        val target = repo.findById(id)
            ?: repo.findById(prefs.lastChannelId)
            ?: repo.channels.firstOrNull()
        if (target == null) {
            hideLoading()
            errorText.text = "还没有可用频道，请先在「设置 → 管理播放源」里添加播放列表。"
            errorText.visibility = View.VISIBLE
        } else {
            tune(target, false, TuneReason.STARTUP)
        }

        handler.post(object : Runnable {
            override fun run() {
                updateClock()
                updateEpgProgress()
                handler.postDelayed(this, 20_000)
            }
        })
    }

    private fun buildActions() {
        actions.removeAllViews()
        listOf(
            "频道列表" to { toggleSide() },
            "音轨" to { showAudioTracks() },
            "画面比例" to { cycleAspect() },
            "播放信息" to { showPlayInfo() },
            "收藏" to { toggleFavorite() },
            "刷新" to { current?.let { tune(it, true, TuneReason.STARTUP) } }
        ).forEach { (label, action) ->
            val tv = Ui.actionButton(this, label)
            tv.setOnClickListener { action() }
            actions.addView(tv)
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val renderers = FreeTVRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val loadControl = DefaultLoadControl.Builder()
            // 换台要快：攒够 800ms 就开始播（实测再低反而更慢，瓶颈在等首个关键帧）
            .setBufferDurationsMs(800, 25_000, 400, 600)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .build()
    }

    /** 备机只需要把流缓冲到含关键帧即可，缓冲目标小一些，避免抢带宽和内存 */
    private fun buildSpare(): ExoPlayer {
        val renderers = FreeTVRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(6000, 10_000, 1000, 2000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .build()
    }

    /** 按频道构造媒体源；组播原始地址等不支持的返回 null */
    private fun createMediaSource(channel: Channel): MediaSource? {
        val url = channel.url.trim()
        if (url.startsWith("rtp://") || url.startsWith("udp://") || url.startsWith("rtsp://")) return null
        val source = repo.sources.firstOrNull { it.id == channel.sourceId }
        val headers = HashMap<String, String>()
        source?.referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        ChannelRepository.parseHeaders(source?.headers.orEmpty()).forEach { (k, v) -> headers[k] = v }
        val ua = source?.userAgent?.takeIf { it.isNotBlank() } ?: prefs.defaultUserAgent
        val ds = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaId(channel.id)
            .build()
        return if (url.contains(".m3u8", true)) {
            // 准备阶段只解析播放列表、不预取分片，起播更快，备机预热也不抢带宽
            HlsMediaSource.Factory(ds)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(ds).createMediaSource(item)
        }
    }

    private fun tune(channel: Channel, manual: Boolean, reason: TuneReason = TuneReason.USER_SWITCH) {
        tuneReason = reason
        current = channel
        prefs.lastChannelId = channel.id
        retryCount = 0
        errorText.visibility = View.GONE
        digits.setLength(0)
        handler.removeCallbacks(preloadTask)

        updateBars()
        sideAdapter.submit(sideChannels(), channel.id)
        sideList.post { sideAdapter.scrollTo(id = channel.id) }
        // 换台后短暂亮出信息条，3 秒后自动收起
        if (!sideVisible) showBars(focusActions = false)

        val url = channel.url.trim()
        if (url.startsWith("rtp://") || url.startsWith("udp://") || url.startsWith("rtsp://")) {
            hideLoading()
            errorText.text = "该频道是原生组播/RTSP 地址：\n$url\n\n请把它改成 udpxy 的 HTTP 单播地址（http://网关:端口/rtp/组播IP:端口）后再试。"
            errorText.visibility = View.VISIBLE
            return
        }

        // 备机刚好把这个台缓冲好了就直接接管：省掉等关键帧的那一两秒
        if (promoteSpare(channel)) return

        // promoteSpare 没命中 → 必须重置 warmTune，否则上一次预热残留
        // 会导致 STATE_BUFFERING 被跳过 → 慢台黑屏
        warmTune = false
        toastText.visibility = View.GONE

        val mediaSource = createMediaSource(channel) ?: run {
            errorText.text = "该频道地址无法播放：\n$url"
            errorText.visibility = View.VISIBLE
            hideLoading()
            return
        }
        player.setMediaSource(mediaSource)
        player.playWhenReady = true
        player.prepare()
        tuneStartMs = android.os.SystemClock.elapsedRealtime()
        // 换台立刻预热下一个，不等 STATE_READY：用户停止快速换台时
        // 备机已经开始缓冲了，下次调台就能真正命中
        startPreload()
    }

    /** 命中预热则把备机提升为主播放器；返回是否成功接管 */
    private fun promoteSpare(channel: Channel): Boolean {
        val warm = spare ?: return false
        if (spareChannelId != channel.id) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("FreeTV", "预热未命中 目标=${channel.number} 备机=${spareChannelId}")
            }
            return false
        }
        if (warm.playbackState == Player.STATE_IDLE) return false
        if (warm.playerError != null) {
            warm.stop()
            spareChannelId = null
            return false
        }
        val old = player
        old.removeListener(playerListener)
        old.stop()
        old.clearMediaItems()
        old.volume = 0f

        // 旧播放器留作下一轮的备机，避免反复创建/销毁
        spare = old
        spareChannelId = null
        player = warm
        warm.volume = 1f
        warm.addListener(playerListener)
        playerView.player = warm
        warm.playWhenReady = true
        warmTune = true
        tuneStartMs = android.os.SystemClock.elapsedRealtime()
        // 备机已处于 READY，不会再回调 STATE_READY，这里的界面状态要自己收尾
        hideLoading()
        errorText.visibility = View.GONE
        updateBars()
        // 接管后立刻预热下一个，不等 STATE_READY 延迟
        startPreload()
        return true
    }

    /** 根据设置安排下一次预热；关闭时同时释放已有备机 */
    private fun schedulePreload() {
        handler.removeCallbacks(preloadTask)
        if (prefs.preloadNext) {
            handler.postDelayed(preloadTask, PRELOAD_DELAY_MS)
        } else {
            clearSpare()
        }
    }

    private fun clearSpare() {
        handler.removeCallbacks(preloadTask)
        spare?.release()
        spare = null
        spareChannelId = null
    }

    /** 在后台把下一个频道缓冲好，沿用上一次换台的方向 */
    private fun startPreload() {
        if (!prefs.preloadNext) {
            clearSpare()
            return
        }
        val cur = current ?: return
        val list = repo.channels
        if (list.size < 2) return
        val idx = list.indexOfFirst { it.id == cur.id }
        if (idx < 0) return
        // 沿用户最近换台方向预热，命中概率高得多
        val nextIdx = (idx + lastZapDelta + list.size) % list.size
        val next = list[nextIdx]
        if (next.id == cur.id) return
        if (next.id == spareChannelId) return
        val src = createMediaSource(next) ?: return
        val p = spare ?: buildSpare().also { spare = it }
        p.volume = 0f
        p.setMediaSource(src)
        p.playWhenReady = false
        p.prepare()
        spareChannelId = next.id
        if (BuildConfig.DEBUG) android.util.Log.d("FreeTV", "开始预热 ${next.number} ${next.name}")
    }

    private fun dumpTracks() {
        if (!BuildConfig.DEBUG) return
        val desc = player.currentTracks.groups.joinToString(" | ") { g ->
            "type=${g.type}" + (0 until g.length).joinToString("") { i ->
                "[${g.getTrackFormat(i).sampleMimeType} supported=${g.isTrackSupported(i)}]"
            }
        }
        android.util.Log.d("FreeTV", "tracks => $desc")
    }

    private fun showError(error: PlaybackException) {
        val msg = when {
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "连接超时：网络或 udpxy 服务没有响应。"
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "服务器返回错误状态码，源可能已失效。"
            else -> "无法播放该频道：${error.errorCodeName}"
        }
        errorText.text = "$msg\n\n频道：${current?.name.orEmpty()}\n地址：${current?.url.orEmpty()}\n\n按「上/下」换台，按「菜单」重试。"
        errorText.visibility = View.VISIBLE
        hideLoading()
    }

    private val loadingDelayedCheck = Runnable {
        if (player.playbackState == Player.STATE_BUFFERING && player != spare) {
            showLoading()
        }
    }

    private fun showLoading(text: String = "正在连接…") {
        loadingText.text = text
        loading.visibility = View.VISIBLE
        loadingShownAt = android.os.SystemClock.elapsedRealtime()
    }

    private fun hideLoading() {
        handler.removeCallbacks(loadingDelayedCheck)
        loading.visibility = View.GONE
    }

    private fun updateBars() {
        val c = current ?: return
        channelLogo.text = Ui.shortName(c.name)
        channelLogo.background = Ui.logoBackground(this, c.name)
        channelNumber.text = String.format(Locale.CHINA, "%03d", c.number)
        channelName.text = c.name
        channelGroup.text = repo.sources.firstOrNull { it.id == c.sourceId }?.name
            ?.takeIf { it.isNotBlank() } ?: c.groupName
        updateClock()
        techInfo.text = techSummary()
        updateEpgProgress()
    }

    private fun updateClock() {
        val now = System.currentTimeMillis()
        clock.text = Ui.hhmm(now)
        dateText.text = java.text.SimpleDateFormat("EEE M月d日", Locale.CHINA).format(java.util.Date(now))
    }

    /** 右下角技术信息：分辨率 · 帧率 · 编码 · 声道 · 码率 */
    private fun techSummary(): String {
        var video: androidx.media3.common.Format? = null
        var audio: androidx.media3.common.Format? = null
        player.currentTracks.groups.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> if (video == null) video = f
                    C.TRACK_TYPE_AUDIO -> if (audio == null) audio = f
                }
            }
        }
        val parts = ArrayList<String>()
        video?.let { f ->
            if (f.height > 0) parts.add(resolutionText(f.height, f.width))
            if (f.frameRate > 0) parts.add("${f.frameRate.toInt()}帧")
            videoCodec(f.sampleMimeType)?.let { parts.add(it) }
            if (f.bitrate > 0) parts.add(String.format(Locale.CHINA, "%.1f Mbps", f.bitrate / 1_000_000f))
        }
        audio?.let { f ->
            if (f.channelCount >= 2) parts.add("立体声")
            else if (f.channelCount == 1) parts.add("单声道")
            Unit
        }
        return parts.joinToString(" · ")
    }

    private fun resolutionText(height: Int, width: Int): String = when {
        height >= 2000 -> "4K"
        height >= 1000 -> "1080p"
        height >= 700 -> "720p"
        height >= 500 -> "576p"
        else -> "${width}x${height}"
    }

    private fun videoCodec(mime: String?): String? = when (mime) {
        androidx.media3.common.MimeTypes.VIDEO_H264 -> "H.264"
        androidx.media3.common.MimeTypes.VIDEO_H265 -> "H.265"
        androidx.media3.common.MimeTypes.VIDEO_AV1 -> "AV1"
        androidx.media3.common.MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        androidx.media3.common.MimeTypes.VIDEO_MP4V -> "MPEG-4"
        else -> null
    }

    private fun updateEpgProgress() {
        val c = current ?: return
        val (now, next) = epg.nowNext(c)
        if (now == null) {
            nowTitle.text = "暂无节目信息"
            nowTime.text = ""
            progress.visibility = View.GONE
            return
        }
        progress.visibility = View.VISIBLE
        nowTitle.text = now.title
        val range = "${Ui.hhmm(now.start)} - ${Ui.hhmm(now.stop)}"
        nowTime.text = next?.let { "$range · 接下来 ${Ui.hhmm(it.start)} ${it.title}" } ?: range
        val total = (now.stop - now.start).coerceAtLeast(1)
        val pos = (System.currentTimeMillis() - now.start).coerceIn(0, total)
        progress.progress = (pos * 1000 / total).toInt()
    }

    private fun showBars(focusActions: Boolean = true) {
        overlayVisible = true
        bottomBar.visibility = View.VISIBLE
        // 自动亮出的信息条不抢焦点，否则上下键会被按钮吃掉，无法连续换台
        (bottomBar as android.view.ViewGroup).descendantFocusability =
            if (focusActions) android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            else android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (focusActions) actions.getChildAt(0)?.requestFocus()
        scheduleHide()
    }

    /** 焦点是否在叠加层的按钮上（此时 D-pad 应交给控件自己导航） */
    private fun focusInsideOverlay(): Boolean {
        val f = currentFocus ?: return false
        var p: android.view.ViewParent? = f.parent
        while (p != null) {
            if (p === bottomBar || p === sidePanel) return true
            p = p.parent
        }
        return false
    }

    private fun hideBars() {
        overlayVisible = false
        bottomBar.visibility = View.GONE
        handler.removeCallbacks(hideRunnable)
        currentFocus?.clearFocus()
    }

    private val hideRunnable = Runnable {
        if (!sideVisible) hideBars()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 3000)
    }

    private fun toggleBars() {
        if (overlayVisible) hideBars() else showBars()
    }

    private fun toggleSide() {
        sideVisible = !sideVisible
        sidePanel.visibility = if (sideVisible) View.VISIBLE else View.GONE
        if (sideVisible) {
            // 首次打开时默认定位到当前频道所在分类
            if (!sideGroupInited) {
                sideGroupInited = true
                sideGroup = current?.group?.takeIf { it.isNotBlank() && it in repo.groups }
            }
            buildSideCats()
            sideAdapter.submit(sideChannels(), current?.id.orEmpty())
            // 打开时默认操作频道列表；分类行要按上键才接管焦点
            setSideChipsFocusable(false)
            hideBars()
            sideList.postDelayed({
                // 第一级从最左边开始显示，避免「全部」被裁出屏幕
                (sideCats.parent as? android.widget.HorizontalScrollView)?.scrollTo(0, 0)
                sideAdapter.scrollTo(current?.id.orEmpty())
                focusSideChannel()
            }, 80)
        } else {
            setSideChipsFocusable(false)
            sideList.clearFocus()
        }
    }

    /** 分类芯片默认不可聚焦，避免打开侧栏时被系统自动选中而误切分类 */
    private fun setSideChipsFocusable(on: Boolean) {
        for (i in 0 until sideCats.childCount) {
            sideCats.getChildAt(i).isFocusable = on
        }
    }

    /** 二级菜单第一级：频道分类，光标移到哪就切到哪一类 */
    private fun buildSideCats() {
        if (!sideCatsBuilt) {
            sideCatsBuilt = true
            sideCats.removeAllViews()
            addSideChip("全部", null)
            repo.groups.forEach { addSideChip(it, it) }
        }
        syncSideCats()
    }

    /** 只同步选中态，不重建视图，避免重建时焦点被自动塞给某个芯片 */
    private fun syncSideCats() {
        for (i in 0 until sideCats.childCount) {
            val chip = sideCats.getChildAt(i)
            chip.isSelected = chip.tag == sideGroup
        }
    }

    private fun addSideChip(label: String, key: String?) {
        val chip = Ui.chip(this, label)
        chip.tag = key
        chip.isSelected = sideGroup == key
        chip.isFocusable = false
        chip.setOnClickListener { selectSideGroup(key) }
        chip.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) selectSideGroup(key)
        }
        sideCats.addView(chip)
    }

    private fun selectSideGroup(key: String?) {
        if (sideGroup == key) return
        sideGroup = key
        syncSideCats()
        sideAdapter.submit(sideChannels(), current?.id.orEmpty())
        (sideList.layoutManager as? LinearLayoutManager)?.scrollToPosition(0)
    }

    /** 打开侧栏时把焦点落到当前频道那一行 */
    private fun focusSideChannel() {
        val idx = current?.let { sideAdapter.indexOf(it.id) } ?: -1
        val item = if (idx >= 0) {
            sideList.findViewHolderForAdapterPosition(idx)?.itemView
        } else null
        if (item != null && item.requestFocus()) return
        sideList.requestFocus()
    }

    private fun sideChannels(): List<Channel> {
        val group = sideGroup ?: return repo.channels
        return repo.channels.filter { it.group == group }
    }

    /** 焦点是否落在分类芯片行上 */
    private fun focusInSideCats(): Boolean = currentFocus?.parent === sideCats

    /** 芯片行内左右移动分类；到头就停住，不让焦点逃出侧栏 */
    private fun moveSideChip(delta: Int) {
        if (!focusInSideCats()) return
        val chip = currentFocus ?: return
        val i = sideCats.indexOfChild(chip)
        if (i < 0) return
        val next = (i + delta).coerceIn(0, sideCats.childCount - 1)
        if (next != i) sideCats.getChildAt(next).requestFocus()
    }

    /**
     * 在频道列表内逐行移动焦点。到达边界返回 false，由调用方决定是否进入分类行。
     * 目标行尚未布局时先滚动，再在下一帧把焦点落过去。
     */
    private fun moveSideChannel(delta: Int): Boolean {
        val focused = sideList.findFocus() ?: return false
        val holder = sideList.findContainingViewHolder(focused) ?: return false
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return false
        val target = position + delta
        if (target !in 0 until sideAdapter.itemCount) return false

        val lm = sideList.layoutManager as? LinearLayoutManager ?: return false
        lm.findViewByPosition(target)?.let { return it.requestFocus() }
        lm.scrollToPosition(target)
        sideList.post { lm.findViewByPosition(target)?.requestFocus() }
        return true
    }

    /** 从频道列表第一行按上键进入分类行，落在当前选中的分类上 */
    private fun focusSideChips() {
        setSideChipsFocusable(true)
        val target = (0 until sideCats.childCount)
            .map { sideCats.getChildAt(it) }
            .firstOrNull { it.tag == sideGroup }
            ?: sideCats.getChildAt(0)
        if (!target.requestFocus()) setSideChipsFocusable(false)
    }

    /** 从分类行按下键回到频道列表顶部 */
    private fun focusSideList() {
        (sideList.layoutManager as? LinearLayoutManager)?.scrollToPosition(0)
        sideList.post {
            focusSideChannel()
            // 焦点确实回到列表后再让分类行退出焦点接管
            if (!focusInSideCats()) setSideChipsFocusable(false)
        }
    }

    private fun hideSide() {
        sideVisible = false
        sidePanel.visibility = View.GONE
        setSideChipsFocusable(false)
        sideList.clearFocus()
    }

    private fun cycleAspect() {
        aspectIndex = (aspectIndex + 1) % 3
        playerView.resizeMode = when (aspectIndex) {
            0 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        val label = when (aspectIndex) {
            0 -> "适应画面"
            1 -> "铺满裁切"
            else -> "拉伸填满"
        }
        showToast("画面比例：$label")
    }

    private fun toggleFavorite() {
        val c = current ?: return
        val added = prefs.toggleFavorite(c.id)
        showToast(if (added) "已收藏 ${c.name}" else "已取消收藏 ${c.name}")
    }

    /** 记住最近一次换台方向，预热同方向的下一个频道 */
    private var lastZapDelta = 1

    private fun zap(delta: Int) {
        lastZapDelta = delta
        val list = repo.channels
        if (list.isEmpty()) return
        val cur = current ?: return
        val idx = list.indexOfFirst { it.id == cur.id }
        if (idx < 0) {
            tune(list.first(), true)
            return
        }
        val next = (idx + delta + list.size) % list.size
        tune(list[next], true)
    }

    private fun zapToNumber(number: Int) {
        val target = repo.channels.firstOrNull { it.number == number }
        if (target != null) tune(target, true) else showToast("没有 0$number 号频道")
    }

    private fun onDigit(digit: Char) {
        digits.append(digit)
        numberBadge.text = digits.toString()
        numberBadge.visibility = View.VISIBLE
        handler.removeCallbacks(numberRunnable)
        handler.postDelayed(numberRunnable, 1600)
        if (digits.length >= 3) {
            handler.removeCallbacks(numberRunnable)
            numberRunnable.run()
        }
    }

    private val numberRunnable = Runnable {
        numberBadge.visibility = View.GONE
        val value = digits.toString().toIntOrNull()
        digits.setLength(0)
        if (value != null) zapToNumber(value)
    }

    private fun adjustVolume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        showToast("音量 ${cur * 100 / max}%")
    }

    private fun showToast(text: String) {
        toastText.text = text
        toastText.visibility = View.VISIBLE
        handler.removeCallbacks(toastRunnable)
        handler.postDelayed(toastRunnable, 2200)
    }

    private val toastRunnable = Runnable { toastText.visibility = View.GONE }

    private fun showAudioTracks() {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) {
            showToast("当前频道没有可切换的音轨")
            return
        }
        val labels = ArrayList<String>()
        labels.add("自动选择")
        val refs = ArrayList<Pair<androidx.media3.common.TrackGroup, Int>>()
        groups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val lang = format.language?.let { Locale.forLanguageTag(it).displayLanguage } ?: "未知语言"
                val channels = if (format.channelCount > 0) "${format.channelCount} 声道" else ""
                labels.add("${lang} · ${format.sampleMimeType ?: "音频"} $channels".trim())
                refs.add(group.mediaTrackGroup to i)
            }
        }
        AlertDialog.Builder(this, R.style.Theme_FreeTV_Dialog)
            .setTitle("选择音轨")
            .setItems(labels.toTypedArray()) { _, which ->
                val params = player.trackSelectionParameters.buildUpon()
                if (which == 0) {
                    params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                } else {
                    val (group, index) = refs[which - 1]
                    params.setOverrideForType(TrackSelectionOverride(group, index))
                }
                player.trackSelectionParameters = params.build()
                showToast("音轨已切换：${labels[which]}")
            }
            .show()
    }

    private fun showPlayInfo() {
        val c = current ?: return
        val tracks = player.currentTracks
        val sb = StringBuilder()
        sb.append("频道　").append(c.name).append('\n')
        sb.append("分组　").append(c.groupName).append('\n')
        sb.append("地址　").append(c.url).append('\n')
        sb.append("状态　").append(stateName()).append('\n')

        tracks.groups.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> {
                        sb.append("\n视频　").append(f.sampleMimeType ?: "未知").append('\n')
                        if (f.width > 0) sb.append("分辨率　").append(f.width).append('x').append(f.height).append('\n')
                        if (f.frameRate > 0) sb.append("帧率　").append(String.format(Locale.CHINA, "%.2f fps", f.frameRate)).append('\n')
                        if (f.bitrate > 0) sb.append("码率　").append(f.bitrate / 1000).append(" kbps\n")
                        sb.append("解码　").append(decoderName(f.sampleMimeType)).append('\n')
                    }
                    C.TRACK_TYPE_AUDIO -> {
                        sb.append("\n音频　").append(f.sampleMimeType ?: "未知").append('\n')
                        if (f.channelCount > 0) sb.append("声道　").append(f.channelCount).append('\n')
                        if (f.sampleRate > 0) sb.append("采样率　").append(f.sampleRate).append(" Hz\n")
                        if (f.bitrate > 0) sb.append("码率　").append(f.bitrate / 1000).append(" kbps\n")
                        sb.append("语言　").append(f.language ?: "未标注").append('\n')
                        sb.append("解码　").append(decoderName(f.sampleMimeType)).append('\n')
                    }
                }
            }
        }
        val buffered = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
        sb.append("\n缓冲　").append(buffered).append(" ms\n")
        sb.append("播放器　Media3 / ExoPlayer\n")
        sb.append("解码策略　").append(if (prefs.hardwareFirst) "硬解优先，失败回落" else "默认").append('\n')
        sb.append("应用版本　").append(io.opentv.live.BuildConfig.VERSION_NAME)

        val tv = TextView(this).apply {
            text = sb.toString()
            setTextIsSelectable(true)
            setPadding(Ui.dpi(this@PlayerActivity, 20f), Ui.dpi(this@PlayerActivity, 8f), Ui.dpi(this@PlayerActivity, 20f), Ui.dpi(this@PlayerActivity, 8f))
            setTextColor(0xFF171A1F.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(tv)
            setPadding(0, Ui.dpi(this@PlayerActivity, 8f), 0, 0)
        }
        AlertDialog.Builder(this, R.style.Theme_FreeTV_Dialog)
            .setTitle("播放信息")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun stateName(): String = when (player.playbackState) {
        Player.STATE_BUFFERING -> "缓冲中"
        Player.STATE_READY -> if (player.isPlaying) "播放中" else "已暂停"
        Player.STATE_ENDED -> "已结束"
        else -> "空闲"
    }

    private fun decoderName(mime: String?): String {
        if (mime.isNullOrBlank()) return "未知"
        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val info: MediaCodecInfo? = list.codecInfos.firstOrNull { c ->
                !c.isEncoder && c.supportedTypes.any { it.equals(mime, true) }
            }
            if (info == null) "未知" else {
                val hw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("OMX.google") && !info.name.startsWith("c2.android")
                }
                "${info.name} ${if (hw) "（硬解）" else "（软解）"}"
            }
        }.getOrDefault("未知")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && consumeCenterUp &&
            event.keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER
            )
        ) {
            consumeCenterUp = false
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val overlayFocus = focusInsideOverlay()
            // 侧栏打开时方向键全部由侧栏接管：上=分类行、下=频道列表、左右=切分类
            if (sideVisible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveSideChip(-1)
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveSideChip(1)
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (focusInSideCats()) return true
                        // 列表内逐行向上；只有已经在第一行时才进入分类行
                        if (moveSideChannel(-1)) return true
                        focusSideChips()
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (focusInSideCats()) {
                            focusSideList()
                            return true
                        }
                    }
                }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    when {
                        sideVisible -> hideSide()
                        overlayVisible -> hideBars()
                        else -> return super.dispatchKeyEvent(event)
                    }
                    return true
                }

                KeyEvent.KEYCODE_MENU -> {
                    hideBars()
                    showPlayMenu()
                    return true
                }

                KeyEvent.KEYCODE_INFO -> {
                    showPlayInfo()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (!sideVisible && !overlayFocus) {
                        zap(-1)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (!sideVisible && !overlayFocus) {
                        zap(1)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!sideVisible && !overlayFocus) {
                        adjustVolume(false)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!sideVisible && !overlayFocus) {
                        adjustVolume(true)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (sideVisible || overlayFocus) {
                        scheduleHide()
                        return super.dispatchKeyEvent(event)
                    }
                    // 信息条亮着时按确认键进入按钮区，否则打开频道列表
                    if (overlayVisible) {
                        showBars(focusActions = true)
                    } else {
                        toggleSide()
                    }
                    consumeCenterUp = true
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (player.isPlaying) player.pause() else player.play()
                    showToast(if (player.isPlaying) "继续播放" else "已暂停")
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    zap(1)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    zap(-1)
                    return true
                }

                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    onDigit((event.keyCode - KeyEvent.KEYCODE_0).toString()[0])
                    return true
                }

                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                    onDigit((event.keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString()[0])
                    return true
                }
            }
            if (!sideVisible && !overlayFocus && event.keyCode != KeyEvent.KEYCODE_BACK) {
                // 任何其它按键响起时收起条子，避免遮挡
                if (overlayVisible) hideBars()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showPlayMenu() {
        val items = arrayOf("频道列表", "音轨切换", "画面比例", "播放信息", "收藏 / 取消收藏", "重新连接")
        AlertDialog.Builder(this, R.style.Theme_FreeTV_Dialog)
            .setTitle("更多")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleSide()
                    1 -> showAudioTracks()
                    2 -> cycleAspect()
                    3 -> showPlayInfo()
                    4 -> toggleFavorite()
                    else -> current?.let { tune(it, true) }
                }
            }
            .show()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.preloadNext) clearSpare()
    }

    override fun onPause() {
        super.onPause()
        player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        spare?.release()
        spare = null
        player.release()
        super.onDestroy()
    }

    private inner class SideAdapter : RecyclerView.Adapter<SideAdapter.Holder>() {

        private var items: List<Channel> = emptyList()
        private var currentId: String = ""

        inner class Holder(val root: View) : RecyclerView.ViewHolder(root)

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Channel>, currentId: String) {
            this.items = list
            this.currentId = currentId
            notifyDataSetChanged()
        }

        fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }

        fun scrollTo(id: String) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) (sideList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(idx, 100)
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder =
            Holder(layoutInflater.inflate(R.layout.item_side_channel, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val channel = items[position]
            val v = holder.root
            v.findViewById<TextView>(R.id.number).text = String.format(Locale.CHINA, "%03d", channel.number)
            v.findViewById<TextView>(R.id.name).text = channel.name
            v.findViewById<TextView>(R.id.fav).visibility =
                if (prefs.isFavorite(channel.id)) View.VISIBLE else View.GONE
            v.isActivated = channel.id == currentId
            v.setOnClickListener {
                if (channel.id != current?.id) tune(channel, true)
                hideSide()
            }
            // 侧栏窄，缩放会把高亮描边顶出面板被裁切，这里只提亮不放大
            v.setOnFocusChangeListener { view, hasFocus ->
                view.elevation = if (hasFocus) Ui.dp(view.context, 6f) else 0f
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"

        /** 起播稳定后多久开始预热下一个频道 */
        private const val PRELOAD_DELAY_MS = 500L
    }
}
