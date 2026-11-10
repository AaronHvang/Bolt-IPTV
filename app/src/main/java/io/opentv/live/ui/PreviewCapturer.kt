package io.opentv.live.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import io.opentv.live.R
import io.opentv.live.data.Channel
import io.opentv.live.data.Prefs

/**
 * 用一台静音的后台播放器依次给频道抓首帧，作为频道卡片的小窗口预览。
 * 一次只开一路流，抓完立刻释放，避免影响正常播放和局域网带宽。
 */
@OptIn(UnstableApi::class)
class PreviewCapturer(
    private val activity: Activity,
    private val onReady: (String) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Channel>()
    private val queued = HashSet<String>()

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var texture: TextureView? = null
    private var active: Channel? = null
    private var running = false
    private var attempts = 0

    private val timeout = Runnable { finish(false) }
    private val grab = Runnable { grabFrame() }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY && active != null) {
                handler.removeCallbacks(grab)
                // 再等一小会儿，跳过刚起播时的黑场/台标动画
                handler.postDelayed(grab, 700)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            finish(false)
        }
    }

    /** 把待生成的频道放进队列，已有的会自动跳过 */
    fun enqueue(channels: List<Channel>) {
        channels.forEach { channel ->
            if (PreviewStore.has(channel.id) || PreviewStore.isFailed(channel.id)) return@forEach
            if (!queued.add(channel.id)) return@forEach
            queue.addLast(channel)
        }
        if (running) next()
    }

    fun start() {
        if (running) return
        running = true
        next()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(grab)
        active = null
        player?.stop()
        player?.clearMediaItems()
    }

    fun release() {
        stop()
        player?.release()
        player = null
        playerView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        playerView = null
        texture = null
    }

    private fun next() {
        if (!running || active != null) return
        while (true) {
            val channel = queue.removeFirstOrNull() ?: return
            queued.remove(channel.id)
            if (PreviewStore.has(channel.id) || PreviewStore.isFailed(channel.id)) continue
            val source = createSource(channel) ?: continue
            active = channel
            attempts = 0
            val p = ensurePlayer()
            p.setMediaSource(source)
            p.prepare()
            p.playWhenReady = true
            handler.postDelayed(timeout, 15_000)
            return
        }
    }

    private fun finish(ok: Boolean) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(grab)
        val channel = active ?: return
        if (!ok) PreviewStore.markFailed(channel.id)
        active = null
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        next()
    }

    private fun grabFrame() {
        val channel = active ?: return
        val bitmap = texture?.bitmap
        if (bitmap != null && bitmap.width > 0) {
            PreviewStore.put(channel.id, bitmap)
            onReady(channel.id)
            finish(true)
        } else if (attempts < 6) {
            attempts++
            handler.postDelayed(grab, 260)
        } else {
            finish(false)
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }

        // 只看画面不听声音，播放器放在屏幕外，抓完帧就停
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.view_preview_host, null, false) as PlayerView
        view.isFocusable = false
        view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.translationX = -10_000f
        view.translationY = -10_000f
        (activity.findViewById<ViewGroup>(android.R.id.content))?.addView(
            view,
            ViewGroup.LayoutParams(
                Ui.dpi(activity, 320f),
                Ui.dpi(activity, 180f)
            )
        )

        val exo = ExoPlayer.Builder(
            activity,
            DefaultRenderersFactory(activity).setEnableDecoderFallback(true)
        ).build()
        exo.volume = 0f
        exo.addListener(listener)
        view.player = exo

        player = exo
        playerView = view
        texture = view.videoSurfaceView as? TextureView
        return exo
    }

    private fun createSource(channel: Channel): MediaSource? {
        val url = channel.url.trim()
        if (url.isEmpty()) return null
        if (url.startsWith("rtp://") || url.startsWith("udp://") || url.startsWith("rtsp://")) return null
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Prefs.DEFAULT_UA)
            .setConnectTimeoutMs(6000)
            .setReadTimeoutMs(6000)
            .setAllowCrossProtocolRedirects(true)
        val item = MediaItem.fromUri(url)
        return if (url.contains(".m3u8", true)) {
            HlsMediaSource.Factory(http).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(http).createMediaSource(item)
        }
    }
}
